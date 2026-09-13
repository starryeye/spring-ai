package dev.starryeye.officialagent;

import java.util.Map;

/**
 * 발견 결과. MCP 서버가 선언한 리소스 식별자와, 그 리소스를 지키는 인가 서버의 메타데이터다.
 *
 * @param resource 보호 리소스 메타데이터의 {@code resource} — 토큰 요청의 {@code resource} 로 그대로 쓴다
 * @param issuer 보호 리소스가 지목한 인가 서버
 * @param authorizationServerMetadata RFC 8414 또는 OIDC 디스커버리 문서
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

	/** RFC 9207 을 지원한다고 광고했는가. 광고했다면 응답에 iss 가 없을 때 거부해야 한다. */
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
