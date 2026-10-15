package dev.starryeye.community.agent.discovery;

import org.springaicommunity.mcp.security.client.sync.oauth2.metadata.McpMetadataDiscoveryService;
import org.springaicommunity.mcp.security.client.sync.oauth2.metadata.ProtectedResourceMetadata;
import org.springaicommunity.mcp.security.client.sync.oauth2.metadata.WwwAuthenticateParameters;
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
 * Authorization Server를 찾는다(MCP 2025-11-25 Authorization — Authorization Server Discovery, 안내서 3장).
 *
 * <p>Protected Resource Metadata까지는 module의 {@link McpMetadataDiscoveryService}가 해 준다.
 * 401 challenge를 읽고, {@code resource_metadata}를 따라가고, 없으면 well-known 경로를
 * 차례로 시도하고, PRM의 {@code resource}가 부른 URL과 같은지 확인한다.
 * 다만 {@code resource_metadata}가 설정한 MCP Server와 같은 origin인지는 보지 않는다.
 * 그래서 module의 두 단계(challenge 읽기 → PRM 가져오기) 사이에서 여기서 확인한다.
 *
 * <p>그다음 단계(Authorization Server Metadata discovery와 검증)는 module이 다루지 않으므로 여기서 한다.
 */
public class McpAuthorizationDiscovery {

    /** IPv4 literal의 한 자리(0~255)다. */
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
     * @param trustedIssuer credentials가 등록된 Authorization Server. PRM이 다른 곳을 가리키면 그 metadata도
     *                      요청하지 않고 멈춘다(MCP 2026-07-28 issuer binding, Security Best Practices — SSRF).
     */
    public DiscoveredAuthorization discover(String resourceUrl, String trustedIssuer) {
        ProtectedResourceMetadata protectedResource;
        try {
            // module의 getMcpMetadata(resourceUrl)는 challenge를 읽고 곧바로 resource_metadata를 따라간다.
            // 그 사이에서 origin을 확인하려고 module의 두 단계를 나눠 부른다.
            WwwAuthenticateParameters challenge = this.protectedResourceDiscovery.getWwwAuthenticateParameters(resourceUrl);
            if (challenge != null && challenge.getResourceMetadata() != null) {
                requireSameOrigin(challenge.getResourceMetadata(), resourceUrl);
            }
            protectedResource = this.protectedResourceDiscovery.getMcpMetadata(resourceUrl, challenge)
                    .protectedResourceMetadata();
        }
        catch (IllegalStateException | RestClientException ex) {
            // module은 resource 불일치와 metadata 없음을 IllegalStateException으로, 401이 아닌 오류 응답을
            // RestClientException으로 올린다.
            throw new McpDiscoveryException("보호 리소스 메타데이터를 얻지 못했다: " + ex.getMessage(), ex);
        }

        List<String> servers = protectedResource.authorizationServers();
        if (servers == null || servers.isEmpty()) {
            throw new McpDiscoveryException("보호 리소스 메타데이터에 authorization_servers 가 없다: " + resourceUrl);
        }
        // 목록의 순서는 신뢰의 근거가 아니다. credentials가 등록된 issuer가 어디에 있든 그것을 쓴다.
        // 없으면 목록의 어떤 Authorization Server에도 요청하지 않는다(local-client의 Discovery와 같은 규칙).
        if (servers.stream().map(String::valueOf).noneMatch(trustedIssuer::equals)) {
            throw new McpDiscoveryException(
                    "자격증명은 %s 에 등록된 것인데 PRM 이 가리키는 인가 서버는 %s 다 — 메타데이터를 요청하지 않는다"
                            .formatted(trustedIssuer, servers));
        }
        String issuer = trustedIssuer;

        return new DiscoveredAuthorization(protectedResource.resource(), issuer, authorizationServerMetadata(issuer));
    }

    /**
     * challenge의 {@code resource_metadata}는 설정한 MCP Server와 origin(scheme·host·port)이 같을 때만 따라간다.
     *
     * <p>RFC 9728은 다른 origin을 금지하지 않는다. 하지만 이 agent는 MCP Server 주소를 설정으로 고정하므로,
     * 다른 origin을 가리키는 값을 따라갈 이유가 없다. 따라가면 401을 준 쪽이 고른 주소로 agent가 요청을 보낸다
     * (Security Best Practices — SSRF). host는 {@link URI#getHost()}로 비교하므로
     * {@code http://localhost:8111@evil.example/}처럼 userinfo로 꾸민 주소도 다른 origin이다.
     */
    private static void requireSameOrigin(String resourceMetadata, String resourceUrl) {
        URI target;
        try {
            target = URI.create(resourceMetadata);
        }
        catch (IllegalArgumentException ex) {
            throw new McpDiscoveryException("챌린지의 resource_metadata 가 URL 이 아니다: " + resourceMetadata, ex);
        }
        if (!sameOrigin(target, URI.create(resourceUrl))) {
            throw new McpDiscoveryException("챌린지의 resource_metadata(%s) 가 MCP Server(%s) 와 다른 origin 이다 — 따라가지 않는다"
                    .formatted(resourceMetadata, resourceUrl));
        }
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme() != null && a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost())
                && port(a) == port(b);
    }

    /** port를 적지 않았으면 scheme의 기본 port로 본다. */
    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
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
            // MCP Security Best Practices: authorization URL을 열기 전에 scheme을 확인하고,
            // http라면 loopback 주소인지 확인한다(MUST).
            requireHttpUrl(metadata, "authorization_endpoint");
            requireHttpUrl(metadata, "token_endpoint");
            return metadata;
        }
        throw new McpDiscoveryException("인가 서버 메타데이터를 찾지 못했다: " + issuer);
    }

    /**
     * MCP Security Best Practices — OAuth Authorization URL Validation: authorization URL은 {@code https}이거나
     * loopback 주소({@code localhost}·127.0.0.0/8·{@code ::1})의 {@code http}만 허용한다(MUST).
     * DNS를 조회하지 않고 URI의 host 문자열로만 판단한다.
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

    /** {@code localhost}, 127.0.0.0/8의 IPv4 literal, {@code ::1}이면 loopback이다. */
    private static boolean isLoopback(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(lower) || "[::1]".equals(lower) || "::1".equals(lower)
                || LOOPBACK_IPV4.matcher(lower).matches();
    }

    /** RFC 8414 §3.1과 OpenID Connect Discovery의 경로 규칙이다. MCP는 RFC 8414를 먼저 시도하라고 한다. */
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
