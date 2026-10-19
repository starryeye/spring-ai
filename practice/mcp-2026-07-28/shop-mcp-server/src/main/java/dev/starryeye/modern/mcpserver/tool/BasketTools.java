package dev.starryeye.modern.mcpserver.tool;

import dev.starryeye.modern.mcpserver.basket.BasketException;
import dev.starryeye.modern.mcpserver.basket.BasketStore;
import dev.starryeye.modern.mcpserver.basket.BasketView;
import dev.starryeye.modern.mcpserver.domain.Product;
import dev.starryeye.modern.mcpserver.repository.ProductRepository;
import dev.starryeye.modern.mcpserver.security.McpCaller;

import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 호출 사이에 남는 상태(장바구니)를 session이 아니라 handle로 다루는 tool이다(SEP-2567의 설계 패턴).
 *
 * <p>{@code createBasket}이 handle을 돌려주고, 모델은 그것을 다음 tool의 인자로 넘긴다.
 * 누가 부르는지는 인자가 아니라 검증한 token의 사용자({@link McpCaller})로 받는다.
 * tool의 이름·설명·입력 schema·scope는 {@link ShopTools}가 정하고, 이 메서드들은 거기서 불린다.
 * 결과는 {@link CallToolResult}로 직접 만든다.
 * 그래야 handle을 {@code structuredContent}로도 주고, 쓸 수 없는 경우를 {@code isError}로 알릴 수 있다.
 */
@Component
public class BasketTools {

	private static final Logger log = LoggerFactory.getLogger(BasketTools.class);

	private final BasketStore baskets;

	private final ProductRepository products;

	public BasketTools(BasketStore baskets, ProductRepository products) {
		this.baskets = baskets;
		this.products = products;
	}

	public CallToolResult createBasket(McpCaller caller) {
		log.info("createBasket 호출 (사용자={}, client_id={})", caller.subject(), caller.clientId());
		try {
			BasketView basket = this.baskets.create(caller.subject());
			return CallToolResult.builder()
					.addContent(TextContent.builder("장바구니를 만들었습니다. ID는 %s이고, %s에 만료됩니다."
							.formatted(basket.handle(), basket.expiresAt())).build())
					.structuredContent(Map.of("basketId", basket.handle(), "expiresAt", basket.expiresAt().toString()))
					.isError(false)
					.build();
		}
		catch (BasketException ex) {
			return error(ex.getMessage());
		}
	}

	public CallToolResult addItem(McpCaller caller, String basketId, String productId, int quantity) {
		log.info("addItem 호출 (사용자={}, productId={}, quantity={})", caller.subject(), productId, quantity);
		if (quantity < 1 || quantity > BasketStore.MAX_QUANTITY_PER_PRODUCT) {
			return error("수량은 1 이상 %d 이하여야 합니다. (받은 값: %d)"
					.formatted(BasketStore.MAX_QUANTITY_PER_PRODUCT, quantity));
		}
		if (this.products.findById(productId).isEmpty()) {
			return error("찾을 수 없는 상품입니다(%s). searchProducts로 상품 ID를 확인하세요.".formatted(productId));
		}
		try {
			return text(describe(this.baskets.addItem(caller.subject(), basketId, productId, quantity)));
		}
		catch (BasketException ex) {
			return error(ex.getMessage());
		}
	}

	public CallToolResult getBasket(McpCaller caller, String basketId) {
		log.info("getBasket 호출 (사용자={})", caller.subject());
		try {
			return text(describe(this.baskets.view(caller.subject(), basketId)));
		}
		catch (BasketException ex) {
			return error(ex.getMessage());
		}
	}

	public CallToolResult checkout(McpCaller caller, String basketId) {
		log.info("checkout 호출 (사용자={}, client_id={})", caller.subject(), caller.clientId());
		try {
			String orderId = this.baskets.checkout(caller.subject(), basketId, this.products::reserve);
			return CallToolResult.builder()
					.addContent(TextContent.builder("주문을 접수했습니다. 주문 번호는 %s입니다.".formatted(orderId)).build())
					.structuredContent(Map.of("orderId", orderId))
					.isError(false)
					.build();
		}
		catch (BasketException | IllegalStateException | IllegalArgumentException ex) {
			return error(ex.getMessage());
		}
	}

	private String describe(BasketView basket) {
		if (basket.items().isEmpty()) {
			return "장바구니 %s에 담긴 상품이 없습니다. %s에 만료됩니다.".formatted(basket.handle(), basket.expiresAt());
		}
		long total = 0;
		StringBuilder lines = new StringBuilder("장바구니 %s:\n".formatted(basket.handle()));
		for (Map.Entry<String, Integer> item : basket.items().entrySet()) {
			Product product = this.products.findById(item.getKey()).orElseThrow();
			long amount = (long) product.price() * item.getValue();
			total += amount;
			lines.append("- [%s] %s × %d = %,d원\n".formatted(product.id(), product.name(), item.getValue(), amount));
		}
		return lines.append("합계 %,d원. %s에 만료됩니다.".formatted(total, basket.expiresAt())).toString();
	}

	/**
	 * 글 하나를 담은 성공 결과다.
	 * {@code isError}를 비워 두면 응답에서 빠지므로, 성공이라는 것을 {@code false}로 적는다.
	 */
	private static CallToolResult text(String text) {
		return CallToolResult.builder().addContent(TextContent.builder(text).build()).isError(false).build();
	}

	private static CallToolResult error(String message) {
		return CallToolResult.builder().addContent(TextContent.builder(message).build()).isError(true).build();
	}
}
