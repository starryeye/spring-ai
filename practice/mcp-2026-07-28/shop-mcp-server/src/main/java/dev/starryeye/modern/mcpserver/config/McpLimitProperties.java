package dev.starryeye.modern.mcpserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;
import org.springframework.util.unit.DataSize;

/**
 * MCP endpoint로 들어오는 요청의 상한이다. 정하지 않은 값은 기본값을 쓴다.
 *
 * <p>본문 상한은 SDK transport의 {@code maxRequestSize}로 넘긴다({@link ModernMcpConfig}).
 * transport는 {@code Content-Length}가 상한을 넘으면 본문을 읽지 않고 {@code 413}으로 답한다.
 * 길이를 미리 알 수 없는 본문은 읽다가 상한을 넘는 순간 {@code 413}으로 끝낸다.
 * scope filter는 header만 보므로 본문을 메모리에 올리는 곳은 transport 하나다.
 *
 * <p>호출 상한은 scope filter의 {@code ToolCallRateLimiter}가 쓴다.
 *
 * @param maxRequestBody MCP transport가 메모리에 올리는 본문의 상한. 넘으면 {@code 413}이다. 기본 1 MiB
 * @param toolCallsPerMinute 사용자(token의 {@code sub})마다 1분 동안 부를 수 있는 {@code tools/call} 수.
 *        넘으면 {@code 429}와 {@code Retry-After}다. 기본 60
 */
@ConfigurationProperties("mcp.limits")
public record McpLimitProperties(DataSize maxRequestBody, Integer toolCallsPerMinute) {

	public McpLimitProperties {
		maxRequestBody = (maxRequestBody == null) ? DataSize.ofMegabytes(1) : maxRequestBody;
		// transport의 maxRequestSize는 int다. 읽은 바이트 수도 int로 세므로 int 최댓값보다 작게 둔다.
		Assert.isTrue(maxRequestBody.toBytes() > 0 && maxRequestBody.toBytes() < Integer.MAX_VALUE,
				"mcp.limits.max-request-body는 0보다 크고 2GB보다 작아야 한다: " + maxRequestBody);
		toolCallsPerMinute = (toolCallsPerMinute == null) ? 60 : toolCallsPerMinute;
		Assert.isTrue(toolCallsPerMinute > 0, "mcp.limits.tool-calls-per-minute는 0보다 커야 한다: " + toolCallsPerMinute);
	}

	/** 본문 상한을 바이트 수로 돌려준다. */
	public int maxRequestBodyBytes() {
		return Math.toIntExact(this.maxRequestBody.toBytes());
	}
}
