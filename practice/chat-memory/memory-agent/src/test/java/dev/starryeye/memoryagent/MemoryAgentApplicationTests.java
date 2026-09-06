package dev.starryeye.memoryagent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.DefaultChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
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

	/**
	 * {@code isNotNull()} 만으로는 "자동설정이 준 {@code InMemoryChatMemoryRepository} 를
	 * 그대로 쓴다"는 이름의 주장을 확인할 수 없다 — 어떤 빈이 와도 통과하기 때문이다.
	 * 타입을 확인하고, 한 걸음 더 나아가 {@code ChatMemory} 와 {@code ChatMemoryRepository}
	 * 가 실제로 같은 저장소를 공유하는지도 증명한다: 저장소에 직접 쓰고 {@code ChatMemory}
	 * 로 읽는다. ({@code MessageWindowChatMemory.get()} 은 창을 적용하지 않고 저장소 조회를
	 * 그대로 반환하므로 이 확인이 가능하다.)
	 */
	@Test
	void 저장소는_자동설정이_준_것을_그대로_쓴다() {
		assertThat(chatMemoryRepository).isInstanceOf(InMemoryChatMemoryRepository.class);

		chatMemoryRepository.saveAll("shared-probe", List.of(new UserMessage("저장소 공유 확인")));

		assertThat(chatMemory.get("shared-probe"))
				.extracting(Message::getText)
				.containsExactly("저장소 공유 확인");
	}

	/**
	 * {@code isNotNull()} 만으로는 이 빈에 {@code MessageChatMemoryAdvisor} 가 실제로
	 * 붙어 있는지 확인할 수 없다 — {@code ChatClientConfig} 에서 {@code defaultAdvisors(...)}
	 * 한 줄을 지워도 이 빈은 여전히 만들어지고 이 단언은 여전히 통과한다. LLM을 부르지
	 * 않고 이걸 확인하려면 어드바이저 목록 자체를 들여다봐야 한다.
	 *
	 * <p>{@code ChatClient.prompt()} 는 모델을 호출하지 않고 기본 요청 스펙을 복사해
	 * 돌려줄 뿐이다({@code DefaultChatClient.prompt()} 바이트코드로 확인). 그 구현체인
	 * {@code DefaultChatClient.DefaultChatClientRequestSpec} 은 공개 메서드
	 * {@code getAdvisors()} 를 가지고 있어, 여기서 붙은 어드바이서 목록을 LLM 호출 없이
	 * 검사할 수 있다.
	 */
	@Test
	void ChatClient_에_MessageChatMemoryAdvisor_가_붙어있다() {
		assertThat(chatClient).isNotNull();

		ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
		assertThat(spec).isInstanceOf(DefaultChatClient.DefaultChatClientRequestSpec.class);

		List<Advisor> advisors = ((DefaultChatClient.DefaultChatClientRequestSpec) spec).getAdvisors();
		assertThat(advisors).anyMatch(MessageChatMemoryAdvisor.class::isInstance);
	}
}
