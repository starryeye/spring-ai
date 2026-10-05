package dev.starryeye.visibility.agent.mcp;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnknownToolAwareToolCallbackTest {

	static final ToolDefinition UPDATE_STOCK = DefaultToolDefinition.builder()
			.name("updateStock").description("재고를 바꾼다").inputSchema("{\"type\":\"object\"}").build();

	AtomicInteger invalidated = new AtomicInteger();

	static ToolCallback 던지는_tool(RuntimeException error) {
		return new ToolCallback() {

			@Override
			public ToolDefinition getToolDefinition() {
				return UPDATE_STOCK;
			}

			@Override
			public String call(String toolInput) {
				throw error;
			}
		};
	}

	static McpError 오류(int code, String message, Object data) {
		return new McpError(new McpSchema.JSONRPCResponse.JSONRPCError(code, message, data));
	}

	@Test
	void 모르는_tool_오류면_목록을_버리고_모델에게_돌려줄_ToolExecutionException으로_바꾼다() {
		ToolCallback callback = new UnknownToolAwareToolCallback(
				던지는_tool(오류(-32602, "Unknown tool: invalid_tool_name", "Tool not found: updateStock")),
				this.invalidated::incrementAndGet);

		assertThatThrownBy(() -> callback.call("{}"))
				.isInstanceOf(ToolExecutionException.class)
				.hasMessageContaining("Unknown tool: invalid_tool_name");
		assertThat(this.invalidated).hasValue(1);
	}

	@Test
	void 다른_JSON_RPC_오류는_그대로_던지고_목록을_버리지_않는다() {
		McpError other = 오류(-32602, "Invalid arguments", null);
		ToolCallback callback = new UnknownToolAwareToolCallback(던지는_tool(other), this.invalidated::incrementAndGet);

		assertThatThrownBy(() -> callback.call("{}")).isSameAs(other);
		assertThat(this.invalidated).hasValue(0);
	}

	@Test
	void 성공하면_결과와_정의를_그대로_쓴다() {
		ToolCallback ok = new ToolCallback() {

			@Override
			public ToolDefinition getToolDefinition() {
				return UPDATE_STOCK;
			}

			@Override
			public String call(String toolInput) {
				return "재고를 10개로 바꿨습니다.";
			}
		};
		ToolCallback callback = new UnknownToolAwareToolCallback(ok, this.invalidated::incrementAndGet);

		assertThat(callback.call("{}")).isEqualTo("재고를 10개로 바꿨습니다.");
		assertThat(callback.getToolDefinition()).isSameAs(UPDATE_STOCK);
		assertThat(this.invalidated).hasValue(0);
	}

	@Test
	void 알려진_message지만_다른_code면_그대로_던지고_목록을_버리지_않는다() {
		McpError other = 오류(-32601, "Unknown tool: invalid_tool_name", null);
		ToolCallback callback = new UnknownToolAwareToolCallback(던지는_tool(other), this.invalidated::incrementAndGet);

		assertThatThrownBy(() -> callback.call("{}")).isSameAs(other);
		assertThat(this.invalidated).hasValue(0);
	}
}
