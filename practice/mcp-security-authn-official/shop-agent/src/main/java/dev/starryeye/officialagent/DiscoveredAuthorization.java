package dev.starryeye.officialagent;

import java.util.Map;

/**
 * discovery 결과다.
 * MCP Server가 선언한 resource 식별자와, 그 resource를 지키는 Authorization Server의 metadata를 담는다.
 *
 * @param resource PRM의 {@code resource}. authorization request와 token request의 {@code resource}로 그대로 쓴다
 * @param issuer PRM이 가리킨 Authorization Server
 * @param authorizationServerMetadata RFC 8414 또는 OpenID Connect Discovery 문서
 */
public record DiscoveredAuthorization(String resource, String issuer, Map<String, Object> authorizationServerMetadata) {

	static final String ISS_PARAMETER_SUPPORTED = "authorization_response_iss_parameter_supported";

	public DiscoveredAuthorization {
		authorizationServerMetadata = Map.copyOf(authorizationServerMetadata);
	}

	public String authorizationEndpoint() {
		return required("authorization_endpoint");
	}

	public String tokenEndpoint() {
		return required("token_endpoint");
	}

	public String jwksUri() {
		return required("jwks_uri");
	}

	/**
	 * Authorization Server가 RFC 9207을 지원한다고 알렸는지 돌려준다.
	 * 알렸다면 응답에 iss가 없을 때 거부해야 한다.
	 */
	public boolean issParameterSupported() {
		return Boolean.TRUE.equals(this.authorizationServerMetadata.get(ISS_PARAMETER_SUPPORTED));
	}

	private String required(String name) {
		if (this.authorizationServerMetadata.get(name) instanceof String value) {
			return value;
		}
		throw new McpDiscoveryException("인가 서버 메타데이터에 %s 가 없다: %s".formatted(name, this.issuer));
	}
}
