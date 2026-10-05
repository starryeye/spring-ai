package dev.starryeye.visibility.mcpserver.tool;

import dev.starryeye.visibility.mcpserver.basket.BasketException;
import dev.starryeye.visibility.mcpserver.basket.BasketStore;
import dev.starryeye.visibility.mcpserver.basket.BasketView;
import dev.starryeye.visibility.mcpserver.domain.Product;
import dev.starryeye.visibility.mcpserver.repository.ProductRepository;
import dev.starryeye.visibility.mcpserver.security.McpCaller;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 호출 사이에 남는 상태(장바구니)를 session이 아니라 handle로 다루는 tool이다(SEP-2567의 설계 패턴).
 *
 * <p>{@code createBasket}이 handle을 돌려주고, 모델은 그것을 다음 tool의 인자로 넘긴다.
 * 누가 부르는지는 인자가 아니라 {@link McpTransportContext}의 token 사용자에서 꺼낸다({@link McpCaller}).
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

	@McpTool(name = "createBasket",
			description = "새 장바구니를 만들고 장바구니 ID(basketId)를 돌려준다. "
					+ "상품을 담거나(addItem) 보거나(getBasket) 주문할(checkout) 때 이 ID를 넘긴다. "
					+ "장바구니는 만든 뒤 30분이 지나면 만료되고, 한 사용자는 열린 장바구니를 5개까지 가진다. "
					+ "이미 쓰던 장바구니가 있으면 새로 만들지 말고 그 ID를 이어 쓴다.")
	@RequiredScope("products:read")
	public CallToolResult createBasket(McpTransportContext context) {
		McpCaller caller = McpCaller.from(context);
		log.info("createBasket 호출 (사용자={}, client_id={})", caller.subject(), caller.clientId());
		try {
			BasketView basket = this.baskets.create(caller.subject());
			return CallToolResult.builder()
					.addTextContent("장바구니 %s를 만들었습니다. %s에 만료됩니다.".formatted(basket.handle(), basket.expiresAt()))
					.structuredContent(Map.of("basketId", basket.handle(), "expiresAt", basket.expiresAt().toString()))
					.build();
		}
		catch (BasketException ex) {
			return error(ex.getMessage());
		}
	}

	@McpTool(name = "addItem",
			description = "장바구니에 상품을 담는다. 같은 상품을 다시 담으면 수량이 더해진다. "
					+ "한 장바구니에는 같은 상품을 " + BasketStore.MAX_QUANTITY_PER_PRODUCT + "개까지 담을 수 있다. "
					+ "basketId는 createBasket이 준 값이다.")
	@RequiredScope("products:read")
	public CallToolResult addItem(McpTransportContext context,
			@McpToolParam(description = "장바구니 ID. createBasket이 준 bsk_로 시작하는 값", required = true)
			String basketId,
			@McpToolParam(description = "상품 ID. 예: p1", required = true)
			String productId,
			@McpToolParam(description = "담을 수량. 1 이상 " + BasketStore.MAX_QUANTITY_PER_PRODUCT + " 이하의 정수",
					required = true)
			int quantity) {
		McpCaller caller = McpCaller.from(context);
		log.info("addItem 호출 (사용자={}, productId={}, quantity={})", caller.subject(), productId, quantity);
		if (quantity < 1 || quantity > BasketStore.MAX_QUANTITY_PER_PRODUCT) {
			return error("수량은 1 이상 %d 이하여야 합니다. (받은 값: %d)"
					.formatted(BasketStore.MAX_QUANTITY_PER_PRODUCT, quantity));
		}
		if (this.products.findById(productId).isEmpty()) {
			return error("상품 %s를 찾을 수 없습니다. searchProducts로 상품 ID를 확인하세요.".formatted(productId));
		}
		try {
			return text(describe(this.baskets.addItem(caller.subject(), basketId, productId, quantity)));
		}
		catch (BasketException ex) {
			return error(ex.getMessage());
		}
	}

	@McpTool(name = "getBasket", description = "장바구니에 담긴 상품, 수량, 합계, 만료 시각을 보여 준다.")
	@RequiredScope("products:read")
	public CallToolResult getBasket(McpTransportContext context,
			@McpToolParam(description = "장바구니 ID. createBasket이 준 bsk_로 시작하는 값", required = true)
			String basketId) {
		McpCaller caller = McpCaller.from(context);
		log.info("getBasket 호출 (사용자={})", caller.subject());
		try {
			return text(describe(this.baskets.view(caller.subject(), basketId)));
		}
		catch (BasketException ex) {
			return error(ex.getMessage());
		}
	}

	@McpTool(name = "checkout",
			description = "장바구니를 주문한다. 재고를 줄이고 주문 번호를 돌려주며, 장바구니는 닫힌다. "
					+ "사용자가 주문이나 결제를 분명히 요청할 때만 사용한다."
					+ " 처음 부르면 사용자에게 주문 권한(orders:write)을 묻는다.")
	@RequiredScope("orders:write")
	public CallToolResult checkout(McpTransportContext context,
			@McpToolParam(description = "장바구니 ID. createBasket이 준 bsk_로 시작하는 값", required = true)
			String basketId) {
		McpCaller caller = McpCaller.from(context);
		log.info("checkout 호출 (사용자={}, client_id={})", caller.subject(), caller.clientId());
		try {
			String orderId = this.baskets.checkout(caller.subject(), basketId, this.products::reserve);
			return CallToolResult.builder()
					.addTextContent("주문 %s를 접수했습니다.".formatted(orderId))
					.structuredContent(Map.of("orderId", orderId))
					.build();
		}
		catch (BasketException | IllegalStateException | IllegalArgumentException ex) {
			return error(ex.getMessage());
		}
	}

	private String describe(BasketView basket) {
		if (basket.items().isEmpty()) {
			return "장바구니 %s는 비어 있습니다. %s에 만료됩니다.".formatted(basket.handle(), basket.expiresAt());
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

	private static CallToolResult text(String text) {
		return CallToolResult.builder().addTextContent(text).build();
	}

	private static CallToolResult error(String message) {
		return CallToolResult.builder().addTextContent(message).isError(true).build();
	}
}
