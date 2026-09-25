package dev.starryeye.memoryauthn.agent;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;

import java.net.URI;
import java.net.http.HttpRequest;

/**
 * 이 MCP client 의 주인(로그인한 사용자)의 access token 을 MCP 로 나가는 모든 HTTP 요청에 붙인다.
 *
 * <p>token 은 agent 의 것이 아니라 사용자의 것이다. {@code authorization_code} 로 발급되어 {@code sub} 가
 * 로그인한 사람이다.
 *
 * <p>주인은 client 를 만들 때 정해지고({@link UserMcpClients}) 요청의 SecurityContext 를 보지 않는다. 그래서
 * 요청 thread 밖에서 도는 tool 호출과, transport context 없이 나가는 session 종료 {@code DELETE} 에도 같은
 * 사용자의 token 이 실린다.
 *
 * <p>{@code authorize(...)} 가 던지는 예외(token endpoint 장애 등)는 잡지 않고 그대로 올린다 — token 없이
 * 요청을 보내 401 로 뭉개지 않기 위해서다.
 */
public class OAuth2TokenAttachingRequestCustomizer implements McpSyncHttpClientRequestCustomizer {

    private static final Logger log = LoggerFactory.getLogger(OAuth2TokenAttachingRequestCustomizer.class);

    private final OAuth2AuthorizedClientManager authorizedClientManager;

    private final String clientRegistrationId;

    private final Authentication owner;

    public OAuth2TokenAttachingRequestCustomizer(OAuth2AuthorizedClientManager authorizedClientManager,
                                                 String clientRegistrationId, Authentication owner) {
        this.authorizedClientManager = authorizedClientManager;
        this.clientRegistrationId = clientRegistrationId;
        this.owner = owner;
    }

    @Override
    public void customize(HttpRequest.Builder builder, String method, URI endpoint, String body,
                          McpTransportContext context) {
        OAuth2AuthorizedClient authorizedClient = this.authorizedClientManager.authorize(OAuth2AuthorizeRequest
                .withClientRegistrationId(this.clientRegistrationId)
                .principal(this.owner)
                .build());

        if (authorizedClient == null) {
            log.debug("{} 의 authorized client 가 없다 — token 을 붙이지 않는다 ({} {})",
                    this.owner.getName(), method, endpoint);
            return;
        }

        builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + authorizedClient.getAccessToken().getTokenValue());
    }
}
