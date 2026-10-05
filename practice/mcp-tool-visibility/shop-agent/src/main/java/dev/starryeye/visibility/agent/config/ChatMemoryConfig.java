package dev.starryeye.visibility.agent.config;

import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 사용자별 대화 기억이다. tool 호출과 결과까지 남긴다(안내서 11장).
 *
 * <p>Spring AI의 기본 배치에서는 대화 기억 advisor가 tool loop 바깥에 있어, 사용자 질문과 마지막 답만 남는다.
 * 그러면 다음 turn의 모델은 이전 tool 결과에 있던 장바구니 handle을 보지 못한다.
 * 그래서 {@code ToolCallingAdvisor}의 내부 history를 끄고, 대화 기억 advisor를 그 안쪽(더 큰 order)에 둔다.
 * tool loop의 한 단계마다 기억을 거치므로 tool 호출과 결과가 차례로 저장된다.
 *
 * <p>두 설정은 함께 바꾼다.
 * 내부 history만 끄면, tool loop의 다음 단계에서 모델은 사용자 질문 없이 tool 결과만 받는다.
 * 내부 history를 켠 채 기억만 안쪽에 두면, {@code ToolCallingAdvisor}와 대화 기억이 같은 message를 따로 쌓는다.
 * {@code MessageChatMemoryAdvisor}는 prompt에 기억이 이미 들어 있으면 다시 넣지 않지만, 이 비교는 message가 똑같을 때만 맞는다.
 * 그래서 대화 history는 대화 기억 한 곳에서만 관리한다.
 */
@Configuration
public class ChatMemoryConfig {

	/** 최근 메시지 20개만 기억한다. 로컬 모델의 context가 짧기 때문이다. */
	public static final int MAX_MESSAGES = 20;

	/** {@link ToolCallingAdvisor#DEFAULT_ORDER}보다 커야 tool loop 안에 들어간다. */
	public static final int MEMORY_ADVISOR_ORDER = ToolCallingAdvisor.DEFAULT_ORDER + 100;

	/**
	 * store는 자동 구성이 만든 {@code ChatMemoryRepository} bean을 그대로 받는다.
	 * 여기서 직접 새로 만들면 자동 구성의 것과 둘이 되어, 하나는 쓰이지 않고 남는다.
	 */
	@Bean
	public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
		return MessageWindowChatMemory.builder()
				.chatMemoryRepository(chatMemoryRepository)
				.maxMessages(MAX_MESSAGES)
				.build();
	}

	/**
	 * 자동 구성의 같은 bean({@code @ConditionalOnMissingBean})을 대신한다.
	 * 여기서 바꾸려는 것은 내부 history 끄기 하나다.
	 * 다만 bean을 대신하면 자동 구성이 하던 설정 두 가지도 빠진다.
	 * 하나는 {@code spring.ai.chat.client.tool-calling.advisor-order} property로 order를 정하는 것이다.
	 * 다른 하나는 {@code ToolExecutionEligibilityChecker} bean이 있으면 넣어 주는 것이다.
	 * 이 practice는 둘 다 쓰지 않아서 지금은 차이가 없다.
	 */
	@Bean
	public ToolCallingAdvisor.Builder<?> toolCallingAdvisorBuilder(ToolCallingManager toolCallingManager) {
		return ToolCallingAdvisor.builder()
				.toolCallingManager(toolCallingManager)
				.disableInternalConversationHistory();
	}
}
