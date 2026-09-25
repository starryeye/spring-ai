package dev.starryeye.localclient;

/**
 * discovery로 알아낸 값.
 *
 * @param resource MCP Server의 공식 이름. authorization request와 token request에 `resource`로 보낸다
 * @param issuer Authorization Server의 이름
 * @param issParameterSupported callback에 `iss`를 넣어 준다고 metadata에 광고했는지
 */
public record AuthorizationServer(String resource, String issuer, String authorizationEndpoint, String tokenEndpoint,
		boolean issParameterSupported) {
}
