package dev.starryeye.modern.agent;

import com.jayway.jsonpath.JsonPath;
import dev.starryeye.modern.agent.cimd.ClientMetadataServer;
import dev.starryeye.modern.agent.cimd.TestTls;
import dev.starryeye.modern.agent.config.McpSecurityConfig;
import dev.starryeye.modern.agent.config.ToolCatalogConfig;
import dev.starryeye.modern.agent.discovery.DiscoveryFixtures;
import dev.starryeye.modern.agent.discovery.McpAuthorizationDiscovery;
import dev.starryeye.modern.agent.mcp.ModernMcpClient;
import dev.starryeye.modern.agent.mcp.UserToolCatalog;
import dev.starryeye.modern.agent.security.SingleFlightAuthorizedClientManager;
import io.modelcontextprotocol.modern.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ShopAgentApplicationTests {

    static final String CHATGPT = "https://localhost:8182/oauth/client.json";

    @MockitoBean
    McpAuthorizationDiscovery discovery;

    /** HTTP를 보내는 부분만 바꾼다. 요청 형식은 {@code ModernMcpClientTest}가 가짜 MCP Server로 본다. */
    @MockitoBean
    ModernMcpClient mcpClient;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ClientRegistrationRepository clientRegistrationRepository;

    @Autowired
    OAuth2AuthorizedClientManager authorizedClientManager;

    @Autowired
    OAuth2AuthorizedClientService authorizedClientService;

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
     * tool 목록은 사용자마다 다르므로, 목록 하나를 모든 사용자에게 같이 쓰는 tool provider가 있으면 안 된다.
     * MCP 요청은 직접 만든 2026-07-28 client 하나가 보낸다.
     * Spring AI의 MCP client 자동 구성(2025-11-25 형식)은 classpath에 없다.
     */
    @Test
    void 사용자별_tool_보관소와_2026_07_28_client만_있다() {
        assertThat(this.applicationContext.getBeansOfType(UserToolCatalog.class)).hasSize(1);
        assertThat(this.applicationContext.getBeansOfType(ModernMcpClient.class)).hasSize(1);
        assertThat(this.applicationContext.getBeansOfType(ToolCallbackProvider.class)).isEmpty();
        assertThat(this.applicationContext.containsBean("mcpSyncClients")).isFalse();
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
        assertThat(registration.getRedirectUri()).isEqualTo("http://localhost:8180/login/oauth2/code/authserver");
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
     * bean은 사용자별로 refresh를 한 번에 하나씩 하는 manager이고, 그 안의 manager는
     * {@code AuthorizedClientServiceOAuth2AuthorizedClientManager}다.
     * {@code TokenRefreshTest}가 같은 bean 메서드로 만든 manager를 servlet 요청 없이 불러 refresh까지 확인한다.
     */
    @Test
    void 서블릿_요청이_필요없는_인가_매니저를_쓴다() {
        assertThat(this.authorizedClientManager).isInstanceOf(SingleFlightAuthorizedClientManager.class);
    }

    /**
     * {@code spring.reactor.context-propagation: auto}가 테스트 context에서도 켜져 reactor의 다른 thread에 SecurityContext가 이어지는지 본다.
     * 이 전파는 {@code main()}이 아니라 설정으로 켜므로 테스트 context에서도 켜져 있어야 한다.
     * MCP 호출은 이 전파에 기대지 않는다.
     * {@code UserToolCatalog}가 요청 thread에서 받은 사용자를 tool마다 묶어 두기 때문이다.
     */
    @Test
    void reactor_의_다른_thread_에서도_SecurityContext_가_이어진다() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));
        try {
            String user = Mono.just("tool call")
                    .publishOn(Schedulers.boundedElastic())
                    .map(ignored -> currentUser())
                    .contextCapture()
                    .block(Duration.ofSeconds(5));

            assertThat(user).isEqualTo("alice");
        }
        finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static String currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (authentication != null) ? authentication.getName() : "(없음)";
    }

    /**
     * bean이 있는지가 아니라, 운영과 같은 연결로 받은 목록 요청에 login한 사용자의 token이 실제로 가는지 본다.
     * token은 {@code OAuth2AuthorizedClientManager}가 그 사용자의 authorized client에서 꺼낸다.
     * 이 연결이 끊기면 오류 없이 token만 빠져 MCP Server가 401을 준다(6장 "조용히 401").
     *
     * <p>context의 catalog bean은 같은 context를 쓰는 다른 테스트가 먼저 불렀을 수 있다.
     * 그러면 {@code server/discover}를 이미 마쳤고 목록도 cache에 있어서, 두 요청이 나가지 않는다.
     * 그래서 bean을 만드는 메서드로 새 catalog를 만들어, 테스트 순서와 상관없이 같은 결과를 보게 한다.
     */
    @Test
    void 로그인한_사용자의_token으로_tool_목록을_받는다() {
        Authentication alice = 로그인한_사용자("alice");
        given(this.mcpClient.listTools(any(), any()))
                .willReturn(McpSchema.ListToolsResult.builder(List.of()).ttlMs(60_000).build());

        새_catalog().callbacks(alice);

        verify(this.mcpClient).discover("alice-access-token");
        verify(this.mcpClient).listTools("alice-access-token", null);
    }

    /** 운영과 같은 연결(이 context의 {@code OAuth2AuthorizedClientManager})로 만든 새 catalog다. */
    UserToolCatalog 새_catalog() {
        return new ToolCatalogConfig().userToolCatalog(this.mcpClient, this.authorizedClientManager);
    }

    /** {@code <name>-access-token}을 가진 authorized client를 저장하고, 그 사용자의 인증 정보를 돌려준다. */
    Authentication 로그인한_사용자(String name) {
        Authentication user = UsernamePasswordAuthenticationToken.authenticated(name, null, List.of());
        Instant now = Instant.now();
        this.authorizedClientService.saveAuthorizedClient(new OAuth2AuthorizedClient(
                this.clientRegistrationRepository.findByRegistrationId(McpSecurityConfig.REGISTRATION_ID), name,
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, name + "-access-token", now,
                        now.plusSeconds(300))), user);
        return user;
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
