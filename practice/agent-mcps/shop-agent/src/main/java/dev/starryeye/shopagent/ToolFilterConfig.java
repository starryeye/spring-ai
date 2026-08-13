package dev.starryeye.shopagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 어떤 MCP 툴을 모델에게 보여줄지 고른다.
 * <p>
 * {@link McpToolFilter} 는 {@code BiPredicate<McpConnectionInfo, McpSchema.Tool>} 이고,
 * 빈으로 등록하면 MCP 자동설정이 주워가 {@code ToolCallbackProvider} 에 적용한다.
 * 접두사({@code alt_1_} 등)가 붙기 <b>전</b> 원본 툴 이름으로 판단하므로
 * 서버를 추가해도 필터가 깨지지 않는다.
 * <p>
 * 필터는 애플리케이션 전역에 하나뿐이다. 요청마다 다른 툴을 쓰려면 필터가 아니라
 * 요청의 {@code .toolCallbacks(...)} 를 쓰거나 {@code ChatClient} 를 여러 개 만들어야 한다.
 * <p>
 * <b>{@code McpToolFilter} 빈은 애플리케이션 컨텍스트에 정확히 하나만 있어야 한다.</b>
 * MCP 자동설정은 {@code getIfUnique(() -> (c, t) -> true)} 로 필터를 찾으므로, 빈이
 * 둘 이상이면 아무 경고 없이 전체 허용(allow-all)으로 조용히 되돌아가 {@code cancelOrder}
 * 까지 노출된다.
 */
@Configuration
public class ToolFilterConfig {

    private static final Logger log = LoggerFactory.getLogger(ToolFilterConfig.class);

    private static final String DANGEROUS_TOOL = "cancelOrder";
    private static final String PRODUCT_CONNECTION_SUFFIX = " - product";

    @Bean
    public McpToolFilter toolFilter(@Value("${shop.tool-filter:safe}") String mode) {
        log.info("MCP 툴 필터 모드: {}", mode);
        return switch (mode) {
            case "none" -> (connection, tool) -> true;
            case "product-only" -> (connection, tool) ->
                    connection.clientInfo().name().endsWith(PRODUCT_CONNECTION_SUFFIX);
            default -> (connection, tool) -> !DANGEROUS_TOOL.equals(tool.name());
        };
    }
}
