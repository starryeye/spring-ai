package dev.starryeye.authz.localclient;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpHttpClientTransportAuthorizationErrorHandler;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.PrintStream;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 요청을 새로 만들 때마다 {@link TokenHolder}의 token을 붙인다.
 *
 * <p>`403 insufficient_scope`가 오면 {@link StepUp}이 browser authorization을 다시 밟아 새 token을
 * 받고 {@link StepUpCompletedException}을 던진다(SDK의 재시도는 이미 만들어 둔 옛 요청을 그대로 다시
 * 보내 옛 token이 그대로 실리므로 쓸 수 없다). {@link #callOnce}가 그 예외를 잡아 같은 tool 호출을 처음부터
 * 새로 만들어(=customizer가 다시 불려 새 token이 실린 요청으로) 한 번만 다시 보낸다.
 */
public final class McpCalls {

	private McpCalls() {
	}

	public static void run(String resourceUrl, TokenHolder holder, StepUp stepUp, Duration requestTimeout,
			PrintStream out) {
		URI uri = URI.create(resourceUrl);
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
				.builder(uri.getScheme() + "://" + uri.getRawAuthority())
				.endpoint(uri.getRawPath())
				// 요청을 새로 만들 때마다 지금 token을 붙인다. SDK가 같은 요청을 그대로 재시도할 때는 이
				// customizer를 다시 부르지 않으므로, step-up 뒤의 새 token은 callOnce가 새로 만드는
				// 요청에만 실린다(재시도가 아니라 새 tool 호출이라야 여기를 다시 지난다).
				.httpRequestCustomizer((builder, method, endpoint, body, context) ->
						builder.setHeader("Authorization", "Bearer " + holder.accessToken()))
				.authorizationErrorHandler(McpHttpClientTransportAuthorizationErrorHandler.fromSync(stepUp))
				.build();
		McpSyncClient client = McpClient.sync(transport)
				.clientInfo(McpSchema.Implementation.builder("local-mcp-client", "0.0.1").build())
				.requestTimeout(requestTimeout)
				// initialize 중에 step-up이 나도(브라우저 로그인을 기다리는 동안) 20초로 끊기지 않게 한다.
				.initializationTimeout(requestTimeout)
				.build();
		try {
			McpSchema.InitializeResult initialized = callOnce(client::initialize, out);
			out.println("    initialize: protocolVersion=" + initialized.protocolVersion()
					+ ", server=" + initialized.serverInfo().name());
			callOnce(client::listTools, out).tools().forEach(tool -> out.println("    tool: " + tool.name()));
			McpSchema.CallToolResult result = callOnce(() -> client.callTool(
					McpSchema.CallToolRequest.builder("getStock").arguments(Map.of("productId", "p1")).build()), out);
			result.content().forEach(content -> out.println("    getStock(p1): "
					+ (content instanceof McpSchema.TextContent text ? text.text() : content)));
			McpSchema.CallToolResult updated = callOnce(() -> client.callTool(McpSchema.CallToolRequest
					.builder("updateStock").arguments(Map.of("productId", "p1", "quantity", 10)).build()), out);
			updated.content().forEach(content -> out.println("    updateStock(p1, 10): "
					+ (content instanceof McpSchema.TextContent text ? text.text() : content)));
		}
		catch (RuntimeException ex) {
			// step-up에서 난 LocalClientException은 SDK를 거치며 감싸일 수 있다. 원인 사슬에서 찾아 그대로 올린다.
			for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
				if (cause instanceof LocalClientException localClient) {
					throw localClient;
				}
			}
			throw new LocalClientException("MCP 호출이 실패했다: " + ex.getMessage(), ex);
		}
		finally {
			client.closeGracefully();
		}
	}

	/**
	 * 호출 하나를 보낸다. 원인 사슬에 {@link StepUpCompletedException}이 있으면(= step-up이 새 token을
	 * 받았다는 뜻) `[7]`을 찍고 같은 호출을 새 요청으로 한 번만 다시 보낸다. 그 재시도에서 또 step-up이
	 * 나도 더 반복하지 않고 그대로 위로 올린다(StepUp의 attempted 규칙이 같은 scope의 무한 반복을 막으므로,
	 * 실제로는 그 재시도가 다시 StepUpCompletedException을 던지는 일은 없다).
	 */
	private static <T> T callOnce(Supplier<T> call, PrintStream out) {
		try {
			return call.get();
		}
		catch (RuntimeException ex) {
			if (!steppedUp(ex)) {
				throw ex;
			}
			out.println("[7] 새 token으로 같은 요청을 새로 보낸다");
			return call.get();
		}
	}

	private static boolean steppedUp(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof StepUpCompletedException) {
				return true;
			}
		}
		return false;
	}
}
