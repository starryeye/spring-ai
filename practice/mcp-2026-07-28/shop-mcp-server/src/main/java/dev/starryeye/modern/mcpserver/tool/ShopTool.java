package dev.starryeye.modern.mcpserver.tool;

import dev.starryeye.modern.mcpserver.security.McpCaller;

import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.Tool;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * tool 하나의 정의다. client에게 보여 줄 {@link Tool}, 부르는 데 필요한 scope, 실행할 handler를 함께 둔다.
 *
 * <p>MCP 명세에는 tool 정의에 필요한 scope를 적는 표준 field가 없다.
 * 그래서 scope는 {@link Tool} 밖의 field로 두고, client에게는 권한이 모자랄 때 {@code 403}의 {@code WWW-Authenticate}로 알린다.
 * 정의와 scope가 한 record에 있으므로, 서버가 내놓는 tool이 scope 표({@link ToolScopeRegistry})에서 빠질 수 없다.
 *
 * @param tool {@code tools/list}로 내놓는 이름·설명·입력 schema
 * @param requiredScope 이 tool을 부르려면 access token에 있어야 하는 scope(안내서 10장)
 * @param handler 요청한 사용자와 {@code arguments}를 받아 결과를 만든다
 */
public record ShopTool(Tool tool, String requiredScope,
		BiFunction<McpCaller, Map<String, Object>, CallToolResult> handler) {

	public String name() {
		return this.tool.name();
	}
}
