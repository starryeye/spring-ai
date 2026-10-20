package dev.starryeye.community.mcpserver.tool;

import dev.starryeye.community.mcpserver.repository.ProductRepository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SYNC 라 반환이 평문 String 이다 — StepVerifier 도, 스텁도 필요 없다.
 */
class ProductToolsTest {

    private final ProductTools tools = new ProductTools(new ProductRepository());

    @Test
    void 키워드로_상품을_검색한다() {
        String text = tools.searchProducts("노트북");

        assertThat(text)
                .contains("게이밍 노트북 15인치")
                .contains("사무용 노트북 14인치")
                .doesNotContain("웹캠");
    }

    @Test
    void 키워드를_생략하면_전체를_반환한다() {
        String text = tools.searchProducts(null);

        assertThat(text)
                .contains("게이밍 노트북 15인치")
                .contains("웹캠 1080p");
    }

    @Test
    void 검색_결과가_없으면_없다고_말한다() {
        assertThat(tools.searchProducts("없는상품")).isEqualTo("'없는상품'에 해당하는 상품이 없습니다.");
    }

    @Test
    void 재고_수량을_문장으로_반환한다() {
        assertThat(tools.getStock("p1")).isEqualTo("상품 p1(게이밍 노트북 15인치)의 현재 재고는 7개입니다.");
    }

    @Test
    void 재고가_0이면_품절이라고_말한다() {
        assertThat(tools.getStock("p3")).isEqualTo("상품 p3(무선 기계식 키보드)의 현재 재고는 0개로, 품절입니다.");
    }

    @Test
    void 없는_상품이면_예외_대신_문장을_반환한다() {
        assertThat(tools.getStock("없는ID")).isEqualTo("상품 ID 없는ID에 해당하는 상품이 없습니다.");
    }
}
