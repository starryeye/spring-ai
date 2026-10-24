package dev.starryeye.authz.localclient;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizationResponseTest {

	AuthorizationServer server = new AuthorizationServer("http://localhost:8141/mcp", "http://localhost:9030",
			"http://localhost:9030/oauth2/authorize", "http://localhost:9030/oauth2/token", true);

	@Test
	void state와_iss가_맞으면_code를_돌려준다() {
		String code = AuthorizationResponse.code(
				Map.of("code", "abc", "state", "s1", "iss", "http://localhost:9030"), "s1", this.server);

		assertThat(code).isEqualTo("abc");
	}

	@Test
	void state가_다르면_멈춘다() {
		assertThatThrownBy(() -> AuthorizationResponse.code(
				Map.of("code", "abc", "state", "other", "iss", "http://localhost:9030"), "s1", this.server))
				.isInstanceOf(LocalClientException.class).hasMessageContaining("state");
	}

	@Test
	void iss가_다르면_멈춘다() {
		assertThatThrownBy(() -> AuthorizationResponse.code(
				Map.of("code", "abc", "state", "s1", "iss", "http://evil.example"), "s1", this.server))
				.isInstanceOf(LocalClientException.class)
				.hasMessage("callback의 iss가 요청한 issuer와 다르다. 다른 Authorization Server의 응답이다 "
						+ "(받은 iss: http://evil.example, 기대한 issuer: " + this.server.issuer() + ")");
	}

	@Test
	void iss를_보낸다고_광고했는데_없으면_멈춘다() {
		assertThatThrownBy(() -> AuthorizationResponse.code(Map.of("code", "abc", "state", "s1"), "s1", this.server))
				.isInstanceOf(LocalClientException.class)
				.hasMessage("callback의 iss가 요청한 issuer와 다르다. 다른 Authorization Server의 응답이다 "
						+ "(받은 iss: null, 기대한 issuer: " + this.server.issuer() + ")");
	}

	@Test
	void 거부_응답은_error를_그대로_보여_준다() {
		assertThatThrownBy(() -> AuthorizationResponse.code(Map.of("error", "access_denied", "state", "s1",
				"iss", "http://localhost:9030"), "s1", this.server))
				.isInstanceOf(LocalClientException.class).hasMessageContaining("access_denied");
	}
}
