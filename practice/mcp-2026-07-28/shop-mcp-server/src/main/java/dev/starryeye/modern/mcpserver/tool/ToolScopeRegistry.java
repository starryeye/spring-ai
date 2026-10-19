package dev.starryeye.modern.mcpserver.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * tool 이름마다 필요한 scope를 모아 둔 표다.
 *
 * <p>scope 검사는 {@code ToolScopeFilter}가 HTTP 단계에서 한다.
 * filter는 tool 코드를 모르므로, 기동할 때 tool 정의({@link ShopTool})의 scope로 이 표를 만든다.
 *
 * <p>표에 없는 tool은 기본 scope만 요구한다.
 * 그래서 서버가 내놓는 tool이 표에서 빠지면 그 tool의 scope가 조용히 무시된다(fail-open).
 * 이 표는 서버가 {@code tools/list}로 내놓는 정의 목록과 같은 목록에서 만들므로 그런 일이 생기지 않는다.
 */
public final class ToolScopeRegistry {

	/** {@code tools/list}를 포함한 모든 MCP 요청에 필요한 기본 scope다. 가장 위험이 낮은 조회 권한이다. */
	public static final String BASE_SCOPE = "products:read";

	private final Map<String, String> scopeByTool;

	private ToolScopeRegistry(Map<String, String> scopeByTool) {
		this.scopeByTool = Map.copyOf(scopeByTool);
	}

	/** 이름이 같은 tool이 둘이면 어느 scope를 써야 할지 모르므로 표를 만들지 않는다. */
	public static ToolScopeRegistry of(List<ShopTool> tools) {
		Map<String, String> scopes = new LinkedHashMap<>();
		for (ShopTool tool : tools) {
			if (scopes.putIfAbsent(tool.name(), tool.requiredScope()) != null) {
				throw new IllegalStateException("이름이 같은 tool이 둘 있다: " + tool.name());
			}
		}
		return new ToolScopeRegistry(scopes);
	}

	/**
	 * 모르는 tool이면 기본 scope만 요구한다.
	 * 없는 tool은 SDK가 JSON-RPC 오류로 답한다.
	 */
	public String scopeFor(String toolName) {
		return this.scopeByTool.getOrDefault(toolName, BASE_SCOPE);
	}

	public Map<String, String> all() {
		return this.scopeByTool;
	}
}
