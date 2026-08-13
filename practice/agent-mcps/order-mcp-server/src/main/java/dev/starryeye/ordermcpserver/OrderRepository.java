package dev.starryeye.ordermcpserver;

import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Repository
public class OrderRepository {

    public static final String STATUS_CANCELLED = "취소됨";

    private final Map<String, Order> store = new LinkedHashMap<>();

    public OrderRepository() {
        List.of(
                new Order("o1", "홍길동", "p1", "게이밍 노트북 15인치", 1, "결제완료"),
                new Order("o2", "홍길동", "p5", "27인치 4K 모니터", 2, "배송중"),
                new Order("o3", "김영희", "p3", "무선 기계식 키보드", 1, "결제완료")
        ).forEach(order -> store.put(order.id(), order));
    }

    public List<Order> findByCustomer(String customer) {
        if (customer == null || customer.isBlank()) {
            return List.copyOf(store.values());
        }
        String normalized = customer.toLowerCase(Locale.ROOT);
        return store.values().stream()
                .filter(order -> order.customer().toLowerCase(Locale.ROOT).contains(normalized))
                .toList();
    }

    public Optional<Order> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    /**
     * 상태를 '취소됨' 으로 바꾸고 바뀐 주문을 반환한다.
     * 주문이 없으면 빈 Optional, 이미 취소된 주문이면 상태가 그대로인 주문을 반환한다.
     * 호출측이 이미 취소된 경우를 구분할 수 있도록 상태를 그대로 돌려준다.
     */
    public Optional<Order> cancel(String id) {
        Order order = store.get(id);
        if (order == null) {
            return Optional.empty();
        }
        if (STATUS_CANCELLED.equals(order.status())) {
            return Optional.of(order);
        }
        Order cancelled = order.withStatus(STATUS_CANCELLED);
        store.put(id, cancelled);
        return Optional.of(cancelled);
    }
}
