package dev.starryeye.stateless.agent;

import dev.starryeye.stateless.agent.controller.ChatEvents;
import dev.starryeye.stateless.agent.discovery.DiscoveryFixtures;
import dev.starryeye.stateless.agent.discovery.McpAuthorizationDiscovery;
import dev.starryeye.stateless.agent.security.StepUpRequiredException;
import dev.starryeye.stateless.agent.security.StepUpState;

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
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * step-up 예외가 Spring AI의 실제 tool 호출 경로를 지나 채팅 stream까지 올라오는지 확인한다.
 *
 * <p>{@code ChatEventsTest}는 {@code Flux.error}를 손으로 만든다.
 * 이 테스트는 {@code ChatController}가 쓰는 {@link ChatClient} bean을 그대로 쓴다.
 * 그 bean은 context의 {@code ChatClient.Builder}로 만들었으므로 {@code ChatMemoryConfig}가 등록한
 * {@code ToolCallingAdvisor.Builder}와 {@code ToolCallingManager}, 그리고
 * {@code StepUpToolExecutionExceptionProcessor}가 그 안에 있다.
 * 모델과 tool 말고 바꾸는 것 하나는, 대화 기억 advisor가 요구하는 conversation ID뿐이다.
 * 모델은 한 번 글을 쓴 뒤 {@code updateStock} tool을 부른다.
 * tool은 {@code SyncMcpToolCallback}처럼 MCP SDK가 감싼 예외를 {@link ToolExecutionException}에 담아 던진다.
 */
@SpringBootTest
class StepUpChatStreamTest {

    static final ToolDefinition UPDATE_STOCK = DefaultToolDefinition.builder()
            .name("updateStock")
            .description("상품 재고를 바꾼다")
            .inputSchema("{\"type\":\"object\"}")
            .build();

    /** MCP Server가 {@code 403 insufficient_scope}를 준 것처럼 던진다. transport 단계라 tool 이름은 모른다. */
    static final ToolCallback 권한이_모자란_updateStock = new ToolCallback() {

        @Override
        public ToolDefinition getToolDefinition() {
            return UPDATE_STOCK;
        }

        @Override
        public String call(String toolInput) {
            throw new ToolExecutionException(UPDATE_STOCK, new RuntimeException("MCP SDK가 감쌈",
                    new StepUpRequiredException(List.of("products:write"), null)));
        }
    };

    @MockitoBean
    McpAuthorizationDiscovery discovery;

    @MockitoBean
    ChatModel chatModel;

    @MockitoBean(answers = org.mockito.Answers.RETURNS_MOCKS)
    ToolCallbackProvider toolCallbackProvider;

    @Autowired
    ChatClient chatClient;

    static ChatResponse 응답(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    @BeforeEach
    void setUp() {
        given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER))
                .willReturn(DiscoveryFixtures.discovered());
        given(this.toolCallbackProvider.getToolCallbacks()).willReturn(new ToolCallback[0]);
        // ToolCallingAdvisor는 prompt의 option이 ToolCallingChatOptions일 때만 tool을 부른다.
        given(this.chatModel.getOptions()).willReturn(ToolCallingChatOptions.builder().build());
        AssistantMessage toolCall = AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "updateStock",
                        "{\"productId\":\"p1\",\"quantity\":10}")))
                .build();
        given(this.chatModel.stream(any(Prompt.class))).willAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            // tool 결과가 prompt에 있다는 것은 step-up 예외가 오류 문장으로 바뀌어 모델에게 돌아왔다는 뜻이다.
            // processor가 빠지면 이 갈래를 타고, 테스트는 오류 없이 끝나 실패한다(같은 tool 호출을 되풀이하지도 않는다).
            if (prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance)) {
                return Flux.just(응답(new AssistantMessage("권한이 없어 바꾸지 못했습니다")));
            }
            return Flux.just(응답(new AssistantMessage("확인해 볼게요")), 응답(toolCall));
        });
    }

    Flux<String> 재고를_바꿔_달라고_한다() {
        return this.chatClient.prompt()
                .user("p1 재고를 10개로 바꿔 줘")
                // 대화 기억 advisor가 기본 advisor로 들어와 있다.
                // conversation ID가 없으면 그 advisor가 IllegalArgumentException을 던진다.
                // 호출마다 새 id를 써서, 다른 test의 기억과 섞이지 않게 한다.
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, UUID.randomUUID().toString()))
                .toolCallbacks(권한이_모자란_updateStock)
                .stream()
                .content();
    }

    @Test
    void tool의_step_up_예외가_Spring_AI를_지나_채팅_stream의_오류로_올라온다() {
        List<String> received = new CopyOnWriteArrayList<>();

        assertThatThrownBy(() -> 재고를_바꿔_달라고_한다().doOnNext(received::add)
                .collectList().block(Duration.ofSeconds(10)))
                .satisfies(error -> {
                    StepUpRequiredException stepUp = StepUpRequiredException.find(error).orElseThrow();
                    assertThat(stepUp.scopes()).containsExactly("products:write");
                    // transport는 tool 이름을 모른다. 이름이 있으면 우리 processor가 이 경로에 있었다는 뜻이다.
                    assertThat(stepUp.tool()).isEqualTo("updateStock");
                });
        // 예외는 이미 흘려보낸 답변 토막 뒤에 온다.
        assertThat(received).containsExactly("확인해 볼게요");
    }

    @Test
    void 채팅_stream을_ChatEvents로_바꾸면_답변_토막_뒤에_consent_카드가_온다() {
        StepUpState state = new StepUpState();

        List<ServerSentEvent<String>> events = ChatEvents.of(재고를_바꿔_달라고_한다(), state)
                .collectList().block(Duration.ofSeconds(10));

        assertThat(events).extracting(ServerSentEvent::event).containsExactly("message", "step-up");
        assertThat(events.get(1).data()).isEqualTo("{\"scope\":\"products:write\",\"tool\":\"updateStock\","
                + "\"url\":\"/oauth2/authorization/authserver?step_up=products:write\"}");
        assertThat(state.challenged("products:write")).isTrue();
    }
}
