package dev.starryeye.stateless.mcpserver.tool;

import dev.starryeye.stateless.mcpserver.basket.BasketStore;
import dev.starryeye.stateless.mcpserver.repository.ProductRepository;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BasketToolsTest {

	ProductRepository products = new ProductRepository();

	BasketTools tools = new BasketTools(new BasketStore(Clock.systemUTC()), this.products);

	static McpTransportContext 사용자(String subject) {
		return McpTransportContext.create(Map.of("sub", subject, "client_id", "test-client"));
	}

	static String 글(CallToolResult result) {
		return ((TextContent) result.content().get(0)).text();
	}

	@SuppressWarnings("unchecked")
	String 새_장바구니(String subject) {
		CallToolResult created = this.tools.createBasket(사용자(subject));
		return (String) ((Map<String, Object>) created.structuredContent()).get("basketId");
	}

	int 재고(String productId) {
		return this.products.findById(productId).orElseThrow().stock();
	}

	@Test
	void createBasket은_handle을_글과_structuredContent로_돌려준다() {
		CallToolResult created = this.tools.createBasket(사용자("user"));

		assertThat(created.isError()).isFalse();
		@SuppressWarnings("unchecked")
		Map<String, Object> structured = (Map<String, Object>) created.structuredContent();
		String handle = (String) structured.get("basketId");
		assertThat(handle).startsWith("bsk_");
		// handle의 끝 글자에 따라 조사가 달라지지 않도록, handle 바로 뒤에는 "이고"를 둔다.
		assertThat(글(created)).isEqualTo("장바구니를 만들었습니다. ID는 %s이고, %s에 만료됩니다."
				.formatted(handle, structured.get("expiresAt")));
	}

	@Test
	void 담고_보면_상품과_합계가_보인다() {
		String handle = 새_장바구니("user");
		this.tools.addItem(사용자("user"), handle, "p4", 2);

		CallToolResult basket = this.tools.getBasket(사용자("user"), handle);

		assertThat(basket.isError()).isFalse();
		assertThat(글(basket)).contains("p4").contains("인체공학 마우스").contains("118,000원");
	}

	@Test
	void 모르는_상품과_1보다_작은_수량은_tool_오류다() {
		String handle = 새_장바구니("user");

		assertThat(this.tools.addItem(사용자("user"), handle, "p999", 1).isError()).isTrue();
		assertThat(this.tools.addItem(사용자("user"), handle, "p4", 0).isError()).isTrue();
	}

	@Test
	void 한_상품을_99개_넘게_담으면_tool_오류이고_장바구니는_그대로다() {
		String handle = 새_장바구니("user");
		this.tools.addItem(사용자("user"), handle, "p4", 98);

		CallToolResult over = this.tools.addItem(사용자("user"), handle, "p4", 2);

		assertThat(over.isError()).isTrue();
		assertThat(글(over)).contains("99개까지");
		assertThat(this.tools.addItem(사용자("user"), handle, "p4", 100).isError()).isTrue();
		assertThat(this.tools.addItem(사용자("user"), handle, "p4", Integer.MAX_VALUE).isError()).isTrue();
		assertThat(글(this.tools.getBasket(사용자("user"), handle))).contains("× 98 =");
	}

	@Test
	void 재고_확보가_잘못된_수량을_거절해도_tool_오류다() {
		// 장바구니는 수량을 1 이상으로 지키므로, 0을 넘기는 저장소로 이 경로를 만든다.
		ProductRepository zero = new ProductRepository() {
			@Override
			public synchronized void reserve(Map<String, Integer> quantities) {
				super.reserve(Map.of("p4", 0));
			}
		};
		BasketTools tools = new BasketTools(new BasketStore(Clock.systemUTC()), zero);
		CallToolResult created = tools.createBasket(사용자("user"));
		@SuppressWarnings("unchecked")
		String handle = (String) ((Map<String, Object>) created.structuredContent()).get("basketId");
		tools.addItem(사용자("user"), handle, "p4", 1);

		CallToolResult result = tools.checkout(사용자("user"), handle);

		assertThat(result.isError()).isTrue();
		assertThat(글(result)).contains("p4");
		assertThat(zero.findById("p4").orElseThrow().stock()).isEqualTo(145);
	}

	@Test
	void 다른_사용자의_handle은_찾을_수_없다는_tool_오류다() {
		String handle = 새_장바구니("user");

		CallToolResult result = this.tools.getBasket(사용자("user2"), handle);

		assertThat(result.isError()).isTrue();
		assertThat(글(result)).contains("찾을 수 없는 장바구니");
	}

	@Test
	void 결제하면_재고가_줄고_주문_번호가_온다() {
		String handle = 새_장바구니("user");
		this.tools.addItem(사용자("user"), handle, "p9", 2);
		int before = 재고("p9");

		CallToolResult ordered = this.tools.checkout(사용자("user"), handle);

		assertThat(ordered.isError()).isFalse();
		assertThat(글(ordered)).matches("주문을 접수했습니다\\. 주문 번호는 ord-\\d+입니다\\.");
		assertThat(재고("p9")).isEqualTo(before - 2);
	}

	@Test
	void 같은_handle로_두_번_결제하면_두_번째는_이미_주문이다() {
		String handle = 새_장바구니("user");
		this.tools.addItem(사용자("user"), handle, "p9", 1);
		this.tools.checkout(사용자("user"), handle);
		int after = 재고("p9");

		CallToolResult again = this.tools.checkout(사용자("user"), handle);

		assertThat(again.isError()).isTrue();
		assertThat(글(again)).contains("이미 주문");
		assertThat(재고("p9")).isEqualTo(after);
	}

	@Test
	void 재고가_모자라면_아무것도_줄지_않고_장바구니가_열려_있다() {
		String handle = 새_장바구니("user");
		this.tools.addItem(사용자("user"), handle, "p4", 1);
		this.tools.addItem(사용자("user"), handle, "p6", 5);
		int p4 = 재고("p4");

		CallToolResult result = this.tools.checkout(사용자("user"), handle);

		assertThat(result.isError()).isTrue();
		assertThat(글(result)).contains("p6");
		assertThat(재고("p4")).isEqualTo(p4);
		assertThat(this.tools.getBasket(사용자("user"), handle).isError()).isFalse();
	}
}
