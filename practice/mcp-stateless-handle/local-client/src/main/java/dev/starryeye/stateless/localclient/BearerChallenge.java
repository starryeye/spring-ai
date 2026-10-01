package dev.starryeye.stateless.localclient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code WWW-Authenticate: Bearer …} challenge에서 {@code error}와 {@code scope}를 읽는다(RFC 6750 §3).
 * agent와 같은 규칙이다. module이 서로 의존하지 않아 따로 둔다.
 *
 * @param error 예: {@code insufficient_scope}. 없으면 {@code null}
 * @param scopes {@code scope}를 공백으로 나눈 값. 없으면 빈 목록
 */
public record BearerChallenge(String error, List<String> scopes) {

	private static final Pattern PARAMETER = Pattern.compile("([A-Za-z_]+)=\"([^\"]*)\"");

	public BearerChallenge {
		scopes = List.copyOf(scopes);
	}

	public static Optional<BearerChallenge> parse(String header) {
		if (header == null || !header.regionMatches(true, 0, "Bearer", 0, 6)) {
			return Optional.empty();
		}
		Map<String, String> parameters = new HashMap<>();
		Matcher matcher = PARAMETER.matcher(header);
		while (matcher.find()) {
			parameters.putIfAbsent(matcher.group(1), matcher.group(2));
		}
		String scope = parameters.get("scope");
		List<String> scopes = (scope == null || scope.isBlank()) ? List.of() : List.of(scope.trim().split("\\s+"));
		return Optional.of(new BearerChallenge(parameters.get("error"), scopes));
	}

	/** step-up을 시작할 challenge인지 본다. 필요한 scope를 알려 주지 않으면 무엇을 요청할지 모른다. */
	public boolean insufficientScope() {
		return "insufficient_scope".equals(this.error) && !this.scopes.isEmpty();
	}
}
