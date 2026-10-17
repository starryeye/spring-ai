package dev.starryeye.modern.mcpserver.tool;

import dev.starryeye.modern.mcpserver.basket.BasketStore;
import dev.starryeye.modern.mcpserver.repository.ProductRepository;
import dev.starryeye.modern.mcpserver.security.McpCaller;

import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ShopToolsTest {

	ProductRepository products = new ProductRepository();

	List<ShopTool> tools = ShopTools.all(new ProductTools(this.products),
			new BasketTools(new BasketStore(Clock.systemUTC()), this.products));

	CallToolResult 부른다(String name, Map<String, Object> arguments) {
		ShopTool tool = this.tools.stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElseThrow();
		return tool.handler().apply(new McpCaller("user", "test-client"), arguments);
	}

	static String 글(CallToolResult result) {
		return ((TextContent) result.content().get(0)).text();
	}

	/**
	 * JSON Schema의 integer는 int 범위 밖의 값도 받는다.
	 * 2^32 + 5를 int로 자르면 5가 되므로, 그대로 실행하면 사용자가 보내지 않은 수량으로 재고가 바뀐다.
	 */
	@Test
	void int로_바꿀_수_없는_정수_인자는_실행하지_않고_tool_오류다() {
		CallToolResult result = 부른다("updateStock", Map.of("productId", "p1", "quantity", 4_294_967_301L));

		assertThat(result.isError()).isTrue();
		assertThat(글(result)).contains("quantity").contains("4294967301");
		assertThat(this.products.findById("p1").orElseThrow().stock()).isEqualTo(7);
	}

	@Test
	void 소수점_표기라도_값이_정수면_그_수로_실행한다() {
		CallToolResult result = 부른다("updateStock", Map.of("productId", "p1", "quantity", 3.0));

		assertThat(result.isError()).isFalse();
		assertThat(this.products.findById("p1").orElseThrow().stock()).isEqualTo(3);
	}
}
