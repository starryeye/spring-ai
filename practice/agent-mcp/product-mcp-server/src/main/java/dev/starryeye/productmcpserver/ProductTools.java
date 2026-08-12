package dev.starryeye.productmcpserver;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.stream.Collectors;

@Component
public class ProductTools {

    private final ProductClient productClient;

    public ProductTools(ProductClient productClient) {
        this.productClient = productClient;
    }

    @McpTool(
            name = "searchProducts",
            description = "상품명이나 카테고리 키워드로 판매 중인 상품을 검색한다. "
                    + "사용자가 특정 상품이 있는지 묻거나 상품 목록을 요청할 때 사용한다. "
                    + "각 상품의 ID, 이름, 카테고리, 가격, 재고 수량을 함께 반환한다."
    )
    public Mono<String> searchProducts(
            @McpToolParam(description = "검색 키워드. 상품명 일부 또는 카테고리명", required = true)
            String keyword) {

        return productClient.search(keyword)
                .map(product -> "- [%s] %s (%s) / %,d원 / 재고 %d개"
                        .formatted(product.id(), product.name(), product.category(),
                                product.price(), product.stock()))
                .collect(Collectors.joining("\n"))
                .map(joined -> joined.isBlank()
                        ? "'%s' 에 해당하는 상품이 없습니다.".formatted(keyword)
                        : joined)
                .onErrorReturn("상품 정보를 조회할 수 없습니다. 상품 서비스에 연결하지 못했습니다.");
    }

    @McpTool(
            name = "getStock",
            description = "상품 ID로 현재 재고 수량을 조회한다. "
                    + "사용자가 특정 상품의 재고나 구매 가능 여부를 물을 때 사용한다. "
                    + "상품 ID를 모르면 먼저 searchProducts 로 상품을 찾아야 한다."
    )
    public Mono<String> getStock(
            @McpToolParam(description = "상품 ID. 예: p1", required = true)
            String productId) {

        return productClient.stockOf(productId)
                .map(stock -> stock == 0
                        ? "상품 %s 는 현재 품절입니다. (재고 0개)".formatted(productId)
                        : "상품 %s 의 현재 재고는 %d개입니다.".formatted(productId, stock))
                .defaultIfEmpty("상품 %s 를 찾을 수 없습니다.".formatted(productId))
                .onErrorReturn("재고 정보를 조회할 수 없습니다. 상품 서비스에 연결하지 못했습니다.");
    }
}
