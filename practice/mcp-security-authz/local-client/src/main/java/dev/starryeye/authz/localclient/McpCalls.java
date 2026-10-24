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

/**
 * 요청을 새로 만들 때마다 {@link TokenHolder}의 token을 붙인다.
 *
 * <p>`403 insufficient_scope`가 오면 {@link StepUp}이 browser authorization을 다시 밟아 새 token을 받고 {@code true}를 돌려준다.
 * 그러면 SDK가 같은 요청을 새로 만들어 다시 보내므로, 이 클래스는 재시도를 따로 다룰 필요가 없다.
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
				// 요청을 만들 때마다 지금 token을 붙인다.
				// SDK는 step-up 뒤의 재시도에도 요청을 새로 만들어 이 customizer를 다시 부르므로, 재시도에는 새 token이 들어간다.
				// httpRequestCustomizer(...)는 다른 customizer를 모두 지우므로 add로 더한다.
				.addHttpRequestCustomizer((builder, method, endpoint, body, context) ->
						builder.setHeader("Authorization", "Bearer " + holder.accessToken()))
				.authorizationErrorHandler(McpHttpClientTransportAuthorizationErrorHandler.fromSync(stepUp))
				.build();
		McpSyncClient client = McpClient.sync(transport)
				.clientInfo(McpSchema.Implementation.builder("local-mcp-client", "0.0.1").build())
				.requestTimeout(requestTimeout)
				// initialize 중에 step-up이 나도(browser login을 기다리는 동안) 20초로 끊기지 않게 한다.
				.initializationTimeout(requestTimeout)
				.build();
		try {
			McpSchema.InitializeResult initialized = client.initialize();
			out.println("    initialize: protocolVersion=" + initialized.protocolVersion()
					+ ", server=" + initialized.serverInfo().name());
			client.listTools().tools().forEach(tool -> out.println("    tool: " + tool.name()));
			McpSchema.CallToolResult result = client.callTool(
					McpSchema.CallToolRequest.builder("getStock").arguments(Map.of("productId", "p1")).build());
			result.content().forEach(content -> out.println("    getStock(p1): "
					+ (content instanceof McpSchema.TextContent text ? text.text() : content)));
			McpSchema.CallToolResult updated = client.callTool(McpSchema.CallToolRequest
					.builder("updateStock").arguments(Map.of("productId", "p1", "quantity", 10)).build());
			updated.content().forEach(content -> out.println("    updateStock(p1, 10): "
					+ (content instanceof McpSchema.TextContent text ? text.text() : content)));
		}
		catch (RuntimeException ex) {
			// step-up에서 난 LocalClientException은 SDK를 거치며 감싸일 수 있다.
			// 원인 사슬에서 찾아 그대로 올린다.
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
}
