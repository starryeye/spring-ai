package dev.starryeye.localclient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * authorization code를 access token으로 바꾼다.
 *
 * <p>public client라서 client 인증 header가 없다. 본문의 `client_id`와 PKCE `code_verifier`가
 * client_secret의 역할을 한다. `resource`를 다시 보내 이 token의 `aud`를 MCP Server로 정한다.
 */
public class TokenClient {

	private final HttpClient http;

	public TokenClient(HttpClient http) {
		this.http = http;
	}

	public TokenResponse exchange(AuthorizationServer server, String clientId, String code, URI redirectUri, Pkce pkce) {
		Map<String, String> form = new LinkedHashMap<>();
		form.put("grant_type", "authorization_code");
		form.put("code", code);
		form.put("redirect_uri", redirectUri.toString());
		form.put("client_id", clientId);
		form.put("code_verifier", pkce.verifier());
		form.put("resource", server.resource());

		HttpResponse<String> response = Http.send(this.http, HttpRequest.newBuilder(URI.create(server.tokenEndpoint()))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(Form.encode(form)))
				.build());

		Map<String, Object> body = response.body().isBlank() ? Map.of() : Json.object(response.body());
		if (response.statusCode() != 200) {
			throw new LocalClientException("token 요청이 거절됐다(%d): %s %s".formatted(response.statusCode(),
					Objects.toString(body.get("error"), ""), Objects.toString(body.get("error_description"), "")).trim());
		}
		if (!(body.get("access_token") instanceof String accessToken)
				|| !"bearer".equalsIgnoreCase(String.valueOf(body.get("token_type")))) {
			throw new LocalClientException("token 응답에 Bearer access_token이 없다");
		}
		long expiresIn = body.get("expires_in") instanceof Number seconds ? seconds.longValue() : -1;
		return new TokenResponse(accessToken, expiresIn);
	}
}
