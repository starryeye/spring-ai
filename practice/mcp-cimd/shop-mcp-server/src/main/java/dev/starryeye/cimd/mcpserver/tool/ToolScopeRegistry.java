package dev.starryeye.cimd.mcpserver.tool;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * tool 이름마다 필요한 scope를 모아 둔 표다.
 *
 * <p>scope 검사는 {@code ToolScopeFilter}가 HTTP 단계에서 한다.
 * filter는 tool 코드를 모르므로, 기동할 때 {@link McpTool} 메서드의 {@link RequiredScope}를 읽어 이 표를 만든다.
 *
 * <p>표는 손으로 고른 bean 목록이 아니라 ApplicationContext의 모든 bean에서 만든다({@link #scan(ListableBeanFactory)}).
 * 표에 없는 tool은 기본 scope만 요구하므로, tool bean 하나를 빠뜨리면 그 tool의 {@link RequiredScope}가 조용히 무시된다(fail-open).
 * Spring AI도 모든 bean에서 {@link McpTool} 메서드를 찾아 tool로 등록하므로, 같은 기준으로 찾으면 등록된 tool이 표에서 빠지지 않는다.
 */
public final class ToolScopeRegistry {

	/** {@code tools/list}를 포함한 모든 MCP 요청에 필요한 기본 scope다. 가장 위험이 낮은 조회 권한이다. */
	public static final String BASE_SCOPE = "products:read";

	private final Map<String, String> scopeByTool;

	private ToolScopeRegistry(Map<String, String> scopeByTool) {
		this.scopeByTool = Map.copyOf(scopeByTool);
	}

	/**
	 * ApplicationContext에 정의된 모든 bean의 class에서 tool을 찾는다.
	 * bean을 새로 만들지 않도록 instance가 아니라 bean의 type을 본다.
	 */
	public static ToolScopeRegistry scan(ListableBeanFactory beanFactory) {
		Set<Class<?>> types = new LinkedHashSet<>();
		for (String name : beanFactory.getBeanDefinitionNames()) {
			Class<?> type = beanFactory.getType(name, false);
			if (type != null) {
				// CGLIB proxy면 사용자가 쓴 class를 본다. annotation은 그 class의 메서드에 있다.
				types.add(ClassUtils.getUserClass(type));
			}
		}
		return of(types.toArray(Class<?>[]::new));
	}

	/**
	 * 주어진 class에서 {@link McpTool}이 붙은 메서드를 찾아 tool 이름과 scope를 모은다. {@link RequiredScope}가 없으면 기본 scope다.
	 *
	 * <p>메서드를 찾는 규칙은 Spring AI 2.0.x의 annotation provider와 같다.
	 * 상속한 메서드까지 보고({@code ReflectionUtils.getUniqueDeclaredMethods}), 이름이 비어 있으면 메서드 이름을 쓴다.
	 * 이름이 같은 tool이 둘이면 어느 scope를 써야 할지 모르므로 표를 만들지 않는다.
	 */
	public static ToolScopeRegistry of(Class<?>... toolClasses) {
		Map<String, String> scopes = new LinkedHashMap<>();
		for (Class<?> type : toolClasses) {
			for (Method method : ReflectionUtils.getUniqueDeclaredMethods(type, ReflectionUtils.USER_DECLARED_METHODS)) {
				McpTool tool = method.getAnnotation(McpTool.class);
				if (tool == null) {
					continue;
				}
				String name = StringUtils.hasText(tool.name()) ? tool.name() : method.getName();
				RequiredScope required = method.getAnnotation(RequiredScope.class);
				if (scopes.putIfAbsent(name, (required != null) ? required.value() : BASE_SCOPE) != null) {
					throw new IllegalStateException("이름이 같은 tool이 둘 있다: " + name);
				}
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
