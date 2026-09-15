package dev.starryeye.memoryauthn.agent;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.FormHttpMessageConverter;
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
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 로그인(코드 교환) 요청에도 resource 가 실려야 한다. resource 가 빠지면 발급되는
 * 토큰의 aud 가 이 MCP 서버로 좁혀지지 않는다 — {@link TokenRefreshTest} 가 검증하는
 * 갱신 요청과 같은 요구사항이지만 별도 빈({@code authorizationCodeTokenResponseClient})으로
 * 배선되어 있어 따로 검증이 필요하다.
 *
 * <p>{@code McpSecurityConfig.authorizationCodeTokenResponseClient(...)} 를 테스트 안에서
 * 다시 조립하지 않고 그대로 호출한다 — 그래야 이 배선이 다른 practice 로 복사되며
 * 깨져도 이 테스트가 잡아낸다.
 */
class AuthorizationCodeTokenRequestTest {

    static final String ISSUER = "http://localhost:9020";

    static final String REDIRECT_URI = "http://localhost:8130/login/oauth2/code/authserver";

    @Test
    void 코드_교환_요청에_resource_를_실어_보낸다() {
        ClientRegistration registration = ClientRegistration.withRegistrationId("authserver")
                .clientId("memory-agent")
                .clientSecret("memory-agent-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(REDIRECT_URI)
                .authorizationUri(ISSUER + "/oauth2/authorize")
                .tokenUri(ISSUER + "/oauth2/token")
                .build();

        // resource 값은 발견 결과에서 오므로, 실제 발견을 거치지 않고 고정된 공급자로 넣는다.
        DiscoveredClientRegistrationRepository registrations = mock(DiscoveredClientRegistrationRepository.class);
        given(registrations.discovered()).willReturn(DiscoveryFixtures.discovered());

        // McpSecurityConfig 의 실제 빈 생성 메서드를 호출한다. 테스트 안에서 클라이언트를
        // 새로 조립하면 이 배선이 빠지거나 깨져도 통과해 버린다.
        RestClientAuthorizationCodeTokenResponseClient tokenResponseClient =
                new McpSecurityConfig().authorizationCodeTokenResponseClient(registrations);

        RestClient.Builder builder = RestClient.builder()
                // 기본 컨버터(범용 Jackson 컨버터 포함)를 그대로 두면 그 컨버터가 먼저
                // 선택되어 access_token 이 null 인 빈 응답을 만든다(TokenRefreshTest 참고).
                .configureMessageConverters(converters -> converters
                        .disableDefaults()
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        tokenResponseClient.setRestClient(builder.build());

        server.expect(requestTo(ISSUER + "/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(Map.of(
                        "grant_type", "authorization_code",
                        "code", "code-1",
                        "redirect_uri", REDIRECT_URI,
                        "code_verifier", "verifier-1",
                        "resource", DiscoveryFixtures.RESOURCE)))
                .andRespond(withSuccess("""
                        {"access_token":"new-token","token_type":"Bearer","expires_in":300}""",
                        MediaType.APPLICATION_JSON));

        OAuth2AuthorizationRequest authorizationRequest = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(ISSUER + "/oauth2/authorize")
                .clientId("memory-agent")
                .redirectUri(REDIRECT_URI)
                .state("state-1")
                // PKCE 로 교환할 때만 code_verifier 가 실린다 — 여기서도 실제로 실리는지 확인한다.
                .attributes(attributes -> attributes.put(PkceParameterNames.CODE_VERIFIER, "verifier-1"))
                .build();
        OAuth2AuthorizationResponse authorizationResponse = OAuth2AuthorizationResponse.success("code-1")
                .redirectUri(REDIRECT_URI)
                .state("state-1")
                .build();
        OAuth2AuthorizationCodeGrantRequest grantRequest = new OAuth2AuthorizationCodeGrantRequest(registration,
                new OAuth2AuthorizationExchange(authorizationRequest, authorizationResponse));

        var accessTokenResponse = tokenResponseClient.getTokenResponse(grantRequest);

        assertThat(accessTokenResponse.getAccessToken().getTokenValue()).isEqualTo("new-token");
        server.verify();
    }
}
