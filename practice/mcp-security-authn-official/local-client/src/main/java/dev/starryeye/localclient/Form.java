package dev.starryeye.localclient;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** `application/x-www-form-urlencoded` 문자열과 map 사이를 바꾼다. query string에도 쓴다. */
final class Form {

	private Form() {
	}

	static String encode(Map<String, String> params) {
		return params.entrySet().stream()
				.map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
						+ URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
				.collect(Collectors.joining("&"));
	}

	/** 같은 이름이 여러 번 오면 처음 값을 쓴다. */
	static Map<String, String> decode(String raw) {
		Map<String, String> params = new LinkedHashMap<>();
		if (raw == null || raw.isEmpty()) {
			return params;
		}
		for (String pair : raw.split("&")) {
			int eq = pair.indexOf('=');
			String name = eq < 0 ? pair : pair.substring(0, eq);
			String value = eq < 0 ? "" : pair.substring(eq + 1);
			params.putIfAbsent(URLDecoder.decode(name, StandardCharsets.UTF_8),
					URLDecoder.decode(value, StandardCharsets.UTF_8));
		}
		return params;
	}
}
