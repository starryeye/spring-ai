package dev.starryeye.authz.mcpserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * MCP endpoint로 들어오는 요청과 MCP session의 상한이다.
 * 정하지 않은 값은 기본값을 쓴다.
 *
 * @param maxRequestBody scope filter가 tool 이름을 읽으려고 메모리에 올리는 본문의 상한. 넘으면 {@code 413}이다. 기본 1 MiB
 * @param toolCallsPerMinute 사용자(token의 {@code sub})마다 1분 동안 부를 수 있는 {@code tools/call} 수.
 *        넘으면 {@code 429}와 {@code Retry-After}다. 기본 60
 * @param maxSessions 서버가 한꺼번에 들고 있는 session 수의 상한.
 *        사용자마다 세지 않고 서버 전체로 센다.
 *        넘으면 새 {@code initialize}가 {@code 503}이다.
 *        기본 1000
 * @param sessionIdleTimeout 요청이 없는 session을 지우기까지의 시간.
 *        지운 session으로 온 요청은 {@code 404}다.
 *        기본 30분
 */
@ConfigurationProperties("mcp.limits")
public record McpLimitProperties(DataSize maxRequestBody, Integer toolCallsPerMinute,
		@DefaultValue("1000") long maxSessions, @DefaultValue("30m") Duration sessionIdleTimeout) {

	public McpLimitProperties {
		maxRequestBody = (maxRequestBody == null) ? DataSize.ofMegabytes(1) : maxRequestBody;
		// 상한보다 1바이트 더 읽어 넘었는지 보므로 int 범위 안에서 1을 더할 수 있어야 한다.
		Assert.isTrue(maxRequestBody.toBytes() > 0 && maxRequestBody.toBytes() < Integer.MAX_VALUE,
				"mcp.limits.max-request-body는 0보다 크고 2GB보다 작아야 한다: " + maxRequestBody);
		toolCallsPerMinute = (toolCallsPerMinute == null) ? 60 : toolCallsPerMinute;
		Assert.isTrue(toolCallsPerMinute > 0, "mcp.limits.tool-calls-per-minute는 0보다 커야 한다: " + toolCallsPerMinute);
		// transport는 0도 받지만, 그러면 모든 initialize가 503이 된다.
		Assert.isTrue(maxSessions > 0, "mcp.limits.max-sessions는 0보다 커야 한다: " + maxSessions);
		Assert.isTrue(sessionIdleTimeout != null && sessionIdleTimeout.isPositive(),
				"mcp.limits.session-idle-timeout은 0보다 길어야 한다: " + sessionIdleTimeout);
	}

	/** 본문 상한을 바이트 수로 돌려준다. */
	public int maxRequestBodyBytes() {
		return Math.toIntExact(this.maxRequestBody.toBytes());
	}
}
