package dev.starryeye.stateless.localclient;

import java.util.Set;

/**
 * 지금 쓰는 access token과 그 scope다.
 * step-up이 새 token을 받으면 바꾸고, MCP 요청은 보낼 때마다 여기서 token을 읽는다.
 */
public final class TokenHolder {

	private volatile String accessToken;

	private volatile Set<String> scopes;

	public TokenHolder(String accessToken, Set<String> scopes) {
		this.accessToken = accessToken;
		this.scopes = Set.copyOf(scopes);
	}

	public String accessToken() {
		return this.accessToken;
	}

	public Set<String> scopes() {
		return this.scopes;
	}

	public void update(String accessToken, Set<String> scopes) {
		this.accessToken = accessToken;
		this.scopes = Set.copyOf(scopes);
	}
}
