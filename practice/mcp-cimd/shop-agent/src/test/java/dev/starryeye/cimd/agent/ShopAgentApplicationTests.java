package dev.starryeye.cimd.agent;

import com.jayway.jsonpath.JsonPath;
import dev.starryeye.cimd.agent.cimd.ClientMetadataServer;
import dev.starryeye.cimd.agent.cimd.TestTls;
import dev.starryeye.cimd.agent.config.McpSecurityConfig;
import dev.starryeye.cimd.agent.discovery.DiscoveryFixtures;
import dev.starryeye.cimd.agent.discovery.McpAuthorizationDiscovery;
import dev.starryeye.cimd.agent.mcp.UserToolCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ShopAgentApplicationTests {

    static final String CHATGPT = "https://localhost:8172/oauth/client.json";

    @MockitoBean
    McpAuthorizationDiscovery discovery;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ClientRegistrationRepository clientRegistrationRepository;

    @Autowired
    OAuth2AuthorizedClientManager authorizedClientManager;

    @Autowired
    ClientMetadataServer clientMetadataServer;

    @Autowired
    ApplicationContext applicationContext;

    @BeforeEach
    void 발견_결과를_고정한다() {
        given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.AUTH_METHOD))
                .willReturn(DiscoveryFixtures.discovered());
    }

    ClientRegistration registration() {
        return this.clientRegistrationRepository.findByRegistrationId(McpSecurityConfig.REGISTRATION_ID);
    }

    @Test
    void contextLoads() {
    }

    /**
     * 자동 구성의 provider는 목록 하나를 모든 사용자에게 같이 쓰므로 꺼야 한다.
     * 그래도 MCP client는 남아 있어야, 사용자마다 목록을 받는 보관소가 그것으로 만들어진다.
     */
    @Test
    void 사용자별_tool_보관소가_있고_자동_구성의_tool_provider는_없다() {
        assertThat(this.applicationContext.getBeansOfType(UserToolCatalog.class)).hasSize(1);
        assertThat(this.applicationContext.getBeansOfType(ToolCallbackProvider.class)).isEmpty();
        assertThat((List<?>) this.applicationContext.getBean("mcpSyncClients")).hasSize(1);
    }

    @Test
    void 로그인하지_않으면_인가_엔드포인트로_보낸다() throws Exception {
        this.mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/oauth2/authorization/**"));
    }

    @Test
    void OAuth2_client_등록은_문서_주소를_client_id로_쓴다() {
        // McpSecurityConfig.REGISTRATION_ID로 찾는다. 이 값이 login 경로와 맞지 않으면 null이 나온다.
        ClientRegistration registration = registration();

        assertThat(registration).isNotNull();
        assertThat(registration.getClientId()).isEqualTo(CHATGPT);
        assertThat(registration.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.PRIVATE_KEY_JWT);
        assertThat(registration.getRedirectUri()).isEqualTo("http://localhost:8170/login/oauth2/code/authserver");
        // endpoint는 설정이 아니라 discovery 결과에서 온다.
        assertThat(registration.getProviderDetails().getIssuerUri()).isEqualTo(DiscoveryFixtures.ISSUER);
        assertThat(registration.getProviderDetails().getAuthorizationUri())
                .isEqualTo(DiscoveryFixtures.ISSUER + "/oauth2/authorize");
    }

    @Test
    void 문서_서버가_올린_client_id와_redirect_주소는_등록과_같다() throws Exception {
        HttpClient client = HttpClient.newBuilder().sslContext(TestTls.client()).build();
        String body = client.send(HttpRequest.newBuilder(URI.create(
                        "https://localhost:" + this.clientMetadataServer.port() + "/oauth/client.json")).build(),
                HttpResponse.BodyHandlers.ofString()).body();

        assertThat(JsonPath.<String>read(body, "$.client_id")).isEqualTo(registration().getClientId());
        assertThat(JsonPath.<List<String>>read(body, "$.redirect_uris")).containsExactly(registration().getRedirectUri());
    }

    @Test
    void 채팅_화면_포트에서는_문서를_주지_않는다() throws Exception {
        // 문서는 https 문서 서버에서만 준다. 채팅 화면 쪽에서는 다른 주소처럼 login부터 요구한다.
        this.mockMvc.perform(get("/oauth/client.json"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/oauth2/authorization/**"));
    }

    /**
     * servlet 요청 없이 Authentication만으로 token을 꺼낼 수 있어야
     * reactor thread에서 token을 붙일 수 있다. manager 타입이 바뀌면 그 성질이 깨진다.
     */
    @Test
    void 서블릿_요청이_필요없는_인가_매니저를_쓴다() {
        assertThat(this.authorizedClientManager).isInstanceOf(AuthorizedClientServiceOAuth2AuthorizedClientManager.class);
    }

    @Test
    void MCP_클라이언트에_인증_커스터마이저가_꽂힌다() {
        assertThat(this.applicationContext.getBeansOfType(McpClientCustomizer.class)).hasSizeGreaterThanOrEqualTo(2);
    }

    /**
     * browser로 보내는 authorization request에 문서 주소, PKCE, resource가 들어 있어야 한다.
     * 하나라도 빠지면 Authorization Server가 거부하거나(client_id, PKCE), token의 aud가 좁혀지지 않는다(resource).
     */
    @Test
    void 인가_요청에_client_id와_PKCE와_resource가_들어_있다() throws Exception {
        String location = this.mockMvc.perform(get("/oauth2/authorization/" + McpSecurityConfig.REGISTRATION_ID))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        var parameters = UriComponentsBuilder.fromUriString(location).build().getQueryParams();

        assertThat(UriUtils.decode(parameters.getFirst("client_id"), StandardCharsets.UTF_8)).isEqualTo(CHATGPT);
        assertThat(parameters.getFirst("code_challenge_method")).isEqualTo("S256");
        assertThat(parameters.getFirst("code_challenge")).isNotBlank();
        assertThat(UriUtils.decode(parameters.getFirst("resource"), StandardCharsets.UTF_8))
                .isEqualTo(DiscoveryFixtures.RESOURCE);
    }
}
