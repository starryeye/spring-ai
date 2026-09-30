package dev.starryeye.stateless.mcpserver.basket;

import java.time.Instant;
import java.util.Map;

/** tool에 돌려주는 장바구니의 한 시점 모습이다. {@code items}는 상품 ID → 수량이고 담은 순서를 지킨다. */
public record BasketView(String handle, Instant expiresAt, Map<String, Integer> items) {
}
