package dev.starryeye.modern.mcpserver.config;

import dev.starryeye.modern.mcpserver.security.McpCaller;
import dev.starryeye.modern.mcpserver.tool.BasketTools;
import dev.starryeye.modern.mcpserver.tool.ProductTools;
import dev.starryeye.modern.mcpserver.tool.ShopTool;
import dev.starryeye.modern.mcpserver.tool.ShopToolRepository;
import dev.starryeye.modern.mcpserver.tool.ShopTools;
import dev.starryeye.modern.mcpserver.tool.ToolScopeRegistry;
import dev.starryeye.modern.mcpserver.tool.ToolVisibility;

import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.feature.ToolsFeature;
import io.modelcontextprotocol.modern.server.transport.HttpServletMcpTransport;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * MCP Java SDK 2.2.0-M1의 2026-07-28 서버를 만들어 {@code /mcp}에 등록한다.
 *
 * <p>2026-07-28에는 {@code initialize}와 session이 없다.
 * client는 요청마다 {@code _meta}에 버전·capability·client 정보를 넣으므로, 서버는 어느 요청이든 혼자 처리한다.
 * 그래서 공유 MCP client를 여러 사용자가 써도 요청 사이에 섞일 상태가 없다(안내서 11장).
 *
 * <p>Spring AI는 아직 이 서버를 감싸지 않는다.
 * 그래서 SDK의 {@link McpServer}와 servlet transport({@link HttpServletMcpTransport})를 직접 만들어 servlet으로 등록한다.
 * 이 servlet은 Spring MVC의 {@code DispatcherServlet}을 거치지 않지만, servlet filter인 Spring Security는 그 앞에서 그대로 돈다.
 * 인증과 {@code 401}·{@code 403}은 filter가 맡고, SDK transport는 header와 본문의 대조, {@code _meta}와 버전 검사를 맡는다.
 */
@Configuration
@EnableConfigurationProperties(McpLimitProperties.class)
public class ModernMcpConfig {

	/**
	 * MCP endpoint의 경로다.
	 * servlet mapping과 filter의 URL pattern이 같은 값을 쓴다.
	 */
	public static final String ENDPOINT = "/mcp";

	/**
	 * {@code tools/list} 결과를 client가 cache해도 되는 기간이다.
	 * 목록은 사용자의 역할로만 달라지고, 역할 표는 서버가 떠 있는 동안 바뀌지 않는다.
	 */
	static final Duration TOOL_LIST_TTL = Duration.ofMinutes(1);

	/**
	 * 서버가 내놓는 tool 정의다.
	 * scope 표와 tool 저장소가 모두 이 목록 하나에서 만들어진다.
	 */
	@Bean
	public List<ShopTool> shopTools(ProductTools products, BasketTools baskets) {
		return ShopTools.all(products, baskets);
	}

	@Bean
	public ToolScopeRegistry toolScopeRegistry(List<ShopTool> shopTools) {
		return ToolScopeRegistry.of(shopTools);
	}

	/**
	 * 학습용 역할 표다. {@code user}만 점원이고, 표에 없는 사용자({@code user2} 포함)는 손님이다.
	 * 실제 서비스라면 이 표는 가게의 사용자 저장소에 있다.
	 */
	@Bean
	public ToolVisibility toolVisibility(ToolScopeRegistry registry) {
		return new ToolVisibility(registry, Map.of("user", ToolVisibility.Role.STAFF));
	}

	@Bean
	public ShopToolRepository shopToolRepository(List<ShopTool> shopTools, ToolVisibility visibility) {
		return new ShopToolRepository(shopTools, visibility, TOOL_LIST_TTL);
	}

	/**
	 * 요청을 method별 기능에 나눠 주는 SDK 서버다.
	 * {@code server/discover}는 SDK가 직접 답하고, {@code tools/list}·{@code tools/call}은 tool 저장소에 넘긴다.
	 *
	 * <p>MRTR의 {@code requestState}는 쓰지 않으므로 codec은 SDK 기본값이다.
	 * 기본값은 process마다 무작위 key를 만들고, 기동할 때 그렇다는 WARN을 한 줄 남긴다.
	 */
	@Bean
	public McpServer mcpServer(ShopToolRepository repository) {
		return McpServer.builder()
				.serverInfo(Implementation.builder("modern-shop-mcp-server", "0.0.1").build())
				.feature(ToolsFeature.ofSync(repository))
				.build();
	}

	/**
	 * SDK의 HTTP transport를 {@code /mcp} servlet으로 등록한다.
	 *
	 * <ul>
	 *   <li>{@code contextExtractor}는 요청마다 token의 사용자와 {@code Mcp-Name}을 transport context에 넣는다({@link McpCaller}).</li>
	 *   <li>{@code maxRequestSize}는 메모리에 올리는 본문의 상한이다.
	 *       넘으면 transport가 {@code 413}으로 답한다.</li>
	 *   <li>transport의 header 검사는 SDK 기본값이다.
	 *       {@code Origin}이 있으면 거절한다.
	 *       {@code Host}는 Spring Security 앞의 {@code McpTransportSecurityFilter}가 먼저 본다.</li>
	 *   <li>{@code subscriptions/listen}은 응답을 async로 보내므로 servlet이 async를 지원해야 한다.
	 *       이 서버는 그 method를 등록하지 않지만, SDK가 요구하는 대로 켜 둔다.</li>
	 * </ul>
	 */
	@Bean
	public ServletRegistrationBean<HttpServletMcpTransport> mcpServlet(McpServer server, McpLimitProperties limits) {
		HttpServletMcpTransport transport = HttpServletMcpTransport.builder(server)
				.endpoint(ENDPOINT)
				.contextExtractor(McpCaller::context)
				.maxRequestSize(limits.maxRequestBodyBytes())
				.build();
		ServletRegistrationBean<HttpServletMcpTransport> registration = new ServletRegistrationBean<>(transport, ENDPOINT);
		registration.setName("mcpTransport");
		registration.setAsyncSupported(true);
		registration.setLoadOnStartup(1);
		return registration;
	}
}
