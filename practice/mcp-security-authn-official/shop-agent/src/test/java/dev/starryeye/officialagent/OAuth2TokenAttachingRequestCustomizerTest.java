package dev.starryeye.officialagent;

import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP 로 나가는 요청에 실제로 토큰이 붙는지를 검증한다. 이 클래스가 하는 일 자체가
 * {@code AUTHENTICATION_KEY} 조회, {@code Bearer } 접두사, 헤더 이름, registration id
 * 전달까지 전부 손으로 짠 배선이라 — 회귀가 나면 스위트는 초록인데 런타임에서만
 * 조용히 401 로 드러난다. 이 테스트가 그 간극을 없앤다.
 */
class OAuth2TokenAttachingRequestCustomizerTest {

    private static final String REGISTRATION_ID = "authserver";

    private HttpRequest.Builder newBuilder() {
        return HttpRequest.newBuilder(URI.create("http://localhost:8111/mcp")).GET();
    }

    private OAuth2AuthorizedClient authorizedClientWithToken(String tokenValue) {
        ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId("official-shop-agent")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("http://localhost:9010/oauth2/authorize")
                .tokenUri("http://localhost:9010/oauth2/token")
                .build();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, tokenValue, Instant.now(), Instant.now().plusSeconds(3600));
        return new OAuth2AuthorizedClient(registration, "user", accessToken);
    }

    @Test
    void 인증이_있으면_토큰을_Bearer_로_헤더에_붙인다() {
        OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
        when(manager.authorize(any(OAuth2AuthorizeRequest.class)))
                .thenReturn(authorizedClientWithToken("abc123"));
        var customizer = new OAuth2TokenAttachingRequestCustomizer(manager, REGISTRATION_ID);

        Authentication authentication = new UsernamePasswordAuthenticationToken("user", "n/a", List.of());
        McpTransportContext context = McpTransportContext.create(
                Map.of(SecurityMcpTransportContextProvider.AUTHENTICATION_KEY, authentication));
        HttpRequest.Builder builder = newBuilder();

        customizer.customize(builder, "POST", URI.create("http://localhost:8111/mcp"), "{}", context);

        HttpRequest request = builder.build();
        assertThat(request.headers().firstValue(HttpHeaders.AUTHORIZATION))
                .contains("Bearer abc123");
    }

    @Test
    void 전송_컨텍스트에_인증이_없으면_헤더를_붙이지_않는다() {
        OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
        var customizer = new OAuth2TokenAttachingRequestCustomizer(manager, REGISTRATION_ID);

        HttpRequest.Builder builder = newBuilder();

        customizer.customize(builder, "POST", URI.create("http://localhost:8111/mcp"), "{}", McpTransportContext.EMPTY);

        HttpRequest request = builder.build();
        assertThat(request.headers().firstValue(HttpHeaders.AUTHORIZATION)).isEmpty();
        verify(manager, never()).authorize(any());
    }

    @Test
    void 인가된_클라이언트를_못_찾으면_헤더를_붙이지_않는다() {
        OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
        when(manager.authorize(any(OAuth2AuthorizeRequest.class))).thenReturn(null);
        var customizer = new OAuth2TokenAttachingRequestCustomizer(manager, REGISTRATION_ID);

        Authentication authentication = new UsernamePasswordAuthenticationToken("user", "n/a", List.of());
        McpTransportContext context = McpTransportContext.create(
                Map.of(SecurityMcpTransportContextProvider.AUTHENTICATION_KEY, authentication));
        HttpRequest.Builder builder = newBuilder();

        customizer.customize(builder, "POST", URI.create("http://localhost:8111/mcp"), "{}", context);

        HttpRequest request = builder.build();
        assertThat(request.headers().firstValue(HttpHeaders.AUTHORIZATION)).isEmpty();
    }
}
