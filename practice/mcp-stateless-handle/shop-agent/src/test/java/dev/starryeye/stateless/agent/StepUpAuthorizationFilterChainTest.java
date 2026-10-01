package dev.starryeye.stateless.agent;

import dev.starryeye.stateless.agent.config.McpSecurityConfig;
import dev.starryeye.stateless.agent.discovery.DiscoveryFixtures;
import dev.starryeye.stateless.agent.discovery.McpAuthorizationDiscovery;
import dev.starryeye.stateless.agent.security.StepUpState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code StepUpAuthorizationRequestResolver}가 실제 filter chain 안에서도
 * {@code SecurityContextHolder}의 scope를 합치는지 확인한다.
 *
 * <p>단위 테스트({@code StepUpAuthorizationRequestResolverTest})는 {@code SecurityContextHolder}를
 * 직접 채워 넣고 resolver 하나만 부른다. 그 방법으로는 이 resolver가 진짜
 * {@code OAuth2AuthorizationRequestRedirectFilter}(=login filter들보다 앞, 그러나
 * {@code SecurityContextHolderFilter}보다는 뒤) 자리에서도 인증 정보를 실제로 읽는지 확인할 수
 * 없다. 이 테스트는 login filter 없이, 이미 인증된 session으로 그 자리를 그대로 통과시켜 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StepUpAuthorizationFilterChainTest {

    /** Boot 4.1 의 {@code @AutoConfigureMockMvc} 는 springSecurity() 를 붙이지 않는다. */
    @TestConfiguration
    static class SecurityMockMvcSupport {

        @Bean
        MockMvcBuilderCustomizer securityMockMvcBuilderCustomizer() {
            return builder -> builder.apply(SecurityMockMvcConfigurers.springSecurity());
        }
    }

    @MockitoBean
    McpAuthorizationDiscovery discovery;

    @MockitoBean
    ChatModel chatModel;

    @MockitoBean(answers = org.mockito.Answers.RETURNS_MOCKS)
    ToolCallbackProvider toolCallbackProvider;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ClientRegistrationRepository clientRegistrationRepository;

    @Autowired
    OAuth2AuthorizedClientService authorizedClientService;

    @BeforeEach
    void setUp() {
        given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER))
                .willReturn(DiscoveryFixtures.discovered());
        given(this.toolCallbackProvider.getToolCallbacks()).willReturn(new ToolCallback[0]);
    }

    @Test
    void step_up_요청은_저장된_token의_scope와_challenge된_scope를_합쳐서_보낸다() throws Exception {
        var principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("sub", "user"), "sub");
        var authentication = new OAuth2AuthenticationToken(principal, principal.getAuthorities(),
                McpSecurityConfig.REGISTRATION_ID);

        ClientRegistration registration =
                this.clientRegistrationRepository.findByRegistrationId(McpSecurityConfig.REGISTRATION_ID);
        // extra:granted는 discovery도 step_up도 모르는 scope다 — 오직 저장된 token에만 있다.
        // 이 값이 최종 redirect에 실려야 SecurityContextHolder를 실제로 읽었다는 뜻이다.
        var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "t", Instant.now(),
                Instant.now().plusSeconds(300), Set.of("openid", "products:read", "extra:granted"));
        this.authorizedClientService.saveAuthorizedClient(new OAuth2AuthorizedClient(registration, "user", token),
                authentication);

        MockHttpSession session = new MockHttpSession();
        StepUpState.of(session).challenge(List.of("products:write"));

        String location = this.mockMvc
                .perform(get("/oauth2/authorization/" + McpSecurityConfig.REGISTRATION_ID)
                        .param("step_up", "products:write")
                        .session(session)
                        .with(SecurityMockMvcRequestPostProcessors.authentication(authentication)))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        var parameters = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        String scope = UriUtils.decode(parameters.getFirst("scope"), StandardCharsets.UTF_8);

        assertThat(List.of(scope.split(" "))).contains("extra:granted", "products:write");
        assertThat(parameters.getFirst("code_challenge")).isNotBlank();
        assertThat(UriUtils.decode(parameters.getFirst("resource"), StandardCharsets.UTF_8))
                .isEqualTo(DiscoveryFixtures.RESOURCE);
    }
}
