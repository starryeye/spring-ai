package dev.starryeye.cimd.agent.cimd;

import com.nimbusds.jose.jwk.JWKSet;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * agent가 올리는 두 client 문서와 JWKS의 내용이다(MCP 2026-07-28 Client Registration, CIMD draft §4).
 *
 * <p>문서는 누구나 읽으므로 비밀을 담지 않는다.
 * ChatGPT형은 key로 자신을 증명하므로 {@code jwks_uri}를 넣고, Claude형은 증명할 수단이 없어 {@code none}이다.
 * 두 문서 모두 같은 redirect 주소 하나만 올린다. Authorization Server는 이 목록에 없는 주소로 code를 보내지 않는다.
 */
public final class ClientMetadataDocuments {

	public static final String JWKS_PATH = "/oauth/jwks.json";

	private final ClientMetadataProperties properties;

	private final ClientSigningKey signingKey;

	private final JsonMapper json = JsonMapper.builder().build();

	public ClientMetadataDocuments(ClientMetadataProperties properties, ClientSigningKey signingKey) {
		this.properties = properties;
		this.signingKey = signingKey;
	}

	public String document(ClientType type) {
		Map<String, Object> document = new LinkedHashMap<>();
		document.put("client_id", type.clientId(this.properties.baseUrl()));
		document.put("client_name", type.clientName());
		document.put("client_uri", this.properties.baseUrl() + "/");
		document.put("redirect_uris", List.of(this.properties.redirectUri()));
		document.put("grant_types", List.of("authorization_code", "refresh_token"));
		document.put("response_types", List.of("code"));
		document.put("token_endpoint_auth_method", type.authenticationMethod().getValue());
		if (ClientAuthenticationMethod.PRIVATE_KEY_JWT.equals(type.authenticationMethod())) {
			document.put("token_endpoint_auth_signing_alg", "RS256");
			document.put("jwks_uri", this.properties.baseUrl() + JWKS_PATH);
		}
		return this.json.writeValueAsString(document);
	}

	/** public key만 담는다. {@code toPublicJWK()}가 비밀 key 값(d, p, q …)을 뺀다. */
	public String jwks() {
		return new JWKSet(this.signingKey.key().toPublicJWK()).toString();
	}
}
