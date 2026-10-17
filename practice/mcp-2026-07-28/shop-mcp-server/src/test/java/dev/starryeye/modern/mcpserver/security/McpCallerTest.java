package dev.starryeye.modern.mcpserver.security;

import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpCallerTest {

	/** CIMD에서는 client_id가 client 문서의 주소다(안내서 13장). 이 practice의 agent 문서 주소를 쓴다. */
	static final String CLIENT_ID = "https://localhost:8182/oauth/client.json";

	static MockHttpServletRequest 요청(Principal principal) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
		request.setUserPrincipal(principal);
		return request;
	}

	static JwtAuthenticationToken 인증(String subject, String clientId) {
		Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(subject)
				.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
		if (clientId != null) {
			jwt.claim("client_id", clientId);
		}
		return new JwtAuthenticationToken(jwt.build());
	}

	static String base64(String name) {
		return "=?base64?" + Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8)) + "?=";
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
	void 인증도_Mcp_Name도_없으면_빈_context이고_사용자를_꺼내면_예외다() {
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
		assertThat(McpCaller.subject(McpTransportContext.create(Map.of(McpCaller.SUBJECT, "user2")))).contains("user2");
		assertThat(McpCaller.subject(McpTransportContext.EMPTY)).isEmpty();
		assertThat(McpCaller.subject(McpTransportContext.create(Map.of(McpCaller.SUBJECT, " ")))).isEmpty();
	}

	@Test
	void Mcp_Name_header를_풀어_transport_context에_넣는다() {
		MockHttpServletRequest plain = 요청(인증("user", CLIENT_ID));
		plain.addHeader("Mcp-Name", "checkout");
		MockHttpServletRequest encoded = 요청(인증("user", CLIENT_ID));
		encoded.addHeader("Mcp-Name", base64("checkout"));

		assertThat(McpCaller.mcpName(McpCaller.context(plain))).isEqualTo("checkout");
		assertThat(McpCaller.mcpName(McpCaller.context(encoded))).isEqualTo("checkout");
	}

	@Test
	void Mcp_Name이_없거나_풀_수_없으면_mcpName은_null이다() {
		MockHttpServletRequest broken = 요청(인증("user", CLIENT_ID));
		broken.addHeader("Mcp-Name", "=?base64?%%%?=");

		assertThat(McpCaller.mcpName(McpCaller.context(요청(인증("user", CLIENT_ID))))).isNull();
		assertThat(McpCaller.mcpName(McpCaller.context(broken))).isNull();
		assertThat(McpCaller.mcpName(McpTransportContext.EMPTY)).isNull();
	}

	/** SDK transport가 {@code Mcp-Name}을 푸는 규칙과 같다. 다르면 header로 본 이름과 SDK가 대조한 이름이 어긋난다. */
	@Test
	void decodeMcpName은_base64_표기만_UTF_8로_푼다() {
		assertThat(McpCaller.decodeMcpName("searchProducts")).isEqualTo("searchProducts");
		assertThat(McpCaller.decodeMcpName(base64("장바구니"))).isEqualTo("장바구니");
		// 앞뒤 표기 중 하나만 있으면 base64 표기가 아니라 이름 그대로다.
		assertThat(McpCaller.decodeMcpName("=?base64?Y2hlY2tvdXQ=")).isEqualTo("=?base64?Y2hlY2tvdXQ=");
		assertThat(McpCaller.decodeMcpName("=?base64?%%%?=")).isNull();
		// 앞뒤 표기가 겹쳐 안쪽 값이 없어도 예외 없이 풀 수 없다고 답한다.
		assertThat(McpCaller.decodeMcpName("=?base64?=")).isNull();
		assertThat(McpCaller.decodeMcpName(null)).isNull();
	}
}
