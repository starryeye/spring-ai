package dev.starryeye.cimd.agent;

import dev.starryeye.cimd.agent.controller.ChatController;
import dev.starryeye.cimd.agent.discovery.DiscoveryFixtures;
import dev.starryeye.cimd.agent.discovery.McpAuthorizationDiscovery;
import dev.starryeye.cimd.agent.mcp.UserToolCatalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
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
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;

/**
 * 질문마다 그 사용자의 tool 목록이 모델에게 가는지 본다.
 * 앱을 시작할 때 고정한 목록이나 다른 사용자의 목록이 섞이면 안 된다.
 */
@SpringBootTest
class UserToolsPerRequestTest {

	@MockitoBean
	McpAuthorizationDiscovery discovery;

	@MockitoBean
	ChatModel chatModel;

	@MockitoBean
	UserToolCatalog toolCatalog;

	@Autowired
	ChatController chatController;

	@Autowired
	ApplicationContext context;

	List<Prompt> prompts = new CopyOnWriteArrayList<>();

	static ToolCallback 도구(String name) {
		ToolDefinition definition = DefaultToolDefinition.builder()
				.name(name).description(name).inputSchema("{\"type\":\"object\"}").build();
		return new ToolCallback() {

			@Override
			public ToolDefinition getToolDefinition() {
				return definition;
			}

			@Override
			public String call(String toolInput) {
				return name;
			}
		};
	}

	static Authentication 사용자(String name) {
		return new TestingAuthenticationToken(name, null);
	}

	@BeforeEach
	void setUp() {
		given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER))
				.willReturn(DiscoveryFixtures.discovered());
		given(this.chatModel.getOptions()).willReturn(ToolCallingChatOptions.builder().build());
		given(this.chatModel.stream(any(Prompt.class))).willAnswer(invocation -> {
			this.prompts.add(invocation.getArgument(0));
			return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("네")))));
		});
		given(this.toolCatalog.callbacks(argThat(user -> user != null && "staff".equals(user.getName()))))
				.willReturn(List.of(도구("getStock"), 도구("updateStock")));
		given(this.toolCatalog.callbacks(argThat(user -> user != null && "customer".equals(user.getName()))))
				.willReturn(List.of(도구("getStock")));
	}

	List<String> 받은_tool(Prompt prompt) {
		return ((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks().stream()
				.map(callback -> callback.getToolDefinition().name()).toList();
	}

	@Test
	void 질문마다_그_사용자의_tool_목록을_넣는다() {
		this.chatController.chat("재고 알려 줘", new MockHttpSession(), 사용자("staff"))
				.collectList().block(Duration.ofSeconds(10));
		this.chatController.chat("재고 알려 줘", new MockHttpSession(), 사용자("customer"))
				.collectList().block(Duration.ofSeconds(10));

		assertThat(this.prompts).hasSize(2);
		assertThat(받은_tool(this.prompts.get(0))).containsExactly("getStock", "updateStock");
		assertThat(받은_tool(this.prompts.get(1))).containsExactly("getStock");
	}

	@Test
	void 자동_구성의_tool_provider는_없다() {
		assertThat(this.context.getBeansOfType(ToolCallbackProvider.class)).isEmpty();
	}
}
