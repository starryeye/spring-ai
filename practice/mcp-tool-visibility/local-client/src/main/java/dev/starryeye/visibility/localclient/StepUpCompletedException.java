package dev.starryeye.visibility.localclient;

/**
 * step-up으로 새 token을 받았다는 신호다. 실패가 아니라 "같은 호출을 새 요청으로 다시 보내라"는 뜻이다.
 *
 * <p>MCP Java SDK 2.0.0의 {@code authorizationErrorHandler}가 {@code true}를 돌려주면, SDK는 이미
 * 만들어 둔(= {@code httpRequestCustomizer}가 이미 지나간, 그래서 옛 token이 실린) {@code HttpRequest}를
 * 그대로 다시 보낸다({@code HttpClientStreamableHttpTransport#sendMessage}). customizer가 다시 불리지
 * 않으니 새 token은 실리지 않는다. 그래서 {@link StepUp#handle}은 성공해도 {@code true}를 돌려주지 않고
 * 이 예외를 던져 SDK의 재시도를 막는다. 호출한 쪽({@link McpCalls})이 이 예외를 받으면 같은 tool 호출을
 * 처음부터 새로 만들어(=customizer가 다시 불려 새 token이 실린 요청으로) 다시 보낸다.
 */
public final class StepUpCompletedException extends RuntimeException {

	public StepUpCompletedException() {
		super("step-up으로 새 token을 받았다. 같은 호출을 새 요청으로 다시 보내야 한다");
	}
}
