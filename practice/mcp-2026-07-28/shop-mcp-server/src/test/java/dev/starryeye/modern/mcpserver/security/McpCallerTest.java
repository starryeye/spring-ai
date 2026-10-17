package dev.starryeye.modern.mcpserver.security;

import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.function.ServerRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpCallerTest {

	/** CIMD에서는 client_id가 client 문서의 주소다(안내서 13장). 이 practice의 agent 문서 주소를 쓴다. */
	static final String CLIENT_ID = "https://localhost:8182/oauth/client.json";

	static ServerRequest 요청(Object principal) {
		MockHttpServletRequest servlet = new MockHttpServletRequest("POST", "/mcp");
		if (principal instanceof java.security.Principal p) {
			servlet.setUserPrincipal(p);
		}
		return ServerRequest.create(servlet, List.of());
	}

	static JwtAuthenticationToken 인증(String subject, String clientId) {
		Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(subject)
				.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
		if (clientId != null) {
			jwt.claim("client_id", clientId);
		}
		return new JwtAuthenticationToken(jwt.build());
	}

	@Test
	void token의_sub와_client_id를_transport_context에_넣는다() {
		McpTransportContext context = McpCaller.context(요청(인증("user", CLIENT_ID)));

		assertThat(McpCaller.from(context)).isEqualTo(new McpCaller("user", CLIENT_ID));
	}

	@Test
	void client_id가_없는_token이면_빈_값이다() {
		McpTransportContext context = McpCaller.context(요청(인증("user", null)));

		assertThat(McpCaller.from(context)).isEqualTo(new McpCaller("user", ""));
	}

	@Test
	void sub가_없는_token이어도_context를_만들고_사용자는_없다() {
		McpTransportContext context = McpCaller.context(요청(인증(null, CLIENT_ID)));

		// 예외 없이 만들어져야 ToolVisibility가 사용자를 모르는 요청을 손님으로 다룰 수 있다.
		assertThat(McpCaller.subject(context)).isEmpty();
		assertThatThrownBy(() -> McpCaller.from(context)).isInstanceOf(IllegalStateException.class)
				.hasMessage("인증된 사용자가 없다");
	}

	@Test
	void 인증이_없으면_빈_context이고_사용자를_꺼내면_예외다() {
		McpTransportContext context = McpCaller.context(요청(null));

		assertThat(context).isSameAs(McpTransportContext.EMPTY);
		assertThatThrownBy(() -> McpCaller.from(context)).isInstanceOf(IllegalStateException.class)
				.hasMessage("인증된 사용자가 없다");
	}

	@Test
	void sub가_빈_문자열이면_사용자가_없는_것이다() {
		assertThatThrownBy(() -> McpCaller.from(McpTransportContext.create(Map.of("sub", " "))))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void subject는_인증된_sub만_돌려준다() {
		assertThat(McpCaller.subject(McpTransportContext.create(java.util.Map.of(McpCaller.SUBJECT, "user2"))))
				.contains("user2");
		assertThat(McpCaller.subject(McpTransportContext.EMPTY)).isEmpty();
		assertThat(McpCaller.subject(McpTransportContext.create(java.util.Map.of(McpCaller.SUBJECT, " "))))
				.isEmpty();
	}
}
