package dev.starryeye.modern.agent.mcp;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.modern.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * MCP Server가 준 {@code ttlMs}·{@code cacheScope}대로 tool 목록을 cache하는지 본다(MCP 2026-07-28 Caching).
 */
class UserToolCatalogTest {

	/** 테스트가 시간을 앞으로 보낼 수 있는 시계다. */
	static final class 시계 extends Clock {

		private Instant now = Instant.parse("2026-10-18T00:00:00Z");

		void 지나감(Duration duration) {
			this.now = this.now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return this.now;
		}
	}

	시계 clock = new 시계();

	ModernMcpClient client = mock(ModernMcpClient.class);

	/**
	 * 사용자 이름 → 지금 그 사용자의 access token이다.
	 * step-up과 refresh는 값을 바꿔 흉내 낸다.
	 */
	Map<String, String> tokens = new HashMap<>(Map.of("a", "token-a", "b", "token-b"));

	UserToolCatalog catalog = new UserToolCatalog(this.client, user -> this.tokens.get(user.getName()), this.clock);

	static Authentication 사용자(String name) {
		return new TestingAuthenticationToken(name, null);
	}

	static McpSchema.Tool 도구(String name) {
		return McpSchema.Tool.builder(name, Map.of("type", "object")).description(name).build();
	}

	/** 실제 MCP Server처럼 1분 동안 이 token에서만 쓰라는 목록이다. */
	static McpSchema.ListToolsResult 목록(String... names) {
		return McpSchema.ListToolsResult.builder(List.of(names).stream().map(UserToolCatalogTest::도구).toList())
				.ttlMs(60_000)
				.cacheScope(McpSchema.CacheScope.PRIVATE)
				.build();
	}

	static List<String> 이름(List<ToolCallback> callbacks) {
		return callbacks.stream().map(callback -> callback.getToolDefinition().name()).toList();
	}

	@BeforeEach
	void setUp() {
		given(this.client.listTools(eq("token-a"), isNull())).willReturn(목록("getStock", "updateStock"));
		given(this.client.listTools(eq("token-b"), isNull())).willReturn(목록("getStock"));
	}

	@Test
	void 처음_쓸_때_discover를_한_번_부른다() {
		this.catalog.callbacks(사용자("a"));
		this.catalog.callbacks(사용자("b"));
		this.clock.지나감(Duration.ofMinutes(2));
		this.catalog.callbacks(사용자("a"));

		verify(this.client, times(1)).discover(anyString());
		verify(this.client).discover("token-a");
	}

	@Test
	void discover가_실패하면_목록을_받지_않고_다음에_다시_묻는다() {
		given(this.client.discover("token-a"))
				.willThrow(new McpProtocolMismatchException(200, null, "MCP Server가 이 client의 protocol version을 지원하지 않는다"))
				.willReturn(null);

		assertThatThrownBy(() -> this.catalog.callbacks(사용자("a")))
				.isInstanceOf(McpProtocolMismatchException.class);
		verify(this.client, never()).listTools(any(), any());

		this.catalog.callbacks(사용자("a"));
		verify(this.client, times(2)).discover("token-a");
	}

	@Test
	void ttlMs_동안은_다시_받지_않는다() {
		this.catalog.callbacks(사용자("a"));
		this.clock.지나감(Duration.ofSeconds(59));
		this.catalog.callbacks(사용자("a"));

		verify(this.client, times(1)).listTools("token-a", null);

		this.clock.지나감(Duration.ofSeconds(1));
		this.catalog.callbacks(사용자("a"));

		verify(this.client, times(2)).listTools("token-a", null);
	}

	@Test
	void ttlMs가_없거나_0이면_cache하지_않는다() throws IOException {
		// SDK는 ttlMs가 빠진 결과를 0으로 읽는다.
		// 실제 응답 본문에서 읽어 그 값을 그대로 쓴다.
		McpSchema.ListToolsResult withoutTtl = new JacksonMcpJsonMapper(JsonMapper.builder().build()).readValue("""
				{"tools":[{"name":"getStock","inputSchema":{"type":"object"}}],"cacheScope":"private"}""",
				McpSchema.ListToolsResult.class);
		McpSchema.ListToolsResult zeroTtl = McpSchema.ListToolsResult.builder(List.of(도구("getStock")))
				.ttlMs(0)
				.cacheScope(McpSchema.CacheScope.PUBLIC)
				.build();
		given(this.client.listTools(eq("token-a"), isNull())).willReturn(withoutTtl);
		given(this.client.listTools(eq("token-b"), isNull())).willReturn(zeroTtl);

		this.catalog.callbacks(사용자("a"));
		this.catalog.callbacks(사용자("a"));
		this.catalog.callbacks(사용자("b"));
		this.catalog.callbacks(사용자("b"));

		verify(this.client, times(2)).listTools("token-a", null);
		verify(this.client, times(2)).listTools("token-b", null);
		assertThat(this.catalog.size()).isZero();
	}

	@Test
	void private_목록은_token마다_따로_둔다() {
		assertThat(이름(this.catalog.callbacks(사용자("a")))).containsExactly("getStock", "updateStock");
		assertThat(이름(this.catalog.callbacks(사용자("b")))).containsExactly("getStock");
		assertThat(이름(this.catalog.callbacks(사용자("a")))).containsExactly("getStock", "updateStock");

		verify(this.client, times(1)).listTools("token-a", null);
		verify(this.client, times(1)).listTools("token-b", null);
	}

	@Test
	void step_up으로_token이_바뀌면_다시_받는다() {
		given(this.client.listTools(eq("token-a-after-step-up"), isNull())).willReturn(목록("getStock", "checkout"));

		this.catalog.callbacks(사용자("a"));
		this.tokens.put("a", "token-a-after-step-up");

		assertThat(이름(this.catalog.callbacks(사용자("a")))).containsExactly("getStock", "checkout");
	}

	@Test
	void public_목록은_token끼리_나눈다() {
		given(this.client.listTools(eq("token-a"), isNull())).willReturn(McpSchema.ListToolsResult
				.builder(List.of(도구("searchProducts"))).ttlMs(60_000).cacheScope(McpSchema.CacheScope.PUBLIC).build());

		this.catalog.callbacks(사용자("a"));

		assertThat(이름(this.catalog.callbacks(사용자("b")))).containsExactly("searchProducts");
		verify(this.client, never()).listTools(eq("token-b"), any());
	}

	@Test
	void public_목록을_나눠_써도_tool은_부르는_사용자의_token으로_부른다() {
		given(this.client.listTools(eq("token-a"), isNull())).willReturn(McpSchema.ListToolsResult
				.builder(List.of(도구("searchProducts"))).ttlMs(60_000).cacheScope(McpSchema.CacheScope.PUBLIC).build());
		given(this.client.callTool(anyString(), anyString(), anyMap()))
				.willReturn(McpSchema.CallToolResult.builder().build());
		this.catalog.callbacks(사용자("a"));

		this.catalog.callbacks(사용자("b")).get(0).call("{}");

		verify(this.client).callTool("token-b", "searchProducts", Map.of());
	}

	@Test
	void nextCursor를_따라가_목록을_합친다() {
		given(this.client.listTools(eq("token-a"), isNull())).willReturn(McpSchema.ListToolsResult
				.builder(List.of(도구("getStock"))).nextCursor("page-2").ttlMs(60_000).build());
		given(this.client.listTools("token-a", "page-2")).willReturn(McpSchema.ListToolsResult
				.builder(List.of(도구("updateStock"))).nextCursor("page-3").ttlMs(30_000).build());
		given(this.client.listTools("token-a", "page-3")).willReturn(McpSchema.ListToolsResult
				.builder(List.of(도구("checkout"))).ttlMs(60_000).build());

		assertThat(이름(this.catalog.callbacks(사용자("a")))).containsExactly("getStock", "updateStock", "checkout");

		// 여러 쪽이면 가장 짧은 ttlMs를 따른다.
		this.clock.지나감(Duration.ofSeconds(30));
		this.catalog.callbacks(사용자("a"));
		verify(this.client, times(2)).listTools("token-a", null);
	}

	@Test
	void forget하면_다음에_다시_받는다() {
		this.catalog.callbacks(사용자("a"));
		this.catalog.callbacks(사용자("b"));

		this.catalog.forget("token-a");
		this.catalog.callbacks(사용자("a"));
		this.catalog.callbacks(사용자("b"));

		verify(this.client, times(2)).listTools("token-a", null);
		verify(this.client, times(1)).listTools("token-b", null);
	}

	@Test
	void 모르는_tool_오류를_받으면_그_목록을_받은_token의_목록을_버린다() {
		given(this.client.callTool(eq("token-a"), eq("updateStock"), anyMap()))
				.willThrow(new UnknownToolException(400, "Unknown tool: updateStock"));
		ToolCallback updateStock = this.catalog.callbacks(사용자("a")).get(1);
		this.catalog.callbacks(사용자("b"));

		catchThrowable(() -> updateStock.call("{}"));
		this.catalog.callbacks(사용자("a"));
		this.catalog.callbacks(사용자("b"));

		verify(this.client, times(2)).listTools("token-a", null);
		verify(this.client, times(1)).listTools("token-b", null);
	}

	@Test
	void 호출할_때마다_지금의_access_token을_꺼낸다() {
		given(this.client.callTool(anyString(), anyString(), anyMap()))
				.willReturn(McpSchema.CallToolResult.builder().build());
		ToolCallback getStock = this.catalog.callbacks(사용자("a")).get(0);
		// 목록을 받은 뒤 token이 refresh되었다.
		this.tokens.put("a", "token-a-refreshed");

		getStock.call("{\"productId\":\"p1\"}");

		verify(this.client).callTool("token-a-refreshed", "getStock", Map.of("productId", "p1"));
	}

	@Test
	void 만료된_항목은_다른_사용자가_꺼낼_때_함께_지운다() {
		this.catalog.callbacks(사용자("a"));
		this.clock.지나감(Duration.ofMinutes(1));
		this.catalog.callbacks(사용자("b"));

		assertThat(this.catalog.size()).isEqualTo(1);
	}

	@Test
	void key는_token_값을_그대로_두지_않는_SHA256_hash다() {
		// SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
		assertThat(UserToolCatalog.key("abc"))
				.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
		assertThat(UserToolCatalog.key("token-a")).isNotEqualTo(UserToolCatalog.key("token-b"));
	}
}
