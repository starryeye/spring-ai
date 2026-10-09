package dev.starryeye.cimd.agent.config;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import dev.starryeye.cimd.agent.TestForms;
import dev.starryeye.cimd.agent.cimd.ClientSigningKey;
import dev.starryeye.cimd.agent.discovery.DiscoveredClientRegistrationRepository;
import dev.starryeye.cimd.agent.discovery.DiscoveryFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 만료된 access token은 refresh로 갱신된다. 갱신 요청에도 resource를 넣어야 token의 aud가 그대로다.
 * ChatGPT형은 refresh 요청에도 assertion을 붙이고, Claude형은 client_id만 보낸다.
 */
class TokenRefreshTest {

    static final String ISSUER = "http://localhost:9060";

    static final String TOKEN_ENDPOINT = ISSUER + "/oauth2/token";

    static final String RESOURCE = "http://localhost:8171/mcp";

    static final String CHATGPT = "https://localhost:8172/oauth/client.json";

    static final String CLAUDE = "https://localhost:8172/oauth/public-client.json";

    static ClientSigningKey signingKey;

    @BeforeAll
    static void 서명_key를_읽는다() throws Exception {
        signingKey = ClientSigningKey.load(new ClassPathResource("test-certs/client-signing.p12"), "changeit",
                "client-signing");
    }

    static ClientRegistration registration(String clientId, ClientAuthenticationMethod method) {
        return ClientRegistration.withRegistrationId("authserver")
                .clientId(clientId)
                .clientAuthenticationMethod(method)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost:8170/login/oauth2/code/authserver")
                .authorizationUri(ISSUER + "/oauth2/authorize")
                .tokenUri(TOKEN_ENDPOINT)
                .issuerUri(ISSUER)
                .build();
    }

    record Refreshed(OAuth2AuthorizedClient client, MultiValueMap<String, String> form) {
    }

    static RestClient.Builder tokenEndpointClient() {
        return RestClient.builder()
                // 기본 converter를 그대로 두면 범용 Jackson converter가 먼저 골라져 access_token이 null인 응답을 만든다.
                .configureMessageConverters(converters -> converters
                        .disableDefaults()
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler());
    }

    /** 운영과 같은 bean 메서드로 refresh client를 만들고, 요청은 테스트의 RestClient로 보낸다. */
    static RestClientRefreshTokenTokenResponseClient refreshTokenClient(RestClient.Builder builder) {
        DiscoveredClientRegistrationRepository discovered = mock(DiscoveredClientRegistrationRepository.class);
        given(discovered.discovered()).willReturn(DiscoveryFixtures.discovered());
        var refreshTokenClient = new McpSecurityConfig().refreshTokenTokenResponseClient(discovered, signingKey);
        refreshTokenClient.setRestClient(builder.build());
        return refreshTokenClient;
    }

    /** 만료된 token과 refresh token을 저장해 두고, 운영과 같은 bean 메서드로 만든 manager로 refresh한다. */
    static Refreshed refresh(ClientRegistration registration, Set<String> scopes) {
        var registrations = new InMemoryClientRegistrationRepository(registration);
        var authorizedClients = new InMemoryOAuth2AuthorizedClientService(registrations);
        var principal = new TestingAuthenticationToken("user", null, "ROLE_USER");
        var expired = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "expired-token",
                Instant.now().minusSeconds(600), Instant.now().minusSeconds(300), scopes);
        authorizedClients.saveAuthorizedClient(new OAuth2AuthorizedClient(registration, "user", expired,
                new OAuth2RefreshToken("refresh-1", Instant.now().minusSeconds(600))), principal);

        RestClient.Builder builder = tokenEndpointClient();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var refreshTokenClient = refreshTokenClient(builder);

        AtomicReference<String> body = new AtomicReference<>();
        server.expect(requestTo(TOKEN_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> body.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess("""
                        {"access_token":"new-token","token_type":"Bearer","expires_in":300}""",
                        MediaType.APPLICATION_JSON));

        var manager = McpSecurityConfig.authorizedClientManager(registrations, authorizedClients, refreshTokenClient);
        OAuth2AuthorizedClient authorized = manager.authorize(OAuth2AuthorizeRequest
                .withClientRegistrationId("authserver").principal(principal).build());
        server.verify();
        return new Refreshed(authorized, TestForms.parse(body.get()));
    }

    @Test
    void 만료된_token을_resource를_넣어_갱신한다() {
        Refreshed refreshed = refresh(registration(CLAUDE, ClientAuthenticationMethod.NONE), Set.of("products:read"));

        assertThat(refreshed.client().getAccessToken().getTokenValue()).isEqualTo("new-token");
        assertThat(refreshed.form().toSingleValueMap())
                .containsEntry("grant_type", "refresh_token")
                .containsEntry("refresh_token", "refresh-1")
                .containsEntry("resource", RESOURCE);
    }

    @Test
    void ChatGPT형_refresh에도_client_assertion을_붙인다() throws Exception {
        Refreshed refreshed = refresh(registration(CHATGPT, ClientAuthenticationMethod.PRIVATE_KEY_JWT),
                Set.of("products:read"));

        SignedJWT assertion = SignedJWT.parse(refreshed.form().getFirst("client_assertion"));
        assertThat(assertion.verify(new RSASSAVerifier(signingKey.key().toRSAPublicKey()))).isTrue();
        assertThat(assertion.getJWTClaimsSet().getSubject()).isEqualTo(CHATGPT);
        // RFC 7523bis: aud는 Authorization Server의 issuer 하나다. typ으로 client assertion임을 밝힌다.
        assertThat(assertion.getJWTClaimsSet().getAudience()).containsExactly(ISSUER);
        assertThat(assertion.getHeader().getType()).hasToString("client-authentication+jwt");
    }

    @Test
    void Claude형_refresh는_client_id만_보낸다() {
        Refreshed refreshed = refresh(registration(CLAUDE, ClientAuthenticationMethod.NONE), Set.of("products:read"));

        assertThat(refreshed.form().getFirst("client_id")).isEqualTo(CLAUDE);
        assertThat(refreshed.form()).doesNotContainKeys("client_assertion", "client_secret");
    }

    @Test
    void refresh_요청에_scope를_보내지_않아_늘어난_scope가_유지된다() {
        // step-up으로 늘어난 scope를 가진 token이 만료됐다.
        Refreshed refreshed = refresh(registration(CHATGPT, ClientAuthenticationMethod.PRIVATE_KEY_JWT),
                Set.of("openid", "products:read", "products:write"));

        // refresh 요청에 scope가 없으면 Authorization Server는 처음 허락한 scope 그대로 발급한다(RFC 6749 §6).
        assertThat(refreshed.form()).doesNotContainKey("scope");
        // 응답에 scope가 없으면 Spring은 이전 token의 scope를 그대로 둔다.
        assertThat(refreshed.client().getAccessToken().getScopes())
                .containsExactlyInAnyOrder("openid", "products:read", "products:write");
    }

    @Test
    void 같은_사용자가_동시에_refresh해도_token_endpoint는_한_번만_부른다() throws Exception {
        ClientRegistration registration = registration(CLAUDE, ClientAuthenticationMethod.NONE);
        var registrations = new InMemoryClientRegistrationRepository(registration);
        var authorizedClients = new InMemoryOAuth2AuthorizedClientService(registrations);
        var principal = new TestingAuthenticationToken("user", null, "ROLE_USER");
        var expired = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "expired-token",
                Instant.now().minusSeconds(600), Instant.now().minusSeconds(300), Set.of("products:read"));
        authorizedClients.saveAuthorizedClient(new OAuth2AuthorizedClient(registration, "user", expired,
                new OAuth2RefreshToken("refresh-1", Instant.now().minusSeconds(600))), principal);

        RestClient.Builder builder = tokenEndpointClient();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // 응답을 늦춰, 두 요청이 refresh 도중에 겹치게 한다.
        // 두 번째 요청이 token endpoint까지 가면 MockRestServiceServer가 실패시킨다.
        server.expect(ExpectedCount.once(), requestTo(TOKEN_ENDPOINT))
                .andRespond(request -> {
                    try {
                        Thread.sleep(300);
                    }
                    catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    return withSuccess("""
                            {"access_token":"new-token","token_type":"Bearer","expires_in":300,"refresh_token":"refresh-2"}""",
                            MediaType.APPLICATION_JSON).createResponse(request);
                });
        var manager = McpSecurityConfig.authorizedClientManager(registrations, authorizedClients,
                refreshTokenClient(builder));
        var authorize = OAuth2AuthorizeRequest.withClientRegistrationId("authserver").principal(principal).build();

        CyclicBarrier start = new CyclicBarrier(2);
        Callable<String> call = () -> {
            start.await();
            return manager.authorize(authorize).getAccessToken().getTokenValue();
        };
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(call);
            Future<String> second = executor.submit(call);
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("new-token");
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("new-token");
        }
        server.verify();
    }
}
