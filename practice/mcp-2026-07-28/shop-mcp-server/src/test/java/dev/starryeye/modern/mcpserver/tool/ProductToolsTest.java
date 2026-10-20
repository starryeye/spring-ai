package dev.starryeye.modern.mcpserver.tool;

import dev.starryeye.modern.mcpserver.repository.ProductRepository;
import dev.starryeye.modern.mcpserver.security.McpCaller;

import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조회·재고 tool은 결과를 글 한 덩어리로 돌려준다.
 * 찾지 못한 경우도 tool 오류가 아니라 글로 알린다.
 */
class ProductToolsTest {

    private static final McpCaller 점원 = new McpCaller("user", "test-client");

    private final ProductTools tools = new ProductTools(new ProductRepository());

    static String 글(CallToolResult result) {
        assertThat(result.isError()).isFalse();
        return ((TextContent) result.content().get(0)).text();
    }

    @Test
    void 키워드로_상품을_검색한다() {
        String text = 글(tools.searchProducts(점원, "노트북"));

        assertThat(text)
                .contains("게이밍 노트북 15인치")
                .contains("사무용 노트북 14인치")
                .doesNotContain("웹캠");
    }

    @Test
    void 키워드를_생략하면_전체를_반환한다() {
        String text = 글(tools.searchProducts(점원, null));

        assertThat(text)
                .contains("게이밍 노트북 15인치")
                .contains("웹캠 1080p");
    }

    @Test
    void 검색_결과가_없으면_없다고_말한다() {
        assertThat(글(tools.searchProducts(점원, "없는상품"))).isEqualTo("'없는상품'에 해당하는 상품이 없습니다.");
    }

    @Test
    void 재고_수량을_문장으로_반환한다() {
        assertThat(글(tools.getStock(점원, "p1"))).isEqualTo("상품 p1(게이밍 노트북 15인치)의 현재 재고는 7개입니다.");
    }

    @Test
    void 재고가_0이면_품절이라고_말한다() {
        assertThat(글(tools.getStock(점원, "p3"))).isEqualTo("상품 p3(무선 기계식 키보드)의 현재 재고는 0개로, 품절입니다.");
    }

    @Test
    void 없는_상품이면_예외_대신_문장을_반환한다() {
        assertThat(글(tools.getStock(점원, "없는ID"))).isEqualTo("상품 ID 없는ID에 해당하는 상품이 없습니다.");
    }

    @Test
    void 재고를_바꾸고_바뀐_값을_문장으로_반환한다() {
        assertThat(글(tools.updateStock(점원, "p1", 10))).isEqualTo("상품 p1(게이밍 노트북 15인치)의 재고를 10개로 바꿨습니다.");
        assertThat(글(tools.getStock(점원, "p1"))).isEqualTo("상품 p1(게이밍 노트북 15인치)의 현재 재고는 10개입니다.");
    }

    @Test
    void 음수_재고는_바꾸지_않는다() {
        assertThat(글(tools.updateStock(점원, "p1", -1))).isEqualTo("재고 수량은 0 이상이어야 합니다. (받은 값: -1)");
        assertThat(글(tools.getStock(점원, "p1"))).isEqualTo("상품 p1(게이밍 노트북 15인치)의 현재 재고는 7개입니다.");
    }

    @Test
    void 없는_상품의_재고는_바꾸지_않는다() {
        assertThat(글(tools.updateStock(점원, "p99", 3))).isEqualTo("상품 ID p99에 해당하는 상품이 없습니다.");
    }
}
