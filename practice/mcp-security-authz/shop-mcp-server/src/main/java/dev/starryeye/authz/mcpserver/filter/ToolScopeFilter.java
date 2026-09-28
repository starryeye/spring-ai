package dev.starryeye.authz.mcpserver.filter;

import dev.starryeye.authz.mcpserver.tool.ToolScopeRegistry;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
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
		if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
			// token이 없거나 틀린 요청은 Spring Security가 이미 401로 끝냈다.
			chain.doFilter(request, response);
			return;
		}
		Set<String> granted = grantedScopes(token);
		HttpServletRequest forwarded = request;
		String tool = null;
		if (HttpMethod.POST.matches(request.getMethod())) {
			CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request);
			forwarded = cached;
			tool = toolName(cached.body());
		}
		String missing = missingScope(granted, tool);
		if (missing != null) {
			insufficientScope(request, response, token, tool, missing, granted);
			return;
		}
		chain.doFilter(forwarded, response);
	}

	private String missingScope(Set<String> granted, String tool) {
		if (!granted.contains(ToolScopeRegistry.BASE_SCOPE)) {
			return ToolScopeRegistry.BASE_SCOPE;
		}
		if (tool != null && !granted.contains(this.registry.scopeFor(tool))) {
			return this.registry.scopeFor(tool);
		}
		return null;
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
	private String toolName(byte[] body) {
		try {
			JsonNode message = this.jsonMapper.readTree(body);
			if (message == null || !message.isObject()) {
				return null;
			}
			JsonNode method = message.get("method");
			if (method == null || !method.isString() || !"tools/call".equals(method.stringValue())) {
				return null;
			}
			JsonNode params = message.get("params");
			JsonNode name = (params == null) ? null : params.get("name");
			return (name != null && name.isString() && !name.stringValue().isBlank()) ? name.stringValue() : null;
		}
		catch (JacksonException ex) {
			// JSON이 아닌 본문은 transport가 JSON-RPC 오류로 답한다. 여기서는 기본 scope만 본다.
			return null;
		}
	}

	private void insufficientScope(HttpServletRequest request, HttpServletResponse response,
			JwtAuthenticationToken token, String tool, String missing, Set<String> granted) {
		// Security Best Practices — Scope Minimization: 권한 상승 요청을 기록으로 남긴다.
		log.info("scope 부족 — 사용자={}, client_id={}, tool={}, 필요한 scope={}, 가진 scope={}",
				token.getName(), token.getToken().getClaimAsString("client_id"), tool, missing, granted);
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\", scope=\"" + missing
				+ "\", resource_metadata=\"" + this.resourceMetadataUrl.apply(request) + "\"");
	}
}
