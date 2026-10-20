package dev.starryeye.authz.agent;

import dev.starryeye.authz.agent.config.McpSecurityConfig;
import dev.starryeye.authz.agent.discovery.DiscoveryFixtures;
import dev.starryeye.authz.agent.discovery.McpAuthorizationDiscovery;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ShopAgentApplicationTests {

    /** 가짜 MCP Server가 받은 {@code Authorization} header다. */
    static final List<String> RECEIVED_AUTHORIZATION = new CopyOnWriteArrayList<>();

    /** MCP client가 실제로 보내는 요청을 받아 보는 가짜 MCP Server다. 무엇을 받든 401로 답한다. */
    static final HttpServer FAKE_MCP_SERVER = startFakeMcpServer();

    static HttpServer startFakeMcpServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/mcp", exchange -> {
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                RECEIVED_AUTHORIZATION.add((authorization != null) ? authorization : "(없음)");
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
            });
            server.start();
            return server;
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @DynamicPropertySource
    static void 가짜_MCP_Server_로_연결한다(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.mcp.client.streamable-http.connections.shop.url",
                () -> "http://127.0.0.1:" + FAKE_MCP_SERVER.getAddress().getPort());
    }

    @AfterAll
    static void 가짜_MCP_Server_를_멈춘다() {
        FAKE_MCP_SERVER.stop(0);
    }

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
    OAuth2AuthorizedClientService authorizedClientService;

    @Autowired
    List<McpSyncClient> mcpSyncClients;

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
        // 리터럴 "authserver" 가 아니라 McpSecurityConfig.REGISTRATION_ID 를 그대로 써서
        // 조회한다 — 이 상수는 application.yml 의 registration 키와 일치해야만 의미가
        // 있는데, 그 일치 여부를 이 테스트가 실제로 검증한다. yml 이나 상수 어느 한쪽만
        // 바뀌면 이 조회가 null 을 돌려주며 즉시 빨간불이 된다.
        var registration = clientRegistrationRepository.findByRegistrationId(McpSecurityConfig.REGISTRATION_ID);

        assertThat(registration).isNotNull();
        assertThat(registration.getClientId()).isEqualTo("authz-shop-agent");
        // endpoint는 설정이 아니라 discovery 결과에서 온다.
        assertThat(registration.getProviderDetails().getIssuerUri()).isEqualTo(DiscoveryFixtures.ISSUER);
        assertThat(registration.getProviderDetails().getAuthorizationUri())
                .isEqualTo(DiscoveryFixtures.ISSUER + "/oauth2/authorize");
        assertThat(oAuth2ClientProperties.getRegistration()).hasSize(1);
    }

    /**
     * 서블릿 요청 없이 Authentication만으로 token을 꺼낼 수 있어야
     * 리액터 스레드에서 token을 붙일 수 있다.
     * 매니저 타입이 바뀌면 그 성질이 깨진다.
     */
    @Test
    void 서블릿_요청이_필요없는_인가_매니저를_쓴다() {
        assertThat(authorizedClientManager)
                .isInstanceOf(org.springframework.security.oauth2.client
                        .AuthorizedClientServiceOAuth2AuthorizedClientManager.class);
    }

    /**
     * MCP tool 호출은 reactor의 thread에서 돈다. 거기서도 SecurityContext가 보여야
     * {@code SecurityMcpTransportContextProvider}가 사용자를 읽고 token이 붙는다(6장 "조용히 401").
     * 이 전파는 {@code main()}이 아니라 설정({@code spring.reactor.context-propagation: auto})으로 켜므로
     * 테스트 context에서도 켜져 있어야 한다.
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
     * customizer bean이 있는지가 아니라, 자동 구성이 만든 {@link McpSyncClient}가 보내는 요청에
     * login한 사용자의 token이 실제로 들어가는지 본다.
     * {@code type: ASYNC}로 바꾸거나 customizer가 빠지면
     * 오류 없이 token만 빠져 MCP Server가 401을 준다(6장 "조용히 401").
     */
    @Test
    void MCP_클라이언트에_인증_커스터마이저가_꽂힌다() {
        Authentication alice = UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of());
        Instant now = Instant.now();
        this.authorizedClientService.saveAuthorizedClient(new OAuth2AuthorizedClient(
                this.clientRegistrationRepository.findByRegistrationId(McpSecurityConfig.REGISTRATION_ID), "alice",
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "alice-access-token", now,
                        now.plusSeconds(300))), alice);
        assertThat(this.mcpSyncClients).hasSize(1);

        SecurityContextHolder.getContext().setAuthentication(alice);
        try {
            // 가짜 MCP Server는 401로 답하므로 initialize는 실패한다. 보낸 요청의 header만 본다.
            catchThrowable(() -> this.mcpSyncClients.get(0).initialize());
        }
        finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(RECEIVED_AUTHORIZATION).contains("Bearer alice-access-token");
    }

    /**
     * browser로 보내는 authorization request에 PKCE challenge와 resource가 들어 있어야 한다.
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
