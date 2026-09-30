package dev.starryeye.stateless.agent.config;

import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
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
 * <p>둘 중 하나만 바꾸면 조용히 틀어진다.
 * 내부 history만 끄면 tool 결과가 어디에도 남지 않고, 기억만 안쪽에 두면 history가 두 번 들어간다.
 */
@Configuration
public class ChatMemoryConfig {

	/** 최근 메시지 20개만 기억한다. 로컬 모델의 context가 짧기 때문이다. */
	public static final int MAX_MESSAGES = 20;

	/** {@link ToolCallingAdvisor#DEFAULT_ORDER}보다 커야 tool loop 안에 들어간다. */
	public static final int MEMORY_ADVISOR_ORDER = ToolCallingAdvisor.DEFAULT_ORDER + 100;

	@Bean
	public ChatMemory chatMemory() {
		return MessageWindowChatMemory.builder()
				.chatMemoryRepository(new InMemoryChatMemoryRepository())
				.maxMessages(MAX_MESSAGES)
				.build();
	}

	/** 자동 구성의 같은 bean({@code @ConditionalOnMissingBean})을 대신한다. 바꾸는 것은 내부 history 끄기뿐이다. */
	@Bean
	public ToolCallingAdvisor.Builder<?> toolCallingAdvisorBuilder(ToolCallingManager toolCallingManager) {
		return ToolCallingAdvisor.builder()
				.toolCallingManager(toolCallingManager)
				.disableInternalConversationHistory();
	}
}
