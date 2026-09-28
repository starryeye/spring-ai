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
 * 요청마다 {@link TokenHolder}의 token을 붙인다. `403 insufficient_scope`는 {@link StepUp}이 처리하고, 같은 요청을 다시 보낸다.
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
				// 요청을 보낼 때마다 지금 token을 붙인다. step-up이 token을 바꾸면 다음 요청부터 새 token이 붙는다.
				.httpRequestCustomizer((builder, method, endpoint, body, context) ->
						builder.setHeader("Authorization", "Bearer " + holder.accessToken()))
				.authorizationErrorHandler(McpHttpClientTransportAuthorizationErrorHandler.fromSync(stepUp))
				.build();
		McpSyncClient client = McpClient.sync(transport)
				.clientInfo(McpSchema.Implementation.builder("local-mcp-client", "0.0.1").build())
				.requestTimeout(requestTimeout)
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
			McpSchema.CallToolResult updated = client.callTool(McpSchema.CallToolRequest.builder("updateStock")
					.arguments(Map.of("productId", "p1", "quantity", 10)).build());
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
}
