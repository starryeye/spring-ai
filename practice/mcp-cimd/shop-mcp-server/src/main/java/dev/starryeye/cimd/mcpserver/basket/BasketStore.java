package dev.starryeye.cimd.mcpserver.basket;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 장바구니를 서버 메모리에 둔다. key는 {@code <sub>:<handle>}이다.
 *
 * <p>handle은 client에게 주는 이름일 뿐, 가졌다고 쓸 수 있는 권한이 아니다.
 * {@code sub}는 client가 보낸 값이 아니라 검증한 token에서 온다({@code McpCaller}).
 * 그래서 다른 사용자가 handle을 알아내도 자기 {@code sub}로 찾게 되어 아무것도 얻지 못한다(안내서 11장).
 *
 * <p>메모리 저장소라 서버 한 대에서만 맞다. 여러 대로 늘리면 Redis 같은 공유 저장소로 바꾼다.
 */
public class BasketStore {

	public static final Duration TTL = Duration.ofMinutes(30);

	/** 만료·결제된 장바구니를 이만큼 더 남겨 "만료"·"이미 주문"을 알려 준 뒤 지운다. */
	public static final Duration KEEP_AFTER_CLOSE = Duration.ofMinutes(30);

	public static final int MAX_OPEN_PER_USER = 5;

	/**
	 * 한 장바구니에 같은 상품을 담을 수 있는 수량이다.
	 * 수량을 int로 더하다 넘치면 음수가 되고, 음수 수량으로 주문하면 재고가 오히려 는다.
	 * 그래서 담기 전에 합친 수량을 이 값과 비교한다.
	 */
	public static final int MAX_QUANTITY_PER_PRODUCT = 99;

	private static final class Basket {

		final String handle;

		final String owner;

		final Instant expiresAt;

		final Map<String, Integer> items = new LinkedHashMap<>();

		String orderId;

		Instant closedAt;

		Basket(String handle, String owner, Instant expiresAt) {
			this.handle = handle;
			this.owner = owner;
			this.expiresAt = expiresAt;
		}

		boolean isOpen(Instant now) {
			return this.orderId == null && now.isBefore(this.expiresAt);
		}

		Instant endedAt() {
			return (this.orderId != null) ? this.closedAt : this.expiresAt;
		}

		BasketView view() {
			// 담은 순서를 지키는 읽기 전용 복사본이다(Map.copyOf는 순서를 지키지 않는다).
			return new BasketView(this.handle, this.expiresAt, Collections.unmodifiableMap(new LinkedHashMap<>(this.items)));
		}
	}

	private final Map<String, Basket> baskets = new HashMap<>();

	private final Clock clock;

	private final SecureRandom random;

	private long orderSequence = 1000;

	public BasketStore(Clock clock) {
		this(clock, new SecureRandom());
	}

	BasketStore(Clock clock, SecureRandom random) {
		this.clock = clock;
		this.random = random;
	}

	public synchronized BasketView create(String subject) {
		removeEnded();
		Instant now = this.clock.instant();
		long open = this.baskets.values().stream()
				.filter(basket -> basket.owner.equals(subject) && basket.isOpen(now))
				.count();
		if (open >= MAX_OPEN_PER_USER) {
			throw BasketException.limit();
		}
		Basket basket = new Basket(newHandle(), subject, now.plus(TTL));
		this.baskets.put(key(subject, basket.handle), basket);
		return basket.view();
	}

	/** 합친 수량이 {@link #MAX_QUANTITY_PER_PRODUCT}를 넘으면 장바구니를 바꾸지 않고 예외를 던진다. */
	public synchronized BasketView addItem(String subject, String handle, String productId, int quantity) {
		if (quantity < 1) {
			throw new IllegalArgumentException("수량은 1 이상이어야 한다: " + quantity);
		}
		Basket basket = open(subject, handle);
		int current = basket.items.getOrDefault(productId, 0);
		// long으로 더해야 int 범위를 넘는 합도 상한을 넘은 것으로 본다.
		long total = (long) current + quantity;
		if (total > MAX_QUANTITY_PER_PRODUCT) {
			throw BasketException.quantity(current, quantity);
		}
		basket.items.put(productId, (int) total);
		return basket.view();
	}

	public synchronized BasketView view(String subject, String handle) {
		return open(subject, handle).view();
	}

	/**
	 * 재고를 확보하고 장바구니를 닫는다.
	 * {@code reserveStock}이 예외를 던지면 장바구니는 열린 채로 남는다.
	 * 이 메서드 전체가 한 lock 안이라, 같은 장바구니를 두 번 결제할 수 없다.
	 */
	public synchronized String checkout(String subject, String handle, Consumer<Map<String, Integer>> reserveStock) {
		Basket basket = open(subject, handle);
		if (basket.items.isEmpty()) {
			throw BasketException.empty(handle);
		}
		reserveStock.accept(Map.copyOf(basket.items));
		basket.orderId = "ord-" + (++this.orderSequence);
		basket.closedAt = this.clock.instant();
		return basket.orderId;
	}

	private Basket open(String subject, String handle) {
		removeEnded();
		if (handle == null || !BasketException.HANDLE.matcher(handle).matches()) {
			throw BasketException.notFound(handle);
		}
		Basket basket = this.baskets.get(key(subject, handle));
		if (basket == null) {
			throw BasketException.notFound(handle);
		}
		if (basket.orderId != null) {
			throw BasketException.ordered(handle, basket.orderId);
		}
		if (!this.clock.instant().isBefore(basket.expiresAt)) {
			throw BasketException.expired(handle);
		}
		return basket;
	}

	private void removeEnded() {
		Instant now = this.clock.instant();
		this.baskets.values().removeIf(basket -> !now.isBefore(basket.endedAt().plus(KEEP_AFTER_CLOSE)));
	}

	/** 128bit 무작위 값을 base64url로 적는다(22자). 추측으로 맞힐 수 없게 {@link SecureRandom}을 쓴다. */
	private String newHandle() {
		byte[] bytes = new byte[16];
		this.random.nextBytes(bytes);
		return "bsk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String key(String subject, String handle) {
		return subject + ":" + handle;
	}
}
