package dev.starryeye.modern.agent.mcp;

import dev.starryeye.modern.agent.security.StepUpRequiredException;

import io.modelcontextprotocol.modern.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import tools.jackson.databind.json.JsonMapper;

import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ModernToolCallbackTest {

	static final McpSchema.Tool UPDATE_STOCK = McpSchema.Tool.builder("updateStock", Map.of(
					"type", "object",
					"properties", Map.of("productId", Map.of("type", "string"), "quantity", Map.of("type", "integer")),
					"required", List.of("productId", "quantity")))
			.description("상품 재고를 바꾼다")
			.build();

	ModernMcpClient client = mock(ModernMcpClient.class);

	AtomicInteger forgotten = new AtomicInteger();

	ModernToolCallback callback = new ModernToolCallback(UPDATE_STOCK, this.client, () -> "token-a",
			this.forgotten::incrementAndGet);

	static McpSchema.CallToolResult 결과(boolean isError, String... texts) {
		McpSchema.CallToolResult.Builder builder = McpSchema.CallToolResult.builder().isError(isError);
		for (String text : texts) {
			builder.addContent(McpSchema.TextContent.builder(text).build());
		}
		return builder.build();
	}

	@Test
	void ToolDefinition은_tool의_이름_설명_입력_schema다() {
		ToolDefinition definition = this.callback.getToolDefinition();

		assertThat(definition.name()).isEqualTo("updateStock");
		assertThat(definition.description()).isEqualTo("상품 재고를 바꾼다");
		JsonMapper json = JsonMapper.builder().build();
		assertThat(json.readTree(definition.inputSchema())).isEqualTo(json.valueToTree(UPDATE_STOCK.inputSchema()));
	}

	@Test
	void 설명이_없는_tool은_이름을_설명으로_쓴다() {
		// Spring AI의 ToolDefinition은 빈 설명을 받지 않는다.
		McpSchema.Tool noDescription = McpSchema.Tool.builder("getBasket", Map.of("type", "object")).build();

		ToolDefinition definition = new ModernToolCallback(noDescription, this.client, () -> "token-a", () -> {
		}).getToolDefinition();

		assertThat(definition.description()).isEqualTo("getBasket");
	}

	@Test
	void content의_text를_이어_돌려준다() {
		given(this.client.callTool(anyString(), anyString(), anyMap()))
				.willReturn(결과(false, "p1 재고를 10개로 바꿨습니다.", "이전 재고는 7개였습니다."));

		String text = this.callback.call("{\"productId\":\"p1\",\"quantity\":10}");

		assertThat(text).isEqualTo("p1 재고를 10개로 바꿨습니다.\n이전 재고는 7개였습니다.");
		verify(this.client).callTool("token-a", "updateStock", Map.of("productId", "p1", "quantity", 10));
	}

	@Test
	void isError_결과도_같은_방식으로_모델에게_돌려준다() {
		given(this.client.callTool(anyString(), anyString(), anyMap()))
				.willReturn(결과(true, "상품 ID p9에 해당하는 상품이 없습니다."));

		assertThat(this.callback.call("{\"productId\":\"p9\",\"quantity\":1}")).isEqualTo("상품 ID p9에 해당하는 상품이 없습니다.");
	}

	@Test
	void 빈_입력은_인자_없는_호출이다() {
		given(this.client.callTool(anyString(), anyString(), anyMap())).willReturn(결과(false, "ok"));

		this.callback.call("");

		verify(this.client).callTool("token-a", "updateStock", Map.of());
	}

	@Test
	void StepUpRequiredException은_그대로_던진다() {
		StepUpRequiredException stepUp = new StepUpRequiredException(List.of("products:write"), "updateStock");
		given(this.client.callTool(anyString(), anyString(), anyMap())).willThrow(stepUp);

		assertThatThrownBy(() -> this.callback.call("{}")).isSameAs(stepUp);
		assertThat(this.forgotten).hasValue(0);
	}

	@Test
	void 모르는_tool이면_목록을_버리고_ToolExecutionException이다() {
		given(this.client.callTool(anyString(), anyString(), anyMap()))
				.willThrow(new UnknownToolException(400, "Unknown tool: updateStock"));

		assertThatThrownBy(() -> this.callback.call("{}"))
				.isInstanceOf(ToolExecutionException.class)
				.hasMessage("Unknown tool: updateStock")
				.hasCauseInstanceOf(UnknownToolException.class);
		assertThat(this.forgotten).hasValue(1);
	}

	@Test
	void 속도_제한과_그_밖의_MCP_오류는_목록을_두고_ToolExecutionException이다() {
		given(this.client.callTool(anyString(), anyString(), anyMap()))
				.willThrow(new ToolRateLimitedException(Duration.ofSeconds(17), -32000,
						"tool 호출이 너무 잦습니다. 17초 뒤에 다시 시도하세요."))
				.willThrow(new ModernMcpException(400, -32602, "Invalid arguments for tool updateStock"));

		assertThatThrownBy(() -> this.callback.call("{}"))
				.isInstanceOf(ToolExecutionException.class)
				.hasMessage("tool 호출이 너무 잦습니다. 17초 뒤에 다시 시도하세요.");
		assertThatThrownBy(() -> this.callback.call("{}"))
				.isInstanceOf(ToolExecutionException.class)
				.hasMessage("Invalid arguments for tool updateStock");
		assertThat(this.forgotten).hasValue(0);
	}

	@Test
	void MCP_Server에_닿지_못하면_목록을_두고_ToolExecutionException이다() {
		// 예외가 tool loop 밖으로 나가면 채팅이 오류로 끊기고, 답 없는 tool 호출이 대화 기억에 남는다.
		given(this.client.callTool(anyString(), anyString(), anyMap()))
				.willThrow(new McpTransportException("MCP Server에 연결하지 못했다", new ConnectException()));

		assertThatThrownBy(() -> this.callback.call("{}"))
				.isInstanceOf(ToolExecutionException.class)
				.hasMessage("MCP Server에 연결하지 못했다");
		assertThat(this.forgotten).hasValue(0);
	}

	@Test
	void JSON_object가_아닌_입력은_ToolExecutionException이다() {
		assertThatThrownBy(() -> this.callback.call("[1,2]")).isInstanceOf(ToolExecutionException.class);
	}
}
