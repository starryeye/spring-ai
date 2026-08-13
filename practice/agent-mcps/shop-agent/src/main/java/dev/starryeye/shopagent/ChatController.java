package dev.starryeye.shopagent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * {@code .stream()} 은 Flux 를 만들기 <b>전에</b>
     * {@code AsyncMcpToolCallbackProvider.getToolCallbacks()} 를 호출하고 그 안에서
     * {@code Mono.block()} 을 쓴다. 컨트롤러는 Netty 이벤트 루프 스레드에서 실행되므로
     * 그대로 두면 {@code IllegalStateException} 으로 500 이 난다.
     * 블로킹이 체인을 <i>만드는 시점</i>에 있으므로 완성된 Flux 에 {@code subscribeOn} 을
     * 붙여도 소용없다 — 호출 자체를 옮겨야 한다.
     * <p>
     * 응답을 {@code text/plain} 으로 흘리는 이유: SSE 로 선언하면 Flux 원소마다
     * {@code data:} 프레임이 붙어 토큰 하나당 한 줄이 되어 읽을 수 없다.
     */
    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public Flux<String> chat(@RequestBody String message) {
        return Mono.fromCallable(() -> chatClient.prompt()
                        .user(message)
                        .stream()
                        .content())
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(content -> content);
    }
}
