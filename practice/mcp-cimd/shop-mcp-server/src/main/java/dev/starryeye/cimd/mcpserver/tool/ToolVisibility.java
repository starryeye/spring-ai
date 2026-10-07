package dev.starryeye.cimd.mcpserver.tool;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 사용자 권한(역할)으로 보이는 tool을 정한다(안내서 12장).
 *
 * <p>지금 token에 없는 scope의 tool을 숨기면 모델이 그 tool을 몰라 부르지 않고, step-up이 시작될 계기가 사라진다.
 * 그래서 "이 사용자가 언젠가 받을 수 있는 scope인가"로 숨길지를 정한다.
 * 받을 수 없으면 숨기고, 받을 수 있지만 아직 없으면 보여 준 뒤 부를 때 {@code 403}으로 step-up한다.
 *
 * <p>권한은 MCP Server가 자기 표로 판단한다. token은 누구인지({@code sub})만 알려 준다.
 * tool별 scope는 {@link ToolScopeRegistry}의 것을 그대로 쓴다.
 */
public final class ToolVisibility {

	/** 점원은 재고를 바꿀 수 있고, 손님은 바꿀 수 없다. */
	public enum Role {
		STAFF, CUSTOMER
	}

	private static final Map<Role, Set<String>> GRANTABLE = Map.of(
			Role.STAFF, Set.of("products:read", "products:write", "orders:write"),
			Role.CUSTOMER, Set.of("products:read", "orders:write"));

	private final ToolScopeRegistry registry;

	private final Map<String, Role> roleBySubject;

	public ToolVisibility(ToolScopeRegistry registry, Map<String, Role> roleBySubject) {
		this.registry = registry;
		this.roleBySubject = Map.copyOf(roleBySubject);
	}

	/** 이 역할이 받을 수 있는 scope다. */
	public Set<String> grantable(Role role) {
		return GRANTABLE.get(role);
	}

	/** 권한을 모르는 사용자에게 넓은 권한을 주지 않으려고, 표에 없으면 손님으로 본다. */
	public Role roleOf(String subject) {
		return (subject == null) ? Role.CUSTOMER : this.roleBySubject.getOrDefault(subject, Role.CUSTOMER);
	}

	/**
	 * 등록된 tool인데 역할이 그 scope를 받을 수 없으면 숨긴다.
	 * 등록되지 않은 이름은 숨긴 tool이 아니다. SDK가 "모르는 tool"로 답한다.
	 */
	public boolean hidden(String subject, String tool) {
		return this.registry.all().containsKey(tool)
				&& !grantable(roleOf(subject)).contains(this.registry.scopeFor(tool));
	}

	/** client cache와 모델의 prompt cache가 맞도록, 순서는 SDK가 준 순서 그대로 둔다. */
	public List<McpSchema.Tool> visible(String subject, List<McpSchema.Tool> tools) {
		return tools.stream().filter(tool -> !hidden(subject, tool.name())).toList();
	}
}
