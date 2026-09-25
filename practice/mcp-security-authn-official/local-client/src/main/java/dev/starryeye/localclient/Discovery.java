package dev.starryeye.localclient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP Server 주소 하나에서 Authorization Server까지 찾아간다(안내서 3장).
 *
 * <ol>
 *   <li>token 없이 `initialize`를 보내 `401`의 `resource_metadata`를 받는다</li>
 *   <li>PRM을 읽고 `resource`가 부른 주소와 같은지 본다</li>
 *   <li>`authorization_servers`가 이 client가 등록된 issuer인지 본다. 아니면 더 요청하지 않는다</li>
 *   <li>Authorization Server Metadata를 읽고 `issuer`, PKCE `S256`, endpoint 주소를 확인한다</li>
 * </ol>
 */
public class Discovery {

	private static final String PROTECTED_RESOURCE_METADATA = "/.well-known/oauth-protected-resource";

	private static final String AUTHORIZATION_SERVER_METADATA = "/.well-known/oauth-authorization-server";

	private static final String OPENID_CONFIGURATION = "/.well-known/openid-configuration";

	private static final Pattern RESOURCE_METADATA = Pattern.compile("resource_metadata=\"([^\"]+)\"");

	private static final Pattern LOOPBACK_IPV4 = Pattern.compile("127(\\.\\d{1,3}){3}");

	private static final String INITIALIZE = """
			{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",\
			"capabilities":{},"clientInfo":{"name":"local-mcp-client","version":"0.0.1"}}}""";

	private final HttpClient http;

	public Discovery(HttpClient http) {
		this.http = http;
	}

	/**
	 * @param trustedIssuer 이 client(`local-mcp-client`)가 등록된 Authorization Server
	 */
	public AuthorizationServer discover(String resourceUrl, String trustedIssuer) {
		Map<String, Object> prm = protectedResourceMetadata(resourceUrl);
		if (!(prm.get("authorization_servers") instanceof List<?> servers) || servers.isEmpty()) {
			throw new LocalClientException("PRM에 authorization_servers가 없다");
		}
		String issuer = String.valueOf(servers.get(0));
		if (!issuer.equals(trustedIssuer)) {
			throw new LocalClientException("이 client는 %s에 등록돼 있는데, MCP Server가 가리키는 Authorization Server는 %s다"
					.formatted(trustedIssuer, issuer));
		}
		Map<String, Object> metadata = authorizationServerMetadata(issuer);
		return new AuthorizationServer((String) prm.get("resource"), issuer,
				requireEndpoint(metadata, "authorization_endpoint"), requireEndpoint(metadata, "token_endpoint"),
				Boolean.TRUE.equals(metadata.get("authorization_response_iss_parameter_supported")));
	}

	private Map<String, Object> protectedResourceMetadata(String resourceUrl) {
		String fromChallenge = resourceMetadataUrl(resourceUrl);
		if (fromChallenge != null) {
			Map<String, Object> metadata = getJson(fromChallenge);
			if (metadata == null) {
				throw new LocalClientException("401이 알려 준 주소에서 PRM을 받지 못했다: " + fromChallenge);
			}
			return requireResource(metadata, resourceUrl);
		}
		URI uri = URI.create(resourceUrl);
		String origin = uri.getScheme() + "://" + uri.getRawAuthority();
		String path = uri.getRawPath();
		if (path != null && !path.isEmpty() && !"/".equals(path)) {
			Map<String, Object> metadata = getJson(origin + PROTECTED_RESOURCE_METADATA + path);
			if (metadata != null) {
				return requireResource(metadata, resourceUrl);
			}
		}
		Map<String, Object> metadata = getJson(origin + PROTECTED_RESOURCE_METADATA);
		if (metadata != null) {
			return requireResource(metadata, origin);
		}
		throw new LocalClientException("PRM을 찾지 못했다: " + resourceUrl);
	}

	/** token 없이 `initialize`를 보내고, `401`의 `WWW-Authenticate`에서 PRM 주소를 꺼낸다. */
	private String resourceMetadataUrl(String resourceUrl) {
		HttpResponse<String> response = Http.send(this.http, HttpRequest.newBuilder(URI.create(resourceUrl))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream")
				.POST(HttpRequest.BodyPublishers.ofString(INITIALIZE))
				.build());
		if (response.statusCode() != 401) {
			throw new LocalClientException("token 없는 요청에 401이 아니라 %d가 왔다".formatted(response.statusCode()));
		}
		return response.headers().firstValue("WWW-Authenticate")
				.map(RESOURCE_METADATA::matcher)
				.filter(Matcher::find)
				.map(m -> m.group(1))
				.orElse(null);
	}

	private static Map<String, Object> requireResource(Map<String, Object> metadata, String expected) {
		if (!expected.equals(metadata.get("resource"))) {
			throw new LocalClientException("PRM의 resource(%s)가 부른 주소(%s)와 다르다"
					.formatted(metadata.get("resource"), expected));
		}
		return metadata;
	}

	private Map<String, Object> authorizationServerMetadata(String issuer) {
		for (String url : metadataUrls(issuer)) {
			Map<String, Object> metadata = getJson(url);
			if (metadata == null) {
				continue;
			}
			if (!issuer.equals(metadata.get("issuer"))) {
				throw new LocalClientException("metadata의 issuer(%s)가 %s가 아니다".formatted(metadata.get("issuer"), issuer));
			}
			if (!(metadata.get("code_challenge_methods_supported") instanceof List<?> methods)
					|| !methods.contains("S256")) {
				throw new LocalClientException("Authorization Server가 PKCE S256을 지원하지 않는다: " + issuer);
			}
			return metadata;
		}
		throw new LocalClientException("Authorization Server Metadata를 찾지 못했다: " + issuer);
	}

	/** RFC 8414를 먼저, OpenID Connect Discovery를 나중에 시도한다. */
	static List<String> metadataUrls(String issuer) {
		URI uri = URI.create(issuer);
		String origin = uri.getScheme() + "://" + uri.getRawAuthority();
		String path = uri.getRawPath();
		if (path == null || path.isEmpty() || "/".equals(path)) {
			return List.of(origin + AUTHORIZATION_SERVER_METADATA, origin + OPENID_CONFIGURATION);
		}
		return List.of(origin + AUTHORIZATION_SERVER_METADATA + path, origin + OPENID_CONFIGURATION + path,
				origin + path + OPENID_CONFIGURATION);
	}

	/** browser로 열 주소와 code를 보낼 주소는 https만 받는다. 개발용 loopback 주소만 http를 허용한다. */
	private static String requireEndpoint(Map<String, Object> metadata, String name) {
		if (!(metadata.get(name) instanceof String url)) {
			throw new LocalClientException("metadata에 %s가 없다".formatted(name));
		}
		URI uri;
		try {
			uri = URI.create(url);
		}
		catch (IllegalArgumentException ex) {
			throw new LocalClientException("%s가 주소 형식이 아니다: %s".formatted(name, url), ex);
		}
		String scheme = uri.getScheme();
		String host = uri.getHost();
		boolean allowed = "https".equalsIgnoreCase(scheme)
				|| ("http".equalsIgnoreCase(scheme) && host != null && isLoopback(host));
		if (!allowed) {
			throw new LocalClientException("%s가 https도, loopback 주소의 http도 아니다: %s".formatted(name, url));
		}
		return url;
	}

	private static boolean isLoopback(String host) {
		String lower = host.toLowerCase(Locale.ROOT);
		return "localhost".equals(lower) || "[::1]".equals(lower) || "::1".equals(lower)
				|| LOOPBACK_IPV4.matcher(lower).matches();
	}

	private Map<String, Object> getJson(String url) {
		HttpResponse<String> response = Http.send(this.http,
				HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json").GET().build());
		return response.statusCode() == 200 ? Json.object(response.body()) : null;
	}
}
