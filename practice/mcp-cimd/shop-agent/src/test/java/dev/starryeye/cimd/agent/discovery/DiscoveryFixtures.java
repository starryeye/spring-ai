package dev.starryeye.cimd.agent.discovery;

import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.util.List;
import java.util.Map;

public final class DiscoveryFixtures {

	public static final String RESOURCE = "http://localhost:8171/mcp";

	public static final String ISSUER = "http://localhost:9060";

	/** 기본 client type(ChatGPT형)의 token endpoint 인증 방식이다. */
	public static final ClientAuthenticationMethod AUTH_METHOD = ClientAuthenticationMethod.PRIVATE_KEY_JWT;

	private DiscoveryFixtures() {
	}

	public static DiscoveredAuthorization discovered() {
		return discovered(ISSUER);
	}

	public static DiscoveredAuthorization discovered(String issuer) {
		return new DiscoveredAuthorization(RESOURCE, issuer, Map.of(
				"issuer", issuer,
				"authorization_endpoint", issuer + "/oauth2/authorize",
				"token_endpoint", issuer + "/oauth2/token",
				"jwks_uri", issuer + "/oauth2/jwks",
				"code_challenge_methods_supported", List.of("S256"),
				"authorization_response_iss_parameter_supported", true,
				"client_id_metadata_document_supported", true,
				"token_endpoint_auth_methods_supported", List.of("private_key_jwt", "none")), List.of("products:read"));
	}
}
