package dev.starryeye.official.mcpserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;

import java.time.Duration;

/**
 * MCP session의 상한이다.
 * 정하지 않은 값은 기본값을 쓴다.
 *
 * @param maxSessions 서버가 한꺼번에 들고 있는 session 수의 상한.
 *        사용자마다 세지 않고 서버 전체로 센다.
 *        넘으면 새 {@code initialize}가 {@code 503}이다.
 *        기본 1000
 * @param sessionIdleTimeout 요청이 없는 session을 지우기까지의 시간.
 *        지운 session으로 온 요청은 {@code 404}다.
 *        기본 30분
 */
@ConfigurationProperties("mcp.limits")
public record McpLimitProperties(@DefaultValue("1000") long maxSessions,
		@DefaultValue("30m") Duration sessionIdleTimeout) {

	public McpLimitProperties {
		// transport는 0도 받지만, 그러면 모든 initialize가 503이 된다.
		Assert.isTrue(maxSessions > 0, "mcp.limits.max-sessions는 0보다 커야 한다: " + maxSessions);
		Assert.isTrue(sessionIdleTimeout != null && sessionIdleTimeout.isPositive(),
				"mcp.limits.session-idle-timeout은 0보다 길어야 한다: " + sessionIdleTimeout);
	}
}
