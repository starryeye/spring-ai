package dev.starryeye.modern.mcpserver.filter;

import dev.starryeye.modern.mcpserver.security.McpCaller;
import dev.starryeye.modern.mcpserver.tool.ToolScopeRegistry;
import dev.starryeye.modern.mcpserver.tool.ToolVisibility;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse.JSONRPCError;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * token의 scope가 이 요청에 충분한지 본다(안내서 10장).
 *
 * <p>모든 MCP 요청에는 기본 scope({@link ToolScopeRegistry#BASE_SCOPE})가 있어야 한다.
 * {@code tools/call}은 그 tool에 필요한 scope도 있어야 한다.
 * 모자라면 {@code 403}과 {@code WWW-Authenticate: Bearer error="insufficient_scope", scope="…"}로 답한다(RFC 6750 §3.1).
 *
 * <p>challenge의 {@code scope}에는 모자란 scope를 모두 공백으로 이어 적는다.
 * 하나씩 알리면 client는 동작 하나를 위해 authorization을 여러 번 다시 받아야 한다.
 * 그래서 MCP 2026-07-28 Authorization — Scope Challenge Handling은 필요한 scope를 challenge 한 번에 모두 적으라고 한다.
 * 이미 가진 scope는 적지 않는다.
 * client가 다시 authorization을 받을 때 이전 scope와 합친다.
 *
 * <p>tool 이름은 본문이 아니라 header에서 읽는다.
 * 2026-07-28 client는 {@code Mcp-Method}에 method를, {@code tools/call}이면 {@code Mcp-Name}에 tool 이름을 적는다
 * (Transports — Streamable HTTP, Standard Request Headers).
 * 이 filter 뒤의 SDK transport는 header가 빠졌거나 본문과 다르면 {@code 400}과 {@code -32020}으로 막는다(Server Validation).
 * 그래서 이 filter가 header로 본 이름과 SDK가 실행하는 이름은 같다.
 * 검사하는 쪽과 실행하는 쪽이 본문을 서로 다르게 읽을 일(parser differential)이 처음부터 없다.
 * 본문의 이름이 문자열이 아니면 SDK 2.2.0-M1은 이 대조를 건너뛴다.
 * 그 경우는 tool 저장소({@code ShopToolRepository})가 막는다.
 *
 * <p>명세는 header로 정책을 정하는 쪽에게, header와 본문을 맞춰 보는 버전의 요청인지 먼저 확인하라고 한다.
 * 이 서버의 SDK는 2026-07-28 요청만 받고, 버전과 header를 모두 맞춰 본 뒤에야 실행한다.
 * 그래서 이 filter는 버전을 따로 보지 않는다.
 *
 * <p>{@code Mcp-Name}은 SDK와 같은 규칙으로 푼다({@link McpCaller#decodeMcpName}).
 * {@code Mcp-Name}이 없으면 tool scope를 정할 수 없어 기본 scope만 보고 넘긴다.
 * 그러면 SDK가 {@code 400}으로 끝낸다.
 * {@code =?base64?…?=} 표기인데 풀 수 없으면 여기서 {@code 400}과 {@code -32020}으로 끝낸다.
 * SDK 2.2.0-M1은 {@code =?base64?=}를 풀다 예외를 던져 {@code 500}으로 답하기 때문이다.
 *
 * <p>숨긴 tool은 scope를 늘려도 쓸 수 없어서 step-up을 시작하면 안 된다.
 * 그래서 tool scope를 보지 않고 넘기고, SDK가 정말 없는 tool과 같은 "모르는 tool" 오류로 답하게 한다.
 *
 * <p>검사를 tool 메서드 안에서 하면 거절이 HTTP {@code 200}의 tool 오류 결과가 된다.
 * client가 step-up을 시작하려면 HTTP {@code 403}과 challenge가 필요하므로 transport 앞에서 검사한다.
 *
 * <p>scope 검사를 통과한 {@code tools/call}은 사용자({@code sub})마다 횟수를 센다({@link ToolCallRateLimiter}).
 * 상한(기본 분당 60회, {@code mcp.limits.tool-calls-per-minute})을 넘으면 {@code 429}와 {@code Retry-After}로 끝낸다.
 *
 * <p>이 filter가 끝내는 {@code 400}·{@code 429}의 본문은 {@code id}가 없는 JSON-RPC 오류다.
 * 본문을 읽지 않아 요청의 {@code id}를 모르기 때문이다.
 * SDK도 {@code id}를 모르는 오류는 이렇게 답한다.
 */
public class ToolScopeFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(ToolScopeFilter.class);

	private static final String SCOPE_AUTHORITY_PREFIX = "SCOPE_";

	private static final String MCP_METHOD = "Mcp-Method";

	private static final String MCP_NAME = "Mcp-Name";

	/**
	 * {@code Mcp-Name}을 풀 수 없을 때의 오류 문구다.
	 * SDK가 같은 경우에 쓰는 문구를 그대로 쓴다.
	 */
	private static final String MALFORMED_MCP_NAME = "Mcp-Name header has a malformed Base64 value";

	private final ToolScopeRegistry registry;

	private final ToolVisibility visibility;

	/** {@code 403} challenge에 넣을 PRM 주소다. 요청의 {@code Host}가 아니라 설정에서 만든 값이다. */
	private final String resourceMetadataUrl;

	/** 사용자마다 {@code tools/call} 횟수를 센다. */
	private final ToolCallRateLimiter rateLimiter;

	public ToolScopeFilter(ToolScopeRegistry registry, ToolVisibility visibility, String resourceMetadataUrl,
			ToolCallRateLimiter rateLimiter) {
		this.registry = registry;
		this.visibility = visibility;
		this.resourceMetadataUrl = resourceMetadataUrl;
		this.rateLimiter = rateLimiter;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		// 풀 수 없는 이름으로는 필요한 scope를 정할 수 없고, SDK에 넘기면 500이 될 수 있다.
		String mcpName = request.getHeader(MCP_NAME);
		String tool = McpCaller.decodeMcpName(mcpName);
		if (mcpName != null && tool == null) {
			reject(response, HttpServletResponse.SC_BAD_REQUEST, ErrorCodes.HEADER_MISMATCH, MALFORMED_MCP_NAME);
			return;
		}

		// JWT가 아닌 인증은 scope가 없는 것으로 본다. 이 filter에 닿을 요청은 이미 Spring Security를 지났으므로
		// 보통은 항상 JwtAuthenticationToken이지만, 다른 인증 방식이 섞여도 열어 두지 않는다.
		JwtAuthenticationToken token =
				(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt) ? jwt
						: null;
		Set<String> granted = (token != null) ? grantedScopes(token) : Set.of();
		String subject = (token != null) ? token.getToken().getSubject() : null;

		boolean toolCall = McpSchema.METHOD_TOOLS_CALL.equals(request.getHeader(MCP_METHOD));
		if (!toolCall) {
			tool = null;
		}
		List<String> missing = required(subject, tool).stream().filter(scope -> !granted.contains(scope)).toList();
		if (!missing.isEmpty()) {
			insufficientScope(response, token, tool, missing, granted);
			return;
		}

		// SDK로 넘길 tool 호출만 센다.
		// Mcp-Name이 없거나 모르는 tool의 호출도 SDK가 처리하므로 센다.
		if (toolCall) {
			String caller = Objects.toString(subject, "");
			Optional<Duration> retryAfter = this.rateLimiter.tryAcquire(caller);
			if (retryAfter.isPresent()) {
				tooManyToolCalls(response, caller, retryAfter.get());
				return;
			}
		}
		chain.doFilter(request, response);
	}

	/**
	 * 이 요청에 필요한 scope다.
	 * 기본 scope가 먼저 오고, 그 뒤에 tool scope가 온다.
	 * tool 이름을 모르거나({@code null}) 이 사용자에게 숨긴 tool이면 기본 scope만 필요하다.
	 */
	private Set<String> required(String subject, String tool) {
		Set<String> required = new LinkedHashSet<>();
		required.add(ToolScopeRegistry.BASE_SCOPE);
		if (tool != null && !this.visibility.hidden(subject, tool)) {
			required.add(this.registry.scopeFor(tool));
		}
		return required;
	}

	/** Spring Security는 token의 {@code scope}를 {@code SCOPE_} authority로 바꿔 둔다. */
	private static Set<String> grantedScopes(JwtAuthenticationToken token) {
		return token.getAuthorities().stream()
				.map(GrantedAuthority::getAuthority)
				.filter(authority -> authority.startsWith(SCOPE_AUTHORITY_PREFIX))
				.map(authority -> authority.substring(SCOPE_AUTHORITY_PREFIX.length()))
				.collect(Collectors.toCollection(TreeSet::new));
	}

	private void insufficientScope(HttpServletResponse response, JwtAuthenticationToken token, String tool,
			List<String> missing, Set<String> granted) {
		String scope = String.join(" ", missing);
		// Security Best Practices — Scope Minimization: 권한 상승 요청을 기록으로 남긴다.
		log.info("scope 부족 — 사용자={}, client_id={}, tool={}, 모자란 scope={}, 가진 scope={}",
				(token != null) ? token.getName() : "(JWT 인증 아님)",
				(token != null) ? token.getToken().getClaimAsString("client_id") : null, tool, scope, granted);
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\", scope=\"" + scope
				+ "\", resource_metadata=\"" + this.resourceMetadataUrl + "\"");
	}

	/**
	 * 사용자의 tool 호출이 상한을 넘었다.
	 * SDK로 넘기지 않고 {@code 429}와 {@code Retry-After}로 끝낸다.
	 * {@code Retry-After}는 다시 시도할 수 있을 때까지 남은 시간을 초 단위로 올림한 값이다.
	 */
	private void tooManyToolCalls(HttpServletResponse response, String subject, Duration retryAfter)
			throws IOException {
		long seconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
		log.info("tool 호출 상한 초과 — 사용자={}, 다시 시도까지 {}초", subject, seconds);
		response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
		reject(response, HttpStatus.TOO_MANY_REQUESTS.value(), -32000,
				"tool 호출이 너무 잦습니다. " + seconds + "초 뒤에 다시 시도하세요.");
	}

	/**
	 * SDK로 넘기지 않을 요청을 여기서 끝낸다.
	 * 본문은 {@code id}가 없는 JSON-RPC 오류다.
	 * SDK의 JSON-RPC 타입과 기본 mapper로 써서, SDK transport가 쓰는 오류 본문과 모양이 같다.
	 */
	private static void reject(HttpServletResponse response, int status, int code, String message)
			throws IOException {
		response.setStatus(status);
		response.setContentType("application/json");
		// getWriter()는 charset을 정하지 않으면 ISO-8859-1로 쓴다.
		// 한국어 문구가 있으므로 UTF-8로 정한다.
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(McpJsonDefaults.getMapper()
				.writeValueAsString(JSONRPCResponse.error(null, new JSONRPCError(code, message))));
	}
}
