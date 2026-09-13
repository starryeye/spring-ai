package dev.starryeye.officialagent;

import java.util.List;
import java.util.Map;

final class DiscoveryFixtures {

	static final String RESOURCE = "http://localhost:8111/mcp";

	static final String ISSUER = "http://localhost:9010";

	private DiscoveryFixtures() {
	}

	static DiscoveredAuthorization discovered() {
		return discovered(ISSUER);
	}

	static DiscoveredAuthorization discovered(String issuer) {
		return new DiscoveredAuthorization(RESOURCE, issuer, Map.of(
				"issuer", issuer,
				"authorization_endpoint", issuer + "/oauth2/authorize",
				"token_endpoint", issuer + "/oauth2/token",
				"jwks_uri", issuer + "/oauth2/jwks",
				"code_challenge_methods_supported", List.of("S256"),
				"authorization_response_iss_parameter_supported", true));
	}
}
