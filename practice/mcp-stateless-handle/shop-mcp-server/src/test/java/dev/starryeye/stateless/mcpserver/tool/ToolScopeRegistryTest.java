package dev.starryeye.stateless.mcpserver.tool;

import dev.starryeye.stateless.mcpserver.repository.ProductRepository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolScopeRegistryTest {

	ToolScopeRegistry registry = ToolScopeRegistry.scan(new ProductTools(new ProductRepository()));

	@Test
	void 조회_tool은_products_read다() {
		assertThat(this.registry.scopeFor("getStock")).isEqualTo("products:read");
		assertThat(this.registry.scopeFor("searchProducts")).isEqualTo("products:read");
	}

	@Test
	void 재고_변경_tool은_products_write다() {
		assertThat(this.registry.scopeFor("updateStock")).isEqualTo("products:write");
	}

	@Test
	void 모르는_tool은_기본_scope만_요구한다() {
		assertThat(this.registry.scopeFor("nope")).isEqualTo(ToolScopeRegistry.BASE_SCOPE);
	}

	@Test
	void McpTool이_붙은_메서드를_모두_모은다() {
		assertThat(this.registry.all()).containsOnlyKeys("searchProducts", "getStock", "updateStock");
	}
}
