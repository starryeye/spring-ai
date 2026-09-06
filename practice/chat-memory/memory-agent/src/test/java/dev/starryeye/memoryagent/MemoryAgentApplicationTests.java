package dev.starryeye.memoryagent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MemoryAgentApplicationTests {

	@Autowired
	ChatMemory chatMemory;

	@Autowired
	ChatMemoryRepository chatMemoryRepository;

	@Autowired
	ChatClient chatClient;

	@Test
	void contextLoads() {
	}

	/**
	 * ChatMemory 자동설정({@code ChatMemoryAutoConfiguration})은
	 * {@code @ConditionalOnMissingBean} 이므로, 우리가 정의한 빈이 이겨야 한다.
	 * 자동설정 것이 잡히면 maxMessages 설정이 무시된다.
	 */
	@Test
	void 내가_정의한_ChatMemory_빈이_쓰인다() {
		assertThat(chatMemory).isInstanceOf(MessageWindowChatMemory.class);
	}

	@Test
	void 저장소는_자동설정이_준_것을_그대로_쓴다() {
		assertThat(chatMemoryRepository).isNotNull();
	}

	@Test
	void ChatClient_빈이_만들어진다() {
		assertThat(chatClient).isNotNull();
	}
}
