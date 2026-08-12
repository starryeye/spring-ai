package dev.starryeye.productservice;

import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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

    public List<Product> findByKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.copyOf(store.values());
        }
        String normalized = keyword.toLowerCase(Locale.ROOT);
        return store.values().stream()
                .filter(product -> product.name().toLowerCase(Locale.ROOT).contains(normalized)
                        || product.category().toLowerCase(Locale.ROOT).contains(normalized))
                .toList();
    }

    public Optional<Product> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }
}
