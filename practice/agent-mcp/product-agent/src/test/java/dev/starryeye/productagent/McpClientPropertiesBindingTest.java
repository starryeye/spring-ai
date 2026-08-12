package dev.starryeye.productagent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpClientCommonProperties;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 {@code application.yml} 이 Spring AI MCP 클라이언트 프로퍼티로 바인딩되는지 확인한다.
 * 키 이름을 하나라도 잘못 적으면 기동은 되지만 MCP 연결이 조용히 사라지므로, yml 자체를 읽어서 검증한다.
 */
class McpClientPropertiesBindingTest {

    private StandardEnvironment environmentFromApplicationYml() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addFirst(source));
        return environment;
    }

    @Test
    void streamable_http_커넥션이_product_이름으로_바인딩된다() throws IOException {
        McpStreamableHttpClientProperties properties = Binder.get(environmentFromApplicationYml())
                .bind(McpStreamableHttpClientProperties.CONFIG_PREFIX,
                        McpStreamableHttpClientProperties.class)
                .orElseThrow(() -> new AssertionError(
                        "spring.ai.mcp.client.streamable-http 설정이 바인딩되지 않았다"));

        assertThat(properties.getConnections()).containsKey("product");
        assertThat(properties.getConnections().get("product").url())
                .isEqualTo("http://localhost:8082");
    }

    @Test
    void MCP_클라이언트는_ASYNC_로_활성화된다() throws IOException {
        McpClientCommonProperties properties = Binder.get(environmentFromApplicationYml())
                .bind(McpClientCommonProperties.CONFIG_PREFIX, McpClientCommonProperties.class)
                .orElseThrow(() -> new AssertionError(
                        "spring.ai.mcp.client 설정이 바인딩되지 않았다"));

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getType())
                .isEqualTo(McpClientCommonProperties.ClientType.ASYNC);
    }
}
