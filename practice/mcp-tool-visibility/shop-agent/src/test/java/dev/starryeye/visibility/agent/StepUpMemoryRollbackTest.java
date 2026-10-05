package dev.starryeye.visibility.agent;

import dev.starryeye.visibility.agent.controller.ChatController;
import dev.starryeye.visibility.agent.discovery.DiscoveryFixtures;
import dev.starryeye.visibility.agent.discovery.McpAuthorizationDiscovery;
import dev.starryeye.visibility.agent.security.StepUpRequiredException;
import dev.starryeye.visibility.agent.security.StepUpState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 결제에서 step-up이 나면 tool 호출이 결과 없이 끊긴다.
 * 그 turn에서 기억에 들어간 질문과 tool 호출을 되돌려, consent 뒤 다시 보낸 질문이 깨끗한 turn으로 시작하는지 본다.
 */
@SpringBootTest
class StepUpMemoryRollbackTest {

	static final ToolDefinition CHECKOUT = DefaultToolDefinition.builder()
			.name("checkout").description("주문한다").inputSchema("{\"type\":\"object\"}").build();

	/** MCP Server가 403 insufficient_scope를 준 것처럼 던진다. */
	static final ToolCallback 권한이_모자란_checkout = new ToolCallback() {

		@Override
		public ToolDefinition getToolDefinition() {
			return CHECKOUT;
		}

		@Override
		public String call(String toolInput) {
			throw new ToolExecutionException(CHECKOUT, new RuntimeException("MCP SDK가 감쌈",
					new StepUpRequiredException(List.of("orders:write"), null)));
		}
	};

	@MockitoBean
	McpAuthorizationDiscovery discovery;

	@MockitoBean
	ChatModel chatModel;

	@MockitoBean(answers = org.mockito.Answers.RETURNS_MOCKS)
	ToolCallbackProvider toolCallbackProvider;

	@Autowired
	ChatController chatController;

	@Autowired
	ChatMemory chatMemory;

	@BeforeEach
	void setUp() {
		given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER))
				.willReturn(DiscoveryFixtures.discovered());
		given(this.toolCallbackProvider.getToolCallbacks()).willReturn(new ToolCallback[] { 권한이_모자란_checkout });
		given(this.chatModel.getOptions()).willReturn(ToolCallingChatOptions.builder().build());
		AssistantMessage toolCall = AssistantMessage.builder()
				.toolCalls(List.of(new AssistantMessage.ToolCall("call-9", "function", "checkout",
						"{\"basketId\":\"bsk_x\"}")))
				.build();
		given(this.chatModel.stream(any(Prompt.class)))
				.willReturn(Flux.just(new ChatResponse(List.of(new Generation(toolCall)))));
	}

	@Test
	void step_up으로_끊긴_turn은_기억에서_되돌린다() {
		List<Message> earlier = List.of(new UserMessage("장바구니 만들어 줘"), new AssistantMessage("bsk_x를 만들었어요"));
		this.chatMemory.add("rollback-user", earlier);

		List<ServerSentEvent<String>> events = this.chatController
				.chat("결제해 줘", new MockHttpSession(), new TestingAuthenticationToken("rollback-user", null))
				.collectList().block(Duration.ofSeconds(10));

		assertThat(events).extracting(ServerSentEvent::event).last().isEqualTo("step-up");
		assertThat(this.chatMemory.get("rollback-user")).extracting(Message::getText)
				.containsExactly("장바구니 만들어 줘", "bsk_x를 만들었어요");
	}

	@Test
	void 거절_안내로_끝난_turn도_기억에서_되돌린다() {
		List<Message> earlier = List.of(new UserMessage("장바구니 만들어 줘"), new AssistantMessage("bsk_x를 만들었어요"));
		this.chatMemory.add("declined-user", earlier);
		// 이 session에서 orders:write로 한 번 step-up했으므로, 다시 403이 오면 카드 대신 거절 안내가 간다.
		MockHttpSession session = new MockHttpSession();
		StepUpState.of(session).start(List.of("orders:write"));

		List<ServerSentEvent<String>> events = this.chatController
				.chat("결제해 줘", session, new TestingAuthenticationToken("declined-user", null))
				.collectList().block(Duration.ofSeconds(10));

		assertThat(events).extracting(ServerSentEvent::event).last().isEqualTo("step-up-declined");
		assertThat(this.chatMemory.get("declined-user")).extracting(Message::getText)
				.containsExactly("장바구니 만들어 줘", "bsk_x를 만들었어요");
	}

	@Test
	void 기억이_비어_있던_사용자도_끊긴_turn이_남지_않는다() {
		this.chatController
				.chat("결제해 줘", new MockHttpSession(), new TestingAuthenticationToken("rollback-empty", null))
				.collectList().block(Duration.ofSeconds(10));

		assertThat(this.chatMemory.get("rollback-empty")).isEmpty();
	}
}
