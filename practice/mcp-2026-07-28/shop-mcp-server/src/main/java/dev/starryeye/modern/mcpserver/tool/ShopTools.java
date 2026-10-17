package dev.starryeye.modern.mcpserver.tool;

import dev.starryeye.modern.mcpserver.basket.BasketStore;
import dev.starryeye.modern.mcpserver.security.McpCaller;

import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.Tool;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.IntFunction;

/**
 * 이 서버가 내놓는 tool 7개의 정의다.
 *
 * <p>SDK의 2026-07-28 서버는 annotation으로 tool을 찾지 않는다.
 * 그래서 이름·설명·입력 schema·필요한 scope를 여기에 data로 적고, 본문은 {@link ProductTools}·{@link BasketTools}의 메서드를 부른다.
 * 값은 mcp-cimd practice의 tool과 같다. 입력 schema도 그때 Spring AI가 만들던 모양 그대로다.
 *
 * <p>SDK는 {@code tools/call}의 {@code arguments}를 입력 schema로 먼저 검증한다.
 * 그래서 handler에 오는 값은 schema가 정한 type이다.
 */
public final class ShopTools {

	private static final String JSON_SCHEMA_2020_12 = "https://json-schema.org/draft/2020-12/schema";

	private static final String PRODUCT_ID = "상품 ID. 예: p1";

	private static final String BASKET_ID = "장바구니 ID. createBasket이 준 bsk_로 시작하는 값";

	private ShopTools() {
	}

	/** client cache와 모델의 prompt cache가 맞도록, tool 순서는 언제나 같다. */
	public static List<ShopTool> all(ProductTools products, BasketTools baskets) {
		return List.of(
				tool("searchProducts", "products:read",
						"판매 중인 상품을 검색한다. "
								+ "각 상품의 ID, 이름, 카테고리, 가격, 재고 수량을 함께 반환한다. "
								+ "키워드는 상품명과 카테고리명에만 부분일치로 적용된다. "
								+ "따라서 가격·재고처럼 이름이 아닌 조건으로 거르려면 "
								+ "(예: '10만원 넘는 상품', '품절 아닌 것') 키워드 없이 호출해 전체 목록을 받은 뒤 "
								+ "그 결과를 직접 판단해야 한다. 조건을 키워드로 넘기면 아무것도 찾지 못한다.",
						schema(Map.of("keyword", string("상품명 또는 카테고리명의 일부. "
								+ "가격·재고 같은 조건이나 문장을 넣으면 안 된다. "
								+ "특정 상품을 지목하지 않는 질문이면 생략한다.")), List.of()),
						(caller, arguments) -> products.searchProducts(caller, text(arguments, "keyword"))),
				tool("getStock", "products:read",
						"상품 ID로 현재 재고 수량을 조회한다. "
								+ "사용자가 특정 상품의 재고나 구매 가능 여부를 물을 때 사용한다. "
								+ "상품 ID를 모르면 먼저 searchProducts 로 상품을 찾아야 한다.",
						schema(Map.of("productId", string(PRODUCT_ID)), List.of("productId")),
						(caller, arguments) -> products.getStock(caller, text(arguments, "productId"))),
				tool("updateStock", "products:write",
						"상품 ID의 재고 수량을 quantity로 바꾼다. "
								+ "사용자가 재고를 바꿔 달라고 분명히 요청할 때만 사용한다. "
								+ "바뀐 뒤의 재고를 반환한다."
								+ " 처음 부르면 사용자에게 재고 변경 권한(products:write)을 묻는다.",
						schema(ordered("productId", string(PRODUCT_ID),
								"quantity", integer("바꿀 재고 수량. 0 이상의 정수")), List.of("productId", "quantity")),
						(caller, arguments) -> withInteger(arguments, "quantity",
								quantity -> products.updateStock(caller, text(arguments, "productId"), quantity))),
				tool("createBasket", "products:read",
						"새 장바구니를 만들고 장바구니 ID(basketId)를 돌려준다. "
								+ "상품을 담거나(addItem) 보거나(getBasket) 주문할(checkout) 때 이 ID를 넘긴다. "
								+ "장바구니는 만든 뒤 30분이 지나면 만료되고, 한 사용자는 열린 장바구니를 5개까지 가진다. "
								+ "이미 쓰던 장바구니가 있으면 새로 만들지 말고 그 ID를 이어 쓴다.",
						schema(Map.of(), List.of()),
						(caller, arguments) -> baskets.createBasket(caller)),
				tool("addItem", "products:read",
						"장바구니에 상품을 담는다. 같은 상품을 다시 담으면 수량이 더해진다. "
								+ "한 장바구니에는 같은 상품을 " + BasketStore.MAX_QUANTITY_PER_PRODUCT + "개까지 담을 수 있다. "
								+ "basketId는 createBasket이 준 값이다.",
						schema(ordered("basketId", string(BASKET_ID),
								"productId", string(PRODUCT_ID),
								"quantity", integer("담을 수량. 1 이상 " + BasketStore.MAX_QUANTITY_PER_PRODUCT + " 이하의 정수")),
								List.of("basketId", "productId", "quantity")),
						(caller, arguments) -> withInteger(arguments, "quantity", quantity -> baskets.addItem(caller,
								text(arguments, "basketId"), text(arguments, "productId"), quantity))),
				tool("getBasket", "products:read",
						"장바구니에 담긴 상품, 수량, 합계, 만료 시각을 보여 준다.",
						schema(Map.of("basketId", string(BASKET_ID)), List.of("basketId")),
						(caller, arguments) -> baskets.getBasket(caller, text(arguments, "basketId"))),
				tool("checkout", "orders:write",
						"장바구니를 주문한다. 재고를 줄이고 주문 번호를 돌려주며, 장바구니는 닫힌다. "
								+ "사용자가 주문이나 결제를 분명히 요청할 때만 사용한다."
								+ " 처음 부르면 사용자에게 주문 권한(orders:write)을 묻는다.",
						schema(Map.of("basketId", string(BASKET_ID)), List.of("basketId")),
						(caller, arguments) -> baskets.checkout(caller, text(arguments, "basketId"))));
	}

	private static ShopTool tool(String name, String scope, String description, Map<String, Object> inputSchema,
			BiFunction<McpCaller, Map<String, Object>, CallToolResult> handler) {
		return new ShopTool(Tool.builder(name, inputSchema).description(description).build(), scope, handler);
	}

	private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("$schema", JSON_SCHEMA_2020_12);
		schema.put("type", "object");
		schema.put("properties", properties);
		schema.put("required", required);
		return schema;
	}

	/** 사람이 읽는 schema가 parameter 순서를 지키도록, 넣은 순서대로 둔다. */
	private static Map<String, Object> ordered(Object... nameAndSchema) {
		Map<String, Object> properties = new LinkedHashMap<>();
		for (int i = 0; i < nameAndSchema.length; i += 2) {
			properties.put((String) nameAndSchema[i], nameAndSchema[i + 1]);
		}
		return properties;
	}

	private static Map<String, Object> string(String description) {
		return Map.of("type", "string", "description", description);
	}

	private static Map<String, Object> integer(String description) {
		Map<String, Object> property = new LinkedHashMap<>();
		property.put("type", "integer");
		property.put("format", "int32");
		property.put("description", description);
		return property;
	}

	/** 문자열 인자다. 생략한 선택 인자는 {@code null}이다. */
	private static String text(Map<String, Object> arguments, String name) {
		return (arguments.get(name) instanceof String value) ? value : null;
	}

	/**
	 * 정수 인자를 {@code int}로 바꿔 {@code body}에 넘긴다.
	 *
	 * <p>JSON Schema의 {@code integer}는 {@code 1e20}처럼 {@code int} 범위 밖의 값과 {@code 2.0} 같은 표기도 받는다.
	 * {@code Number.intValue()}로 바꾸면 범위 밖의 값이 엉뚱한 수로 잘려, 의도하지 않은 수량으로 실행된다.
	 * 그래서 값을 잃지 않고 {@code int}로 바꿀 수 있을 때만 실행하고, 아니면 tool 오류로 알려 모델이 고쳐 부르게 한다.
	 */
	private static CallToolResult withInteger(Map<String, Object> arguments, String name,
			IntFunction<CallToolResult> body) {
		Object value = arguments.get(name);
		Integer exact = exactInt(value);
		if (exact == null) {
			return CallToolResult.builder()
					.addContent(TextContent.builder("%s는 %d 이상 %d 이하의 정수여야 합니다. (받은 값: %s)"
							.formatted(name, Integer.MIN_VALUE, Integer.MAX_VALUE, value)).build())
					.isError(true)
					.build();
		}
		return body.apply(exact);
	}

	private static Integer exactInt(Object value) {
		if (!(value instanceof Number number)) {
			return null;
		}
		try {
			return new BigDecimal(number.toString()).intValueExact();
		}
		catch (ArithmeticException | NumberFormatException ex) {
			return null;
		}
	}
}
