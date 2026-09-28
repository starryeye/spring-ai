package dev.starryeye.authz.localclient;

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
 * SDK가 재시도로 옛 token이 실린 요청을 다시 보내는 데 있었다(task-9-fix-1). 이 test는 fake MCP
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

	List<FakeMcpServer.Recorded> updateStockCalls() {
		return this.mcp.requests.stream().filter(r -> "updateStock".equals(r.toolName())).toList();
	}

	@Test
	void step_up_뒤_updateStock을_새_token을_실은_새_요청으로_다시_보낸다() {
		TokenHolder holder = new TokenHolder("read-token", Set.of("products:read"));
		StepUp stepUp = stepUp(holder, new TokenResponse("write-token", 300, "products:read products:write"));

		McpCalls.run(this.mcp.origin() + "/mcp", holder, stepUp, Duration.ofSeconds(20), this.out);

		List<FakeMcpServer.Recorded> updateStockCalls = updateStockCalls();
		assertThat(updateStockCalls).hasSize(2);
		assertThat(updateStockCalls.get(0).authorization()).isEqualTo("Bearer read-token");
		assertThat(updateStockCalls.get(1).authorization()).isEqualTo("Bearer write-token");

		assertThat(this.requested).containsExactly(Set.of("products:read", "products:write"));
		String printedText = this.printed.toString(StandardCharsets.UTF_8);
		assertThat(printedText).contains("updateStock(p1, 10): p1 재고를 10으로 바꿨다").contains("[7]");
	}

	@Test
	void authorizer가_products_write를_못_주면_실패하고_updateStock을_다시_보내지_않는다() {
		TokenHolder holder = new TokenHolder("read-token", Set.of("products:read"));
		StepUp stepUp = stepUp(holder, new TokenResponse("still-read-token", 300, "products:read"));

		assertThatThrownBy(
				() -> McpCalls.run(this.mcp.origin() + "/mcp", holder, stepUp, Duration.ofSeconds(20), this.out))
				.isInstanceOf(LocalClientException.class)
				.hasMessage("products:write 권한을 받지 못했다");

		assertThat(updateStockCalls()).hasSize(1);
		assertThat(this.requested).containsExactly(Set.of("products:read", "products:write"));
	}
}
