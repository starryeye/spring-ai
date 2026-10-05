package dev.starryeye.visibility.mcpserver.security;

import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.function.ServerRequest;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * tool을 부른 사용자다. token의 {@code sub}와 {@code client_id}다.
 *
 * <p>stateless transport는 요청마다 {@link #context(ServerRequest)}로 이 값을 {@link McpTransportContext}에 넣는다.
 * tool 메서드는 {@code McpTransportContext} 인자로 받아 {@link #from(McpTransportContext)}로 꺼낸다.
 * handle을 사용자에게 묶는 key의 {@code <sub>}가 여기서 온다(안내서 11장).
 * client가 보낸 값이 아니라 검증한 token에서 꺼낸 값이라는 점이 중요하다.
 */
public record McpCaller(String subject, String clientId) {

	static final String SUBJECT = "sub";

	static final String CLIENT_ID = "client_id";

	/** Spring Security가 검증한 token을 읽는다. 인증이 없으면 빈 context다. */
	public static McpTransportContext context(ServerRequest request) {
		return request.principal()
				.filter(JwtAuthenticationToken.class::isInstance)
				.map(JwtAuthenticationToken.class::cast)
				.map(authentication -> McpTransportContext.create(Map.of(
						SUBJECT, authentication.getToken().getSubject(),
						CLIENT_ID, Objects.toString(authentication.getToken().getClaimAsString(CLIENT_ID), ""))))
				.orElse(McpTransportContext.EMPTY);
	}

	public static McpCaller from(McpTransportContext context) {
		if (!(context.get(SUBJECT) instanceof String subject) || subject.isBlank()) {
			throw new IllegalStateException("인증된 사용자가 없다");
		}
		return new McpCaller(subject, Objects.toString(context.get(CLIENT_ID), ""));
	}

	/** stateless transport가 넣어 둔 token의 sub다. 인증이 없으면 비어 있다. */
	public static Optional<String> subject(McpTransportContext context) {
		return (context.get(SUBJECT) instanceof String subject && !subject.isBlank())
				? Optional.of(subject) : Optional.empty();
	}
}
