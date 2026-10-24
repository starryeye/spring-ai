package dev.starryeye.memory.agent;

import dev.starryeye.memory.agent.config.McpSecurityConfig;
import dev.starryeye.memory.agent.discovery.DiscoveryFixtures;
import dev.starryeye.memory.agent.discovery.McpAuthorizationDiscovery;
import dev.starryeye.memory.agent.mcp.UserMcpClients;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ShopAgentApplicationTests {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    McpAuthorizationDiscovery discovery;

    @org.junit.jupiter.api.BeforeEach
    void 발견_결과를_고정한다() {
        org.mockito.BDDMockito.given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER))
                .willReturn(DiscoveryFixtures.discovered());
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ClientRegistrationRepository clientRegistrationRepository;

    @Autowired
    OAuth2ClientProperties oAuth2ClientProperties;

    @Autowired
    OAuth2AuthorizedClientManager authorizedClientManager;

    @Autowired
    ApplicationContext applicationContext;

    @Test
    void contextLoads() {
    }

    @Test
    void 로그인하지_않으면_인가_엔드포인트로_보낸다() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/oauth2/authorization/**"));
    }

    @Test
    void OAuth2_클라이언트_등록이_정확히_하나다() {
        // 리터럴 "authserver"가 아니라 McpSecurityConfig.REGISTRATION_ID를 그대로 써서 조회한다.
        // 이 상수는 application.yml의 registration key와 같아야만 의미가 있는데, 이 테스트가 그 일치를 실제로 검증한다.
        // yml이나 상수 어느 한쪽만 바뀌면 이 조회가 null을 돌려주며 바로 실패한다.
        var registration = clientRegistrationRepository.findByRegistrationId(McpSecurityConfig.REGISTRATION_ID);

        assertThat(registration).isNotNull();
        assertThat(registration.getClientId()).isEqualTo("memory-agent");
        // endpoint는 설정이 아니라 discovery 결과에서 온다.
        assertThat(registration.getProviderDetails().getIssuerUri()).isEqualTo(DiscoveryFixtures.ISSUER);
        assertThat(registration.getProviderDetails().getAuthorizationUri())
                .isEqualTo(DiscoveryFixtures.ISSUER + "/oauth2/authorize");
        assertThat(oAuth2ClientProperties.getRegistration()).hasSize(1);
    }

    /**
     * 요청 thread 밖(tool 호출, session 종료 {@code DELETE})에서도 주인의 token을 꺼낼 수 있어야 한다.
     * manager 타입이 바뀌면 그 성질이 깨진다.
     */
    @Test
    void 서블릿_요청이_필요없는_인가_매니저를_쓴다() {
        assertThat(authorizedClientManager)
                .isInstanceOf(org.springframework.security.oauth2.client
                        .AuthorizedClientServiceOAuth2AuthorizedClientManager.class);
    }

    /**
     * MCP client는 사용자마다 따로 만든다.
     * 자동 구성의 공유 client는 없어야 한다.
     */
    @Test
    void 공유_MCP_client_없이_사용자별_client_저장소를_쓴다() {
        assertThat(applicationContext.getBeansOfType(io.modelcontextprotocol.client.McpSyncClient.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(UserMcpClients.class)).hasSize(1);
    }

    /**
     * browser로 보내는 authorization request에 PKCE challenge와 resource가 있어야 한다.
     * 둘 중 하나라도 빠지면 Authorization Server가 거부하거나(PKCE), token의 aud가 좁혀지지 않는다(resource).
     */
    @Test
    void 인가_요청에_PKCE_와_resource_가_실린다() throws Exception {
        String location = mockMvc.perform(get("/oauth2/authorization/" + McpSecurityConfig.REGISTRATION_ID))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        var parameters = org.springframework.web.util.UriComponentsBuilder.fromUriString(location).build()
                .getQueryParams();

        assertThat(parameters.getFirst("code_challenge_method")).isEqualTo("S256");
        assertThat(parameters.getFirst("code_challenge")).isNotBlank();
        assertThat(org.springframework.web.util.UriUtils.decode(parameters.getFirst("resource"),
                java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(DiscoveryFixtures.RESOURCE);
    }
}
