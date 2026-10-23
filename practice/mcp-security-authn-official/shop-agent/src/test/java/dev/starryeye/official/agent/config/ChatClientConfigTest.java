package dev.starryeye.official.agent.config;

import dev.starryeye.official.agent.discovery.DiscoveryFixtures;
import dev.starryeye.official.agent.discovery.McpAuthorizationDiscovery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * {@code ChatController}가 쓰는 {@link ChatClient} bean의 MCP tool이 session 만료 뒤 호출을 다시 보내는지 확인한다.
 *
 * <p>context의 {@code ChatClient.Builder}로 만든 bean을 그대로 쓰고, 모델과 MCP tool만 바꾼다.
 * MCP tool은 첫 호출에서 SDK가 session을 닫을 때의 예외를 던지고, 다음 호출에서 답한다.
 * 모델은 tool을 부른 뒤 tool 결과를 그대로 답한다.
 */
@SpringBootTest
class ChatClientConfigTest {

    static final ToolDefinition GET_STOCK = DefaultToolDefinition.builder()
            .name("getStock")
            .description("재고를 본다")
            .inputSchema("{\"type\":\"object\"}")
            .build();

    @MockitoBean
    McpAuthorizationDiscovery discovery;

    @MockitoBean
    ChatModel chatModel;

    /** context가 뜰 때 {@code getToolCallbacks()}가 불린다. {@code RETURNS_MOCKS}면 stub 전에도 빈 배열을 준다. */
    @MockitoBean(answers = org.mockito.Answers.RETURNS_MOCKS)
    ToolCallbackProvider toolCallbackProvider;

    @Autowired
    ChatClient chatClient;

    final AtomicInteger calls = new AtomicInteger();

    /** 첫 호출은 MCP Server가 session을 지운 뒤처럼 실패하고, 다음 호출은 답한다. */
    final ToolCallback 만료된_session의_getStock = new ToolCallback() {

        @Override
        public ToolDefinition getToolDefinition() {
            return GET_STOCK;
        }

        @Override
        public String call(String toolInput) {
            if (calls.incrementAndGet() == 1) {
                throw new ToolExecutionException(GET_STOCK, new RuntimeException("MCP session with server terminated"));
            }
            return "p1: 3개";
        }
    };

    @BeforeEach
    void setUp() {
        given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER))
                .willReturn(DiscoveryFixtures.discovered());
        given(this.toolCallbackProvider.getToolCallbacks()).willReturn(new ToolCallback[] { 만료된_session의_getStock });
        // ToolCallingAdvisor는 prompt의 option이 ToolCallingChatOptions일 때만 tool을 부른다.
        given(this.chatModel.getOptions()).willReturn(ToolCallingChatOptions.builder().build());
        AssistantMessage toolCall = AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "getStock", "{}")))
                .build();
        given(this.chatModel.stream(any(Prompt.class))).willAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            return prompt.getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .findFirst()
                    .map(response -> Flux.just(응답(new AssistantMessage(response.getResponses().get(0).responseData()))))
                    .orElseGet(() -> Flux.just(응답(toolCall)));
        });
    }

    static ChatResponse 응답(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    @Test
    void session이_없어져_실패한_MCP_tool_호출을_채팅_안에서_다시_보낸다() {
        List<String> answer = this.chatClient.prompt()
                .user("p1 재고 있어?")
                .stream()
                .content()
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(String.join("", answer)).isEqualTo("p1: 3개");
        assertThat(this.calls).hasValue(2);
    }
}
