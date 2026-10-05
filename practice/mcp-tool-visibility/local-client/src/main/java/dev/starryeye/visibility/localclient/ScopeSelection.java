package dev.starryeye.visibility.localclient;

import java.util.List;

/**
 * 처음 요청할 scope다(MCP 2025-11-25 Authorization — Scope Selection Strategy, 안내서 10장).
 *
 * <p>범용 client는 서버마다 어떤 scope가 필요한지 모른다.
 * 그래서 {@code 401}의 {@code scope}를 먼저 쓰고, 없으면 PRM의 {@code scopes_supported} 전부를 쓰고,
 * 둘 다 없으면 scope 없이 요청한다.
 *
 * @param scopes 요청할 scope. 비어 있으면 scope parameter를 보내지 않는다
 * @param source 어디서 고른 값인지. 실행 출력에 쓴다
 */
public record ScopeSelection(List<String> scopes, String source) {

	public ScopeSelection {
		scopes = List.copyOf(scopes);
	}

	public static ScopeSelection select(String challengeScope, Object scopesSupported) {
		if (challengeScope != null && !challengeScope.isBlank()) {
			return new ScopeSelection(List.of(challengeScope.trim().split("\\s+")), "401의 scope");
		}
		if (scopesSupported instanceof List<?> supported && !supported.isEmpty()) {
			return new ScopeSelection(supported.stream().map(String::valueOf).toList(), "PRM의 scopes_supported");
		}
		return new ScopeSelection(List.of(), "scope parameter를 보내지 않는다");
	}

	public String describe() {
		return (this.scopes.isEmpty() ? "없음" : String.join(" ", this.scopes)) + " (" + this.source + ")";
	}
}
