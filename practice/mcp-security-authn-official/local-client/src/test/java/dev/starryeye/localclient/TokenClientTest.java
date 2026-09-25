package dev.starryeye.localclient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenClientTest {

	FakeServer as;

	AuthorizationServer server;

	Pkce pkce = Pkce.fromVerifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk");

	@BeforeEach
	void setUp() throws Exception {
		this.as = new FakeServer();
		this.server = new AuthorizationServer("http://localhost:8111/mcp", this.as.origin(),
				this.as.origin() + "/oauth2/authorize", this.as.origin() + "/oauth2/token", true);
	}

	@AfterEach
	void tearDown() {
		this.as.close();
	}

	TokenResponse exchange() {
		return new TokenClient(HttpClient.newHttpClient()).exchange(this.server, "local-mcp-client", "code-1",
				URI.create("http://127.0.0.1:50000/callback"), this.pkce);
	}

	@Test
	void client_secret_없이_client_id와_code_verifier로_token을_받는다() {
		this.as.on("POST", "/oauth2/token", FakeServer.Reply.json(
				"{\"access_token\":\"at-1\",\"token_type\":\"Bearer\",\"expires_in\":300}"));

		TokenResponse token = exchange();

		assertThat(token.accessToken()).isEqualTo("at-1");
		assertThat(token.expiresIn()).isEqualTo(300);
		FakeServer.Recorded request = this.as.requests.get(0);
		assertThat(request.headers()).doesNotContainKey("Authorization");
		assertThat(Form.decode(request.body())).containsEntry("grant_type", "authorization_code")
				.containsEntry("code", "code-1")
				.containsEntry("redirect_uri", "http://127.0.0.1:50000/callback")
				.containsEntry("client_id", "local-mcp-client")
				.containsEntry("code_verifier", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
				.containsEntry("resource", "http://localhost:8111/mcp")
				.doesNotContainKey("client_secret");
	}

	@Test
	void 오류_응답은_error와_설명을_보여_준다() {
		this.as.on("POST", "/oauth2/token", new FakeServer.Reply(400, Map.of("Content-Type", "application/json"),
				"{\"error\":\"invalid_grant\",\"error_description\":\"code_verifier mismatch\"}"));

		assertThatThrownBy(this::exchange).isInstanceOf(LocalClientException.class)
				.hasMessageContaining("invalid_grant").hasMessageContaining("code_verifier mismatch");
	}

	@Test
	void JSON이_아닌_오류_응답도_상태_코드로_알린다() {
		this.as.on("POST", "/oauth2/token", FakeServer.Reply.status(401));

		assertThatThrownBy(this::exchange).isInstanceOf(LocalClientException.class).hasMessageContaining("401");
	}

	@Test
	void HTML_오류_응답도_상태_코드를_잃지_않는다() {
		this.as.on("POST", "/oauth2/token", new FakeServer.Reply(502, Map.of("Content-Type", "text/html"),
				"<html><body>Bad Gateway</body></html>"));

		assertThatThrownBy(this::exchange).isInstanceOf(LocalClientException.class).hasMessageContaining("502");
	}
}
