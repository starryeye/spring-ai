package dev.starryeye.community.agent.discovery;

import org.springaicommunity.mcp.security.client.sync.oauth2.metadata.McpMetadataDiscoveryService;
import org.springaicommunity.mcp.security.client.sync.oauth2.metadata.ProtectedResourceMetadata;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 인가 서버를 발견한다(MCP 2025-11-25 인가 §2.3).
 *
 * <p>보호 리소스 메타데이터까지는 모듈의 {@link McpMetadataDiscoveryService} 가 해 준다 —
 * 401 챌린지를 읽고, {@code resource_metadata} 를 따라가고, 없으면 well-known 경로를
 * 차례로 시도하고, 메타데이터의 {@code resource} 가 우리가 부른 URL 과 같은지 확인한다.
 *
 * <p>그다음 단계(인가 서버 메타데이터 발견과 검증)는 모듈이 다루지 않으므로 여기서 한다.
 */
public class McpAuthorizationDiscovery {

    /** IPv4 literal 의 한 자리(0~255). */
    private static final String OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";

    /** 127.0.0.0/8. */
    private static final Pattern LOOPBACK_IPV4 = Pattern.compile("127\\." + OCTET + "\\." + OCTET + "\\." + OCTET);

    private static final String AUTHORIZATION_SERVER_METADATA = "/.well-known/oauth-authorization-server";

    private static final String OPENID_CONFIGURATION = "/.well-known/openid-configuration";

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {
            };

    /** MCP Server나 Authorization Server가 연결을 받아 주기까지 기다리는 시간이다. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    /** 요청 하나를 보내고 응답 본문을 다 읽을 때까지 기다리는 시간이다. */
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final McpMetadataDiscoveryService protectedResourceDiscovery;

    private final RestClient restClient;

    public McpAuthorizationDiscovery(McpMetadataDiscoveryService protectedResourceDiscovery, RestClient restClient) {
        this.protectedResourceDiscovery = protectedResourceDiscovery;
        this.restClient = restClient;
    }

    /**
     * discovery 요청에 쓸 request factory다. JDK {@link HttpClient}로 보내고 연결과 응답에 시간 제한을 둔다.
     *
     * <p>{@code RestClient.create()}는 classpath에서 고른 client(이 agent에서는 Reactor Netty)의 기본값을 쓴다.
     * 그 기본값에는 응답 전체에 걸리는 시간 제한이 없어서, 응답을 조금씩 흘리는 서버를 끝없이 기다린다.
     * Spring의 {@link JdkClientHttpRequestFactory}는 read timeout을 요청을 보낸 때부터 응답 본문을 다 읽을 때까지에 건다.
     *
     * <p>redirect는 따라가지 않는다. JDK {@code HttpClient}의 기본값이지만, 의존성이 바뀌어도 그대로이도록 적어 둔다.
     * HTTP/1.1로 고정해 {@code http} 주소에 h2c upgrade를 시도하지 않는다.
     */
    public static ClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return requestFactory;
    }

    /**
     * @param trustedIssuer 자격증명이 등록된 Authorization Server. PRM 이 다른 곳을 가리키면 그 metadata 도
     *                      요청하지 않고 멈춘다(MCP 2026-07-28 issuer binding, Security Best Practices — SSRF).
     */
    public DiscoveredAuthorization discover(String resourceUrl, String trustedIssuer) {
        ProtectedResourceMetadata protectedResource;
        try {
            protectedResource = this.protectedResourceDiscovery.getMcpMetadata(resourceUrl).protectedResourceMetadata();
        }
        catch (IllegalStateException | RestClientException ex) {
            // 모듈은 resource 불일치·metadata 없음을 IllegalStateException 으로, 401 이 아닌 오류 응답을
            // RestClientException 으로 올린다.
            throw new McpDiscoveryException("보호 리소스 메타데이터를 얻지 못했다: " + ex.getMessage(), ex);
        }

        List<String> servers = protectedResource.authorizationServers();
        if (servers == null || servers.isEmpty()) {
            throw new McpDiscoveryException("보호 리소스 메타데이터에 authorization_servers 가 없다: " + resourceUrl);
        }
        String issuer = servers.get(0);
        if (!trustedIssuer.equals(issuer)) {
            throw new McpDiscoveryException(
                    "자격증명은 %s 에 등록된 것인데 PRM 이 가리키는 인가 서버는 %s 다 — 메타데이터를 요청하지 않는다"
                            .formatted(trustedIssuer, issuer));
        }

        return new DiscoveredAuthorization(protectedResource.resource(), issuer, authorizationServerMetadata(issuer));
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
            if (!(metadata.get("code_challenge_methods_supported") instanceof List<?> methods)
                    || !methods.contains("S256")) {
                throw new McpDiscoveryException("인가 서버가 PKCE S256 을 광고하지 않는다: " + issuer);
            }
            // MCP Security Best Practices: authorization URL 을 열기 전에 스킴과, http 면 loopback 주소인지 확인한다(MUST).
            requireHttpUrl(metadata, "authorization_endpoint");
            requireHttpUrl(metadata, "token_endpoint");
            return metadata;
        }
        throw new McpDiscoveryException("인가 서버 메타데이터를 찾지 못했다: " + issuer);
    }

    /**
     * MCP Security Best Practices — OAuth Authorization URL Validation: authorization URL 은 {@code https}, 또는
     * loopback 주소({@code localhost}·127.0.0.0/8·{@code ::1})의 {@code http} 만 허용한다(MUST).
     * DNS 를 조회하지 않고 URI 의 host 문자열로만 판단한다.
     */
    private static void requireHttpUrl(Map<String, Object> metadata, String name) {
        Object value = metadata.get(name);
        URI uri = null;
        if (value instanceof String url) {
            try {
                uri = URI.create(url);
            }
            catch (IllegalArgumentException ex) {
                uri = null;
            }
        }
        String scheme = (uri != null) ? uri.getScheme() : null;
        String host = (uri != null) ? uri.getHost() : null;
        boolean allowed = host != null
                && ("https".equalsIgnoreCase(scheme) || ("http".equalsIgnoreCase(scheme) && isLoopback(host)));
        if (!allowed) {
            throw new McpDiscoveryException("인가 서버 메타데이터의 %s 가 https URL 도, loopback 주소의 http URL 도 아니다: %s"
                    .formatted(name, value));
        }
    }

    /** {@code localhost}, 127.0.0.0/8 의 IPv4 literal, {@code ::1} 이면 loopback 이다. */
    private static boolean isLoopback(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(lower) || "[::1]".equals(lower) || "::1".equals(lower)
                || LOOPBACK_IPV4.matcher(lower).matches();
    }

    /** RFC 8414 §3.1 과 OIDC 디스커버리의 경로 규칙. MCP 는 RFC 8414 를 먼저 시도하라고 한다. */
    private static List<String> metadataUrls(String issuer) {
        URI uri = URI.create(issuer);
        String origin = uri.getScheme() + "://" + uri.getRawAuthority();
        String path = uri.getRawPath();

        if (path == null || path.isEmpty() || "/".equals(path)) {
            return List.of(origin + AUTHORIZATION_SERVER_METADATA, origin + OPENID_CONFIGURATION);
        }
        return List.of(origin + AUTHORIZATION_SERVER_METADATA + path, origin + OPENID_CONFIGURATION + path,
                origin + path + OPENID_CONFIGURATION);
    }

    private Map<String, Object> json(String url) {
        try {
            return this.restClient.get()
                    .uri(url)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> response.getStatusCode().is2xxSuccessful()
                            ? response.bodyTo(JSON_OBJECT) : null);
        }
        catch (RestClientException ex) {
            throw new McpDiscoveryException("%s 를 읽지 못했다: %s".formatted(url, ex.getMessage()), ex);
        }
    }
}
