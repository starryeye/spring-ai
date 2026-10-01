package dev.starryeye.stateless.localclient;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * token endpoint의 응답 중 이 client가 쓰는 값. public client에는 refresh token이 오지 않는다.
 *
 * @param scope 응답의 `scope`. 요청과 같으면 Authorization Server가 생략할 수 있어 `null`일 수 있다(RFC 6749 §5.1)
 */
public record TokenResponse(String accessToken, long expiresIn, String scope) {

	/** 받은 scope다. 응답에 `scope`가 없으면 요청한 scope를 그대로 받은 것이다. */
	public Set<String> grantedScopes(Collection<String> requested) {
		return (this.scope == null) ? new LinkedHashSet<>(requested)
				: new LinkedHashSet<>(List.of(this.scope.trim().split("\\s+")));
	}
}
