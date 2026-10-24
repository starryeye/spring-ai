package dev.starryeye.memory.mcpserver.config;

import dev.starryeye.memory.mcpserver.filter.McpProtocolVersionFilter;
import dev.starryeye.memory.mcpserver.filter.McpTransportSecurityFilter;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * MCP endpoint에만 적용하는 servlet filter 두 개를 등록한다.
 *
 * <ul>
 *   <li>{@link McpTransportSecurityFilter} — {@code Origin}·{@code Host}를 검증한다. Spring Security보다 먼저 돈다.</li>
 *   <li>{@link McpProtocolVersionFilter} — {@code MCP-Protocol-Version}을 검증한다. 인증 뒤에 돈다.</li>
 * </ul>
 *
 * <p>Streamable HTTP transport bean과 그 router bean은 직접 만든다.
 * transport에 session 상한과 만료를 넣고, transport의 오류 응답에서 Java 예외 정보를 빼기 위해서다.
 */
@Configuration
@EnableConfigurationProperties(McpLimitProperties.class)
public class McpTransportConfig {

	/**
	 * Spring AI 자동 구성의 transport bean을 대신한다(그 bean은 {@code @ConditionalOnMissingBean}이다).
	 * session 상한({@code maxSessions})과 만료({@code sessionIdleTimeout})는 builder에만 있고 Spring AI 설정으로는 바꿀 수 없다.
	 * 그래서 bean을 직접 만든다.
	 * 나머지 builder 호출은 Spring AI 2.0.2의 {@code McpServerStreamableHttpWebMvcAutoConfiguration}과 같다.
	 *
	 * <p>streamable 서버는 {@code initialize}마다 session을 메모리에 만든다.
	 * 이 bean이 없으면 transport의 상한은 100,000이고 만료가 없다.
	 * 그러면 token이 있는 사용자 하나가 {@code initialize}를 되풀이해 메모리를 채울 수 있고, 한 번 찬 상한은 다시 비지 않는다.
	 * 상한을 넘은 {@code initialize}는 {@code 503}이다.
	 * 상한은 서버 전체의 session 수라서 사용자마다 따로 세지 않는다.
	 * 그래서 한 사용자가 상한을 채우면 다른 사용자의 {@code initialize}도 {@code 503}이 된다.
	 * 만료가 그 자리를 다시 비운다.
	 * 다만 Spring AI 2.0.2의 만료 검사는 session을 꺼낸 뒤 지우기 전에 같은 session의 DELETE가 먼저 끝나면 {@code NullPointerException}으로 멈춘다.
	 * 멈춘 검사는 다시 돌지 않는다.
	 * 그래서 재시작 전까지 session이 만료되지 않고, 상한이 차면 모든 {@code initialize}가 {@code 503}이다.
	 * token이 있는 client는 DELETE를 보낼 때를 스스로 고르므로, 검사가 도는 때에 맞춰 DELETE를 몰아 보내 이 멈춤을 일부러 일으킬 수 있다.
	 * 멈추면 Reactor가 ERROR 로그를 남긴다.
	 * logger는 {@code reactor.core.publisher.Operators}, 메시지는 {@code Operator called default onErrorDropped}, 원인은 {@code NullPointerException}이다.
	 * transport 기본값은 만료가 없어 한 번 찬 상한이 다시 비지 않으므로, 이 위험이 기본값보다 나쁜 것은 아니다.
	 *
	 * <p>만료된 session으로 온 요청은 {@code 404}다.
	 * client는 이 응답을 받으면 새 session을 연다.
	 * transport는 {@code sessionIdleTimeout} 간격으로 만료를 검사하므로, 쉬는 session은 정한 시간의 두 배까지 남을 수 있다.
	 * keep-alive({@code spring.ai.mcp.server.streamable-http.keep-alive-interval})를 켜면 만료 시간이 그 간격보다 길어야 한다.
	 * 그렇지 않으면 연결된 client도 ping에 답하기 전에 지워질 수 있어서, builder가 bean 생성을 실패시킨다.
	 *
	 * <p>{@code McpSessionBindingFilter}는 session을 연 사용자의 묶음을 같은 {@code sessionIdleTimeout}이 지나면 지운다({@code SecurityConfig}).
	 * 묶음이 없는 session ID로 온 요청은 transport에 넘기지 않고 이 filter가 직접 {@code 404}로 답한다.
	 */
	@Bean
	public WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
			@Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper, McpServerStreamableHttpProperties properties,
			McpLimitProperties limits) {
		return WebMvcStreamableServerTransportProvider.builder()
				.jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
				.mcpEndpoint(properties.getMcpEndpoint())
				.keepAliveInterval(properties.getKeepAliveInterval())
				.disallowDelete(properties.isDisallowDelete())
				.maxSessions(limits.maxSessions())
				.sessionIdleTimeout(limits.sessionIdleTimeout())
				.build();
	}

	/**
	 * Spring AI 자동 구성의 router bean을 대신한다.
	 * 자동 구성은 이 이름({@code webMvcStreamableServerRouterFunction})의 bean이 없을 때만 router bean을 만든다.
	 * 그래서 이 bean 이름은 바꾸면 안 된다.
	 * 바꾸면 filter 없는 자동 구성 router도 함께 등록되고, 어느 router가 답할지는 bean 순서에 달린다.
	 * transport의 router는 그대로 쓰고, 오류 응답에서 예외 객체를 빼는 {@link TransportErrorResponseFilter}만 더한다.
	 */
	@Bean
	public RouterFunction<ServerResponse> webMvcStreamableServerRouterFunction(
			WebMvcStreamableServerTransportProvider transport,
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
		// 순서는 일부러 정하지 않는다. 기본값(Ordered.LOWEST_PRECEDENCE)이라
		// Spring Security filter chain(SecurityFilterProperties.DEFAULT_FILTER_ORDER) 뒤에 돈다.
		// 그래서 token이 없는 요청은 버전을 보기 전에 401을 받는다.
		return registration;
	}
}
