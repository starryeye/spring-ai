package dev.starryeye.memoryauthn.agent;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 만료된 액세스 토큰은 refresh 로 갱신되어야 하고, 갱신 요청에도 resource 가 실려야 한다.
 * resource 가 빠지면 갱신된 토큰의 aud 가 달라져 MCP 서버가 거부한다.
 */
class TokenRefreshTest {

    static final String ISSUER = "http://localhost:9020";

    static final String RESOURCE = "http://localhost:8131/mcp";

    @Test
    void 만료된_토큰을_resource_를_실어_갱신한다() {
        ClientRegistration registration = ClientRegistration.withRegistrationId("authserver")
                .clientId("memory-agent")
                .clientSecret("memory-agent-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri(ISSUER + "/oauth2/authorize")
                .tokenUri(ISSUER + "/oauth2/token")
                .build();
        var registrations = new InMemoryClientRegistrationRepository(registration);
        var authorizedClients = new InMemoryOAuth2AuthorizedClientService(registrations);

        var principal = new TestingAuthenticationToken("user", null, "ROLE_USER");
        var expired = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "expired-token",
                Instant.now().minusSeconds(600), Instant.now().minusSeconds(300));
        authorizedClients.saveAuthorizedClient(new OAuth2AuthorizedClient(registration, "user", expired,
                new OAuth2RefreshToken("refresh-1", Instant.now().minusSeconds(600))), principal);

        RestClient.Builder builder = RestClient.builder()
                // 기본 컨버터(범용 Jackson 컨버터 포함)를 그대로 두면, 그 컨버터가
                // OAuth2AccessTokenResponse 도 읽을 수 있다고 주장해 먼저 선택되고,
                // 리플렉션으로 필드 없는 빈 객체를 만들어 access_token 이 null 인 채로
                // 반환한다. 기본값을 끄고 폼 인코딩과 토큰 응답 변환만 명시적으로 쓴다.
                .configureMessageConverters(converters -> converters
                        .disableDefaults()
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        var refreshTokenClient = new RestClientRefreshTokenTokenResponseClient();
        refreshTokenClient.setRestClient(builder.build());
        refreshTokenClient.addParametersConverter(ResourceIndicators.tokenRequest(() -> RESOURCE));

        server.expect(requestTo(ISSUER + "/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(Map.of(
                        "grant_type", "refresh_token",
                        "refresh_token", "refresh-1",
                        "resource", RESOURCE)))
                .andRespond(withSuccess("""
                        {"access_token":"new-token","token_type":"Bearer","expires_in":300}""",
                        MediaType.APPLICATION_JSON));

        var manager = McpSecurityConfig.authorizedClientManager(registrations, authorizedClients, refreshTokenClient);
        OAuth2AuthorizedClient authorized = manager.authorize(OAuth2AuthorizeRequest
                .withClientRegistrationId("authserver").principal(principal).build());

        assertThat(authorized.getAccessToken().getTokenValue()).isEqualTo("new-token");
        server.verify();
    }
}
