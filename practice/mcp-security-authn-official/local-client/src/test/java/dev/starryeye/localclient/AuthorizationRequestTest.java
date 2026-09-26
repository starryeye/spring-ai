package dev.starryeye.localclient;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationRequestTest {

	@Test
	void PKCE_resource_state를_담고_client_secret은_없다() {
		AuthorizationServer server = new AuthorizationServer("http://localhost:8111/mcp", "http://localhost:9010",
				"http://localhost:9010/oauth2/authorize", "http://localhost:9010/oauth2/token", true);
		Pkce pkce = Pkce.fromVerifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk");

		URI uri = AuthorizationRequest.uri(server, "local-mcp-client", URI.create("http://127.0.0.1:50000/callback"),
				"openid profile", "state-1", pkce);

		Map<String, String> query = Form.decode(uri.getRawQuery());
		assertThat(uri.toString()).startsWith("http://localhost:9010/oauth2/authorize?");
		assertThat(query).containsEntry("response_type", "code")
				.containsEntry("client_id", "local-mcp-client")
				.containsEntry("redirect_uri", "http://127.0.0.1:50000/callback")
				.containsEntry("scope", "openid profile")
				.containsEntry("state", "state-1")
				.containsEntry("code_challenge", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
				.containsEntry("code_challenge_method", "S256")
				.containsEntry("resource", "http://localhost:8111/mcp")
				.doesNotContainKey("client_secret");
	}
}
