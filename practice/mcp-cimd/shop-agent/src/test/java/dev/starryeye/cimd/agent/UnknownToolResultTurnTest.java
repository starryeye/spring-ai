package dev.starryeye.cimd.agent;

import dev.starryeye.cimd.agent.controller.ChatController;
import dev.starryeye.cimd.agent.discovery.DiscoveryFixtures;
import dev.starryeye.cimd.agent.discovery.McpAuthorizationDiscovery;
import dev.starryeye.cimd.agent.mcp.UserToolCatalog;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * agent의 목록이 낡으면, 목록에 있는 tool을 불러도 MCP Server가 "모르는 tool" 오류로 답한다.
 * 이 오류가 Spring AI의 실제 tool loop를 지나 모델에게 tool 결과로 돌아가고, turn이 이어지는지 본다.
 *
 * <p>목록은 운영과 같은 loader({@link UserToolCatalog#mcp})로 만든다.
 * 그래서 tool마다 {@code SyncMcpToolCallback}을 {@code UnknownToolAwareToolCallback}이 감싼다.
 * MCP client만 mock이고, {@code callTool}은 SDK 2.0.1이 없는 tool에 주는 JSON-RPC 오류({@link McpError})를 던진다.
 */
@SpringBootTest
class UnknownToolResultTurnTest {

	@MockitoBean
	McpAuthorizationDiscovery discovery;

	@MockitoBean
	ChatModel chatModel;

	@MockitoBean
	UserToolCatalog toolCatalog;

	@Autowired
	ChatController chatController;

	McpSyncClient client = mock(McpSyncClient.class);

	/** 목록을 버린 횟수다. */
	AtomicInteger invalidated = new AtomicInteger();

	/** 모델이 받은 tool 결과다. */
	List<ToolResponseMessage> toolResults = new CopyOnWriteArrayList<>();

	static ChatResponse 응답(AssistantMessage message) {
		return new ChatResponse(List.of(new Generation(message)));
	}

	@BeforeEach
	void setUp() {
		given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.AUTH_METHOD))
				.willReturn(DiscoveryFixtures.discovered());
		McpSchema.Tool updateStock = McpSchema.Tool.builder("updateStock", Map.of("type", "object"))
				.description("상품 재고를 바꾼다")
				.build();
		given(this.client.listTools()).willReturn(McpSchema.ListToolsResult.builder(List.of(updateStock)).build());
		given(this.client.callTool(any())).willThrow(new McpError(new McpSchema.JSONRPCResponse.JSONRPCError(
				McpSchema.ErrorCodes.INVALID_PARAMS, "Unknown tool: invalid_tool_name", "Tool not found: updateStock")));
		List<ToolCallback> tools = UserToolCatalog.mcp(this.client).load(this.invalidated::incrementAndGet);
		given(this.toolCatalog.callbacks(any())).willReturn(tools);
		// ToolCallingAdvisor는 prompt의 option이 ToolCallingChatOptions일 때만 tool을 부른다.
		given(this.chatModel.getOptions()).willReturn(ToolCallingChatOptions.builder().build());
		AssistantMessage toolCall = AssistantMessage.builder()
				.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "updateStock",
						"{\"productId\":\"p1\",\"quantity\":10}")))
				.build();
		given(this.chatModel.stream(any(Prompt.class))).willAnswer(invocation -> {
			Prompt prompt = invocation.getArgument(0);
			List<ToolResponseMessage> results = prompt.getInstructions().stream()
					.filter(ToolResponseMessage.class::isInstance)
					.map(ToolResponseMessage.class::cast)
					.toList();
			if (!results.isEmpty()) {
				this.toolResults.addAll(results);
				return Flux.just(응답(new AssistantMessage("재고를 바꾸지 못했습니다")));
			}
			return Flux.just(응답(toolCall));
		});
	}

	@Test
	void 낡은_목록의_tool이_모르는_tool_오류를_받으면_모델에게_tool_결과로_돌려주고_turn이_이어진다() {
		// 감싸는 callback이 빠지면 McpError가 tool loop 밖으로 나가 이 stream이 오류로 끝난다.
		List<ServerSentEvent<String>> events = this.chatController
				.chat("p1 재고를 10개로 바꿔 줘", new MockHttpSession(), new TestingAuthenticationToken("stale-user", null))
				.collectList().block(Duration.ofSeconds(10));

		assertThat(this.toolResults).flatExtracting(ToolResponseMessage::getResponses)
				.singleElement()
				.satisfies(response -> {
					assertThat(response.name()).isEqualTo("updateStock");
					assertThat(response.responseData()).contains("Unknown tool: invalid_tool_name");
				});
		// step-up 카드나 tool-unavailable 안내 없이, 모델의 답으로 turn이 끝난다.
		assertThat(events).extracting(ServerSentEvent::event).containsOnly("message");
		assertThat(events).extracting(ServerSentEvent::data).containsExactly("\"재고를 바꾸지 못했습니다\"");
		assertThat(this.invalidated).hasValue(1);
	}
}
