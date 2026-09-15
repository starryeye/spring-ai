package dev.starryeye.shopagent;

import org.springaicommunity.mcp.security.client.sync.oauth2.metadata.McpMetadataDiscoveryService;
import org.springaicommunity.mcp.security.client.sync.oauth2.metadata.ProtectedResourceMetadata;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.Map;

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

    private static final String AUTHORIZATION_SERVER_METADATA = "/.well-known/oauth-authorization-server";

    private static final String OPENID_CONFIGURATION = "/.well-known/openid-configuration";

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {
            };

    private final McpMetadataDiscoveryService protectedResourceDiscovery;

    private final RestClient restClient;

    public McpAuthorizationDiscovery(McpMetadataDiscoveryService protectedResourceDiscovery, RestClient restClient) {
        this.protectedResourceDiscovery = protectedResourceDiscovery;
        this.restClient = restClient;
    }

    public DiscoveredAuthorization discover(String resourceUrl) {
        ProtectedResourceMetadata protectedResource;
        try {
            protectedResource = this.protectedResourceDiscovery.getMcpMetadata(resourceUrl).protectedResourceMetadata();
        }
        catch (IllegalStateException ex) {
            throw new McpDiscoveryException("보호 리소스 메타데이터를 얻지 못했다: " + ex.getMessage());
        }

        List<String> servers = protectedResource.authorizationServers();
        if (servers == null || servers.isEmpty()) {
            throw new McpDiscoveryException("보호 리소스 메타데이터에 authorization_servers 가 없다: " + resourceUrl);
        }
        String issuer = servers.get(0);

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
            return metadata;
        }
        throw new McpDiscoveryException("인가 서버 메타데이터를 찾지 못했다: " + issuer);
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
        return this.restClient.get()
                .uri(url)
                .accept(MediaType.APPLICATION_JSON)
                .exchange((request, response) -> response.getStatusCode().is2xxSuccessful()
                        ? response.bodyTo(JSON_OBJECT) : null);
    }
}
