package dev.starryeye.modern.mcpserver.tool;

import dev.starryeye.modern.mcpserver.basket.BasketStore;
import dev.starryeye.modern.mcpserver.repository.ProductRepository;

import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolScopeRegistryTest {

	List<ShopTool> tools = ShopTools.all(new ProductTools(new ProductRepository()),
			new BasketTools(new BasketStore(Clock.systemUTC()), new ProductRepository()));

	ToolScopeRegistry registry = ToolScopeRegistry.of(this.tools);

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
	void 결제만_orders_write이고_나머지_장바구니_tool은_기본_scope다() {
		assertThat(this.registry.scopeFor("checkout")).isEqualTo("orders:write");
		assertThat(this.registry.scopeFor("createBasket")).isEqualTo("products:read");
		assertThat(this.registry.scopeFor("addItem")).isEqualTo("products:read");
		assertThat(this.registry.scopeFor("getBasket")).isEqualTo("products:read");
	}

	@Test
	void 모르는_tool은_기본_scope만_요구한다() {
		assertThat(this.registry.scopeFor("nope")).isEqualTo(ToolScopeRegistry.BASE_SCOPE);
	}

	/**
	 * 표는 서버가 내놓는 tool 정의 목록 하나에서 만든다.
	 * 그래서 내놓는 tool이 표에서 빠질 수 없다.
	 */
	@Test
	void tool_정의의_scope를_모두_모은다() {
		assertThat(this.registry.all()).containsOnlyKeys("searchProducts", "getStock", "updateStock", "createBasket",
				"addItem", "getBasket", "checkout");
	}

	@Test
	void 이름이_같은_tool이_둘이면_표를_만들지_않는다() {
		List<ShopTool> duplicated = new ArrayList<>(this.tools);
		duplicated.add(new ShopTool(Tool.builder("updateStock", Map.of("type", "object")).build(), "products:read",
				(caller, arguments) -> CallToolResult.builder().build()));

		assertThatThrownBy(() -> ToolScopeRegistry.of(duplicated))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("updateStock");
	}
}
