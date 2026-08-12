package dev.starryeye.productagent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class ChatController {

    private static final String SYSTEM_PROMPT = """
            당신은 상품 재고를 안내하는 도우미입니다.
            상품이나 재고에 대한 질문에는 반드시 제공된 도구로 실제 데이터를 조회한 뒤 답하세요.
            추측하지 말고, 조회 결과에 없는 내용은 모른다고 답하세요.
            """;

    private final ChatClient chatClient;

    /**
     * MCP 클라이언트 자동설정은 {@link ToolCallbackProvider} 빈을 만들어줄 뿐,
     * 그 툴들을 모델에 알려주지는 않는다. 여기서 직접 {@code ChatClient} 에 꽂아야
     * LLM이 툴 정의를 보게 된다.
     * <p>
     * {@link ObjectProvider} 로 받는 이유는 {@code spring.ai.mcp.client.enabled=false} 인
     * 환경(테스트 등)에서는 그 빈이 아예 없기 때문이다 — 툴 없이도 기동은 되어야 한다.
     * <p>
     * Spring AI 2.0 에서 {@code defaultToolCallbacks(...)} 는 제거 예정으로 표시되었으므로
     * {@code defaultTools(Object...)} 를 쓴다. 이 메서드는 인자가 {@code ToolCallbackProvider}
     * 이면 provider 목록에, {@code ToolCallback} 이면 콜백 목록에 넣는다.
     */
    public ChatController(ChatClient.Builder builder,
                          ObjectProvider<ToolCallbackProvider> toolCallbackProvider) {
        ChatClient.Builder configured = builder.defaultSystem(SYSTEM_PROMPT);
        toolCallbackProvider.ifAvailable(configured::defaultTools);
        this.chatClient = configured.build();
    }

    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody String message) {
        return chatClient.prompt()
                .user(message)
                .stream()
                .content();
    }
}
