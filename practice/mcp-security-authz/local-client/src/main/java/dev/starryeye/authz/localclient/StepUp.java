package dev.starryeye.authz.localclient;

import io.modelcontextprotocol.client.transport.HttpRequestSnapshot;
import io.modelcontextprotocol.client.transport.customizer.McpHttpClientTransportAuthorizationErrorHandler;
import io.modelcontextprotocol.common.McpTransportContext;

import java.io.PrintStream;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * MCP 요청이 `403 insufficient_scope`를 받으면, 합친 scope로 browser authorization을 한 번 더 하고 요청을 다시 보낸다(안내서 10장).
 *
 * <p>사용자 기기의 앱은 사용자가 바로 앞에 있으므로 그 자리에서 browser를 연다.
 * 새 scope만이 아니라 지금 가진 scope와 합쳐 요청한다(MCP 2026-07-28).
 * 같은 scope로는 한 번만 시도한다. 사용자가 허락하지 않으면 멈춘다.
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
		if (!this.attempted.addAll(needed)) {
			throw new LocalClientException(neededText + " 권한을 받지 못했다");
		}
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
		this.out.println("[7] 새 token으로 같은 요청을 다시 보낸다");
		return true;
	}
}
