package dev.starryeye.memoryagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.MediaType;
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
     * {@code conversationId} 를 <b>클라이언트가 보낸다.</b> 이 practice 는 사용자가
     * 한 명이라고 가정하므로 서버가 "누구인지"로 대화를 가를 수 없다.
     *
     * <p>이건 의도적으로 비워둔 자리다. 후속 practice 에서 인증과 합칠 때
     * 이 자리에 {@code Authentication.getName()} 이 들어가면 그대로 사용자별 격리가 된다.
     * 지금 이대로는 남의 대화 ID 를 넣으면 그대로 읽힌다 — 그것이 다음 단계의 출발점이다.
     *
     * <p>SSE 가 아니라 {@code text/plain} 이다. SSE 는 Flux 원소마다 프레임을 붙이는데
     * {@code .content()} 는 토큰 단위로 방출하므로 읽을 수 없는 출력이 된다.
     */
    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public Flux<String> chat(@RequestParam String conversationId, @RequestBody String message) {
        log.debug("질문 수신 (conversationId={})", conversationId);

        return chatClient.prompt()
                .user(message)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();
    }
}
