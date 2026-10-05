package dev.starryeye.visibility.localclient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StepUpTest는 handler를 직접 불러 "성공하면 새 token으로 바뀌는지"만 본다. 실제 문제는 그 뒤,
 * SDK가 재시도로 옛 token이 실린 요청을 다시 보내는 데 있었다. 이 test는 fake MCP
 * server를 실제로 띄워 McpCalls.run이 끝까지 새 token으로 다시 보내는지를 본다.
 */
class McpCallsTest {

	FakeMcpServer mcp;

	ByteArrayOutputStream printed = new ByteArrayOutputStream();

	PrintStream out = new PrintStream(this.printed, true, StandardCharsets.UTF_8);

	List<Set<String>> requested = new ArrayList<>();

	@BeforeEach
	void setUp() throws Exception {
		this.mcp = new FakeMcpServer();
	}

	@AfterEach
	void tearDown() {
		this.mcp.close();
	}

	StepUp stepUp(TokenHolder holder, TokenResponse answer) {
		return new StepUp(holder, scopes -> {
			this.requested.add(scopes);
			return answer;
		}, this.out);
	}

	List<FakeMcpServer.Recorded> calls(String tool) {
		return this.mcp.requests.stream().filter(r -> tool.equals(r.toolName())).toList();
	}

	String printed() {
		return this.printed.toString(StandardCharsets.UTF_8);
	}

	@Test
	void step_up_뒤_checkout을_새_token을_실은_새_요청으로_다시_보낸다() {
		TokenHolder holder = new TokenHolder("read-token", Set.of("products:read"));
		StepUp stepUp = stepUp(holder, new TokenResponse("write-token", 300, "products:read orders:write"));

		McpCalls.run(this.mcp.origin() + "/mcp", holder, stepUp, Duration.ofSeconds(20), this.out);

		List<FakeMcpServer.Recorded> checkouts = calls("checkout");
		assertThat(checkouts).extracting(FakeMcpServer.Recorded::authorization)
				.containsExactly("Bearer read-token", "Bearer write-token");
		assertThat(this.requested).containsExactly(Set.of("products:read", "orders:write"));
		assertThat(printed()).contains("[7]").contains("checkout: 주문 ord-1001를 접수했습니다.");
	}

	@Test
	void createBasket이_준_basketId를_다음_호출에_넘긴다() {
		TokenHolder holder = new TokenHolder("read-token", Set.of("products:read"));

		McpCalls.run(this.mcp.origin() + "/mcp", holder,
				stepUp(holder, new TokenResponse("write-token", 300, "products:read orders:write")),
				Duration.ofSeconds(20), this.out);

		assertThat(calls("addItem")).extracting(r -> r.arguments().get("basketId"))
				.containsOnly(FakeMcpServer.BASKET_ID);
		// 첫 getBasket은 받은 handle로, 두 번째는 일부러 모르는 handle로 부른다.
		assertThat(calls("getBasket")).extracting(r -> r.arguments().get("basketId"))
				.containsExactly(FakeMcpServer.BASKET_ID, McpCalls.UNKNOWN_BASKET);
		assertThat(calls("checkout")).extracting(r -> r.arguments().get("basketId"))
				.containsOnly(FakeMcpServer.BASKET_ID);
	}

	@Test
	void 모르는_장바구니는_오류로_찍고_이어_간다() {
		TokenHolder holder = new TokenHolder("read-token", Set.of("products:read"));

		McpCalls.run(this.mcp.origin() + "/mcp", holder,
				stepUp(holder, new TokenResponse("write-token", 300, "products:read orders:write")),
				Duration.ofSeconds(20), this.out);

		assertThat(printed()).contains("getBasket(모르는 ID): [오류] 장바구니를 찾을 수 없습니다.");
	}

	@Test
	void session이_없으면_끝날_때_DELETE를_보내지_않는다() {
		TokenHolder holder = new TokenHolder("read-token", Set.of("products:read"));

		McpCalls.run(this.mcp.origin() + "/mcp", holder,
				stepUp(holder, new TokenResponse("write-token", 300, "products:read orders:write")),
				Duration.ofSeconds(20), this.out);

		assertThat(this.mcp.requests).extracting(FakeMcpServer.Recorded::httpMethod).doesNotContain("DELETE");
	}

	@Test
	void authorizer가_orders_write를_못_주면_실패하고_checkout을_다시_보내지_않는다() {
		TokenHolder holder = new TokenHolder("read-token", Set.of("products:read"));
		StepUp stepUp = stepUp(holder, new TokenResponse("still-read-token", 300, "products:read"));

		assertThatThrownBy(
				() -> McpCalls.run(this.mcp.origin() + "/mcp", holder, stepUp, Duration.ofSeconds(20), this.out))
				.isInstanceOf(LocalClientException.class)
				.hasMessage("orders:write 권한을 받지 못했다");

		assertThat(calls("checkout")).hasSize(1);
		assertThat(this.requested).containsExactly(Set.of("products:read", "orders:write"));
	}
}
