package dev.starryeye.productagent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 조립은 {@link ChatClientConfig} 가 하고, 여기서는 받아 쓰기만 한다.
 * <p>
 * 응답을 {@code text/plain} 으로 흘리는 이유: {@code text/event-stream} (SSE) 으로 선언하면
 * WebFlux 가 Flux 의 <b>원소마다</b> {@code data:} 프레임을 붙인다. {@code .content()} 는
 * 토큰 단위로 방출하므로 토큰 하나당 한 줄이 되어 터미널에서 읽기 어렵다.
 * {@code text/plain} 은 프레이밍 없이 청크로 흘려보내므로 스트리밍은 그대로면서 읽기 좋다.
 * 브라우저 {@code EventSource} 로 받을 거라면 {@link MediaType#TEXT_EVENT_STREAM_VALUE} 로
 * 되돌리면 된다 — 그때는 프레이밍이 필요하다.
 */
@RestController
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * {@code .stream()} 호출을 {@code Mono.fromCallable} 로 감싸는 이유:
     * <p>
     * {@code ChatClient.stream()} 은 Flux 를 만들기 <b>전에</b>
     * {@code AsyncMcpToolCallbackProvider.getToolCallbacks()} 를 호출해 MCP 서버에서
     * 툴 목록을 가져오는데, 그 구현이 {@code Mono.block()} 이다. 컨트롤러 메서드는
     * Netty 이벤트 루프 스레드에서 실행되므로 그대로 두면
     * {@code IllegalStateException: block()/blockFirst()/blockLast() are blocking,
     * which is not supported in thread reactor-http-nio-N} 이 나고 요청이 500 이 된다.
     * <p>
     * 블로킹이 리액티브 체인 <i>안</i>이 아니라 체인을 <i>만드는 시점</i>에 일어나므로,
     * 완성된 Flux 에 {@code subscribeOn} 을 붙이는 것으로는 해결되지 않는다.
     * {@code .stream()} 호출 자체를 blocking 허용 스케줄러로 옮겨야 한다.
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
