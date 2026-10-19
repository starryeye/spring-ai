package dev.starryeye.modern.agent;

import dev.starryeye.modern.agent.config.McpSecurityConfig;
import dev.starryeye.modern.agent.controller.ChatController;
import dev.starryeye.modern.agent.discovery.DiscoveryFixtures;
import dev.starryeye.modern.agent.discovery.McpAuthorizationDiscovery;
import dev.starryeye.modern.agent.mcp.ModernMcpClient;
import dev.starryeye.modern.agent.mcp.UserToolCatalog;
import dev.starryeye.modern.agent.security.StepUpRequiredException;
import dev.starryeye.modern.agent.security.StepUpState;

import io.modelcontextprotocol.modern.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * step-up 예외가 Spring AI의 실제 tool 호출 경로를 지나 채팅 응답의 consent 카드까지 올라오는지 확인한다.
 *
 * <p>{@code ChatEventsTest}는 {@code Flux.error}를 손으로 만든다.
 * 이 테스트는 HTTP를 보내는 {@link ModernMcpClient}만 mock으로 바꾸고 나머지는 운영과 같은 bean을 쓴다.
 * tool 목록은 실제 {@link UserToolCatalog}가 이 사용자의 token으로 받고, tool은 {@code ModernToolCallback}이 부른다.
 * {@link ChatClient}는 context의 {@code ChatClient.Builder}로 만들었으므로 {@code ChatMemoryConfig}가 등록한
 * {@code ToolCallingAdvisor.Builder}와 {@code ToolCallingManager}가 그 안에 있다.
 * tool 실행 예외 처리는 Spring AI 자동 구성의 기본 bean이다.
 * 모델은 한 번 글을 쓴 뒤 {@code updateStock} tool을 부르고, MCP Server는 {@code 403 insufficient_scope}로 답한다.
 */
@SpringBootTest
class StepUpChatStreamTest {

    static final McpSchema.Tool UPDATE_STOCK = McpSchema.Tool.builder("updateStock", Map.of("type", "object"))
            .description("상품 재고를 바꾼다")
            .build();

    /**
     * {@link ModernMcpClient}가 {@code 403 insufficient_scope}를 받으면 던지는 예외다.
     * reactor는 {@code block()}에서 예외에 suppressed 예외를 더하므로 테스트마다 새로 만든다.
     */
    StepUpRequiredException 권한_부족 = new StepUpRequiredException(List.of("products:write"), "updateStock");

    @MockitoBean
    McpAuthorizationDiscovery discovery;

    @MockitoBean
    ChatModel chatModel;

    @MockitoBean
    ModernMcpClient mcpClient;

    @Autowired
    ChatController chatController;

    @Autowired
    ChatClient chatClient;

    @Autowired
    UserToolCatalog toolCatalog;

    @Autowired
    ClientRegistrationRepository clientRegistrationRepository;

    @Autowired
    OAuth2AuthorizedClientService authorizedClientService;

    /**
     * 테스트마다 다른 사용자다.
     * 대화 기억과 tool 목록 cache가 다른 테스트와 섞이지 않게 한다.
     */
    Authentication user;

    String accessToken;

    static ChatResponse 응답(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    @BeforeEach
    void setUp() {
        given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.AUTH_METHOD))
                .willReturn(DiscoveryFixtures.discovered());
        String name = "stream-" + UUID.randomUUID();
        this.user = UsernamePasswordAuthenticationToken.authenticated(name, null, List.of());
        this.accessToken = name + "-access-token";
        Instant now = Instant.now();
        this.authorizedClientService.saveAuthorizedClient(new OAuth2AuthorizedClient(
                this.clientRegistrationRepository.findByRegistrationId(McpSecurityConfig.REGISTRATION_ID), name,
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, this.accessToken, now,
                        now.plusSeconds(300))), this.user);
        given(this.mcpClient.listTools(anyString(), any())).willReturn(McpSchema.ListToolsResult
                .builder(List.of(UPDATE_STOCK)).ttlMs(60_000).build());
        given(this.mcpClient.callTool(anyString(), anyString(), anyMap())).willThrow(this.권한_부족);
        // ToolCallingAdvisor는 prompt의 option이 ToolCallingChatOptions일 때만 tool을 부른다.
        given(this.chatModel.getOptions()).willReturn(ToolCallingChatOptions.builder().build());
        AssistantMessage toolCall = AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "updateStock",
                        "{\"productId\":\"p1\",\"quantity\":10}")))
                .build();
        given(this.chatModel.stream(any(Prompt.class))).willAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            // tool 결과가 prompt에 있다는 것은 step-up 예외가 오류 문장으로 바뀌어 모델에게 돌아왔다는 뜻이다.
            // 그러면 테스트는 오류 없이 이 답으로 끝나 실패한다.
            if (prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance)) {
                return Flux.just(응답(new AssistantMessage("권한이 없어 바꾸지 못했습니다")));
            }
            return Flux.just(응답(new AssistantMessage("확인해 볼게요")), 응답(toolCall));
        });
    }

    @Test
    void step_up_예외가_채팅_응답의_step_up_event까지_올라간다() {
        MockHttpSession session = new MockHttpSession();

        List<ServerSentEvent<String>> events = this.chatController
                .chat("p1 재고를 10개로 바꿔 줘", session, this.user)
                .collectList().block(Duration.ofSeconds(10));

        assertThat(events).extracting(ServerSentEvent::event).containsExactly("message", "step-up");
        assertThat(events.get(0).data()).isEqualTo("\"확인해 볼게요\"");
        assertThat(events.get(1).data()).isEqualTo("{\"scope\":\"products:write\",\"tool\":\"updateStock\","
                + "\"url\":\"/oauth2/authorization/authserver?step_up=products:write\"}");
        assertThat(StepUpState.of(session).challenged("products:write")).isTrue();
        // 목록과 tool 호출 모두 이 사용자의 token으로 나갔다.
        verify(this.mcpClient).listTools(this.accessToken, null);
        verify(this.mcpClient).callTool(this.accessToken, "updateStock", Map.of("productId", "p1", "quantity", 10));
    }

    @Test
    void tool의_step_up_예외가_Spring_AI를_지나_채팅_stream의_오류로_올라온다() {
        List<String> received = new CopyOnWriteArrayList<>();

        assertThatThrownBy(() -> this.chatClient.prompt()
                .user("p1 재고를 10개로 바꿔 줘")
                // 대화 기억 advisor가 기본 advisor로 들어와 있다.
                // conversation ID가 없으면 그 advisor가 IllegalArgumentException을 던진다.
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, this.user.getName()))
                .tools(this.toolCatalog.callbacks(this.user).toArray())
                .stream()
                .content()
                .doOnNext(received::add)
                .collectList()
                .block(Duration.ofSeconds(10)))
                // client가 던진 예외 그대로다.
                // 문장으로 바뀌거나 다른 예외로 바뀌지 않는다.
                .satisfies(error -> assertThat(StepUpRequiredException.find(error)).containsSame(this.권한_부족));
        // 예외는 이미 흘려보낸 답변 토막 뒤에 온다.
        assertThat(received).containsExactly("확인해 볼게요");
    }
}
