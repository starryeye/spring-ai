package dev.starryeye.visibility.mcpserver.config;

import dev.starryeye.visibility.mcpserver.basket.BasketStore;
import dev.starryeye.visibility.mcpserver.filter.McpProtocolVersionFilter;
import dev.starryeye.visibility.mcpserver.filter.McpTransportSecurityFilter;
import dev.starryeye.visibility.mcpserver.filter.ToolCallRateLimiter;
import dev.starryeye.visibility.mcpserver.filter.ToolScopeFilter;
import dev.starryeye.visibility.mcpserver.security.McpCaller;
import dev.starryeye.visibility.mcpserver.tool.ToolScopeRegistry;
import dev.starryeye.visibility.mcpserver.tool.ToolVisibility;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import org.springframework.ai.mcp.customizer.McpStatelessSyncServerCustomizer;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * MCP endpoint에만 적용하는 servlet filter 세 개를 등록한다.
 *
 * <ul>
 *   <li>{@link McpTransportSecurityFilter} — {@code Origin}·{@code Host}를 검증한다. Spring Security보다 먼저 돈다.</li>
 *   <li>{@link ToolScopeFilter} — token의 scope가 이 요청에 충분한지 본다. 인증 뒤, 버전 검사 앞에서 돈다.</li>
 *   <li>{@link McpProtocolVersionFilter} — {@code MCP-Protocol-Version}을 검증한다. scope 검사 뒤에 돈다.</li>
 * </ul>
 *
 * <p>stateless transport bean은 사용자를 읽는 {@code contextExtractor}를 넣으려고 직접 만든다.
 *
 * <p>학습용 역할 표({@link ToolVisibility})도 여기서 만든다.
 * 역할에 따라 {@code tools/list}를 거르는 것은 SDK의 tool filter({@link #toolVisibilityListFilter})고,
 * 숨긴 tool의 호출에 "모르는 tool"로 답하는 것은 {@code @Primary}인 transport wrapper({@link ToolVisibilityTransport})다.
 * 둘은 한 쌍이다. filter는 목록만 거르므로, wrapper가 없으면 숨긴 tool도 이름으로 부르면 실행된다.
 */
@Configuration
@EnableConfigurationProperties(McpLimitProperties.class)
public class McpTransportConfig {

	/**
	 * Spring AI 자동 설정의 stateless transport bean을 대신한다(그 bean은 {@code @ConditionalOnMissingBean}이다).
	 * 바꾸는 것은 {@code contextExtractor} 하나다.
	 * 요청마다 token의 사용자를 {@code McpTransportContext}에 넣어, tool이 누가 불렀는지 알게 한다.
	 */
	@Bean
	public WebMvcStatelessServerTransport webMvcStatelessServerTransport(
			@Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper, McpServerStreamableHttpProperties properties) {
		return WebMvcStatelessServerTransport.builder()
				.jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
				.messageEndpoint(properties.getMcpEndpoint())
				.contextExtractor(McpCaller::context)
				.build();
	}

	/**
	 * Spring AI 자동 구성의 router bean을 대신한다(그 bean은 이 이름의 bean이 없을 때만 만들어진다).
	 * transport의 router는 그대로 쓰고, 오류 응답에서 예외 객체를 빼는 {@link TransportErrorResponseFilter}만 더한다.
	 */
	@Bean
	public RouterFunction<ServerResponse> webMvcStatelessServerRouterFunction(WebMvcStatelessServerTransport transport,
			@Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper) {
		return transport.getRouterFunction().filter(new TransportErrorResponseFilter(jsonMapper));
	}

	@Bean
	public FilterRegistrationBean<McpTransportSecurityFilter> mcpTransportSecurityFilter(
			McpServerStreamableHttpProperties properties, @Value("${server.port}") int port) {
		DefaultServerTransportSecurityValidator validator = DefaultServerTransportSecurityValidator.builder()
				// browser에서 직접 부를 일이 없으므로 허용 Origin을 두지 않는다.
				// Origin header가 있으면 403이다.
				.allowedHosts(List.of("localhost:" + port, "127.0.0.1:" + port))
				.build();
		FilterRegistrationBean<McpTransportSecurityFilter> registration =
				new FilterRegistrationBean<>(new McpTransportSecurityFilter(validator));
		registration.addUrlPatterns(properties.getMcpEndpoint());
		// Spring Security filter chain보다 먼저 돌아서, 인증 전에 막는다.
		registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1);
		return registration;
	}

	/** 장바구니 저장소다. 만료 계산은 {@link Clock}으로 한다(테스트가 시간을 돌릴 수 있게). */
	@Bean
	public BasketStore basketStore() {
		return new BasketStore(Clock.systemUTC());
	}

	/**
	 * tool별 scope 표다. tool bean을 손으로 넘기지 않고 ApplicationContext의 모든 bean에서 찾는다.
	 * 새 tool bean을 더하고 여기에 넣는 것을 잊어도 그 tool의 scope가 빠지지 않는다.
	 * 숨김 판단({@link ToolVisibility})도 이 표를 쓰므로, 표에서 빠진 쓰기 tool은 손님에게도 보이고 조회 token으로도 불린다.
	 */
	@Bean
	public ToolScopeRegistry toolScopeRegistry(ApplicationContext context) {
		return ToolScopeRegistry.scan(context);
	}

	/**
	 * {@link ToolScopeFilter}를 MCP endpoint에만 적용한다.
	 * token 검증(Spring Security) 바로 뒤, 버전 검사 앞에서 돈다.
	 * 인증된 사용자의 scope를 보고, 모자라면 transport에 닿기 전에 {@code 403}으로 끝낸다.
	 * 사용자마다 {@code tools/call} 횟수도 세어, 1분 상한을 넘으면 {@code 429}로 끝낸다.
	 */
	@Bean
	public FilterRegistrationBean<ToolScopeFilter> toolScopeFilter(ToolScopeRegistry registry,
			ToolVisibility visibility, @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper,
			McpServerStreamableHttpProperties properties, OAuth2ResourceServerProperties resourceServer,
			McpLimitProperties limits) {
		String resourceMetadataUrl = ResourceMetadataUrl.of(ResourceMetadataUrl.resource(resourceServer));
		ToolCallRateLimiter rateLimiter =
				new ToolCallRateLimiter(limits.toolCallsPerMinute(), Duration.ofMinutes(1), Clock.systemUTC());
		ToolScopeFilter filter = new ToolScopeFilter(registry, visibility, jsonMapper, resourceMetadataUrl,
				limits.maxRequestBodyBytes(), rateLimiter);
		FilterRegistrationBean<ToolScopeFilter> registration = new FilterRegistrationBean<>(filter);
		registration.addUrlPatterns(properties.getMcpEndpoint());
		registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1);
		return registration;
	}

	/**
	 * {@link McpProtocolVersionFilter}를 MCP endpoint에만 적용한다.
	 * 포트처럼 practice마다 달라지는 값을 코드에 고정하지 않도록,
	 * endpoint 경로도 설정값에서 가져온다.
	 */
	@Bean
	public FilterRegistrationBean<McpProtocolVersionFilter> mcpProtocolVersionFilter(
			@Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper, McpServerStreamableHttpProperties properties) {
		FilterRegistrationBean<McpProtocolVersionFilter> registration =
				new FilterRegistrationBean<>(new McpProtocolVersionFilter(jsonMapper));
		registration.addUrlPatterns(properties.getMcpEndpoint());
		// scope 검사 뒤에 돈다.
		registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 2);
		return registration;
	}

	/**
	 * 학습용 역할 표다. {@code user}만 점원이고, 표에 없는 사용자({@code user2} 포함)는 손님이다.
	 * 실제 서비스라면 이 표는 가게의 사용자 저장소에 있다.
	 */
	@Bean
	public ToolVisibility toolVisibility(ToolScopeRegistry registry) {
		return new ToolVisibility(registry, Map.of("user", ToolVisibility.Role.STAFF));
	}

	/**
	 * 역할로 거른 {@code tools/list}를 만든다. SDK 2.1.0의 tool filter를 Spring AI 2.0.2의 customizer bean으로 등록한다.
	 * Spring AI가 tool을 모두 더한 뒤에 이 customizer를 적용하므로, 자동 구성의 server를 대신 만들 필요가 없다.
	 *
	 * <p>filter는 tool마다 불리고 요청의 {@code McpTransportContext}만 받는다.
	 * 사용자는 transport가 token에서 꺼내 넣은 {@code sub}를 읽는다.
	 * SDK는 filter를 {@code filterWhen}으로 적용해 tool 순서를 등록된 순서 그대로 둔다.
	 * 그래서 client의 목록 cache와 모델의 prompt cache가 흔들리지 않는다.
	 * filter가 예외를 던지면 SDK가 {@code tools/list} 전체를 내부 오류({@code -32603})로 바꾸므로 던지면 안 된다.
	 * {@code sub}가 없는 요청은 사용자를 모르는 요청이라, 사용자를 {@code null}로 넘겨 {@link ToolVisibility}가 손님으로 다루게 한다.
	 *
	 * <p>이 filter는 {@code tools/list}에만 적용된다. {@code tools/call}은 거치지 않으므로,
	 * 숨긴 tool의 호출은 {@link ToolVisibilityTransport}가 막는다.
	 */
	@Bean
	public McpStatelessSyncServerCustomizer toolVisibilityListFilter(ToolVisibility visibility) {
		return builder -> builder.addToolFilter(
				(context, tool) -> !visibility.hidden(McpCaller.subject(context).orElse(null), tool.name()));
	}

	/**
	 * 숨긴 tool의 {@code tools/call}만 막는 wrapper다. 목록은 {@link #toolVisibilityListFilter}가 거른다.
	 * 자동 구성의 server는 transport를 {@code McpStatelessServerTransport} 타입으로 받으므로, {@code @Primary}인 이 bean이 간다.
	 * router는 {@code WebMvcStatelessServerTransport} 타입으로 받으므로 원래 bean이 그대로 간다.
	 */
	@Bean
	@Primary
	public ToolVisibilityTransport toolVisibilityTransport(WebMvcStatelessServerTransport transport,
			ToolVisibility visibility) {
		// SDK server는 mapper를 따로 받지 않아 McpJsonDefaults의 mapper를 쓴다(Spring AI 자동 구성이 넘기지 않는다).
		// wrapper가 params를 SDK와 다르게 변환하면 요청 형식에 따라 숨긴 tool만 다르게 답해 존재가 알려지므로, 같은 mapper를 쓴다.
		// mcpServerJsonMapper는 빈 문자열을 null 객체로 받아들이는 등 더 너그러워서 쓰면 안 된다.
		return new ToolVisibilityTransport(transport, visibility, McpJsonDefaults.getMapper());
	}
}
