package dev.starryeye.memoryauthn.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

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
     *
     * <p>{@code conversationId} 를 클라이언트가 보내지 않는다. 서버가
     * {@link Authentication} 에서 파생시킨다 — 부모 practice {@code chat-memory} 와
     * 정확히 반대다. 클라이언트가 고를 수 있는 것은 label 뿐이고,
     * 접두사는 {@link ConversationId} 가 강제한다.
     */
    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public Flux<String> chat(Authentication authentication,
                             @RequestParam(required = false) String label,
                             @RequestBody String message) {
        String conversationId = ConversationId.of(authentication, label);
        log.debug("질문 수신 (conversationId={})", conversationId);

        return chatClient.prompt()
                .user(message)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();
    }
}
