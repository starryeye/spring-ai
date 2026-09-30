package dev.starryeye.stateless.agent.controller;

import dev.starryeye.stateless.agent.mcp.SecurityMcpTransportContextProvider;
import dev.starryeye.stateless.agent.security.StepUpState;

import jakarta.servlet.http.HttpSession;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
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
     * 답은 SSE event로 보낸다. tool이 step-up을 요구하면 consent 카드 event로 끝난다({@link ChatEvents}).
     */
    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestBody String message, HttpSession session,
            Authentication authentication) {
        // 대화 기억은 로그인 사용자(sub)마다 따로 둔다.
        String conversationId = authentication.getName();
        return ChatEvents.of(chatClient.prompt()
                .user(message)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content(), StepUpState.of(session));
    }
}
