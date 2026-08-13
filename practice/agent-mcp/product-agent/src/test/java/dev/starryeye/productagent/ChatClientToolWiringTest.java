package dev.starryeye.productagent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.DefaultChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP 툴이 실제로 LLM에게 전달되는지에 대한 회귀 테스트.
 * <p>
 * MCP 클라이언트 자동설정은 {@link ToolCallbackProvider} 빈을 만들 뿐이고, 그것을
 * {@code ChatClient} 에 꽂는 것은 {@link ChatClientConfig} 의 몫이다. 그 배선이 빠지면
 * 모델은 툴 정의를 하나도 못 받고 기억에 의존해 답한다 — 기동도 되고 응답도 오기 때문에
 * 조용히 망가진다. 여기서는 실제 MCP 서버 대신 스텁 {@link ToolCallbackProvider} 를 등록해
 * 배선만 검증한다.
 */
@SpringBootTest(properties = {
        "spring.ai.mcp.client.enabled=false",
        "spring.ai.openai.api-key=test-key"
})
@ActiveProfiles("openai")
class ChatClientToolWiringTest {

    @TestConfiguration
    static class StubToolConfiguration {

        @Bean
        ToolCallbackProvider stubToolCallbackProvider() {
            return ToolCallbackProvider.from(new StubToolCallback());
        }
    }

    @Autowired
    ChatClient chatClient;

    @Autowired
    ToolCallbackProvider stubToolCallbackProvider;

    @Test
    void ToolCallbackProvider_가_ChatClient_에_연결된다() {
        var requestSpec = (DefaultChatClient.DefaultChatClientRequestSpec) chatClient.prompt();

        assertThat(requestSpec.getToolCallbackProviders())
                .as("MCP 툴 콜백이 ChatClient 기본 요청에 붙어 있어야 한다")
                .contains(stubToolCallbackProvider);
    }

    @Test
    void 연결된_provider_가_실제_툴_정의를_노출한다() {
        var requestSpec = (DefaultChatClient.DefaultChatClientRequestSpec) chatClient.prompt();

        assertThat(requestSpec.getToolCallbackProviders())
                .flatExtracting(provider -> List.of(provider.getToolCallbacks()))
                .extracting(callback -> callback.getToolDefinition().name())
                .contains("stubTool");
    }

    private static class StubToolCallback implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name("stubTool")
                    .description("테스트용 스텁 툴")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return "stub";
        }
    }
}
