package dev.starryeye.memoryagent;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code ChatMemory} 와 {@code ChatMemoryRepository} 빈은 사실 자동설정
 * ({@code ChatMemoryAutoConfiguration})이 공짜로 준다. 둘 다
 * {@code @ConditionalOnMissingBean} 이므로 <b>여기서 직접 정의하면 자동설정이 물러난다.</b>
 *
 * <p>그럼에도 직접 정의하는 이유는 {@code maxMessages} 를 설정으로 바꿔가며
 * "창이 넘치면 무슨 일이 나는지"를 관측하기 위해서다(검증 시나리오 4).
 *
 * <p>{@code ChatMemoryRepository} 는 자동설정이 주는
 * {@code InMemoryChatMemoryRepository} 를 그대로 주입받아 쓴다 — 저장소까지 직접 만들 이유는 없다.
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
