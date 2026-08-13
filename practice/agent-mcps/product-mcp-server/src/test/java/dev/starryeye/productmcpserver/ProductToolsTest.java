package dev.starryeye.productmcpserver;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class ProductToolsTest {

    private final ProductTools tools = new ProductTools(new ProductRepository());

    @Test
    void 키워드로_상품을_검색한다() {
        StepVerifier.create(tools.searchProducts("노트북"))
                .assertNext(text -> assertThat(text)
                        .contains("게이밍 노트북 15인치")
                        .contains("사무용 노트북 14인치")
                        .doesNotContain("웹캠"))
                .verifyComplete();
    }

    @Test
    void 키워드를_생략하면_전체를_반환한다() {
        StepVerifier.create(tools.searchProducts(null))
                .assertNext(text -> assertThat(text)
                        .contains("게이밍 노트북 15인치")
                        .contains("웹캠 1080p"))
                .verifyComplete();
    }

    @Test
    void 검색_결과가_없으면_없다고_말한다() {
        StepVerifier.create(tools.searchProducts("없는상품"))
                .assertNext(text -> assertThat(text).contains("없습니다"))
                .verifyComplete();
    }

    @Test
    void 재고_수량을_문장으로_반환한다() {
        StepVerifier.create(tools.getStock("p1"))
                .assertNext(text -> assertThat(text).contains("7"))
                .verifyComplete();
    }

    @Test
    void 재고가_0이면_품절이라고_말한다() {
        StepVerifier.create(tools.getStock("p3"))
                .assertNext(text -> assertThat(text).contains("품절"))
                .verifyComplete();
    }

    @Test
    void 없는_상품이면_찾을_수_없다고_말한다() {
        StepVerifier.create(tools.getStock("nope"))
                .assertNext(text -> assertThat(text).contains("찾을 수 없습니다"))
                .verifyComplete();
    }
}
