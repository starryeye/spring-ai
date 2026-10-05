package dev.starryeye.visibility.mcpserver.basket;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BasketStoreTest {

	/** 테스트가 시간을 앞으로 돌릴 수 있는 시계다. */
	static final class 시계 extends Clock {

		private Instant now = Instant.parse("2026-10-01T00:00:00Z");

		void 지나감(Duration duration) {
			this.now = this.now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return this.now;
		}
	}

	시계 clock = new 시계();

	BasketStore store = new BasketStore(this.clock);

	List<Map<String, Integer>> reserved = new ArrayList<>();

	static BasketException.Reason 이유(Runnable call) {
		try {
			call.run();
		}
		catch (BasketException ex) {
			return ex.reason();
		}
		throw new AssertionError("BasketException이 나야 한다");
	}

	@Test
	void 새_장바구니의_handle은_bsk_와_22자이고_30분_뒤_만료된다() {
		BasketView basket = this.store.create("user");

		assertThat(basket.handle()).matches("bsk_[A-Za-z0-9_-]{22}");
		assertThat(basket.expiresAt()).isEqualTo(this.clock.instant().plus(Duration.ofMinutes(30)));
		assertThat(basket.items()).isEmpty();
	}

	@Test
	void 만들_때마다_다른_handle이다() {
		assertThat(this.store.create("user").handle()).isNotEqualTo(this.store.create("user").handle());
	}

	@Test
	void 같은_상품을_두_번_담으면_수량이_더해진다() {
		String handle = this.store.create("user").handle();
		this.store.addItem("user", handle, "p4", 1);

		BasketView basket = this.store.addItem("user", handle, "p4", 2);

		assertThat(basket.items()).containsExactly(Map.entry("p4", 3));
	}

	@Test
	void 한_상품은_합쳐서_99개까지_담고_넘으면_장바구니가_그대로다() {
		String handle = this.store.create("user").handle();
		this.store.addItem("user", handle, "p4", 98);

		BasketException over = catchBasket(() -> this.store.addItem("user", handle, "p4", 2));

		assertThat(over.reason()).isEqualTo(BasketException.Reason.QUANTITY);
		assertThat(over.getMessage()).contains("99개까지").contains("지금 98개").contains("더하려는 수량 2개");
		assertThat(this.store.view("user", handle).items()).containsExactly(Map.entry("p4", 98));
		assertThat(this.store.addItem("user", handle, "p4", 1).items()).containsExactly(Map.entry("p4", 99));
	}

	@Test
	void int를_넘는_수량을_더해도_수량이_음수로_바뀌지_않는다() {
		String handle = this.store.create("user").handle();

		// int끼리 더하면 넘쳐서 음수가 된다. 그런 수량으로 결제하면 재고가 오히려 는다.
		assertThat(이유(() -> this.store.addItem("user", handle, "p1", Integer.MAX_VALUE)))
				.isEqualTo(BasketException.Reason.QUANTITY);
		assertThat(이유(() -> this.store.addItem("user", handle, "p1", Integer.MAX_VALUE)))
				.isEqualTo(BasketException.Reason.QUANTITY);
		assertThat(this.store.view("user", handle).items()).isEmpty();

		this.store.addItem("user", handle, "p1", 1);
		assertThat(이유(() -> this.store.addItem("user", handle, "p1", Integer.MAX_VALUE)))
				.isEqualTo(BasketException.Reason.QUANTITY);
		assertThat(this.store.view("user", handle).items()).containsExactly(Map.entry("p1", 1));
	}

	@Test
	void 수량이_1보다_작으면_담지_않는다() {
		String handle = this.store.create("user").handle();
		this.store.addItem("user", handle, "p4", 3);

		for (int quantity : new int[] { 0, -3, Integer.MIN_VALUE }) {
			assertThatThrownBy(() -> this.store.addItem("user", handle, "p4", quantity))
					.isInstanceOf(IllegalArgumentException.class);
		}
		assertThat(this.store.view("user", handle).items()).containsExactly(Map.entry("p4", 3));
	}

	@Test
	void 다른_사용자는_같은_handle을_찾지_못한다() {
		String handle = this.store.create("user").handle();

		assertThat(이유(() -> this.store.view("user2", handle))).isEqualTo(BasketException.Reason.NOT_FOUND);
		assertThat(이유(() -> this.store.addItem("user2", handle, "p4", 1))).isEqualTo(BasketException.Reason.NOT_FOUND);
		assertThat(이유(() -> this.store.checkout("user2", handle, this.reserved::add)))
				.isEqualTo(BasketException.Reason.NOT_FOUND);
		assertThat(this.reserved).isEmpty();
	}

	@Test
	void 다른_사용자의_handle과_모르는_handle은_같은_오류_문장이다() {
		String handle = this.store.create("user").handle();
		String unknown = "bsk_" + "A".repeat(22);

		BasketException other = catchBasket(() -> this.store.view("user2", handle));
		BasketException missing = catchBasket(() -> this.store.view("user2", unknown));

		assertThat(other.getMessage().replace(handle, "<id>")).isEqualTo(missing.getMessage().replace(unknown, "<id>"));
	}

	@Test
	void 만료된_장바구니는_만료_오류다() {
		String handle = this.store.create("user").handle();
		this.clock.지나감(Duration.ofMinutes(30));

		assertThat(이유(() -> this.store.view("user", handle))).isEqualTo(BasketException.Reason.EXPIRED);
	}

	@Test
	void 만료되고_30분이_더_지나면_지워져_찾을_수_없다() {
		String handle = this.store.create("user").handle();
		this.clock.지나감(Duration.ofMinutes(60));

		assertThat(이유(() -> this.store.view("user", handle))).isEqualTo(BasketException.Reason.NOT_FOUND);
	}

	@Test
	void 결제한_장바구니는_30분_뒤_지워져_찾을_수_없다() {
		String handle = this.store.create("user").handle();
		this.store.addItem("user", handle, "p4", 1);
		this.store.checkout("user", handle, this.reserved::add);

		// 결제하고 29분까지는 "이미 주문"을 알려 준다.
		this.clock.지나감(Duration.ofMinutes(29));
		assertThat(이유(() -> this.store.view("user", handle))).isEqualTo(BasketException.Reason.ORDERED);

		// 결제하고 30분이 지나면 지워져 "찾을 수 없음"이 된다.
		this.clock.지나감(Duration.ofMinutes(1));
		assertThat(이유(() -> this.store.view("user", handle))).isEqualTo(BasketException.Reason.NOT_FOUND);
	}

	@Test
	void 결제한_장바구니는_다시_결제할_수_없다() {
		String handle = this.store.create("user").handle();
		this.store.addItem("user", handle, "p4", 1);

		String orderId = this.store.checkout("user", handle, this.reserved::add);
		BasketException again = catchBasket(() -> this.store.checkout("user", handle, this.reserved::add));

		assertThat(orderId).isEqualTo("ord-1001");
		assertThat(again.reason()).isEqualTo(BasketException.Reason.ORDERED);
		assertThat(again.getMessage()).contains("ord-1001");
		assertThat(this.reserved).containsExactly(Map.of("p4", 1));
	}

	@Test
	void 재고_확보가_실패하면_장바구니가_열린_채로_남는다() {
		String handle = this.store.create("user").handle();
		this.store.addItem("user", handle, "p6", 5);

		assertThatThrownBy(() -> this.store.checkout("user", handle, items -> {
			throw new IllegalStateException("재고가 모자라 주문할 수 없습니다: p6");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(this.store.view("user", handle).items()).containsExactly(Map.entry("p6", 5));
	}

	@Test
	void 빈_장바구니는_결제할_수_없다() {
		String handle = this.store.create("user").handle();

		assertThat(이유(() -> this.store.checkout("user", handle, this.reserved::add)))
				.isEqualTo(BasketException.Reason.EMPTY);
	}

	@Test
	void 사용자당_열린_장바구니는_5개까지다() {
		for (int i = 0; i < 5; i++) {
			this.store.create("user");
		}

		assertThat(이유(() -> this.store.create("user"))).isEqualTo(BasketException.Reason.LIMIT);
		assertThat(this.store.create("user2").handle()).startsWith("bsk_");
	}

	@Test
	void 결제했거나_만료된_장바구니는_개수에_세지_않는다() {
		String ordered = this.store.create("user").handle();
		this.store.addItem("user", ordered, "p4", 1);
		this.store.checkout("user", ordered, this.reserved::add);
		for (int i = 0; i < 4; i++) {
			this.store.create("user");
		}
		this.clock.지나감(Duration.ofMinutes(30));

		// 결제됐거나 만료된 장바구니는 개수에 세지 않으므로 5개를 더 만들 수 있다.
		assertThatCode(() -> {
			for (int i = 0; i < 5; i++) {
				this.store.create("user");
			}
		}).doesNotThrowAnyException();
	}

	@Test
	void 형식이_틀린_handle은_찾을_수_없다() {
		String handle = this.store.create("user").handle();

		for (String wrong : new String[] { " " + handle, handle + " ", "basket_1", "", null }) {
			assertThat(이유(() -> this.store.view("user", wrong))).isEqualTo(BasketException.Reason.NOT_FOUND);
		}
	}

	@Test
	void 형식이_틀린_handle의_오류_메시지는_입력을_그대로_보여주지_않는다() {
		// 모델이 보낸 값이 handle 형식이 아니면 오류 문장에 그대로 되풀이하지 않는다.
		BasketException ex = catchBasket(() -> this.store.view("user", "basket_1"));

		assertThat(ex.getMessage()).contains("(올바르지 않은 ID)");
		assertThat(ex.getMessage()).doesNotContain("basket_1");
	}

	static BasketException catchBasket(Runnable call) {
		try {
			call.run();
		}
		catch (BasketException ex) {
			return ex;
		}
		throw new AssertionError("BasketException이 나야 한다");
	}
}
