package dev.starryeye.modern.agent.mcp;

import java.time.Duration;

/**
 * MCP Server가 이 사용자의 tool 호출이 너무 잦다며 {@code 429}로 답했다.
 *
 * <p>MCP Server는 사용자마다 1분에 받을 tool 호출 수를 정해 둔다.
 * 다시 보내도 되는 시점은 {@code Retry-After} header로 알려 준다.
 * 이 예외는 HTTP status {@code 429}로 알아본다.
 * MCP 명세에 속도 제한 오류 code가 없어서, 본문의 JSON-RPC {@code code}는 서버마다 다를 수 있고 분류에 쓰지 않는다.
 */
public class ToolRateLimitedException extends ModernMcpException {

	private final Duration retryAfter;

	/**
	 * @param retryAfter {@code Retry-After}의 초.
	 *        header가 없거나 초가 아니면 {@code null}
	 * @param jsonRpcCode 본문 JSON-RPC 오류의 {@code code}. 본문이 없으면 {@code null}
	 * @param message 본문 오류의 {@code message}.
	 *        모델에게 tool 결과로 간다
	 */
	public ToolRateLimitedException(Duration retryAfter, Integer jsonRpcCode, String message) {
		super(429, jsonRpcCode, message);
		this.retryAfter = retryAfter;
	}

	public Duration retryAfter() {
		return this.retryAfter;
	}
}
