package dev.starryeye.cimd.agent.cimd;

import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * agent가 어느 제품처럼 Authorization Server에 붙을지 정한다.
 *
 * <p>두 경우 모두 문서 주소가 client_id다. 다른 것은 token endpoint에서 자신을 증명하는 방식이다.
 */
public enum ClientType {

	/**
	 * ChatGPT와 같은 방식이다. 문서에 {@code private_key_jwt}와 {@code jwks_uri}를 선언하고,
	 * token request마다 서명 key로 만든 client assertion을 붙인다.
	 */
	CHATGPT("/oauth/client.json", "Shop Agent (ChatGPT형)", ClientAuthenticationMethod.PRIVATE_KEY_JWT),

	/**
	 * Claude 앱과 같은 방식이다. 문서에 {@code none}을 선언한 public client다.
	 * token request에는 client_id와 PKCE의 code_verifier만 보낸다.
	 */
	CLAUDE("/oauth/public-client.json", "Shop Agent (Claude형)", ClientAuthenticationMethod.NONE);

	private final String path;

	private final String clientName;

	private final ClientAuthenticationMethod authenticationMethod;

	ClientType(String path, String clientName, ClientAuthenticationMethod authenticationMethod) {
		this.path = path;
		this.clientName = clientName;
		this.authenticationMethod = authenticationMethod;
	}

	public String path() {
		return this.path;
	}

	public String clientName() {
		return this.clientName;
	}

	public ClientAuthenticationMethod authenticationMethod() {
		return this.authenticationMethod;
	}

	/** 문서 주소다. 이 값이 그대로 client_id가 된다. */
	public String clientId(String baseUrl) {
		return baseUrl + this.path;
	}
}
