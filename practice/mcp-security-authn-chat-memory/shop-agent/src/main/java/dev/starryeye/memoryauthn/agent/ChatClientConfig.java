package dev.starryeye.memoryauthn.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    private static final String SYSTEM_PROMPT = """
            당신은 온라인 쇼핑몰의 상담 도우미입니다.
            상품과 재고에 대한 질문에는 반드시 제공된 툴을 사용해 실제 데이터를 조회한 뒤 답하세요.
            기억이나 추측으로 답하지 마세요.
            조회 결과가 없으면 없다고 그대로 알려주세요.
            답변은 한국어로 간결하게 합니다.
            """;

    /** MCP tool 은 기본값으로 두지 않는다. 요청마다 그 사용자의 MCP client 에서 받아 넘긴다({@link ChatController}). */
    @Bean
    public ChatClient shopChatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
