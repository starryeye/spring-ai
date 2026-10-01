package dev.starryeye.stateless.localclient;

import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepUpTest {

	record Info(int statusCode, HttpHeaders headers, HttpClient.Version version) implements HttpResponse.ResponseInfo {
	}

	static HttpResponse.ResponseInfo info(int status, String challenge) {
		Map<String, List<String>> headers = (challenge == null) ? Map.of() : Map.of("WWW-Authenticate", List.of(challenge));
		return new Info(status, HttpHeaders.of(headers, (name, value) -> true), HttpClient.Version.HTTP_1_1);
	}

	static final String WRITE = "Bearer error=\"insufficient_scope\", scope=\"products:write\"";

	TokenHolder holder = new TokenHolder("read-token", Set.of("products:read"));

	List<Set<String>> requested = new ArrayList<>();

	ByteArrayOutputStream printed = new ByteArrayOutputStream();

	StepUp stepUp(TokenResponse answer) {
		return new StepUp(this.holder, scopes -> {
			this.requested.add(scopes);
			return answer;
		}, new PrintStream(this.printed, true, StandardCharsets.UTF_8));
	}

	@Test
	void 합친_scope로_다시_authorization을_받고_새_token으로_바꾼다() {
		StepUp stepUp = stepUp(new TokenResponse("write-token", 300, "products:read products:write"));

		// handle은 성공해도 true를 돌려주지 않는다. SDK의 재시도가 옛 token이 실린 요청을 그대로 다시
		// 보내는 문제를 피하려면, 성공을 예외로 알려 호출한 쪽(McpCalls)이 새 요청을 만들게 해야 한다.
		assertThatThrownBy(() -> stepUp.handle(null, info(403, WRITE), McpTransportContext.EMPTY))
				.isInstanceOf(StepUpCompletedException.class);

		assertThat(this.requested).containsExactly(Set.of("products:read", "products:write"));
		assertThat(this.holder.accessToken()).isEqualTo("write-token");
		assertThat(this.holder.scopes()).containsExactlyInAnyOrder("products:read", "products:write");
		assertThat(this.printed.toString(StandardCharsets.UTF_8)).contains("[6] step-up");
	}

	@Test
	void 새_token에도_scope가_없으면_멈춘다() {
		StepUp stepUp = stepUp(new TokenResponse("read-token-2", 300, "products:read"));

		assertThatThrownBy(() -> stepUp.handle(null, info(403, WRITE), McpTransportContext.EMPTY))
				.isInstanceOf(LocalClientException.class)
				.hasMessage("products:write 권한을 받지 못했다");
	}

	@Test
	void 같은_scope로_두_번_step_up하지_않는다() {
		StepUp stepUp = stepUp(new TokenResponse("write-token", 300, "products:read products:write"));
		assertThatThrownBy(() -> stepUp.handle(null, info(403, WRITE), McpTransportContext.EMPTY))
				.isInstanceOf(StepUpCompletedException.class);

		assertThatThrownBy(() -> stepUp.handle(null, info(403, WRITE), McpTransportContext.EMPTY))
				.isInstanceOf(LocalClientException.class);
		assertThat(this.requested).hasSize(1);
	}

	@Test
	void 이미_가진_scope를_모자라다고_하면_멈춘다() {
		// 서버가 이미 갖고 있는 scope를 모자라다고 하는 건 (SDK가 재시도로 옛 요청을 다시 보낸 경우 등)
		// step-up으로 고칠 수 없는 상황이다. authorizer를 다시 부르지 않고 바로 멈춘다.
		TokenHolder holder = new TokenHolder("token", Set.of("products:read", "products:write"));
		List<Set<String>> requested = new ArrayList<>();
		StepUp stepUp = new StepUp(holder, scopes -> {
			requested.add(scopes);
			return new TokenResponse("unused", 300, "products:read products:write");
		}, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

		assertThatThrownBy(() -> stepUp.handle(null, info(403, WRITE), McpTransportContext.EMPTY))
				.isInstanceOf(LocalClientException.class)
				.hasMessage("products:write 권한을 받지 못했다");
		assertThat(requested).isEmpty();
	}

	@Test
	void 이미_시도했지만_못_받은_scope는_다시_시도하지_않는다() {
		StepUp stepUp = stepUp(new TokenResponse("still-read-token", 300, "products:read"));

		assertThatThrownBy(() -> stepUp.handle(null, info(403, WRITE), McpTransportContext.EMPTY))
				.isInstanceOf(LocalClientException.class);
		assertThatThrownBy(() -> stepUp.handle(null, info(403, WRITE), McpTransportContext.EMPTY))
				.isInstanceOf(LocalClientException.class);

		assertThat(this.requested).hasSize(1);
	}

	@Test
	void insufficient_scope가_아닌_403은_step_up을_시작하지_않는다() {
		StepUp stepUp = stepUp(new TokenResponse("x", 300, null));

		assertThat(stepUp.handle(null, info(403, null), McpTransportContext.EMPTY)).isFalse();
		assertThat(stepUp.handle(null, info(401, "Bearer resource_metadata=\"x\""), McpTransportContext.EMPTY)).isFalse();
		assertThat(this.requested).isEmpty();
	}

	@Test
	void token_응답에_scope가_없으면_요청한_scope를_받은_것으로_본다() {
		assertThat(new TokenResponse("t", 300, null).grantedScopes(List.of("products:read")))
				.containsExactly("products:read");
	}
}
