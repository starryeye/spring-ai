package dev.starryeye.cimd.agent;

import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** token request의 form 본문을 parameter로 푼다. */
public final class TestForms {

	private TestForms() {
	}

	public static MultiValueMap<String, String> parse(String body) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		for (String pair : body.split("&")) {
			if (pair.isEmpty()) {
				continue;
			}
			String[] nameAndValue = pair.split("=", 2);
			form.add(URLDecoder.decode(nameAndValue[0], StandardCharsets.UTF_8),
					(nameAndValue.length > 1) ? URLDecoder.decode(nameAndValue[1], StandardCharsets.UTF_8) : "");
		}
		return form;
	}
}
