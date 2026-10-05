package dev.starryeye.visibility.agent;

import dev.starryeye.visibility.agent.controller.ChatController;
import dev.starryeye.visibility.agent.discovery.DiscoveryFixtures;
import dev.starryeye.visibility.agent.discovery.McpAuthorizationDiscovery;
import dev.starryeye.visibility.agent.mcp.UserToolCatalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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

	static final ToolDefinition ADD_ITEM = DefaultToolDefinition.builder()
			.name("addItem").description("장바구니에 상품을 담는다").inputSchema("{\"type\":\"object\"}").build();

	/** 앞 turn에서 만든 handle(bsk_test)이 이 turn의 tool 호출까지 이어지는지 보려고 둔 tool이다. */
	static final ToolCallback addItem = new ToolCallback() {

		@Override
		public ToolDefinition getToolDefinition() {
			return ADD_ITEM;
		}

		@Override
		public String call(String toolInput) {
			return "bsk_test 장바구니에 p4를 담았습니다.";
		}
	};

	@MockitoBean
	McpAuthorizationDiscovery discovery;

	@MockitoBean
	ChatModel chatModel;

	@MockitoBean
	UserToolCatalog toolCatalog;

	@Autowired
	ChatClient chatClient;

	@Autowired
	ChatMemory chatMemory;

	@Autowired
	ChatController chatController;

	List<Prompt> prompts = new CopyOnWriteArrayList<>();

	/** tool 호출마다 다른 id를 주려고 둔 counter다. id가 겹치면, 서로 다른 대화의 기억이 값만으로 우연히 같아 보인다. */
	AtomicInteger toolCallSequence = new AtomicInteger();

	static ChatResponse 응답(AssistantMessage message) {
		return new ChatResponse(List.of(new Generation(message)));
	}

	static AssistantMessage 도구_호출(String id, String toolName) {
		return AssistantMessage.builder()
				.toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", toolName, "{}")))
				.build();
	}

	@BeforeEach
	void setUp() {
		given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER))
				.willReturn(DiscoveryFixtures.discovered());
		given(this.toolCatalog.callbacks(any())).willReturn(List.of());
		given(this.chatModel.getOptions()).willReturn(ToolCallingChatOptions.builder().build());
		given(this.chatModel.stream(any(Prompt.class))).willAnswer(invocation -> {
			Prompt prompt = invocation.getArgument(0);
			this.prompts.add(prompt);
			List<Message> messages = prompt.getInstructions();
			Message last = messages.get(messages.size() - 1);
			// 직전 tool의 결과를 받은 iteration이다.
			// 어느 tool이었는지로 이번 turn의 마지막 답을 가른다.
			if (last instanceof ToolResponseMessage response) {
				String toolName = response.getResponses().get(0).name();
				if ("addItem".equals(toolName)) {
					return Flux.just(응답(new AssistantMessage("그 장바구니에 담았어요")));
				}
				return Flux.just(응답(new AssistantMessage("장바구니를 만들었어요")));
			}
			String callId = "call-" + this.toolCallSequence.incrementAndGet();
			if (prompt.getUserMessage().getText().contains("담아")) {
				return Flux.just(응답(도구_호출(callId, "addItem")));
			}
			return Flux.just(응답(도구_호출(callId, "createBasket")));
		});
	}

	void 묻는다(String conversationId, String question) {
		this.chatClient.prompt()
				.user(question)
				.advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
				// toolCallbacks(ToolCallback...)는 없어질 예정(deprecated, forRemoval)이다.
				// tools(Object...)가 그 대체다.
				.tools(createBasket, addItem)
				.stream()
				.content()
				.collectList()
				.block(Duration.ofSeconds(10));
	}

	@Test
	void tool_결과의_handle이_대화_기억에_남는다() {
		묻는다("memory-a", "장바구니 만들어 줘");

		List<Message> remembered = this.chatMemory.get("memory-a");
		// anySatisfy는 순서와 중복을 보지 않는다. 여기서는 정확히 이 네 개가 이 순서로 있어야 한다.
		assertThat(remembered).extracting(Message::getMessageType)
				.containsExactly(MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL, MessageType.ASSISTANT);
		assertThat(remembered.get(1)).isInstanceOfSatisfying(AssistantMessage.class,
				assistant -> assertThat(assistant.hasToolCalls()).isTrue());
		assertThat(remembered.get(2)).isInstanceOfSatisfying(ToolResponseMessage.class,
				response -> assertThat(response.getResponses().get(0).responseData()).contains("bsk_test"));
		// 마지막 자리는 streaming이 다 모은 뒤 기억에 넣은 최종 답이다.
		assertThat(remembered.get(3)).isInstanceOfSatisfying(AssistantMessage.class,
				assistant -> assertThat(assistant.getText()).isEqualTo("장바구니를 만들었어요"));
	}

	@Test
	void 다음_turn의_모델은_이전_tool_결과를_본다() {
		묻는다("memory-b", "장바구니 만들어 줘");
		this.prompts.clear();

		묻는다("memory-b", "p4 하나 담아 줘");

		// 이번 turn도 tool을 부르니 loop이 두 번 돈다.
		// 두 번째 iteration의 prompt가 모델이 실제로 받는 전체 대화다.
		assertThat(this.prompts).hasSize(2);
		List<Message> secondIterationPrompt = this.prompts.get(1).getInstructions();
		// 여기 순서·개수가 하나라도 틀리면, 기억 advisor가 tool loop 안에 제대로 있지 않다는 뜻이다.
		assertThat(secondIterationPrompt).extracting(Message::getMessageType)
				.containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL,
						MessageType.ASSISTANT, MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL);
		// 앞 turn의 handle(bsk_test)이 그대로 있어야, 이번 turn의 addItem이 그 장바구니를 찾을 수 있다.
		assertThat(secondIterationPrompt.get(3)).isInstanceOfSatisfying(ToolResponseMessage.class,
				response -> assertThat(response.getResponses().get(0).responseData()).contains("bsk_test"));
	}

	@Test
	void 사용자끼리_대화_기억이_섞이지_않는다() {
		// ChatController가 authentication.getName()을 conversation ID로 쓴다는 것이 이 test가 보장하려는 것이다.
		// 그래서 대화 ID를 직접 고르지 않고 실제 controller를 거친다.
		given(this.toolCatalog.callbacks(any())).willReturn(List.of(createBasket, addItem));
		Authentication userA = new TestingAuthenticationToken("a", null, "ROLE_USER");
		Authentication userB = new TestingAuthenticationToken("b", null, "ROLE_USER");

		this.chatController.chat("장바구니 만들어 줘", new MockHttpSession(), userA)
				.collectList().block(Duration.ofSeconds(10));
		this.chatController.chat("장바구니 만들어 줘", new MockHttpSession(), userB)
				.collectList().block(Duration.ofSeconds(10));

		List<Message> conversationA = this.chatMemory.get("a");
		List<Message> conversationB = this.chatMemory.get("b");
		// 같은 질문·같은 답이라 문구만으로는 두 대화가 우연히 같아 보일 수 있다.
		// 그래서 각 대화가 따로 받은 tool 호출 id로 저장이 실제로 갈라져 있는지를 본다.
		assertThat(conversationA).extracting(Message::getMessageType)
				.containsExactly(MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL, MessageType.ASSISTANT);
		assertThat(conversationB).extracting(Message::getMessageType)
				.containsExactly(MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL, MessageType.ASSISTANT);
		String toolCallIdInA = ((AssistantMessage) conversationA.get(1)).getToolCalls().get(0).id();
		String toolCallIdInB = ((AssistantMessage) conversationB.get(1)).getToolCalls().get(0).id();
		assertThat(toolCallIdInA).isNotEqualTo(toolCallIdInB);
	}
}
