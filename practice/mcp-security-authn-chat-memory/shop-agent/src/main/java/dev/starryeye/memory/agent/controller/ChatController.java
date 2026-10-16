package dev.starryeye.memory.agent.controller;

import dev.starryeye.memory.agent.domain.ConversationId;
import dev.starryeye.memory.agent.mcp.UserMcpClients;

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
     * {@code conversationId}는 client가 보내지 않고 {@link Authentication}에서 만든다({@link ConversationId}).
     * client가 고를 수 있는 것은 label뿐이다.
     *
     * <p>MCP tool은 요청한 사용자의 MCP client에서 가져온다({@link UserMcpClients}).
     * 그 client의 MCP session과 token은 이 사용자에게만 묶여 있다.
     * client는 stream이 끝날 때(완료·오류·취소)까지 빌려 쓴다.
     * 그래서 그 사이 다른 탭에서 logout해도, client는 이 채팅의 tool 호출이 끝난 뒤에 닫힌다.
     */
    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public Flux<String> chat(Authentication authentication,
                             @RequestParam(required = false) String label,
                             @RequestBody String message) {
        String conversationId = ConversationId.of(authentication, label);
        log.debug("질문 수신 (conversationId={})", conversationId);

        UserMcpClients.Lease mcp = this.userMcpClients.acquire(authentication);
        try {
            return chatClient.prompt()
                    .user(message)
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .toolCallbacks(mcp.tools())
                    .stream()
                    .content()
                    .doFinally(signal -> mcp.close());
        }
        catch (RuntimeException ex) {
            mcp.close();
            throw ex;
        }
    }
}
