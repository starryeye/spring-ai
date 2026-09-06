package dev.starryeye.memoryagent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    private static final String SYSTEM_PROMPT = """
            당신은 친절한 대화 상대입니다.
            사용자가 앞서 한 말을 기억하고 그것을 참고해 답하세요.
            앞선 대화에 없는 내용을 지어내지 마세요. 모르면 모른다고 답하세요.
            답변은 한국어로 간결하게 합니다.
            """;

    /**
     * {@code MessageChatMemoryAdvisor} 를 기본 어드바이저로 붙인다.
     * 이 어드바이저가 요청 전에는 저장된 대화를 프롬프트에 끼워 넣고,
     * 응답 후에는 새 메시지를 저장한다.
     *
     * <p>어드바이저만 붙여서는 부족하다 — <b>어느 대화인지</b>를 요청마다
     * {@code ChatMemory.CONVERSATION_ID} 파라미터로 알려줘야 한다
     * ({@link ChatController} 참고). 그 파라미터가 없으면 모든 요청이
     * 같은 기본 대화로 섞인다.
     */
    @Bean
    public ChatClient memoryChatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
