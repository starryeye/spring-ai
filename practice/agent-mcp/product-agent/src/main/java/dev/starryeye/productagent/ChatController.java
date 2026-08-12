package dev.starryeye.productagent;

import org.springframework.ai.chat.client.ChatClient;
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

    public ChatController(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody String message) {
        return chatClient.prompt()
                .user(message)
                .stream()
                .content();
    }
}
