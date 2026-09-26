package dev.starryeye.localclient;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** browser로 열 authorization request 주소를 만든다(안내서 5장). client_secret은 넣지 않는다. */
public final class AuthorizationRequest {

	private AuthorizationRequest() {
	}

	public static URI uri(AuthorizationServer server, String clientId, URI redirectUri, String scope, String state,
			Pkce pkce) {
		Map<String, String> params = new LinkedHashMap<>();
		params.put("response_type", "code");
		params.put("client_id", clientId);
		params.put("redirect_uri", redirectUri.toString());
		params.put("scope", scope);
		params.put("state", state);
		params.put("code_challenge", pkce.challenge());
		params.put("code_challenge_method", "S256");
		params.put("resource", server.resource());
		String endpoint = server.authorizationEndpoint();
		return URI.create(endpoint + (endpoint.contains("?") ? "&" : "?") + Form.encode(params));
	}
}
