package dev.starryeye.localclient;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;

/**
 * access token을 붙여 MCP Server를 부른다(안내서 1장·6장).
 *
 * <p>모든 요청이 같은 token을 쓰므로 transport의 기본 요청에 `Authorization` header를 한 번 넣는다.
 * SDK는 이 기본 요청을 복사해 `initialize`·`tools/list`·`tools/call`과 session을 끝내는 `DELETE`를 보낸다.
 */
public final class McpCalls {

	private McpCalls() {
	}

	public static void run(String resourceUrl, String accessToken, PrintStream out) {
		URI uri = URI.create(resourceUrl);
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
				.builder(uri.getScheme() + "://" + uri.getRawAuthority())
				.endpoint(uri.getRawPath())
				.requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + accessToken))
				.build();
		McpSyncClient client = McpClient.sync(transport)
				.clientInfo(McpSchema.Implementation.builder("local-mcp-client", "0.0.1").build())
				.requestTimeout(Duration.ofSeconds(20))
				.build();
		try {
			McpSchema.InitializeResult initialized = client.initialize();
			out.println("    initialize: protocolVersion=" + initialized.protocolVersion()
					+ ", server=" + initialized.serverInfo().name());
			client.listTools().tools().forEach(tool -> out.println("    tool: " + tool.name()));
			McpSchema.CallToolResult result = client
					.callTool(new McpSchema.CallToolRequest("getStock", Map.of("productId", "p1"), null));
			result.content().forEach(content -> out.println("    getStock(p1): "
					+ (content instanceof McpSchema.TextContent text ? text.text() : content)));
		}
		finally {
			client.closeGracefully();
		}
	}
}
