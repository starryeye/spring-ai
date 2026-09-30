package dev.starryeye.stateless.mcpserver.repository;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductRepositoryTest {

	ProductRepository products = new ProductRepository();

	int 재고(String productId) {
		return this.products.findById(productId).orElseThrow().stock();
	}

	@Test
	void 주문_수량만큼_재고를_줄인다() {
		this.products.reserve(Map.of("p1", 2, "p4", 1));

		assertThat(재고("p1")).isEqualTo(5);
		assertThat(재고("p4")).isEqualTo(144);
	}

	@Test
	void 수량이_0이거나_음수면_재고를_건드리지_않고_거절한다() {
		// 음수 수량을 빼면 재고가 오히려 는다.
		for (int quantity : new int[] { 0, -2, Integer.MIN_VALUE }) {
			assertThatThrownBy(() -> this.products.reserve(Map.of("p1", quantity, "p4", 1)))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("p1");
		}

		assertThat(재고("p1")).isEqualTo(7);
		assertThat(재고("p4")).isEqualTo(145);
	}
}
