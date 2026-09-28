package dev.starryeye.authz.mcpserver.tool;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * tool 이름마다 필요한 scope를 모아 둔 표다.
 *
 * <p>scope 검사는 {@code ToolScopeFilter}가 HTTP 단계에서 한다.
 * filter는 tool 코드를 모르므로, 기동할 때 {@link McpTool} 메서드의 {@link RequiredScope}를 읽어 이 표를 만든다.
 */
public final class ToolScopeRegistry {

	/** {@code tools/list}를 포함한 모든 MCP 요청에 필요한 기본 scope다. 가장 위험이 낮은 조회 권한이다. */
	public static final String BASE_SCOPE = "products:read";

	private final Map<String, String> scopeByTool;

	private ToolScopeRegistry(Map<String, String> scopeByTool) {
		this.scopeByTool = Map.copyOf(scopeByTool);
	}

	/** {@link McpTool}이 붙은 메서드를 찾아 tool 이름과 scope를 모은다. {@link RequiredScope}가 없으면 기본 scope다. */
	public static ToolScopeRegistry scan(Object... toolBeans) {
		Map<String, String> scopes = new LinkedHashMap<>();
		for (Object bean : toolBeans) {
			for (Method method : ClassUtils.getUserClass(bean).getMethods()) {
				McpTool tool = method.getAnnotation(McpTool.class);
				if (tool == null) {
					continue;
				}
				String name = tool.name().isEmpty() ? method.getName() : tool.name();
				RequiredScope required = method.getAnnotation(RequiredScope.class);
				scopes.put(name, (required != null) ? required.value() : BASE_SCOPE);
			}
		}
		return new ToolScopeRegistry(scopes);
	}

	/** 모르는 tool이면 기본 scope만 요구한다. 없는 tool은 transport가 JSON-RPC 오류로 답한다. */
	public String scopeFor(String toolName) {
		return this.scopeByTool.getOrDefault(toolName, BASE_SCOPE);
	}

	public Map<String, String> all() {
		return this.scopeByTool;
	}
}
