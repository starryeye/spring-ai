package dev.starryeye.localclient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

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
				.timeout(REQUEST_TIMEOUT)
				.POST(HttpRequest.BodyPublishers.ofString(Form.encode(form)))
				.build());

		// 상태 코드부터 본다. 본문이 JSON이 아니어도(502 HTML 오류 페이지 등) 상태 코드는 메시지에 남아야 한다.
		if (response.statusCode() != 200) {
			Map<String, Object> body = leniently(response.body());
			throw new LocalClientException("token 요청이 거절됐다(%d): %s %s".formatted(response.statusCode(),
					Objects.toString(body.get("error"), ""), Objects.toString(body.get("error_description"), "")).trim());
		}
		Map<String, Object> body = response.body().isBlank() ? Map.of() : Json.object(response.body());
		if (!(body.get("access_token") instanceof String accessToken)
				|| !"bearer".equalsIgnoreCase(String.valueOf(body.get("token_type")))) {
			throw new LocalClientException("token 응답에 Bearer access_token이 없다");
		}
		long expiresIn = body.get("expires_in") instanceof Number seconds ? seconds.longValue() : -1;
		return new TokenResponse(accessToken, expiresIn);
	}

	/** 오류 응답의 본문은 JSON이 아닐 수도 있다(예: 502의 HTML 오류 페이지). 그때는 빈 값으로 본다. */
	private static Map<String, Object> leniently(String body) {
		if (body.isBlank()) {
			return Map.of();
		}
		try {
			return Json.object(body);
		}
		catch (LocalClientException ex) {
			return Map.of();
		}
	}
}
