package dev.starryeye.modern.mcpserver.tool;

import dev.starryeye.modern.mcpserver.repository.ProductRepository;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.context.support.GenericApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolScopeRegistryTest {

	ToolScopeRegistry registry = ToolScopeRegistry.of(ProductTools.class);

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

	@Test
	void 결제만_orders_write이고_나머지_장바구니_tool은_기본_scope다() {
		ToolScopeRegistry registry = ToolScopeRegistry.of(BasketTools.class);

		assertThat(registry.scopeFor("checkout")).isEqualTo("orders:write");
		assertThat(registry.scopeFor("createBasket")).isEqualTo("products:read");
		assertThat(registry.scopeFor("addItem")).isEqualTo("products:read");
		assertThat(registry.scopeFor("getBasket")).isEqualTo("products:read");
	}

	/**
	 * 표에 넣을 bean을 손으로 고르지 않는다. 새 tool bean을 만들고 표에 넣는 것을 잊어도,
	 * Spring AI가 그 tool을 등록하는 것과 같이 표에도 들어간다.
	 */
	@Test
	void context의_모든_bean에서_McpTool_메서드를_찾는다() {
		try (GenericApplicationContext context = new GenericApplicationContext()) {
			context.registerBean(ProductRepository.class);
			context.registerBean(ProductTools.class);
			context.registerBean(OrderTools.class);
			context.refresh();

			ToolScopeRegistry scanned = ToolScopeRegistry.scan(context);

			assertThat(scanned.all()).containsOnlyKeys("searchProducts", "getStock", "updateStock", "cancelOrder",
					"orderStatus");
			assertThat(scanned.scopeFor("cancelOrder")).isEqualTo("orders:write");
		}
	}

	@Test
	void McpTool에_이름이_없으면_Spring_AI처럼_메서드_이름을_쓴다() {
		assertThat(ToolScopeRegistry.of(OrderTools.class).all()).containsEntry("orderStatus", "products:read");
	}

	@Test
	void 이름이_같은_tool이_둘이면_표를_만들지_않는다() {
		assertThatThrownBy(() -> ToolScopeRegistry.of(ProductTools.class, DuplicateTools.class))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("updateStock");
	}

	static class OrderTools {

		@McpTool(name = "cancelOrder", description = "주문을 취소한다")
		@RequiredScope("orders:write")
		public String cancelOrder(String orderId) {
			return orderId;
		}

		@McpTool(description = "주문 상태를 본다")
		public String orderStatus(String orderId) {
			return orderId;
		}
	}

	static class DuplicateTools {

		@McpTool(name = "updateStock", description = "이름이 겹치는 tool")
		public String updateStock(String productId) {
			return productId;
		}
	}
}
