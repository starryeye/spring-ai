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

    private final UserMcpClients userMcpClients;

    public ChatController(ChatClient chatClient, UserMcpClients userMcpClients) {
        this.chatClient = chatClient;
        this.userMcpClients = userMcpClients;
    }

    /**
     * {@code conversationId} 는 client 가 보내지 않고 {@link Authentication} 에서 만든다
     * ({@link ConversationId}). client 가 고를 수 있는 것은 label 뿐이다.
     *
     * <p>MCP tool 은 요청한 사용자의 MCP client 에서 가져온다({@link UserMcpClients}). 그 client 의
     * MCP session 과 token 은 이 사용자에게만 묶여 있다.
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
                .toolCallbacks(this.userMcpClients.toolsFor(authentication))
                .stream()
                .content();
    }
}
