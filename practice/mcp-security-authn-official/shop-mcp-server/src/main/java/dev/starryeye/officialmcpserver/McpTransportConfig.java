package dev.starryeye.officialmcpserver;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Streamable HTTP 전송을 직접 만든다. 자동설정 빈과 같되
 * {@code Origin}/{@code Host} 검증기를 하나 더 단다(MCP 2025-11-25 전송 §보안).
 *
 * <p>브라우저가 로컬 MCP 서버로 요청을 보내는 DNS 리바인딩 공격을 막는 장치다.
 * 서버 간 호출에는 {@code Origin} 이 없고, 없는 요청은 그대로 통과한다.
 */
@Configuration
public class McpTransportConfig {

	@Bean
	public WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
			@Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper,
			McpServerStreamableHttpProperties properties, @Value("${server.port}") int port) {
		return WebMvcStreamableServerTransportProvider.builder()
				.jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
				.mcpEndpoint(properties.getMcpEndpoint())
				.keepAliveInterval(properties.getKeepAliveInterval())
				.disallowDelete(properties.isDisallowDelete())
				.securityValidator(DefaultServerTransportSecurityValidator.builder()
						// 브라우저에서 직접 호출할 일이 없으므로 허용 Origin 을 두지 않는다.
						// Origin 이 실려 오면 403 이다.
						.allowedHosts(List.of("localhost:" + port, "127.0.0.1:" + port))
						.build())
				.build();
	}
}
