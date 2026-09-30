package dev.starryeye.stateless.mcpserver.filter;

import dev.starryeye.stateless.mcpserver.tool.ToolScopeRegistry;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * token의 scope가 이 요청에 충분한지 본다(안내서 10장).
 *
 * <p>모든 MCP 요청에는 기본 scope({@link ToolScopeRegistry#BASE_SCOPE})가 있어야 한다.
 * {@code tools/call}은 그 tool에 필요한 scope도 있어야 한다.
 * 모자라면 {@code 403}과 {@code WWW-Authenticate: Bearer error="insufficient_scope", scope="…"}로 답한다
 * (RFC 6750 §3.1, MCP 2025-11-25 Authorization — Scope Challenge Handling).
 * challenge에는 이 요청에 필요한 scope만 적는다. scope 목록 전체를 알리지 않는다.
 *
 * <p>tool 이름은 JSON-RPC 본문의 {@code params.name}에 있어 본문을 읽어야 한다.
 * MCP 2026-07-28은 이 이름을 {@code Mcp-Name} header로도 보내게 해서, 서버는 본문 없이 같은 검사를 할 수 있다.
 * 지금 SDK는 2025-11-25라 본문을 읽는다.
 *
 * <p>검사를 tool 메서드 안(예: {@code @PreAuthorize})에서 하면 거절이 HTTP {@code 200}의 tool 오류 결과가 된다.
 * client가 step-up을 시작하려면 HTTP {@code 403}과 challenge가 필요하므로 transport 앞에서 검사한다.
 *
 * <p><b>본문은 transport가 실제로 실행할 그 text를 읽어야 한다.</b>
 * transport({@code WebMvcStreamableServerTransportProvider})는 본문을 {@code request.body(String.class)}로 읽는다.
 * Spring의 {@code StringHttpMessageConverter}는 {@code Content-Type}의 charset(없으면 UTF-8)으로 바이트를 decode하는데,
 * 잘못된 바이트를 만나도 예외를 던지지 않고 대체 문자({@code U+FFFD})로 조용히 바꿔 넣는다.
 * 이 필터가 그 대신 {@code jsonMapper.readTree(byte[])}로 바이트를 직접 읽으면, Jackson은 잘못된 UTF-8 바이트에서
 * 예외를 던진다. 그러면 이 필터는 "JSON이 아니다"로 보고 tool 이름 검사를 건너뛰어 통과시키지만, transport는 같은
 * 바이트를 대체 문자로 채워 넣어 여전히 유효한 JSON으로 읽고 그대로 실행한다. 검사와 실행이 서로 다른 것을 보는
 * parser differential이 생겨, scope가 없는 tool 호출이 검사를 피해 나간다.
 * 그래서 이 필터는 transport와 같은 규칙으로 decode한다. 그 결과가 JSON object가 아니면(구문 오류, 배열, 모르는
 * charset 이름) transport에 넘기지 않고 여기서 {@code 400}으로 끝낸다. transport가 읽을 것과 다르게 해석할 수 있는
 * 본문은 넘기지 않는 것이 "다르게 보일 수 있는 것은 아예 넘기지 않는다"는 원칙에 맞다.
 */
public class ToolScopeFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(ToolScopeFilter.class);

	private static final String SCOPE_AUTHORITY_PREFIX = "SCOPE_";

	private final ToolScopeRegistry registry;

	private final JsonMapper jsonMapper;

	private final Function<HttpServletRequest, String> resourceMetadataUrl;

	public ToolScopeFilter(ToolScopeRegistry registry, JsonMapper jsonMapper,
			Function<HttpServletRequest, String> resourceMetadataUrl) {
		this.registry = registry;
		this.jsonMapper = jsonMapper;
		this.resourceMetadataUrl = resourceMetadataUrl;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		// JWT가 아닌 인증은 scope가 없는 것으로 본다. 이 filter에 닿을 요청은 이미 Spring Security를 지났으므로
		// 보통은 항상 JwtAuthenticationToken이지만, 다른 인증 방식이 섞여도 열어 두지 않는다.
		JwtAuthenticationToken token =
				(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt) ? jwt
						: null;
		Set<String> granted = (token != null) ? grantedScopes(token) : Set.of();

		// 본문을 버퍼링하기 전에 기본 scope부터 본다. 기본 scope가 없으면 tool 이름을 알 필요도 없다.
		if (!granted.contains(ToolScopeRegistry.BASE_SCOPE)) {
			insufficientScope(request, response, token, null, ToolScopeRegistry.BASE_SCOPE, granted);
			return;
		}

		HttpServletRequest forwarded = request;
		if (HttpMethod.POST.matches(request.getMethod())) {
			CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request);
			forwarded = cached;

			JsonNode message;
			try {
				message = parseAsTransportWill(cached);
			}
			catch (BodyRejected rejected) {
				rejectBody(response, rejected.code, rejected.reason);
				return;
			}

			String tool = toolName(message);
			String missing = (tool != null && !granted.contains(this.registry.scopeFor(tool)))
					? this.registry.scopeFor(tool) : null;
			if (missing != null) {
				insufficientScope(request, response, token, tool, missing, granted);
				return;
			}
		}
		chain.doFilter(forwarded, response);
	}

	/**
	 * transport({@code StringHttpMessageConverter})와 같은 규칙으로 본문을 decode하고 parse한다.
	 * charset이 없으면 UTF-8, 읽을 수 없으면 거절한다.
	 * decode한 text가 JSON object가 아니면(구문 오류, 배열) {@link BodyRejected}로 거절한다.
	 */
	// 이 decode 규칙은 SDK transport(request.body(String.class) → StringHttpMessageConverter)를 따라 한 것이다.
	// SDK를 올릴 때 다시 확인한다.
	private JsonNode parseAsTransportWill(CachedBodyHttpServletRequest request) {
		Charset charset = charset(request);
		String text = new String(request.body(), charset);
		JsonNode message;
		try {
			message = this.jsonMapper.readTree(text);
		}
		catch (JacksonException ex) {
			throw new BodyRejected(-32700, "요청 본문이 올바른 JSON이 아닙니다.");
		}
		if (message == null || !message.isObject()) {
			throw new BodyRejected(-32600, "요청 본문은 JSON object여야 합니다.");
		}
		return message;
	}

	/**
	 * {@code Content-Type}의 charset을 돌려준다. header가 없으면 UTF-8이다.
	 * media type을 읽을 수 없거나(모르는 charset 이름 포함) {@link BodyRejected}로 거절한다.
	 */
	private static Charset charset(HttpServletRequest request) {
		String contentType = request.getContentType();
		if (contentType == null) {
			return StandardCharsets.UTF_8;
		}
		MediaType mediaType;
		try {
			mediaType = MediaType.parseMediaType(contentType);
		}
		catch (InvalidMediaTypeException ex) {
			throw new BodyRejected(-32700, "요청의 Content-Type을 읽을 수 없습니다.");
		}
		Charset charset = mediaType.getCharset();
		return (charset != null) ? charset : StandardCharsets.UTF_8;
	}

	/** Spring Security는 token의 {@code scope}를 {@code SCOPE_} authority로 바꿔 둔다. */
	private static Set<String> grantedScopes(JwtAuthenticationToken token) {
		return token.getAuthorities().stream()
				.map(GrantedAuthority::getAuthority)
				.filter(authority -> authority.startsWith(SCOPE_AUTHORITY_PREFIX))
				.map(authority -> authority.substring(SCOPE_AUTHORITY_PREFIX.length()))
				.collect(Collectors.toCollection(TreeSet::new));
	}

	/** {@code tools/call}이면 tool 이름을, 아니면 {@code null}을 돌려준다. */
	private String toolName(JsonNode message) {
		JsonNode method = message.get("method");
		if (method == null || !method.isString() || !"tools/call".equals(method.stringValue())) {
			return null;
		}
		JsonNode params = message.get("params");
		JsonNode name = (params == null) ? null : params.get("name");
		return (name != null && name.isString() && !name.stringValue().isBlank()) ? name.stringValue() : null;
	}

	private void insufficientScope(HttpServletRequest request, HttpServletResponse response,
			JwtAuthenticationToken token, String tool, String missing, Set<String> granted) {
		// Security Best Practices — Scope Minimization: 권한 상승 요청을 기록으로 남긴다.
		log.info("scope 부족 — 사용자={}, client_id={}, tool={}, 필요한 scope={}, 가진 scope={}",
				(token != null) ? token.getName() : "(JWT 인증 아님)",
				(token != null) ? token.getToken().getClaimAsString("client_id") : null, tool, missing, granted);
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\", scope=\"" + missing
				+ "\", resource_metadata=\"" + this.resourceMetadataUrl.apply(request) + "\"");
	}

	/** transport가 실행할 수 없는 본문은 transport로 넘기지 않고 여기서 {@code 400}으로 끝낸다. */
	private void rejectBody(HttpServletResponse response, int code, String message) throws IOException {
		response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
		// getWriter()는 Content-Type에 charset이 없으면 ISO-8859-1로 쓴다. 그래서 charset을 적는다.
		response.setContentType("application/json;charset=UTF-8");
		ObjectNode body = this.jsonMapper.createObjectNode();
		body.put("jsonrpc", "2.0");
		body.putNull("id");
		ObjectNode error = body.putObject("error");
		error.put("code", code);
		error.put("message", message);
		response.getWriter().write(this.jsonMapper.writeValueAsString(body));
	}

	/** {@link #parseAsTransportWill}이 본문을 거절할 때 쓰는 사유다. JSON-RPC 오류의 {@code code}와 {@code message}가 된다. */
	private static final class BodyRejected extends RuntimeException {

		private final int code;

		private final String reason;

		BodyRejected(int code, String reason) {
			super(reason, null, false, false);
			this.code = code;
			this.reason = reason;
		}
	}
}
