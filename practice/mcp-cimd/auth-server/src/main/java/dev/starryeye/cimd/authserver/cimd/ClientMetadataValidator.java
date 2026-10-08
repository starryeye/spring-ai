package dev.starryeye.cimd.authserver.cimd;

import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 가져온 CIMD 문서의 내용을 검사한다(MCP 2026-07-28 Client Registration, CIMD draft §4.1).
 *
 * <p>문서의 {@code client_id}가 문서 주소와 다르면, 남의 문서를 복사해 자기 주소에 올린 client가 그 client 행세를 한다.
 * 문서는 누구나 읽을 수 있으므로 비밀을 담을 수 없고, 공유 비밀로 인증하는 방식도 쓸 수 없다.
 * 이 서버는 {@code none}과 {@code private_key_jwt}만 받는다.
 * redirect 주소도 문서를 쓴 쪽이 정하므로, host가 있는 {@code https}이거나 loopback {@code http}만 받는다
 * (MCP Authorization — Communication Security).
 * 모르는 field는 무시한다. ChatGPT 문서의 {@code token_endpoint_auth_methods_supported} 같은 확장 field가 그런 예다.
 */
public final class ClientMetadataValidator {

	private static final List<String> FORBIDDEN = List.of("client_secret", "client_secret_expires_at");

	/** 각 자리가 0~255이고 앞에 0이 붙지 않은 IPv4 주소다. 이런 문자열만 {@link InetAddress}에 넘겨 DNS 조회를 막는다. */
	private static final Pattern IPV4_LITERAL = Pattern
			.compile("(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");

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
			URI uri = (item instanceof String text) ? absoluteWithoutFragment(text) : null;
			if (uri == null) {
				throw new InvalidClientMetadataException("redirect_uris는 fragment 없는 절대 주소여야 한다: " + item);
			}
			if (!isHttpsOrLoopbackHttp(uri)) {
				throw new InvalidClientMetadataException(
						"redirect_uris는 host가 있는 https이거나 loopback http여야 한다: " + item);
			}
			result.add((String) item);
		}
		return List.copyOf(result);
	}

	private static URI absoluteWithoutFragment(String value) {
		try {
			URI uri = new URI(value);
			return (uri.isAbsolute() && uri.getRawFragment() == null) ? uri : null;
		}
		catch (URISyntaxException ex) {
			return null;
		}
	}

	/**
	 * authorization code는 redirect 주소로 간다.
	 * 다른 host로 가는 {@code http}면 code가 평문으로 network를 지나고,
	 * {@code javascript:}·{@code data:} 같은 주소면 browser가 엉뚱한 곳으로 이동한다.
	 * 그래서 host가 있는 {@code https}와, 같은 기기로 돌아오는 {@code http}만 받는다.
	 */
	private static boolean isHttpsOrLoopbackHttp(URI uri) {
		String host = uri.getHost();
		if (host == null) {
			return false;
		}
		if ("https".equalsIgnoreCase(uri.getScheme())) {
			return true;
		}
		return "http".equalsIgnoreCase(uri.getScheme()) && isLoopbackHost(host.toLowerCase(Locale.ROOT));
	}

	/**
	 * host가 {@code localhost}, 127.0.0.0/8의 IPv4 주소, loopback IPv6 주소({@code [::1]})인지 본다.
	 * DNS로 풀지 않고 글자로만 판단한다.
	 * {@code 127.evil.example}처럼 숫자로 시작하는 이름이 IP로 해석되지 않도록, IP 표기만 {@link InetAddress}에 넘긴다.
	 */
	private static boolean isLoopbackHost(String host) {
		if ("localhost".equals(host)) {
			return true;
		}
		if (host.startsWith("[") && host.endsWith("]")) {
			return isLoopbackLiteral(host.substring(1, host.length() - 1));
		}
		return IPV4_LITERAL.matcher(host).matches() && isLoopbackLiteral(host);
	}

	/** IP 주소 문자열은 {@link InetAddress#getByName}이 DNS 없이 바로 해석한다. */
	private static boolean isLoopbackLiteral(String literal) {
		try {
			return InetAddress.getByName(literal).isLoopbackAddress();
		}
		catch (UnknownHostException ex) {
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
