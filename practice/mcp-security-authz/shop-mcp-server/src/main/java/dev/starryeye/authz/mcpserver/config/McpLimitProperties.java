package dev.starryeye.authz.mcpserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;
import org.springframework.util.unit.DataSize;

/**
 * MCP endpoint로 들어오는 요청의 상한이다. 정하지 않은 값은 기본값을 쓴다.
 *
 * @param maxRequestBody scope filter가 tool 이름을 읽으려고 메모리에 올리는 본문의 상한. 넘으면 {@code 413}이다. 기본 1 MiB
 */
@ConfigurationProperties("mcp.limits")
public record McpLimitProperties(DataSize maxRequestBody) {

	public McpLimitProperties {
		maxRequestBody = (maxRequestBody == null) ? DataSize.ofMegabytes(1) : maxRequestBody;
		// 상한보다 1바이트 더 읽어 넘었는지 보므로 int 범위 안에서 1을 더할 수 있어야 한다.
		Assert.isTrue(maxRequestBody.toBytes() > 0 && maxRequestBody.toBytes() < Integer.MAX_VALUE,
				"mcp.limits.max-request-body는 0보다 크고 2GB보다 작아야 한다: " + maxRequestBody);
	}

	/** 본문 상한을 바이트 수로 돌려준다. */
	public int maxRequestBodyBytes() {
		return Math.toIntExact(this.maxRequestBody.toBytes());
	}
}
