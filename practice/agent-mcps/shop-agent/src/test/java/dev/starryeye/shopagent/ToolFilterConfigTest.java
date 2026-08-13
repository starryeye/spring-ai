package dev.starryeye.shopagent;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.McpConnectionInfo;
import org.springframework.ai.mcp.McpToolFilter;

import static org.assertj.core.api.Assertions.assertThat;

class ToolFilterConfigTest {

    private final ToolFilterConfig config = new ToolFilterConfig();

    /** 자동설정이 넘겨주는 것과 같은 형태로 커넥션 정보를 만든다. */
    private McpConnectionInfo connection(String connectionName) {
        return McpConnectionInfo.builder()
                .clientInfo(new McpSchema.Implementation(
                        "spring-ai-mcp-client - " + connectionName, "1.0.0"))
                // McpConnectionInfo.Builder#build() 는 clientCapabilities 도 null 이 아니어야 한다고 단언한다
                // (brief 원문에는 없던 호출 — 실제 API 검증 중 발견).
                .clientCapabilities(McpSchema.ClientCapabilities.builder().build())
                .build();
    }

    private McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder().name(name).build();
    }

    @Test
    void none_모드는_모든_툴을_통과시킨다() {
        McpToolFilter filter = config.toolFilter("none");

        assertThat(filter.test(connection("product"), tool("searchProducts"))).isTrue();
        assertThat(filter.test(connection("order"), tool("searchOrders"))).isTrue();
        assertThat(filter.test(connection("order"), tool("cancelOrder"))).isTrue();
    }

    @Test
    void safe_모드는_cancelOrder_만_막는다() {
        McpToolFilter filter = config.toolFilter("safe");

        assertThat(filter.test(connection("order"), tool("cancelOrder"))).isFalse();
        assertThat(filter.test(connection("order"), tool("searchOrders"))).isTrue();
        assertThat(filter.test(connection("order"), tool("getOrder"))).isTrue();
        assertThat(filter.test(connection("product"), tool("searchProducts"))).isTrue();
        assertThat(filter.test(connection("product"), tool("getStock"))).isTrue();
    }

    @Test
    void product_only_모드는_order_서버의_툴을_전부_막는다() {
        McpToolFilter filter = config.toolFilter("product-only");

        assertThat(filter.test(connection("product"), tool("searchProducts"))).isTrue();
        assertThat(filter.test(connection("product"), tool("getStock"))).isTrue();
        assertThat(filter.test(connection("order"), tool("searchOrders"))).isFalse();
        assertThat(filter.test(connection("order"), tool("getOrder"))).isFalse();
        assertThat(filter.test(connection("order"), tool("cancelOrder"))).isFalse();
    }

    @Test
    void 알_수_없는_모드는_safe_로_동작한다() {
        McpToolFilter filter = config.toolFilter("오타난모드");

        assertThat(filter.test(connection("order"), tool("cancelOrder")))
                .as("모르는 값이면 안전한 쪽으로 기운다")
                .isFalse();
        assertThat(filter.test(connection("order"), tool("getOrder"))).isTrue();
    }

    @Test
    void 모드별_통과_툴_개수가_5_4_2_다() {
        record Case(String mode, int expected) {
        }
        var tools = new String[][]{
                {"product", "searchProducts"}, {"product", "getStock"},
                {"order", "searchOrders"}, {"order", "getOrder"}, {"order", "cancelOrder"}
        };

        for (Case c : new Case[]{new Case("none", 5), new Case("safe", 4), new Case("product-only", 2)}) {
            McpToolFilter filter = config.toolFilter(c.mode());
            long passed = java.util.Arrays.stream(tools)
                    .filter(t -> filter.test(connection(t[0]), tool(t[1])))
                    .count();
            assertThat(passed).as("모드 %s", c.mode()).isEqualTo(c.expected());
        }
    }
}
