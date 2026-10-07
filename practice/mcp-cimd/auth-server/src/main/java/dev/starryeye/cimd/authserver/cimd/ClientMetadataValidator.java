package dev.starryeye.cimd.authserver.cimd;

import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 가져온 CIMD 문서의 내용을 검사한다(MCP 2026-07-28 Client Registration, CIMD draft §4.1).
 *
 * <p>문서의 {@code client_id}가 문서 주소와 다르면, 남의 문서를 복사해 자기 주소에 올린 client가 그 client 행세를 한다.
 * 문서는 누구나 읽을 수 있으므로 비밀을 담을 수 없고, 공유 비밀로 인증하는 방식도 쓸 수 없다.
 * 이 서버는 {@code none}과 {@code private_key_jwt}만 받는다.
 * 모르는 field는 무시한다. ChatGPT 문서의 {@code token_endpoint_auth_methods_supported} 같은 확장 field가 그런 예다.
 */
public final class ClientMetadataValidator {

	private static final List<String> FORBIDDEN = List.of("client_secret", "client_secret_expires_at");

	private final ClientIdUrlValidator urlValidator;

	public ClientMetadataValidator(ClientIdUrlValidator urlValidator) {
		this.urlValidator = urlValidator;
	}

	public ClientMetadata validate(URI documentUrl, Map<String, Object> document) {
		String clientId = text(document, "client_id");
		if (!documentUrl.toString().equals(clientId)) {
			throw new InvalidClientMetadataException(
					"문서의 client_id(%s)가 문서 주소(%s)와 다르다".formatted(clientId, documentUrl));
		}
		String clientName = text(document, "client_name");
		List<String> redirectUris = redirectUris(document.get("redirect_uris"));
		for (String field : FORBIDDEN) {
			if (document.containsKey(field)) {
				throw new InvalidClientMetadataException(field + "가 있다. 문서는 누구나 읽을 수 있어 비밀을 담을 수 없다");
			}
		}
		ClientAuthenticationMethod method = method(document.get("token_endpoint_auth_method"));
		String jwksUri = null;
		if (ClientAuthenticationMethod.PRIVATE_KEY_JWT.equals(method)) {
			jwksUri = text(document, "jwks_uri");
			this.urlValidator.validate(jwksUri);
			Object algorithm = document.get("token_endpoint_auth_signing_alg");
			if (algorithm != null && !"RS256".equals(algorithm)) {
				throw new InvalidClientMetadataException("token_endpoint_auth_signing_alg는 RS256만 받는다: " + algorithm);
			}
		}
		return new ClientMetadata(clientId, clientName, redirectUris, grantTypes(document.get("grant_types")),
				method, jwksUri);
	}

	private static String text(Map<String, Object> document, String field) {
		if (document.get(field) instanceof String value && !value.isBlank()) {
			return value;
		}
		throw new InvalidClientMetadataException(field + "가 없다");
	}

	private static List<String> redirectUris(Object value) {
		if (!(value instanceof List<?> list) || list.isEmpty()) {
			throw new InvalidClientMetadataException("redirect_uris가 없다");
		}
		List<String> result = new ArrayList<>();
		for (Object item : list) {
			if (!(item instanceof String uri) || !isAbsoluteWithoutFragment(uri)) {
				throw new InvalidClientMetadataException("redirect_uris는 fragment 없는 절대 주소여야 한다: " + item);
			}
			result.add(uri);
		}
		return List.copyOf(result);
	}

	private static boolean isAbsoluteWithoutFragment(String value) {
		try {
			URI uri = new URI(value);
			return uri.isAbsolute() && uri.getRawFragment() == null;
		}
		catch (URISyntaxException ex) {
			return false;
		}
	}

	private static ClientAuthenticationMethod method(Object value) {
		if (value == null) {
			throw new InvalidClientMetadataException("token_endpoint_auth_method가 없다");
		}
		if (ClientAuthenticationMethod.NONE.getValue().equals(value)) {
			return ClientAuthenticationMethod.NONE;
		}
		if (ClientAuthenticationMethod.PRIVATE_KEY_JWT.getValue().equals(value)) {
			return ClientAuthenticationMethod.PRIVATE_KEY_JWT;
		}
		throw new InvalidClientMetadataException("token_endpoint_auth_method는 none이나 private_key_jwt여야 한다: " + value);
	}

	private static Set<AuthorizationGrantType> grantTypes(Object value) {
		Set<AuthorizationGrantType> result = new LinkedHashSet<>();
		result.add(AuthorizationGrantType.AUTHORIZATION_CODE);
		if (value == null) {
			return Collections.unmodifiableSet(result);
		}
		if (!(value instanceof List<?> list) || !list.contains("authorization_code")) {
			throw new InvalidClientMetadataException("grant_types에 authorization_code가 없다");
		}
		if (list.contains("refresh_token")) {
			result.add(AuthorizationGrantType.REFRESH_TOKEN);
		}
		return Collections.unmodifiableSet(result);
	}
}
