package dev.starryeye.cimd.agent.mcp;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * MCP tool 호출이 "모르는 tool" 오류로 끝나면, 이 사용자의 tool 목록이 낡았다고 보고 버린다(안내서 12장).
 *
 * <p>Spring AI 2.0.1의 {@code SyncMcpToolCallback}은 JSON-RPC 오류({@link McpError})를 감싸지 않고 그대로 던진다.
 * tool loop는 {@link ToolExecutionException}만 잡으므로, 그대로 두면 채팅 stream이 오류로 끝난다.
 * 그래서 여기서 받아 목록을 버리고 {@link ToolExecutionException}으로 바꾼다.
 * 그러면 tool 실행 예외 처리가 오류 문장을 모델에게 tool 결과로 돌려주고 turn이 이어진다.
 */
public final class UnknownToolAwareToolCallback implements ToolCallback {

	/** MCP Java SDK 2.0.1이 없는 tool에 주는 message다. SDK를 올리면 다시 확인한다. */
	static final String UNKNOWN_TOOL_MESSAGE = "Unknown tool: invalid_tool_name";

	private final ToolCallback delegate;

	private final Runnable onUnknownTool;

	public UnknownToolAwareToolCallback(ToolCallback delegate, Runnable onUnknownTool) {
		this.delegate = delegate;
		this.onUnknownTool = onUnknownTool;
	}

	/** code {@code -32602}는 인자 오류에도 쓰이므로 message까지 본다. null code를 안전하게 다룬다. */
	public static boolean isUnknownTool(Throwable error) {
		return error instanceof McpError mcpError && mcpError.getJsonRpcError() != null
				&& Integer.valueOf(McpSchema.ErrorCodes.INVALID_PARAMS).equals(mcpError.getJsonRpcError().code())
				&& UNKNOWN_TOOL_MESSAGE.equals(mcpError.getJsonRpcError().message());
	}

	@Override
	public ToolDefinition getToolDefinition() {
		return this.delegate.getToolDefinition();
	}

	@Override
	public ToolMetadata getToolMetadata() {
		return this.delegate.getToolMetadata();
	}

	@Override
	public String call(String toolInput) {
		return call(toolInput, null);
	}

	@Override
	public String call(String toolInput, @Nullable ToolContext toolContext) {
		try {
			return this.delegate.call(toolInput, toolContext);
		}
		catch (McpError ex) {
			if (!isUnknownTool(ex)) {
				throw ex;
			}
			this.onUnknownTool.run();
			throw new ToolExecutionException(getToolDefinition(), ex);
		}
	}
}
