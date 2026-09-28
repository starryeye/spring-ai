package dev.starryeye.authz.agent.discovery;

import java.util.List;
import java.util.Map;

public final class DiscoveryFixtures {

	public static final String RESOURCE = "http://localhost:8141/mcp";

	public static final String ISSUER = "http://localhost:9030";

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
				"authorization_response_iss_parameter_supported", true));
	}
}
