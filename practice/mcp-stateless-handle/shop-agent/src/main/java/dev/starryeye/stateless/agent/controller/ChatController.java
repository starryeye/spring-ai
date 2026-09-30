package dev.starryeye.stateless.agent.controller;

import dev.starryeye.stateless.agent.mcp.SecurityMcpTransportContextProvider;
import dev.starryeye.stateless.agent.security.StepUpState;

import jakarta.servlet.http.HttpSession;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
public class ChatController {

    private final ChatClient chatClient;

    private final ChatMemory chatMemory;

    public ChatController(ChatClient chatClient, ChatMemory chatMemory) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
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
        // step-up으로 끊기면 이 turn에서 기억에 들어간 질문과 결과 없는 tool 호출을 되돌린다.
        // consent 뒤 browser가 같은 질문을 다시 보내므로, 그 turn이 깨끗하게 시작해야 한다.
        List<Message> before = List.copyOf(this.chatMemory.get(conversationId));
        Flux<String> content = this.chatClient.prompt()
                .user(message)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();
        return ChatEvents.of(content, StepUpState.of(session), () -> restore(conversationId, before));
    }

    /** 화면의 "새 대화" 버튼이 부른다. 로그인 사용자의 대화 기억을 지운다. */
    @PostMapping("/api/chat/reset")
    public ResponseEntity<Void> reset(Authentication authentication) {
        this.chatMemory.clear(authentication.getName());
        return ResponseEntity.noContent().build();
    }

    /**
     * 같은 사용자가 두 tab에서 동시에 결제하다 한쪽이 step-up으로 끊기면, 다른 tab이 그 사이에 쌓은 기억도 되돌려진다.
     * 이 practice는 이 경우를 다루지 않는다.
     */
    private void restore(String conversationId, List<Message> before) {
        this.chatMemory.clear(conversationId);
        if (!before.isEmpty()) {
            this.chatMemory.add(conversationId, before);
        }
    }
}
