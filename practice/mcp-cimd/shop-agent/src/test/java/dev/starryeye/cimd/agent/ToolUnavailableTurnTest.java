package dev.starryeye.cimd.agent;

import dev.starryeye.cimd.agent.controller.ChatController;
import dev.starryeye.cimd.agent.discovery.DiscoveryFixtures;
import dev.starryeye.cimd.agent.discovery.McpAuthorizationDiscovery;
import dev.starryeye.cimd.agent.mcp.UserToolCatalog;

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
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
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
 * 손님의 목록에는 updateStock이 없다.
 * 그래도 모델이 그 tool을 부르면, 채팅이 오류로 깨지지 않고 안내 event로 끝나며 그 turn이 기억에서 되돌려지는지 본다.
 */
@SpringBootTest
class ToolUnavailableTurnTest {

	static final ToolDefinition GET_STOCK = DefaultToolDefinition.builder()
			.name("getStock").description("재고를 본다").inputSchema("{\"type\":\"object\"}").build();

	static final ToolCallback getStock = new ToolCallback() {

		@Override
		public ToolDefinition getToolDefinition() {
			return GET_STOCK;
		}

		@Override
		public String call(String toolInput) {
			return "p1 재고 7개";
		}
	};

	@MockitoBean
	McpAuthorizationDiscovery discovery;

	@MockitoBean
	ChatModel chatModel;

	@MockitoBean
	UserToolCatalog toolCatalog;

	@Autowired
	ChatController chatController;

	@Autowired
	ChatMemory chatMemory;

	@BeforeEach
	void setUp() {
		given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER))
				.willReturn(DiscoveryFixtures.discovered());
		given(this.toolCatalog.callbacks(any())).willReturn(List.of(getStock));
		given(this.chatModel.getOptions()).willReturn(ToolCallingChatOptions.builder().build());
		AssistantMessage toolCall = AssistantMessage.builder()
				.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "updateStock",
						"{\"productId\":\"p1\",\"quantity\":10}")))
				.build();
		given(this.chatModel.stream(any(Prompt.class)))
				.willReturn(Flux.just(new ChatResponse(List.of(new Generation(toolCall)))));
	}

	@Test
	void 목록에_없는_tool을_부른_turn은_되돌리고_안내한다() {
		List<Message> earlier = List.of(new UserMessage("p1 재고 알려 줘"), new AssistantMessage("7개예요"));
		this.chatMemory.add("unavailable-user", earlier);

		List<ServerSentEvent<String>> events = this.chatController
				.chat("p1 재고를 10개로 바꿔 줘", new MockHttpSession(), new TestingAuthenticationToken("unavailable-user", null))
				.collectList().block(Duration.ofSeconds(10));

		assertThat(events).extracting(ServerSentEvent::event).last().isEqualTo("tool-unavailable");
		assertThat(events.get(events.size() - 1).data()).isEqualTo("{\"tool\":\"updateStock\"}");
		assertThat(this.chatMemory.get("unavailable-user")).extracting(Message::getText)
				.containsExactly("p1 재고 알려 줘", "7개예요");
	}
}
