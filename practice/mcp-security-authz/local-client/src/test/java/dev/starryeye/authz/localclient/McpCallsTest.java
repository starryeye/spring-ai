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
 * StepUpTest는 handler를 직접 불러 scope 판단과 token 교체만 본다.
 * 이 test는 fake MCP server를 실제로 띄워, handler가 true를 돌려준 뒤 SDK의 재시도 요청에 새 token이 붙는지 본다.
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
	void step_up_뒤_SDK가_새_token으로_다시_보낸다() {
		TokenHolder holder = new TokenHolder("read-token", Set.of("products:read"));
		StepUp stepUp = stepUp(holder, new TokenResponse("write-token", 300, "products:read products:write"));

		McpCalls.run(this.mcp.origin() + "/mcp", holder, stepUp, Duration.ofSeconds(20), this.out);

		assertThat(updateStockCalls()).extracting(FakeMcpServer.Recorded::authorization)
				.containsExactly("Bearer read-token", "Bearer write-token");
		assertThat(this.requested).containsExactly(Set.of("products:read", "products:write"));
		String printedText = this.printed.toString(StandardCharsets.UTF_8);
		assertThat(printedText).contains("updateStock(p1, 10): p1 재고를 10으로 바꿨다");
		assertThat(printedText.lines().filter(line -> line.startsWith("[7]")))
				.containsExactly("[7] 새 token으로 같은 요청을 새로 보낸다");
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
		assertThat(this.printed.toString(StandardCharsets.UTF_8)).doesNotContain("[7]");
	}
}
