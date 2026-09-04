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
     * community 버전은 여기에
     * {@code .contextWrite(AuthenticationMcpTransportContextProvider.writeToReactorContext())}
     * 가 있었다. 그 메서드가 Spring AI 의 internal 패키지에 의존한다.
     *
     * <p>이 practice 는 {@code Hooks.enableAutomaticContextPropagation()}(애플리케이션
     * 시작 시)으로 같은 일을 하려 한다 — Spring Security 가 제공하는 공식
     * {@code ThreadLocalAccessor} 를 쓰는 경로다.
     * <b>실제로 통하는지는 Step 10 에서 종단으로 확인한다.</b>
     */
    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public Flux<String> chat(@RequestBody String message) {
        return chatClient.prompt()
                .user(message)
                .stream()
                .content();
    }
}
