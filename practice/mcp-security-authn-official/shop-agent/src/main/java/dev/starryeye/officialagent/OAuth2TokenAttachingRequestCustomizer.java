package dev.starryeye.officialagent;

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
 * MCP 로 나가는 모든 HTTP 요청에 로그인한 사용자의 액세스 토큰을 붙인다.
 *
 * <p>community 버전에서 {@code OAuth2AuthorizationCodeSyncHttpRequestCustomizer} 가
 * 하던 일이고, 이 practice 가 직접 쓰는 두 클래스 중 하나다.
 *
 * <p>토큰은 <b>에이전트의 것이 아니라 사용자의 것</b>이다.
 * {@code authorization_code} 로 발급되어 {@code sub} 가 로그인한 사람이다.
 */
public class OAuth2TokenAttachingRequestCustomizer implements McpSyncHttpClientRequestCustomizer {

    private static final Logger log = LoggerFactory.getLogger(OAuth2TokenAttachingRequestCustomizer.class);

    private final OAuth2AuthorizedClientManager authorizedClientManager;

    private final String clientRegistrationId;

    public OAuth2TokenAttachingRequestCustomizer(OAuth2AuthorizedClientManager authorizedClientManager,
                                                 String clientRegistrationId) {
        this.authorizedClientManager = authorizedClientManager;
        this.clientRegistrationId = clientRegistrationId;
    }

    @Override
    public void customize(HttpRequest.Builder builder, String method, URI endpoint, String body,
                          McpTransportContext context) {
        Object candidate = context.get(SecurityMcpTransportContextProvider.AUTHENTICATION_KEY);

        if (!(candidate instanceof Authentication authentication)) {
            log.debug("전송 컨텍스트에 인증이 없다 — 토큰을 붙이지 않는다 ({} {})", method, endpoint);
            return;
        }

        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                .withClientRegistrationId(this.clientRegistrationId)
                .principal(authentication)
                .build();

        OAuth2AuthorizedClient authorizedClient = this.authorizedClientManager.authorize(authorizeRequest);

        if (authorizedClient == null) {
            log.debug("인가된 클라이언트를 찾지 못했다 ({}) — 토큰을 붙이지 않는다", this.clientRegistrationId);
            return;
        }

        builder.header(HttpHeaders.AUTHORIZATION,
                "Bearer " + authorizedClient.getAccessToken().getTokenValue());
        log.debug("토큰을 헤더에 붙였다 (사용자={})", authentication.getName());
    }
}
