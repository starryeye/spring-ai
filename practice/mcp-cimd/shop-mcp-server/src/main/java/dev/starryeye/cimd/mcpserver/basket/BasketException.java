package dev.starryeye.cimd.mcpserver.basket;

import java.util.regex.Pattern;

/**
 * 장바구니를 쓸 수 없는 이유다. 메시지는 모델이 읽고 다음 행동을 정할 수 있는 문장이다.
 *
 * <p>다른 사용자의 handle은 그 사용자의 key로 찾으므로 "모르는 handle"과 같은 {@link Reason#NOT_FOUND}가 된다.
 * 그래서 만료·결제 완료를 따로 알려도 다른 사용자는 handle이 있는지 알 수 없다.
 */
public class BasketException extends RuntimeException {

	public enum Reason { NOT_FOUND, EXPIRED, ORDERED, EMPTY, LIMIT, QUANTITY }

	static final Pattern HANDLE = Pattern.compile("bsk_[A-Za-z0-9_-]{22}");

	private final Reason reason;

	private BasketException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public Reason reason() {
		return this.reason;
	}

	static BasketException notFound(String handle) {
		return new BasketException(Reason.NOT_FOUND,
				"찾을 수 없는 장바구니입니다(%s). createBasket으로 새 장바구니를 만드세요.".formatted(shown(handle)));
	}

	static BasketException expired(String handle) {
		return new BasketException(Reason.EXPIRED,
				"만료된 장바구니입니다(%s, 만든 뒤 %d분). createBasket으로 새 장바구니를 만드세요."
						.formatted(handle, BasketStore.TTL.toMinutes()));
	}

	static BasketException ordered(String handle, String orderId) {
		return new BasketException(Reason.ORDERED,
				"이미 주문한 장바구니입니다(%s, 주문 번호 %s).".formatted(handle, orderId));
	}

	static BasketException empty(String handle) {
		return new BasketException(Reason.EMPTY,
				"비어 있는 장바구니입니다(%s). addItem으로 상품을 담으세요.".formatted(handle));
	}

	static BasketException limit() {
		return new BasketException(Reason.LIMIT,
				"열린 장바구니는 %d개까지 만들 수 있습니다. 쓰던 장바구니를 이어 쓰세요.".formatted(BasketStore.MAX_OPEN_PER_USER));
	}

	static BasketException quantity(int current, int adding) {
		return new BasketException(Reason.QUANTITY,
				"한 장바구니에는 같은 상품을 %d개까지 담을 수 있습니다(지금 %d개, 더하려는 수량 %d개)."
						.formatted(BasketStore.MAX_QUANTITY_PER_PRODUCT, current, adding));
	}

	/** 모델이 보낸 값이 handle 형식이 아니면 그대로 되풀이하지 않는다. */
	private static String shown(String handle) {
		return (handle != null && HANDLE.matcher(handle).matches()) ? handle : "올바르지 않은 ID";
	}
}
