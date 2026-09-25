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
     * MCP tool 호출은 요청 thread 밖(reactor)에서 돈다. {@code ShopAgentApplication} 이 켠
     * {@code Hooks.enableAutomaticContextPropagation()} 이 Spring Security 의 {@code ThreadLocalAccessor} 로
     * SecurityContext 를 그 thread 에 옮기고, {@link SecurityMcpTransportContextProvider} 가 거기서 사용자를 읽는다.
     * community practice 는 같은 일을 모듈의 {@code .contextWrite(...)} 로 한다.
     */
    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public Flux<String> chat(@RequestBody String message) {
        return chatClient.prompt()
                .user(message)
                .stream()
                .content();
    }
}
