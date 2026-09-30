package dev.starryeye.stateless.agent;

import dev.starryeye.stateless.agent.discovery.DiscoveryFixtures;
import dev.starryeye.stateless.agent.discovery.McpAuthorizationDiscovery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 대화 기억이 tool loop 안에 있어, tool 호출과 결과(장바구니 handle)가 기억에 남는지 본다.
 * 모델은 첫 요청에서 {@code createBasket}을 부르고, tool 결과를 받으면 답을 쓴다.
 */
@SpringBootTest
class ChatMemoryToolResultTest {

	static final ToolDefinition CREATE_BASKET = DefaultToolDefinition.builder()
			.name("createBasket").description("장바구니를 만든다").inputSchema("{\"type\":\"object\"}").build();

	static final ToolCallback createBasket = new ToolCallback() {

		@Override
		public ToolDefinition getToolDefinition() {
			return CREATE_BASKET;
		}

		@Override
		public String call(String toolInput) {
			return "장바구니 bsk_test를 만들었습니다.";
		}
	};

	@MockitoBean
	McpAuthorizationDiscovery discovery;

	@MockitoBean
	ChatModel chatModel;

	@MockitoBean(answers = org.mockito.Answers.RETURNS_MOCKS)
	ToolCallbackProvider toolCallbackProvider;

	@Autowired
	ChatClient chatClient;

	@Autowired
	ChatMemory chatMemory;

	List<Prompt> prompts = new CopyOnWriteArrayList<>();

	static ChatResponse 응답(AssistantMessage message) {
		return new ChatResponse(List.of(new Generation(message)));
	}

	@BeforeEach
	void setUp() {
		given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER))
				.willReturn(DiscoveryFixtures.discovered());
		given(this.toolCallbackProvider.getToolCallbacks()).willReturn(new ToolCallback[0]);
		given(this.chatModel.getOptions()).willReturn(ToolCallingChatOptions.builder().build());
		AssistantMessage toolCall = AssistantMessage.builder()
				.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "createBasket", "{}")))
				.build();
		given(this.chatModel.stream(any(Prompt.class))).willAnswer(invocation -> {
			Prompt prompt = invocation.getArgument(0);
			this.prompts.add(prompt);
			List<Message> messages = prompt.getInstructions();
			if (messages.get(messages.size() - 1) instanceof ToolResponseMessage) {
				return Flux.just(응답(new AssistantMessage("장바구니를 만들었어요")));
			}
			if (prompt.getUserMessage().getText().contains("담아")) {
				return Flux.just(응답(new AssistantMessage("그 장바구니에 담을게요")));
			}
			return Flux.just(응답(toolCall));
		});
	}

	void 묻는다(String conversationId, String question) {
		this.chatClient.prompt()
				.user(question)
				.advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
				// toolCallbacks(ToolCallback...)는 2.0.0부터 deprecated(forRemoval)다 — tools(Object...)가 대체한다.
				.tools(createBasket)
				.stream()
				.content()
				.collectList()
				.block(Duration.ofSeconds(10));
	}

	@Test
	void tool_결과의_handle이_대화_기억에_남는다() {
		묻는다("memory-a", "장바구니 만들어 줘");

		List<Message> remembered = this.chatMemory.get("memory-a");
		assertThat(remembered).anySatisfy(message -> assertThat(message).isInstanceOfSatisfying(
				AssistantMessage.class, assistant -> assertThat(assistant.hasToolCalls()).isTrue()));
		assertThat(remembered).anySatisfy(message -> assertThat(message).isInstanceOfSatisfying(
				ToolResponseMessage.class,
				response -> assertThat(response.getResponses().get(0).responseData()).contains("bsk_test")));
	}

	@Test
	void 다음_turn의_모델은_이전_tool_결과를_본다() {
		묻는다("memory-b", "장바구니 만들어 줘");
		this.prompts.clear();

		묻는다("memory-b", "p4 하나 담아 줘");

		assertThat(this.prompts.get(0).getInstructions()).anySatisfy(message -> assertThat(message)
				.isInstanceOfSatisfying(ToolResponseMessage.class,
						response -> assertThat(response.getResponses().get(0).responseData()).contains("bsk_test")));
	}

	@Test
	void 사용자끼리_대화_기억이_섞이지_않는다() {
		묻는다("memory-c", "장바구니 만들어 줘");

		assertThat(this.chatMemory.get("memory-d")).isEmpty();
	}
}
