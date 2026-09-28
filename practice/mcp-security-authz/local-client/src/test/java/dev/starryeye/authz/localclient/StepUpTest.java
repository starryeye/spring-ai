package dev.starryeye.authz.localclient;

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
	void 합친_scope로_다시_authorization을_받고_새_token으로_다시_보낸다() {
		StepUp stepUp = stepUp(new TokenResponse("write-token", 300, "products:read products:write"));

		boolean retry = stepUp.handle(null, info(403, WRITE), McpTransportContext.EMPTY);

		assertThat(retry).isTrue();
		assertThat(this.requested).containsExactly(Set.of("products:read", "products:write"));
		assertThat(this.holder.accessToken()).isEqualTo("write-token");
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
		stepUp.handle(null, info(403, WRITE), McpTransportContext.EMPTY);

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
