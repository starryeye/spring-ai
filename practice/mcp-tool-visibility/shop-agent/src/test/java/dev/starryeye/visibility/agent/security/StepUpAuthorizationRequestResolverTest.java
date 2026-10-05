package dev.starryeye.visibility.agent.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StepUpAuthorizationRequestResolverTest {

    static final ClientRegistration REGISTRATION = ClientRegistration.withRegistrationId("authserver")
            .clientId("visibility-shop-agent").clientSecret("s")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost:8160/login/oauth2/code/authserver")
            .authorizationUri("http://localhost:9050/oauth2/authorize")
            .tokenUri("http://localhost:9050/oauth2/token")
            .scope("openid", "products:read")
            .build();

    static final OAuth2AuthorizationRequest ORIGINAL = OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri("http://localhost:9050/oauth2/authorize")
            .clientId("visibility-shop-agent")
            .redirectUri("http://localhost:8160/login/oauth2/code/authserver")
            .scopes(Set.of("openid", "products:read"))
            .state("state-1")
            .additionalParameters(Map.of("resource", "http://localhost:8161/mcp",
                    PkceParameterNames.CODE_CHALLENGE, "challenge-1",
                    PkceParameterNames.CODE_CHALLENGE_METHOD, "S256"))
            .attributes(attributes -> attributes.put(PkceParameterNames.CODE_VERIFIER, "verifier-1"))
            .build();

    /** 원래 resolver 자리에 고정된 요청을 돌려주는 stub을 둔다. */
    static final OAuth2AuthorizationRequestResolver DELEGATE = new OAuth2AuthorizationRequestResolver() {

        @Override
        public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
            return ORIGINAL;
        }

        @Override
        public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
            return ORIGINAL;
        }
    };

    InMemoryOAuth2AuthorizedClientService authorizedClients =
            new InMemoryOAuth2AuthorizedClientService(new InMemoryClientRegistrationRepository(REGISTRATION));

    StepUpAuthorizationRequestResolver resolver =
            new StepUpAuthorizationRequestResolver(DELEGATE, this.authorizedClients, "authserver");

    Logger logger = (Logger) LoggerFactory.getLogger(StepUpAuthorizationRequestResolver.class);

    ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void 로그를_모은다() {
        this.logs.start();
        this.logger.addAppender(this.logs);
    }

    /**
     * resolver는 {@code request.getUserPrincipal()}이 아니라 {@code SecurityContextHolder}를
     * 읽는다. 여기서 넣은 인증 정보는 다음 테스트로 새지 않도록 반드시 지운다.
     */
    @AfterEach
    void 인증_정보와_로그_수집을_정리한다() {
        SecurityContextHolder.clearContext();
        this.logger.detachAppender(this.logs);
    }

    List<String> 경고() {
        return this.logs.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    MockHttpServletRequest 요청(String stepUp) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/authserver");
        if (stepUp != null) {
            request.setParameter(StepUpAuthorizationRequestResolver.PARAMETER, stepUp);
        }
        return request;
    }

    void 인증한다(String name) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(name, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    void 토큰을_저장한다(String principal, String... scopes) {
        var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "t", Instant.now(),
                Instant.now().plusSeconds(300), Set.of(scopes));
        this.authorizedClients.saveAuthorizedClient(new OAuth2AuthorizedClient(REGISTRATION, principal, token),
                new TestingAuthenticationToken(principal, null));
    }

    @Test
    void step_up이_없으면_원래_요청을_그대로_쓴다() {
        assertThat(this.resolver.resolve(요청(null))).isSameAs(ORIGINAL);
    }

    @Test
    void challenge된_scope와_SecurityContextHolder의_scope를_합친다() {
        인증한다("user");
        토큰을_저장한다("user", "openid", "products:read", "extra:granted");
        MockHttpServletRequest request = 요청("products:write");
        StepUpState.of(request.getSession()).challenge(List.of("products:write"));

        OAuth2AuthorizationRequest stepUp = this.resolver.resolve(request);

        // extra:granted는 원래 요청에도 step_up 값에도 없다 — SecurityContextHolder를 실제로
        // 읽어야만 나타난다(request.getUserPrincipal()은 이 자리에서 항상 null이다).
        assertThat(stepUp.getScopes()).containsExactlyInAnyOrder(
                "openid", "products:read", "extra:granted", "products:write");
        assertThat(stepUp.getAdditionalParameters()).containsEntry("resource", "http://localhost:8161/mcp");
        assertThat(stepUp.getAuthorizationRequestUri()).contains("products:write");
        assertThat(StepUpState.of(request.getSession()).isPending()).isTrue();
    }

    @Test
    void PKCE_값이_그대로_남는다() {
        인증한다("user");
        MockHttpServletRequest request = 요청("products:write");
        StepUpState.of(request.getSession()).challenge(List.of("products:write"));

        OAuth2AuthorizationRequest stepUp = this.resolver.resolve(request);

        assertThat(stepUp.getAdditionalParameters())
                .containsEntry(PkceParameterNames.CODE_CHALLENGE, "challenge-1")
                .containsEntry(PkceParameterNames.CODE_CHALLENGE_METHOD, "S256");
        assertThat(stepUp.getAttributes()).containsEntry(PkceParameterNames.CODE_VERIFIER, "verifier-1");
    }

    @Test
    void 인증_기록이_없어도_challenge된_scope는_더한다() {
        MockHttpServletRequest request = 요청("products:write");
        StepUpState.of(request.getSession()).challenge(List.of("products:write"));

        OAuth2AuthorizationRequest stepUp = this.resolver.resolve(request, "authserver");

        assertThat(stepUp.getScopes()).containsExactlyInAnyOrder("openid", "products:read", "products:write");
    }

    @Test
    void challenge되지_않은_scope는_무시하고_원래_요청을_그대로_쓴다() {
        MockHttpServletRequest request = 요청("products:write");
        // products:write는 challenge된 적이 없다 — 다른 사이트가 끼워 넣었을 수 있다.
        StepUpState state = StepUpState.of(request.getSession());
        state.challenge(List.of("products:read"));

        OAuth2AuthorizationRequest stepUp = this.resolver.resolve(request);

        assertThat(stepUp).isSameAs(ORIGINAL);
        assertThat(state.isPending()).isFalse();
        assertThat(경고()).singleElement().asString().contains("MCP Server가 요구한 적 없는 scope라 무시한다");
    }

    @Test
    void anonymous_인증은_scope를_더하지_않는다() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        // anonymous 사용자 이름으로 token을 둔다. anonymous 검사를 빼면 extra:granted가 섞여 이 테스트가 실패한다.
        토큰을_저장한다("anonymousUser", "openid", "products:read", "extra:granted");
        MockHttpServletRequest request = 요청("products:write");
        StepUpState.of(request.getSession()).challenge(List.of("products:write"));

        OAuth2AuthorizationRequest stepUp = this.resolver.resolve(request);

        assertThat(stepUp.getScopes()).containsExactlyInAnyOrder("openid", "products:read", "products:write");
    }

    @Test
    void 여러_scope_중_challenge된_것만_더한다() {
        MockHttpServletRequest request = 요청("products:write products:delete");
        // products:delete는 challenge되지 않았다.
        StepUpState.of(request.getSession()).challenge(List.of("products:write"));

        OAuth2AuthorizationRequest stepUp = this.resolver.resolve(request);

        assertThat(stepUp.getScopes()).containsExactlyInAnyOrder("openid", "products:read", "products:write");
    }

    @Test
    void session에_step_up_상태가_없으면_원래_scope로_login하고_그_이유를_남긴다() {
        // session이 만료되면 challenge 기록도 사라진다. step_up 값만 보고 scope를 더하지 않는다.
        OAuth2AuthorizationRequest login = this.resolver.resolve(요청("products:write"));

        assertThat(login).isSameAs(ORIGINAL);
        assertThat(경고()).singleElement().asString()
                .contains("step-up 상태가 없어(session 만료 등) step-up 없이 login한다");
    }

    @Test
    void session에_step_up_상태가_없어도_인증된_사용자는_받은_scope를_유지한다() {
        인증한다("user");
        토큰을_저장한다("user", "openid", "products:read", "products:write");

        OAuth2AuthorizationRequest login = this.resolver.resolve(요청("products:write"));

        assertThat(login.getScopes()).containsExactlyInAnyOrder("openid", "products:read", "products:write");
    }

    @Test
    void 인증된_채_step_up_없이_다시_login해도_받은_scope를_유지한다() {
        인증한다("user");
        // products:write는 앞선 step-up으로 받은 scope다. discovery가 고른 원래 요청에는 없다.
        토큰을_저장한다("user", "openid", "products:read", "products:write");
        MockHttpServletRequest request = 요청(null);
        StepUpState state = StepUpState.of(request.getSession());
        state.challenge(List.of("products:write"));

        OAuth2AuthorizationRequest login = this.resolver.resolve(request, "authserver");

        assertThat(login.getScopes()).containsExactlyInAnyOrder("openid", "products:read", "products:write");
        assertThat(login.getAuthorizationRequestUri()).contains("products:write");
        assertThat(login.getAdditionalParameters()).containsEntry("resource", "http://localhost:8161/mcp");
        // step-up이 아니므로 기다림을 시작하지 않는다. 다음 login 성공도 step-up 결과로 보지 않는다.
        assertThat(state.isPending()).isFalse();
    }

    @Test
    void step_up_값의_줄바꿈과_제어_문자는_로그에_그대로_남지_않는다() {
        // 다른 사이트가 줄바꿈으로 가짜 로그 줄을 끼워 넣으려 한다(CWE-117).
        // 공백으로 나누어도 남는 terminal 제어 문자(ESC)도 함께 넣는다.
        MockHttpServletRequest request = 요청("products:write\r\n2026-09-29 WARN [main] \u001B[31m가짜 로그 줄");
        StepUpState.of(request.getSession()).challenge(List.of("products:read"));

        OAuth2AuthorizationRequest login = this.resolver.resolve(request);

        assertThat(login).isSameAs(ORIGINAL);
        assertThat(this.logs.list).isNotEmpty().allSatisfy(event -> assertThat(event.getFormattedMessage())
                .doesNotContain("\r").doesNotContain("\n").doesNotContain("\u001B"));
        assertThat(경고()).singleElement().asString().contains("products:write", "2026-09-29", "WARN");
    }

    @Test
    void step_up_값은_scope마다_64자까지만_로그에_남긴다() {
        MockHttpServletRequest request = 요청("a".repeat(200) + " products:write");
        StepUpState.of(request.getSession()).challenge(List.of("products:read"));

        this.resolver.resolve(request);

        assertThat(경고()).singleElement().asString()
                .contains("a".repeat(64)).doesNotContain("a".repeat(65)).contains("products:write");
    }
}
