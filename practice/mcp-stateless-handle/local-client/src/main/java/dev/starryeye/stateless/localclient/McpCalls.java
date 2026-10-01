package dev.starryeye.stateless.localclient;

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
 *
 * <p>장바구니는 session이 아니라 서버가 만든 handle로 다룬다(안내서 11장).
 * 이 앱은 {@code createBasket}이 돌려준 {@code basketId}를 {@code addItem}·{@code getBasket}·{@code checkout}의 인자로 그대로 넘긴다.
 * {@code checkout}만 {@code orders:write}를 요구하므로, 조회 token으로 부르면 403이 오고 그 자리에서 step-up이 일어난다.
 */
public final class McpCalls {

	/** 이 사용자가 만든 적 없는 handle이다. 형식은 맞지만 서버에 없다. */
	static final String UNKNOWN_BASKET = "bsk_" + "A".repeat(22);

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
			print(out, "getStock(p1)", callOnce(() -> call(client, "getStock", Map.of("productId", "p1")), out));

			// handle은 서버가 만든다. 이 앱은 받은 handle을 다음 호출의 인자로 넘긴다(안내서 11장).
			McpSchema.CallToolResult created = callOnce(() -> call(client, "createBasket", Map.of()), out);
			print(out, "createBasket", created);
			String basketId = basketId(created);
			print(out, "addItem(p4, 1)", callOnce(() -> call(client, "addItem",
					Map.of("basketId", basketId, "productId", "p4", "quantity", 1)), out));
			print(out, "addItem(p9, 2)", callOnce(() -> call(client, "addItem",
					Map.of("basketId", basketId, "productId", "p9", "quantity", 2)), out));
			print(out, "getBasket", callOnce(() -> call(client, "getBasket", Map.of("basketId", basketId)), out));
			// 만든 적 없는 handle은 "찾을 수 없다"다. 가진 것만으로는 쓸 수 없다.
			print(out, "getBasket(모르는 ID)",
					callOnce(() -> call(client, "getBasket", Map.of("basketId", UNKNOWN_BASKET)), out));
			// 주문은 orders:write가 필요하다. 조회 token이면 403 → 그 자리에서 step-up → 새 요청으로 다시 보낸다.
			print(out, "checkout", callOnce(() -> call(client, "checkout", Map.of("basketId", basketId)), out));
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

	private static McpSchema.CallToolResult call(McpSyncClient client, String tool, Map<String, Object> arguments) {
		return client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());
	}

	/** tool 결과를 한 줄씩 찍는다. {@code isError}면 앞에 {@code [오류]}를 붙인다. */
	private static void print(PrintStream out, String label, McpSchema.CallToolResult result) {
		String prefix = Boolean.TRUE.equals(result.isError()) ? "[오류] " : "";
		result.content().forEach(content -> out.println("    " + label + ": " + prefix
				+ (content instanceof McpSchema.TextContent text ? text.text() : content)));
	}

	/** {@code createBasket}의 {@code structuredContent}에서 handle을 꺼낸다. */
	static String basketId(McpSchema.CallToolResult created) {
		if (created.structuredContent() instanceof Map<?, ?> content && content.get("basketId") instanceof String id) {
			return id;
		}
		throw new LocalClientException("createBasket이 basketId를 돌려주지 않았다");
	}
}
