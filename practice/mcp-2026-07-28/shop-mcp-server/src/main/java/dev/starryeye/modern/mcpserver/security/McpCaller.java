package dev.starryeye.modern.mcpserver.security;

import io.modelcontextprotocol.common.McpTransportContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * tool을 부른 사용자다.
 * token의 {@code sub}와 {@code client_id}다.
 *
 * <p>MCP transport는 요청마다 {@link #context(HttpServletRequest)}로 이 값을 {@link McpTransportContext}에 넣는다.
 * tool 저장소는 {@code McpRequestContext.transportContext()}로 받아 {@link #from(McpTransportContext)}로 꺼낸다.
 * handle을 사용자에게 묶는 key의 {@code <sub>}가 여기서 온다(안내서 11장).
 * client가 보낸 값이 아니라 검증한 token에서 꺼낸 값이라는 점이 중요하다.
 *
 * <p>같은 context에 {@code Mcp-Name} header의 tool 이름도 넣는다.
 * scope 검사는 이 header로 하므로, 실행하는 쪽은 실행할 이름이 이 header와 같은지 한 번 더 본다.
 */
public record McpCaller(String subject, String clientId) {

	public static final String SUBJECT = "sub";

	public static final String CLIENT_ID = "client_id";

	/** {@code Mcp-Name} header를 푼 값의 key다. */
	public static final String MCP_NAME = "mcp-name";

	private static final String BASE64_PREFIX = "=?base64?";

	private static final String BASE64_SUFFIX = "?=";

	/**
	 * Spring Security가 검증한 token과 {@code Mcp-Name} header를 읽는다.
	 * 둘 다 없으면 빈 context다.
	 * {@code sub}가 없는 token의 요청은 사용자를 모르는 요청이라 손님으로 다뤄야 한다.
	 * 그래서 token에 {@code sub}가 없으면 {@link #SUBJECT}를 넣지 않는다.
	 */
	public static McpTransportContext context(HttpServletRequest request) {
		Map<String, Object> values = new HashMap<>();
		if (request.getUserPrincipal() instanceof JwtAuthenticationToken authentication) {
			String subject = authentication.getToken().getSubject();
			if (subject != null && !subject.isBlank()) {
				values.put(SUBJECT, subject);
			}
			values.put(CLIENT_ID, Objects.toString(authentication.getToken().getClaimAsString(CLIENT_ID), ""));
		}
		String mcpName = decodeMcpName(request.getHeader("Mcp-Name"));
		if (mcpName != null) {
			values.put(MCP_NAME, mcpName);
		}
		return values.isEmpty() ? McpTransportContext.EMPTY : McpTransportContext.create(values);
	}

	public static McpCaller from(McpTransportContext context) {
		if (!(context.get(SUBJECT) instanceof String subject) || subject.isBlank()) {
			throw new IllegalStateException("인증된 사용자가 없다");
		}
		return new McpCaller(subject, Objects.toString(context.get(CLIENT_ID), ""));
	}

	/**
	 * transport가 넣어 둔 token의 sub다.
	 * 인증이 없으면 비어 있다.
	 */
	public static Optional<String> subject(McpTransportContext context) {
		return (context.get(SUBJECT) instanceof String subject && !subject.isBlank())
				? Optional.of(subject) : Optional.empty();
	}

	/**
	 * transport가 넣어 둔 {@code Mcp-Name}이다.
	 * header가 없었거나 풀 수 없었으면 {@code null}이다.
	 */
	public static String mcpName(McpTransportContext context) {
		return (context.get(MCP_NAME) instanceof String name) ? name : null;
	}

	/**
	 * {@code Mcp-Name} header 값을 tool 이름으로 푼다.
	 *
	 * <p>header에는 출력할 수 있는 ASCII만 넣을 수 있어서, client는 그 밖의 글자가 든 이름을 {@code =?base64?…?=}로 적는다.
	 * 그 표기면 안쪽을 base64로 풀어 UTF-8로 읽고, 아니면 값 그대로 쓴다.
	 * 풀 수 없는 base64면 {@code null}이다.
	 * 규칙은 SDK transport가 header와 본문을 맞춰 볼 때와 같다.
	 * 다르면 header로 본 이름과 SDK가 대조한 이름이 어긋난다.
	 */
	public static String decodeMcpName(String header) {
		if (header == null) {
			return null;
		}
		if (!header.startsWith(BASE64_PREFIX) || !header.endsWith(BASE64_SUFFIX)) {
			return header;
		}
		// "=?base64?="처럼 앞뒤 표기가 겹치면 안쪽 값이 없다.
		// 잘라 내다 예외가 나지 않게 먼저 거른다.
		if (header.length() < BASE64_PREFIX.length() + BASE64_SUFFIX.length()) {
			return null;
		}
		String base64 = header.substring(BASE64_PREFIX.length(), header.length() - BASE64_SUFFIX.length());
		try {
			return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}
}
