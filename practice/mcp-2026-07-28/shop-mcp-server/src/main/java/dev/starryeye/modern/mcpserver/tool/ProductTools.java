package dev.starryeye.modern.mcpserver.tool;

import dev.starryeye.modern.mcpserver.repository.ProductRepository;
import dev.starryeye.modern.mcpserver.security.McpCaller;

import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * 상품 검색과 재고 조회·변경 tool의 본문이다.
 *
 * <p>tool의 이름·설명·입력 schema·scope는 {@link ShopTools}가 정하고, 이 메서드들은 거기서 불린다.
 * 결과는 글 한 덩어리다.
 * 찾지 못했거나 값이 틀린 경우도 tool 오류가 아니라 글로 알린다.
 *
 * <p>log의 {@code 사용자}는 token의 {@code sub}다.
 * 그래서 agent가 아니라 <em>사용자</em>가 찍히는 것을 이 practice에서 확인할 수 있다(안내서 6장).
 */
@Component
public class ProductTools {

    private static final Logger log = LoggerFactory.getLogger(ProductTools.class);

    private final ProductRepository productRepository;

    public ProductTools(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public CallToolResult searchProducts(McpCaller caller, String keyword) {
        log.info("searchProducts 호출 (keyword={}, 사용자={})", keyword, caller.subject());

        String joined = productRepository.findByKeyword(keyword).stream()
                .map(product -> "- [%s] %s (%s) / %,d원 / 재고 %d개"
                        .formatted(product.id(), product.name(), product.category(),
                                product.price(), product.stock()))
                .collect(Collectors.joining("\n"));

        return text(joined.isBlank()
                ? "'%s'에 해당하는 상품이 없습니다.".formatted(keyword)
                : joined);
    }

    public CallToolResult getStock(McpCaller caller, String productId) {
        log.info("getStock 호출 (productId={}, 사용자={})", productId, caller.subject());

        return text(productRepository.findById(productId)
                .map(product -> product.stock() == 0
                        ? "상품 %s(%s)의 현재 재고는 0개로, 품절입니다."
                                .formatted(productId, product.name())
                        : "상품 %s(%s)의 현재 재고는 %d개입니다."
                                .formatted(productId, product.name(), product.stock()))
                .orElse("상품 ID %s에 해당하는 상품이 없습니다.".formatted(productId)));
    }

    public CallToolResult updateStock(McpCaller caller, String productId, int quantity) {
        log.info("updateStock 호출 (productId={}, quantity={}, 사용자={})", productId, quantity, caller.subject());

        if (quantity < 0) {
            return text("재고 수량은 0 이상이어야 합니다. (받은 값: %d)".formatted(quantity));
        }
        return text(productRepository.updateStock(productId, quantity)
                .map(product -> "상품 %s(%s)의 재고를 %d개로 바꿨습니다."
                        .formatted(productId, product.name(), product.stock()))
                .orElse("상품 ID %s에 해당하는 상품이 없습니다.".formatted(productId)));
    }

    /**
     * 글 하나를 담은 성공 결과다.
     * {@code isError}를 비워 두면 응답에서 빠지므로, 성공이라는 것을 {@code false}로 적는다.
     */
    private static CallToolResult text(String text) {
        return CallToolResult.builder().addContent(TextContent.builder(text).build()).isError(false).build();
    }
}
