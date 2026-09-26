package dev.starryeye.localclient;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/** 응답 본문을 JSON object로 읽는다. MCP SDK가 가져오는 Jackson 3을 쓴다. */
final class Json {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private Json() {
	}

	static Map<String, Object> object(String body) {
		try {
			return MAPPER.readValue(body, new TypeReference<Map<String, Object>>() {
			});
		}
		catch (JacksonException ex) {
			throw new LocalClientException("응답이 JSON object가 아니다", ex);
		}
	}
}
