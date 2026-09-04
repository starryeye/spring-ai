package dev.starryeye.shopagent;

import org.springaicommunity.mcp.security.client.sync.AuthenticationMcpTransportContextProvider;
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
     * SSE 가 아니라 {@code text/plain} 이다. SSE 는 Flux 원소마다 프레임을 붙이는데
     * {@code .content()} 는 토큰 단위로 방출하므로 읽을 수 없는 출력이 된다.
     *
     * <p><b>{@code contextWrite} 를 지우면 안 된다.</b>
     * {@code AuthenticationMcpTransportContextProvider} 는 {@code SecurityContextHolder} 와
     * {@code RequestContextHolder} 의 thread-local 에서 인증 정보를 읽는데, 리액터 체인은
     * 요청 스레드가 아닌 곳에서 실행되므로 그대로면 비어 있다. 그러면 토큰이 붙지 않고
     * MCP 서버가 401 을 주는데, 원인은 DEBUG 로그 한 줄
     * ("No authentication or request context found") 에만 남는다.
     *
     * <p>{@code writeToReactorContext()} 는 <em>호출되는 그 순간의</em> thread-local 을
     * 캡처하므로 반드시 이 메서드 본문(요청 스레드) 안에서 불러야 한다.
     */
    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public Flux<String> chat(@RequestBody String message) {
        return chatClient.prompt()
                .user(message)
                .stream()
                .content()
                .contextWrite(AuthenticationMcpTransportContextProvider.writeToReactorContext());
    }
}
