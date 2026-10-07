package dev.starryeye.cimd.agent;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import dev.starryeye.cimd.agent.cimd.ClientSigningKey;
import dev.starryeye.cimd.agent.config.McpSecurityConfig;
import dev.starryeye.cimd.agent.discovery.DiscoveredClientRegistrationRepository;
import dev.starryeye.cimd.agent.discovery.DiscoveryFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * login(code 교환) 요청을 검증한다. 두 client type 모두 resource와 code_verifier를 넣고,
 * ChatGPT형은 서명한 client assertion을, Claude형은 client_id만 넣는다.
 *
 * <p>{@code McpSecurityConfig.authorizationCodeTokenResponseClient(...)}를 그대로 불러,
 * 이 연결이 빠지거나 깨지면 이 테스트가 잡는다.
 */
class AuthorizationCodeTokenRequestTest {

    static final String ISSUER = "http://localhost:9060";

    static final String TOKEN_ENDPOINT = ISSUER + "/oauth2/token";

    static final String REDIRECT_URI = "http://localhost:8170/login/oauth2/code/authserver";

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
                .redirectUri(REDIRECT_URI)
                .authorizationUri(ISSUER + "/oauth2/authorize")
                .tokenUri(TOKEN_ENDPOINT)
                .build();
    }

    /** code 교환 요청을 보내고, Authorization Server가 받았을 form을 돌려준다. */
    static MultiValueMap<String, String> 보낸_form(ClientRegistration registration) {
        // resource 값은 discovery 결과에서 오므로, 실제 discovery 없이 고정된 결과를 넣는다.
        DiscoveredClientRegistrationRepository registrations = mock(DiscoveredClientRegistrationRepository.class);
        given(registrations.discovered()).willReturn(DiscoveryFixtures.discovered());
        RestClientAuthorizationCodeTokenResponseClient tokenResponseClient =
                new McpSecurityConfig().authorizationCodeTokenResponseClient(registrations, signingKey);

        RestClient.Builder builder = RestClient.builder()
                // 기본 converter(범용 Jackson converter 포함)를 그대로 두면 그 converter가 먼저 골라져
                // access_token이 null인 빈 응답을 만든다.
                .configureMessageConverters(converters -> converters
                        .disableDefaults()
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        tokenResponseClient.setRestClient(builder.build());

        AtomicReference<String> body = new AtomicReference<>();
        server.expect(requestTo(TOKEN_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> body.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess("""
                        {"access_token":"new-token","token_type":"Bearer","expires_in":300}""",
                        MediaType.APPLICATION_JSON));

        OAuth2AuthorizationRequest authorizationRequest = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(ISSUER + "/oauth2/authorize")
                .clientId(registration.getClientId())
                .redirectUri(REDIRECT_URI)
                .state("state-1")
                .attributes(attributes -> attributes.put(PkceParameterNames.CODE_VERIFIER, "verifier-1"))
                .build();
        OAuth2AuthorizationResponse authorizationResponse = OAuth2AuthorizationResponse.success("code-1")
                .redirectUri(REDIRECT_URI)
                .state("state-1")
                .build();

        var response = tokenResponseClient.getTokenResponse(new OAuth2AuthorizationCodeGrantRequest(registration,
                new OAuth2AuthorizationExchange(authorizationRequest, authorizationResponse)));

        assertThat(response.getAccessToken().getTokenValue()).isEqualTo("new-token");
        server.verify();
        return TestForms.parse(body.get());
    }

    @Test
    void 코드_교환_요청에_resource와_code_verifier를_넣는다() {
        MultiValueMap<String, String> form = 보낸_form(registration(CLAUDE, ClientAuthenticationMethod.NONE));

        assertThat(form.toSingleValueMap())
                .containsEntry("grant_type", "authorization_code")
                .containsEntry("code", "code-1")
                .containsEntry("redirect_uri", REDIRECT_URI)
                .containsEntry("code_verifier", "verifier-1")
                .containsEntry("resource", DiscoveryFixtures.RESOURCE);
    }

    @Test
    void ChatGPT형은_서명한_client_assertion을_붙인다() throws Exception {
        MultiValueMap<String, String> form = 보낸_form(registration(CHATGPT, ClientAuthenticationMethod.PRIVATE_KEY_JWT));

        assertThat(form.getFirst("client_assertion_type"))
                .isEqualTo("urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        SignedJWT assertion = SignedJWT.parse(form.getFirst("client_assertion"));
        assertThat(assertion.verify(new RSASSAVerifier(signingKey.key().toRSAPublicKey()))).isTrue();
        assertThat(assertion.getHeader().getKeyID()).isEqualTo(signingKey.key().getKeyID());
        assertThat(assertion.getJWTClaimsSet().getIssuer()).isEqualTo(CHATGPT);
        assertThat(assertion.getJWTClaimsSet().getSubject()).isEqualTo(CHATGPT);
        assertThat(assertion.getJWTClaimsSet().getAudience()).containsExactly(TOKEN_ENDPOINT);
        assertThat(form).doesNotContainKey("client_secret");
    }

    @Test
    void Claude형은_client_id만_보내고_assertion이_없다() {
        MultiValueMap<String, String> form = 보낸_form(registration(CLAUDE, ClientAuthenticationMethod.NONE));

        assertThat(form.getFirst("client_id")).isEqualTo(CLAUDE);
        assertThat(form).doesNotContainKeys("client_assertion", "client_assertion_type", "client_secret");
    }
}
