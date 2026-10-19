package dev.starryeye.modern.agent.mcp;

/**
 * MCP Server가 그 이름의 tool을 모른다고 답했다(안내서 12장).
 *
 * <p>MCP Server는 이 사용자에게 숨긴 tool도 정말 없는 tool과 똑같이 답한다.
 * 그래서 이 오류는 agent가 들고 있는 tool 목록이 낡았다는 뜻이다.
 * {@code ModernToolCallback}은 이 예외를 받으면 그 token의 목록을 버린다.
 */
public class UnknownToolException extends ModernMcpException {

	/**
	 * MCP Java SDK 2.2.0-M1이 모르는 tool에 주는 message의 앞부분이다.
	 * 뒤에 tool 이름이 온다.
	 */
	public static final String MESSAGE_PREFIX = "Unknown tool: ";

	/** 모르는 tool은 JSON-RPC {@code -32602}(Invalid params)로 온다. */
	public static final int CODE = -32602;

	public UnknownToolException(int httpStatus, String message) {
		super(httpStatus, CODE, message);
	}

	/** code {@code -32602}는 인자 오류에도 쓰이므로 message까지 본다. */
	static boolean matches(int code, String message) {
		return code == CODE && message != null && message.startsWith(MESSAGE_PREFIX);
	}
}
