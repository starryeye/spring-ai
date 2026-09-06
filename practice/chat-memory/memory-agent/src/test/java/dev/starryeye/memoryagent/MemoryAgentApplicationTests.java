package dev.starryeye.memoryagent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code chat.memory.max-messages} 를 기본값(20)이 아닌 3으로 오버라이드한 컨텍스트.
 * 자동설정({@code ChatMemoryAutoConfiguration})이 만드는 {@code ChatMemory} 도
 * 똑같이 {@code MessageWindowChatMemory} 이고 그 기본 창 크기(20)가 우리 커밋값(20)과
 * 우연히 같아서, 타입만 확인하는 검사로는 우리가 정의한 빈이 실제로 쓰이는지
 * 구분할 수 없다. 그래서 이 값을 3으로 바꿔 창 크기가 실제로 반영되는지를
 * 행동으로 검증한다 — {@link ChatMemoryConfig} 의 {@code @Bean} 이 사라지면
 * 자동설정 것이 대신 들어오고, 그것은 이 프로퍼티를 읽지 않으므로 창이 20으로
 * 남아 이 테스트가 실패한다.
 */
@SpringBootTest(properties = "chat.memory.max-messages=3")
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
	 * 자동설정 것이 잡히면 {@code chat.memory.max-messages} 설정이 무시되고
	 * 창 크기가 기본값(20)으로 남는다.
	 *
	 * <p>단순히 타입만 확인하면 안 된다 — 자동설정도 {@code MessageWindowChatMemory}
	 * 를 만들기 때문에 타입 검사로는 어느 빈이 이겼는지 구분되지 않는다.
	 * 그래서 창 크기(3)를 실제로 넘겨서 오래된 메시지가 밀려나는지를 확인한다.
	 */
	@Test
	void 내가_정의한_ChatMemory_빈이_쓰인다() {
		assertThat(chatMemory).isInstanceOf(MessageWindowChatMemory.class);

		chatMemory.add("probe", List.of(new UserMessage("첫번째")));
		chatMemory.add("probe", List.of(new UserMessage("두번째")));
		chatMemory.add("probe", List.of(new UserMessage("세번째")));
		chatMemory.add("probe", List.of(new UserMessage("네번째")));

		List<Message> messages = chatMemory.get("probe");

		assertThat(messages).hasSize(3);
		assertThat(messages).extracting(Message::getText)
				.containsExactly("두번째", "세번째", "네번째");
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
