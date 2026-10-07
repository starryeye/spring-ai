package dev.starryeye.cimd.authserver.cimd;

import java.time.Duration;
import java.util.Locale;

/**
 * 가져온 문서의 본문과 cache 지시다.
 *
 * @param maxAge 응답의 {@code Cache-Control: max-age}. 없으면 {@code null}
 * @param noStore 응답이 {@code no-store}면 {@code true}. 이때는 cache하지 않는다
 */
public record FetchedDocument(byte[] body, Duration maxAge, boolean noStore) {

	public static FetchedDocument of(byte[] body, String cacheControl) {
		if (cacheControl == null) {
			return new FetchedDocument(body, null, false);
		}
		Duration maxAge = null;
		boolean noStore = false;
		for (String directive : cacheControl.toLowerCase(Locale.ROOT).split(",")) {
			String value = directive.trim();
			if ("no-store".equals(value)) {
				noStore = true;
			}
			else if (value.startsWith("max-age=")) {
				Duration parsed = seconds(value.substring("max-age=".length()));
				if (parsed != null) {
					maxAge = parsed;
				}
			}
		}
		return new FetchedDocument(body, maxAge, noStore);
	}

	/** header는 문서 host가 정하므로, 숫자가 아니거나 long 범위를 넘는 값은 없는 것으로 본다. */
	private static Duration seconds(String value) {
		if (!value.matches("\\d+")) {
			return null;
		}
		try {
			return Duration.ofSeconds(Long.parseLong(value));
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}
}
