package dev.starryeye.officialagent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * MCP tool 호출은 요청 thread 밖, reactor의 thread에서 돈다.
     * {@code ShopAgentApplication}이 켠 {@code Hooks.enableAutomaticContextPropagation()}은
     * Spring Security의 {@code ThreadLocalAccessor}로 SecurityContext를 그 thread에 옮긴다.
     * {@link SecurityMcpTransportContextProvider}는 거기서 사용자를 읽는다.
     * community practice는 같은 일을 {@code ChatController}의 {@code .contextWrite(...)}로 한다.
     */
    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public Flux<String> chat(@RequestBody String message) {
        return chatClient.prompt()
                .user(message)
                .stream()
                .content();
    }
}
