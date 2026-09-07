package dev.starryeye.memoryauthn.agent;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code ChatMemory} 와 {@code ChatMemoryRepository} 는 자동설정
 * ({@code ChatMemoryAutoConfiguration})이 공짜로 준다 — 둘 다
 * {@code @ConditionalOnMissingBean} 이라 여기서 정의하면 자동설정이 물러난다.
 * {@code maxMessages} 를 통제하려고 {@code ChatMemory} 만 직접 정의하고,
 * 저장소는 자동설정이 주는 {@code InMemoryChatMemoryRepository} 를 그대로 쓴다.
 */
@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository,
                                 @Value("${chat.memory.max-messages:20}") int maxMessages) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(maxMessages)
                .build();
    }
}
