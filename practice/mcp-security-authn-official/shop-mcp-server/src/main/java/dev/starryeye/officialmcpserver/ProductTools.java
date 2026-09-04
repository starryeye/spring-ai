package dev.starryeye.officialmcpserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * SYNC 서버이므로 반환 타입은 평문 {@code String} 이다.
 * {@code Mono<String>} 으로 바꾸면 오류 없이 툴 등록에서 빠진다 —
 * {@code ShopMcpServerApplicationTests} 의 등록 테스트가 그 사고를 잡는다.
 */
@Component
public class ProductTools {

    private static final Logger log = LoggerFactory.getLogger(ProductTools.class);

    private final ProductRepository productRepository;

    public ProductTools(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @McpTool(
            name = "searchProducts",
            description = "판매 중인 상품을 검색한다. "
                    + "각 상품의 ID, 이름, 카테고리, 가격, 재고 수량을 함께 반환한다. "
                    + "키워드는 상품명과 카테고리명에만 부분일치로 적용된다. "
                    + "따라서 가격·재고처럼 이름이 아닌 조건으로 거르려면 "
                    + "(예: '10만원 넘는 상품', '품절 아닌 것') 키워드 없이 호출해 전체 목록을 받은 뒤 "
                    + "그 결과를 직접 판단해야 한다. 조건을 키워드로 넘기면 아무것도 찾지 못한다."
    )
    public String searchProducts(
            @McpToolParam(description = "상품명 또는 카테고리명의 일부. "
                    + "가격·재고 같은 조건이나 문장을 넣으면 안 된다. "
                    + "특정 상품을 지목하지 않는 질문이면 생략한다.", required = false)
            String keyword) {
        log.info("searchProducts 호출 (keyword={}, 사용자={})", keyword, currentUser());

        String joined = productRepository.findByKeyword(keyword).stream()
                .map(product -> "- [%s] %s (%s) / %,d원 / 재고 %d개"
                        .formatted(product.id(), product.name(), product.category(),
                                product.price(), product.stock()))
                .collect(Collectors.joining("\n"));

        return joined.isBlank()
                ? "'%s' 에 해당하는 상품이 없습니다.".formatted(keyword)
                : joined;
    }

    @McpTool(
            name = "getStock",
            description = "상품 ID로 현재 재고 수량을 조회한다. "
                    + "사용자가 특정 상품의 재고나 구매 가능 여부를 물을 때 사용한다. "
                    + "상품 ID를 모르면 먼저 searchProducts 로 상품을 찾아야 한다."
    )
    public String getStock(
            @McpToolParam(description = "상품 ID. 예: p1", required = true)
            String productId) {
        log.info("getStock 호출 (productId={}, 사용자={})", productId, currentUser());

        return productRepository.findById(productId)
                .map(product -> product.stock() == 0
                        ? "상품 %s (%s) 는 현재 품절입니다. (재고 0개)"
                                .formatted(productId, product.name())
                        : "상품 %s (%s) 의 현재 재고는 %d개입니다."
                                .formatted(productId, product.name(), product.stock()))
                .orElse("상품 %s 를 찾을 수 없습니다.".formatted(productId));
    }

    /**
     * 누가 이 툴을 호출했는지 로그에 남긴다. 검증 시나리오 4번이 이 값을 본다.
     * 토큰의 {@code sub} 가 그대로 principal 이름이 되므로, 에이전트가 아니라
     * <em>사용자</em> 가 찍히는 것이 이 practice 의 관찰 포인트다.
     */
    private String currentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "(인증정보 없음)" : authentication.getName();
    }
}
