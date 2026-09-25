package dev.starryeye.localclient;

import java.util.Map;
import java.util.Objects;

/**
 * callback으로 돌아온 authorization response를 확인하고 code를 꺼낸다.
 *
 * <p>확인 순서: `state`(내가 보낸 요청의 응답인가) → `iss`(내가 보낸 Authorization Server의 응답인가,
 * RFC 9207) → `error` → `code`. `iss`는 오류 응답에서도 확인한다.
 */
public final class AuthorizationResponse {

	private AuthorizationResponse() {
	}

	public static String code(Map<String, String> params, String expectedState, AuthorizationServer server) {
		if (!expectedState.equals(params.get("state"))) {
			throw new LocalClientException("callback의 state가 보낸 값과 다르다. 다른 요청의 응답이다");
		}
		String iss = params.get("iss");
		if (iss != null ? !iss.equals(server.issuer()) : server.issParameterSupported()) {
			throw new LocalClientException("callback의 iss(%s)가 %s가 아니다. 다른 Authorization Server의 응답이다"
					.formatted(iss, server.issuer()));
		}
		String error = params.get("error");
		if (error != null) {
			throw new LocalClientException("Authorization Server가 거절했다: %s %s"
					.formatted(error, Objects.toString(params.get("error_description"), "")).trim());
		}
		String code = params.get("code");
		if (code == null || code.isBlank()) {
			throw new LocalClientException("callback에 code가 없다");
		}
		return code;
	}
}
