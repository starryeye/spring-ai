package dev.starryeye.stateless.localclient;

import io.modelcontextprotocol.client.transport.HttpRequestSnapshot;
import io.modelcontextprotocol.client.transport.customizer.McpHttpClientTransportAuthorizationErrorHandler;
import io.modelcontextprotocol.common.McpTransportContext;

import java.io.PrintStream;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * MCP 요청이 `403 insufficient_scope`를 받으면, 합친 scope로 browser authorization을 한 번 더 한다(안내서 10장).
 *
 * <p>사용자 기기의 앱은 사용자가 바로 앞에 있으므로 그 자리에서 browser를 연다.
 * 새 scope만이 아니라 지금 가진 scope와 합쳐 요청한다(MCP 2026-07-28).
 * 이미 가진 scope를 서버가 모자라다고 하거나, 이미 한 번 요청했는데도 못 받은 scope면 더 시도하지 않고 멈춘다.
 *
 * <p>{@link #handle}은 성공해도 {@code true}를 돌려주지 않는다. MCP Java SDK 2.0.0은 {@code true}를
 * 받으면 {@code httpRequestCustomizer}를 다시 부르지 않고 옛 token이 실린 요청을 그대로 재시도한다
 * ({@code HttpClientStreamableHttpTransport#sendMessage} 참고). 그래서 성공하면 대신
 * {@link StepUpCompletedException}을 던져, 호출한 쪽({@link McpCalls})이 같은 요청을 새로 만들어
 * 다시 보내게 한다.
 */
public final class StepUp implements McpHttpClientTransportAuthorizationErrorHandler.Sync {

	/** 주어진 scope로 authorization code 흐름을 한 번 밟아 token을 받는다. */
	public interface Authorizer {

		TokenResponse authorize(Set<String> scopes);
	}

	private final TokenHolder holder;

	private final Authorizer authorizer;

	private final PrintStream out;

	private final Set<String> attempted = new HashSet<>();

	public StepUp(TokenHolder holder, Authorizer authorizer, PrintStream out) {
		this.holder = holder;
		this.authorizer = authorizer;
		this.out = out;
	}

	@Override
	public boolean handle(HttpRequestSnapshot requestSnapshot, HttpResponse.ResponseInfo responseInfo,
			McpTransportContext context) {
		if (responseInfo.statusCode() != 403) {
			return false;
		}
		Optional<BearerChallenge> challenge = responseInfo.headers().firstValue("WWW-Authenticate")
				.flatMap(BearerChallenge::parse)
				.filter(BearerChallenge::insufficientScope);
		if (challenge.isEmpty()) {
			return false;
		}
		List<String> needed = challenge.get().scopes();
		String neededText = String.join(" ", needed);
		Set<String> missing = new LinkedHashSet<>(needed);
		missing.removeAll(this.holder.scopes());
		// missing이 비었다는 건 서버가 이미 가진 scope를 모자라다고 한 것이고, attempted와 겹친다는 건 그
		// scope를 이미 한 번 요청했는데도 못 받은 것이다. 두 경우 다 다시 시도해도 달라지지 않는다.
		if (missing.isEmpty() || !Collections.disjoint(missing, this.attempted)) {
			throw new LocalClientException(neededText + " 권한을 받지 못했다");
		}
		this.attempted.addAll(missing);
		Set<String> scopes = new LinkedHashSet<>(this.holder.scopes());
		scopes.addAll(needed);
		this.out.println("    403 insufficient_scope — 필요한 scope: " + neededText);
		this.out.println("[6] step-up: " + String.join(" ", scopes) + "로 다시 authorization을 받는다");
		TokenResponse token = this.authorizer.authorize(scopes);
		Set<String> granted = token.grantedScopes(scopes);
		if (!granted.containsAll(needed)) {
			throw new LocalClientException(neededText + " 권한을 받지 못했다");
		}
		this.holder.update(token.accessToken(), granted);
		throw new StepUpCompletedException();
	}
}
