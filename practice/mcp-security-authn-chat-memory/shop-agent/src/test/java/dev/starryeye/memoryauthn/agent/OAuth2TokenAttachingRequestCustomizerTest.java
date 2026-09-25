package dev.starryeye.memoryauthn.agent;

import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP 로 나가는 요청에 client 주인의 token 이 붙는지를 검증한다. 주인 전달, {@code Bearer } 접두사,
 * 헤더 이름, registration id 가 모두 손으로 짠 배선이라, 회귀가 나면 런타임에서 401 로만 드러난다.
 */
class OAuth2TokenAttachingRequestCustomizerTest {

    private static final String REGISTRATION_ID = "authserver";

    private final Authentication alice = new UsernamePasswordAuthenticationToken("alice", "n/a", List.of());

    private HttpRequest.Builder newBuilder() {
        return HttpRequest.newBuilder(URI.create("http://localhost:8131/mcp")).GET();
    }

    private OAuth2AuthorizedClient authorizedClientWithToken(String tokenValue) {
        ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId("memory-agent")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("http://localhost:9020/oauth2/authorize")
                .tokenUri("http://localhost:9020/oauth2/token")
                .build();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, tokenValue, Instant.now(), Instant.now().plusSeconds(3600));
        return new OAuth2AuthorizedClient(registration, "user", accessToken);
    }

    @Test
    void 주인의_토큰을_Bearer_로_붙이고_registrationId_와_주인을_넘긴다() {
        OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
        when(manager.authorize(any(OAuth2AuthorizeRequest.class))).thenReturn(authorizedClientWithToken("abc123"));
        var customizer = new OAuth2TokenAttachingRequestCustomizer(manager, REGISTRATION_ID, this.alice);
        HttpRequest.Builder builder = newBuilder();

        customizer.customize(builder, "POST", URI.create("http://localhost:8131/mcp"), "{}", McpTransportContext.EMPTY);

        assertThat(builder.build().headers().firstValue(HttpHeaders.AUTHORIZATION)).contains("Bearer abc123");
        ArgumentCaptor<OAuth2AuthorizeRequest> request = ArgumentCaptor.forClass(OAuth2AuthorizeRequest.class);
        verify(manager).authorize(request.capture());
        assertThat(request.getValue().getClientRegistrationId()).isEqualTo(REGISTRATION_ID);
        assertThat(request.getValue().getPrincipal()).isSameAs(this.alice);
    }

    @Test
    void transport_context_없이_나가는_session_종료_DELETE_에도_토큰을_붙인다() {
        // McpSyncClient#closeGracefully 는 transport context 를 넘기지 않는다.
        OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
        when(manager.authorize(any(OAuth2AuthorizeRequest.class))).thenReturn(authorizedClientWithToken("abc123"));
        var customizer = new OAuth2TokenAttachingRequestCustomizer(manager, REGISTRATION_ID, this.alice);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:8131/mcp")).DELETE();

        customizer.customize(builder, "DELETE", URI.create("http://localhost:8131/mcp"), null, McpTransportContext.EMPTY);

        assertThat(builder.build().headers().firstValue(HttpHeaders.AUTHORIZATION)).contains("Bearer abc123");
    }

    @Test
    void 인가된_클라이언트를_못_찾으면_헤더를_붙이지_않는다() {
        OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
        when(manager.authorize(any(OAuth2AuthorizeRequest.class))).thenReturn(null);
        var customizer = new OAuth2TokenAttachingRequestCustomizer(manager, REGISTRATION_ID, this.alice);
        HttpRequest.Builder builder = newBuilder();

        customizer.customize(builder, "POST", URI.create("http://localhost:8131/mcp"), "{}", McpTransportContext.EMPTY);

        assertThat(builder.build().headers().firstValue(HttpHeaders.AUTHORIZATION)).isEmpty();
    }
}
