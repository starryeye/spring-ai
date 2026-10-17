package dev.starryeye.modern.agent.mcp;

/**
 * MCP Server가 결과 대신 오류로 답했다.
 *
 * <p>2026-07-28 MCP Server는 오류를 HTTP status와 JSON-RPC {@code error.code} 둘로 알린다.
 * 같은 {@code -32602}도 모르는 tool일 때와 인자가 틀렸을 때 모두 쓰이므로, 둘 다 남겨 둔다.
 * filter와 SDK가 만든 오류 본문에는 {@code id}가 없을 수 있어서, 요청과 맞춰 보지 않고 이 두 값으로만 가른다.
 * 자주 만나는 경우는 하위 class로 나눈다({@link UnknownToolException}, {@link ToolRateLimitedException},
 * {@link McpProtocolMismatchException}).
 */
public class ModernMcpException extends RuntimeException {

	private final int httpStatus;

	private final Integer jsonRpcCode;

	/**
	 * @param httpStatus 응답의 HTTP status
	 * @param jsonRpcCode 본문 JSON-RPC 오류의 {@code code}. 본문이 JSON-RPC 오류가 아니면 {@code null}
	 * @param message 본문 오류의 {@code message}나, 본문이 없을 때 이 client가 만든 설명.
	 * tool 호출에서 나면 이 문장이 모델에게 tool 결과로 간다
	 */
	public ModernMcpException(int httpStatus, Integer jsonRpcCode, String message) {
		super(message);
		this.httpStatus = httpStatus;
		this.jsonRpcCode = jsonRpcCode;
	}

	public int httpStatus() {
		return this.httpStatus;
	}

	public Integer jsonRpcCode() {
		return this.jsonRpcCode;
	}
}
