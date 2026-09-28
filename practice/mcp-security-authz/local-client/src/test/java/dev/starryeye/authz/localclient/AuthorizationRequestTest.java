package dev.starryeye.authz.localclient;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationRequestTest {

	@Test
	void PKCE_resource_state를_담고_client_secret은_없다() {
		AuthorizationServer server = new AuthorizationServer("http://localhost:8141/mcp", "http://localhost:9030",
				"http://localhost:9030/oauth2/authorize", "http://localhost:9030/oauth2/token", true);
		Pkce pkce = Pkce.fromVerifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk");

		URI uri = AuthorizationRequest.uri(server, "local-mcp-client", URI.create("http://127.0.0.1:50000/callback"),
				"products:read products:write", "state-1", pkce);

		Map<String, String> query = Form.decode(uri.getRawQuery());
		assertThat(uri.toString()).startsWith("http://localhost:9030/oauth2/authorize?");
		assertThat(query).containsEntry("response_type", "code")
				.containsEntry("client_id", "local-mcp-client")
				.containsEntry("redirect_uri", "http://127.0.0.1:50000/callback")
				.containsEntry("scope", "products:read products:write")
				.containsEntry("state", "state-1")
				.containsEntry("code_challenge", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
				.containsEntry("code_challenge_method", "S256")
				.containsEntry("resource", "http://localhost:8141/mcp")
				.doesNotContainKey("client_secret");
	}

	@Test
	void scope가_없으면_scope_parameter를_보내지_않는다() {
		AuthorizationServer server = new AuthorizationServer("http://localhost:8141/mcp", "http://localhost:9030",
				"http://localhost:9030/oauth2/authorize", "http://localhost:9030/oauth2/token", true);

		URI uri = AuthorizationRequest.uri(server, "local-mcp-client", URI.create("http://127.0.0.1:50000/callback"),
				null, "state-1", Pkce.fromVerifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));

		assertThat(Form.decode(uri.getRawQuery())).doesNotContainKey("scope");
	}
}
