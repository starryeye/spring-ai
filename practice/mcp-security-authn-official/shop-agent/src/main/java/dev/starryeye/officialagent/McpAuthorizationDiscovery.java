package dev.starryeye.officialagent;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 인가 서버를 발견한다(MCP 2025-11-25 인가 §2.3).
 *
 * <p>순서는 명세가 정해 두었다.
 * <ol>
 *   <li>토큰 없이 MCP 서버를 호출해 401 과 {@code WWW-Authenticate} 를 받는다</li>
 *   <li>헤더의 {@code resource_metadata} 를 따라간다. 없으면 경로형 → 루트형 well-known 순서로 찾는다</li>
 *   <li>메타데이터의 {@code resource} 가 우리가 부른 URL 과 같은지 확인한다(RFC 9728 §3.3)</li>
 *   <li>{@code authorization_servers} 가 자격증명이 등록된 issuer 인지 먼저 확인한다(아니면 더 요청하지 않는다)</li>
 *   <li>인가 서버 메타데이터를 RFC 8414 → OIDC 순서로 찾는다</li>
 *   <li>메타데이터의 {@code issuer} 가 같은지, PKCE {@code S256} 을 지원하는지,
 *       {@code authorization_endpoint}·{@code token_endpoint} 가 http(s) 인지 확인한다</li>
 * </ol>
 *
 * <p>이 단계들이 있어야 "MCP 서버 주소 하나만 알면 나머지는 서버가 알려준다"가 성립한다.
 * 인가 서버 주소를 설정에 적어 두는 방식은 명세가 아니다.
 */
public class McpAuthorizationDiscovery {

	private static final String PROTECTED_RESOURCE_METADATA = "/.well-known/oauth-protected-resource";

	private static final String AUTHORIZATION_SERVER_METADATA = "/.well-known/oauth-authorization-server";

	private static final String OPENID_CONFIGURATION = "/.well-known/openid-configuration";

	private static final Pattern RESOURCE_METADATA = Pattern.compile("resource_metadata=\"([^\"]+)\"");

	private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
			new ParameterizedTypeReference<>() {
			};

	/** 발견용 탐침. 서버는 이 본문을 읽기 전에 401 을 준다. */
	private static final String INITIALIZE = """
			{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",\
			"capabilities":{},"clientInfo":{"name":"discovery-probe","version":"1.0.0"}}}""";

	private final RestClient restClient;

	public McpAuthorizationDiscovery(RestClient restClient) {
		this.restClient = restClient;
	}

	/**
	 * @param trustedIssuer 자격증명이 등록된 Authorization Server. PRM 이 다른 곳을 가리키면 그 metadata 도
	 *                      요청하지 않고 멈춘다(MCP 2026-07-28 issuer binding, Security Best Practices — SSRF).
	 */
	public DiscoveredAuthorization discover(String resourceUrl, String trustedIssuer) {
		Map<String, Object> protectedResource = protectedResourceMetadata(resourceUrl);

		if (!(protectedResource.get("authorization_servers") instanceof List<?> servers) || servers.isEmpty()) {
			throw new McpDiscoveryException("보호 리소스 메타데이터에 authorization_servers 가 없다: " + resourceUrl);
		}
		String issuer = String.valueOf(servers.get(0));
		if (!trustedIssuer.equals(issuer)) {
			throw new McpDiscoveryException(
					"자격증명은 %s 에 등록된 것인데 PRM 이 가리키는 인가 서버는 %s 다 — 메타데이터를 요청하지 않는다"
							.formatted(trustedIssuer, issuer));
		}

		return new DiscoveredAuthorization((String) protectedResource.get("resource"), issuer,
				authorizationServerMetadata(issuer));
	}

	private Map<String, Object> protectedResourceMetadata(String resourceUrl) {
		String fromChallenge = resourceMetadataUrlFromChallenge(resourceUrl);
		if (fromChallenge != null) {
			Map<String, Object> metadata = json(fromChallenge);
			if (metadata == null) {
				throw new McpDiscoveryException("챌린지가 가리킨 메타데이터를 받지 못했다: " + fromChallenge);
			}
			return verifyResource(metadata, resourceUrl);
		}

		URI uri = URI.create(resourceUrl);
		String origin = origin(uri);
		String path = uri.getRawPath();

		if (path != null && !path.isEmpty() && !"/".equals(path)) {
			Map<String, Object> metadata = json(origin + PROTECTED_RESOURCE_METADATA + path);
			if (metadata != null) {
				return verifyResource(metadata, resourceUrl);
			}
		}

		Map<String, Object> metadata = json(origin + PROTECTED_RESOURCE_METADATA);
		if (metadata != null) {
			// 루트형 메타데이터의 리소스 식별자는 서버 루트다.
			return verifyResource(metadata, origin);
		}

		throw new McpDiscoveryException("보호 리소스 메타데이터를 찾지 못했다: " + resourceUrl);
	}

	/**
	 * RFC 9728 §3.3 — 메타데이터의 {@code resource} 는 그 메타데이터 URL 을 만든
	 * 리소스 식별자와 같아야 한다. 다르면 남의 메타데이터를 보고 있는 것이다.
	 */
	private static Map<String, Object> verifyResource(Map<String, Object> metadata, String expected) {
		if (!expected.equals(metadata.get("resource"))) {
			throw new McpDiscoveryException("메타데이터의 resource(%s) 가 요청한 리소스(%s) 와 다르다"
					.formatted(metadata.get("resource"), expected));
		}
		return metadata;
	}

	private String resourceMetadataUrlFromChallenge(String resourceUrl) {
		return this.restClient.post()
				.uri(resourceUrl)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
				.body(INITIALIZE)
				.exchange((request, response) -> {
					if (response.getStatusCode().value() != 401) {
						throw new McpDiscoveryException("토큰 없는 요청에 401 이 아니라 %s 가 왔다: %s"
								.formatted(response.getStatusCode(), resourceUrl));
					}
					String header = response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE);
					if (header == null) {
						return null;
					}
					Matcher matcher = RESOURCE_METADATA.matcher(header);
					return matcher.find() ? matcher.group(1) : null;
				});
	}

	private Map<String, Object> authorizationServerMetadata(String issuer) {
		for (String url : metadataUrls(issuer)) {
			Map<String, Object> metadata = json(url);
			if (metadata == null) {
				continue;
			}
			if (!issuer.equals(metadata.get("issuer"))) {
				throw new McpDiscoveryException("메타데이터의 issuer(%s) 가 요청한 인가 서버(%s) 와 다르다"
						.formatted(metadata.get("issuer"), issuer));
			}
			// MCP 2025-11-25 인가: 클라이언트는 S256 지원을 확인하고, 없으면 진행하면 안 된다.
			if (!(metadata.get("code_challenge_methods_supported") instanceof List<?> methods)
					|| !methods.contains("S256")) {
				throw new McpDiscoveryException("인가 서버가 PKCE S256 을 광고하지 않는다: " + issuer);
			}
			// MCP Security Best Practices: authorization URL 을 열기 전에 스킴을 확인한다(MUST).
			requireHttpUrl(metadata, "authorization_endpoint");
			requireHttpUrl(metadata, "token_endpoint");
			return metadata;
		}
		throw new McpDiscoveryException("인가 서버 메타데이터를 찾지 못했다: " + issuer);
	}

	private static void requireHttpUrl(Map<String, Object> metadata, String name) {
		Object value = metadata.get(name);
		String scheme = null;
		if (value instanceof String url) {
			try {
				scheme = URI.create(url).getScheme();
			}
			catch (IllegalArgumentException ex) {
				scheme = null;
			}
		}
		if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
			throw new McpDiscoveryException("인가 서버 메타데이터의 %s 가 http(s) URL 이 아니다: %s".formatted(name, value));
		}
	}

	/** RFC 8414 §3.1 과 OIDC 디스커버리의 경로 규칙. MCP 는 RFC 8414 를 먼저 시도하라고 한다. */
	private static List<String> metadataUrls(String issuer) {
		URI uri = URI.create(issuer);
		String origin = origin(uri);
		String path = uri.getRawPath();

		if (path == null || path.isEmpty() || "/".equals(path)) {
			return List.of(origin + AUTHORIZATION_SERVER_METADATA, origin + OPENID_CONFIGURATION);
		}
		return List.of(origin + AUTHORIZATION_SERVER_METADATA + path, origin + OPENID_CONFIGURATION + path,
				origin + path + OPENID_CONFIGURATION);
	}

	private Map<String, Object> json(String url) {
		return this.restClient.get()
				.uri(url)
				.accept(MediaType.APPLICATION_JSON)
				.exchange((request, response) -> response.getStatusCode().is2xxSuccessful()
						? response.bodyTo(JSON_OBJECT) : null);
	}

	private static String origin(URI uri) {
		return uri.getScheme() + "://" + uri.getRawAuthority();
	}
}
