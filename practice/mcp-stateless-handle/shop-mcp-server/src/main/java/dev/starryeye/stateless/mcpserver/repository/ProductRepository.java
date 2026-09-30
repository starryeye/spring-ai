package dev.starryeye.stateless.mcpserver.repository;

import dev.starryeye.stateless.mcpserver.domain.Product;

import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * seed data는 agent-mcp·agent-mcps practice와 일부러 같게 둔다.
 * 세 practice를 오가며 같은 질문의 답을 비교하기 위해서다.
 *
 * <p>재고를 바꾸는 tool이 생겨 여러 요청이 같은 저장소를 고칠 수 있으므로 메서드를 {@code synchronized}로 둔다.
 */
@Repository
public class ProductRepository {

    private final Map<String, Product> store = new LinkedHashMap<>();

    public ProductRepository() {
        List.of(
                new Product("p1", "게이밍 노트북 15인치", "노트북", 1_890_000, 7),
                new Product("p2", "사무용 노트북 14인치", "노트북", 990_000, 23),
                new Product("p3", "무선 기계식 키보드", "주변기기", 149_000, 0),
                new Product("p4", "인체공학 마우스", "주변기기", 59_000, 145),
                new Product("p5", "27인치 4K 모니터", "모니터", 549_000, 12),
                new Product("p6", "34인치 울트라와이드 모니터", "모니터", 899_000, 3),
                new Product("p7", "노이즈 캔슬링 헤드폰", "음향", 379_000, 41),
                new Product("p8", "USB-C 도킹 스테이션", "주변기기", 219_000, 18),
                new Product("p9", "휴대용 SSD 1TB", "저장장치", 139_000, 62),
                new Product("p10", "웹캠 1080p", "주변기기", 89_000, 0)
        ).forEach(product -> store.put(product.id(), product));
    }

    public synchronized List<Product> findByKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.copyOf(store.values());
        }
        String normalized = keyword.toLowerCase(Locale.ROOT);
        return store.values().stream()
                .filter(product -> product.name().toLowerCase(Locale.ROOT).contains(normalized)
                        || product.category().toLowerCase(Locale.ROOT).contains(normalized))
                .toList();
    }

    public synchronized Optional<Product> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    /** 재고를 바꾼다. 없는 상품이면 빈 값이다. */
    public synchronized Optional<Product> updateStock(String id, int stock) {
        Product updated = store.computeIfPresent(id, (key, product) ->
                new Product(product.id(), product.name(), product.category(), product.price(), stock));
        return Optional.ofNullable(updated);
    }

    /**
     * 주문할 수량만큼 재고를 한꺼번에 줄인다.
     * 하나라도 모자라거나 없는 상품이면 아무것도 줄이지 않고 예외를 던진다(checkout의 all-or-nothing 요구).
     */
    public synchronized void reserve(Map<String, Integer> quantities) {
        List<String> shortages = quantities.entrySet().stream()
                .filter(entry -> {
                    Product product = store.get(entry.getKey());
                    return product == null || product.stock() < entry.getValue();
                })
                .map(Map.Entry::getKey)
                .toList();
        if (!shortages.isEmpty()) {
            throw new IllegalStateException("재고가 모자라 주문할 수 없습니다: " + String.join(", ", shortages));
        }
        quantities.forEach((id, quantity) -> store.computeIfPresent(id, (key, product) ->
                new Product(product.id(), product.name(), product.category(), product.price(),
                        product.stock() - quantity)));
    }
}
