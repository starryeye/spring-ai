package dev.starryeye.visibility.mcpserver.tool;

import dev.starryeye.visibility.mcpserver.repository.ProductRepository;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사용자가 원래 받을 수 없는 scope의 tool만 숨기는지 본다.
 * 받을 수는 있지만 아직 없는 scope의 tool은 숨기지 않는다(그 tool은 부를 때 step-up한다).
 */
class ToolVisibilityTest {

	ToolScopeRegistry registry = ToolScopeRegistry.scan(new ProductTools(new ProductRepository()),
			new BasketTools(null, null));

	ToolVisibility visibility = new ToolVisibility(this.registry, Map.of("user", ToolVisibility.Role.STAFF));

	Set<String> 숨긴_tool(String subject) {
		return this.registry.all().keySet().stream()
				.filter(tool -> this.visibility.hidden(subject, tool))
				.collect(Collectors.toSet());
	}

	@Test
	void 점원에게는_어떤_tool도_숨기지_않는다() {
		assertThat(숨긴_tool("user")).isEmpty();
	}

	@Test
	void 손님에게는_재고를_바꾸는_updateStock만_숨긴다() {
		assertThat(숨긴_tool("user2")).containsExactly("updateStock");
	}

	@Test
	void 역할_표에_없는_사용자와_sub가_없는_요청은_손님이다() {
		assertThat(this.visibility.roleOf("stranger")).isEqualTo(ToolVisibility.Role.CUSTOMER);
		assertThat(this.visibility.roleOf(null)).isEqualTo(ToolVisibility.Role.CUSTOMER);
		assertThat(this.visibility.hidden(null, "updateStock")).isTrue();
	}

	@Test
	void 등록되지_않은_이름은_숨긴_tool이_아니다() {
		// 없는 tool은 SDK가 "모르는 tool"로 답한다. 숨긴 tool도 같은 답을 받게 하므로 여기서는 구분만 한다.
		assertThat(this.visibility.hidden("user2", "noSuchTool")).isFalse();
	}

	@Test
	void 손님은_주문_scope는_받을_수_있고_재고_변경_scope는_받을_수_없다() {
		assertThat(this.visibility.grantable(ToolVisibility.Role.CUSTOMER))
				.containsExactlyInAnyOrder("products:read", "orders:write");
		assertThat(this.visibility.grantable(ToolVisibility.Role.STAFF))
				.containsExactlyInAnyOrder("products:read", "products:write", "orders:write");
	}
}
