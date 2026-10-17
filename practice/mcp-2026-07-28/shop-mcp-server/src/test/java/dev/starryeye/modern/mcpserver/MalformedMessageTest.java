package dev.starryeye.modern.mcpserver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 형식이 틀린 JSON-RPC 본문을 token이 있는 사용자가 보내도, 응답에 서버 내부 정보(stack trace, class 이름)가 나오지 않는지 본다.
 *
 * <p>Spring AI 2.0.1의 stateless transport는 처리하지 못한 요청에 예외 객체({@code McpError})를 그대로 본문으로 써서,
 * Spring MVC가 그 {@code stackTrace}까지 JSON으로 직렬화했다.
 * {@code tools/call}의 형식 오류는 scope filter가 transport 앞에서 {@code 400}으로 끝내고,
 * 그 밖에 transport가 끝내는 오류는 router filter가 JSON-RPC 오류만 남긴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(McpAuthorizationStandardTest.StubAuthorizationServer.class)
class MalformedMessageTest {

	static final JsonMapper JSON = JsonMapper.builder().build();

	/** 응답 본문에 나오면 안 되는 내부 정보의 흔적이다. */
	static final List<String> INTERNALS = List.of("stackTrace", "className", "Exception", "io.modelcontextprotocol",
			"org.springframework", "java.", "tools.jackson", "$");

	@Autowired
	MockMvc mockMvc;

	static String 호출(String params) {
		return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\"" + ((params == null) ? "" : ",\"params\":" + params)
				+ "}";
	}

	MockHttpServletResponse 보낸다(String body) throws Exception {
		String token = McpScopeTest.토큰("products:read", "products:write", "orders:write");
		return this.mockMvc.perform(McpScopeTest.mcp(token, body)).andReturn().getResponse();
	}

	/** 응답이 내부 정보 없는 JSON-RPC 오류이고, 상태와 오류 code가 기대와 같은지 본다. */
	void 내부_정보_없는_오류다(String label, String body, int status, int code) throws Exception {
		MockHttpServletResponse response = 보낸다(body);
		String text = response.getContentAsString(StandardCharsets.UTF_8);

		assertThat(text).as(label).doesNotContain(INTERNALS);
		assertThat(response.getStatus()).as(label).isEqualTo(status);
		JsonNode error = JSON.readTree(text).get("error");
		assertThat(error).as(label + " 본문: " + text).isNotNull();
		assertThat(error.get("code").asInt()).as(label).isEqualTo(code);
	}

	@Test
	void tools_call의_params_name_arguments_meta가_틀린_모양이면_transport로_넘기지_않고_400과_32602다() throws Exception {
		Map<String, String> shapes = new LinkedHashMap<>();
		shapes.put("params 없음", 호출(null));
		shapes.put("params null", 호출("null"));
		shapes.put("params 배열", 호출("[{\"name\":\"getStock\"}]"));
		shapes.put("params 문자열", 호출("\"getStock\""));
		shapes.put("params 숫자", 호출("1"));
		shapes.put("params boolean", 호출("true"));
		shapes.put("name 없음", 호출("{\"arguments\":{}}"));
		shapes.put("name null", 호출("{\"name\":null,\"arguments\":{}}"));
		shapes.put("name 배열", 호출("{\"name\":[\"getStock\"],\"arguments\":{}}"));
		shapes.put("name object", 호출("{\"name\":{\"a\":1},\"arguments\":{}}"));
		shapes.put("name 숫자", 호출("{\"name\":123,\"arguments\":{}}"));
		shapes.put("name boolean", 호출("{\"name\":true,\"arguments\":{}}"));
		shapes.put("arguments 배열", 호출("{\"name\":\"getStock\",\"arguments\":[\"p1\"]}"));
		shapes.put("arguments 문자열", 호출("{\"name\":\"getStock\",\"arguments\":\"p1\"}"));
		shapes.put("arguments 숫자", 호출("{\"name\":\"getStock\",\"arguments\":1}"));
		shapes.put("arguments boolean", 호출("{\"name\":\"getStock\",\"arguments\":true}"));
		shapes.put("_meta 배열", 호출("{\"name\":\"getStock\",\"arguments\":{\"productId\":\"p1\"},\"_meta\":[]}"));
		shapes.put("_meta 문자열", 호출("{\"name\":\"getStock\",\"arguments\":{\"productId\":\"p1\"},\"_meta\":\"x\"}"));

		for (Map.Entry<String, String> shape : shapes.entrySet()) {
			내부_정보_없는_오류다(shape.getKey(), shape.getValue(), 400, -32602);
		}
	}

	@Test
	void arguments와_meta가_null인_호출은_filter가_넘기고_SDK가_입력_검증으로_답한다() throws Exception {
		MockHttpServletResponse response = 보낸다(호출("{\"name\":\"getStock\",\"arguments\":null,\"_meta\":null}"));
		String text = response.getContentAsString(StandardCharsets.UTF_8);

		assertThat(text).doesNotContain(INTERNALS);
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(JSON.readTree(text).at("/result/isError").asBoolean()).isTrue();
	}

	@Test
	void id나_jsonrpc가_틀린_본문도_내부_정보_없는_JSON_RPC_오류다() throws Exception {
		String params = ",\"params\":{\"name\":\"getStock\",\"arguments\":{\"productId\":\"p1\"}}}";
		내부_정보_없는_오류다("id object", "{\"jsonrpc\":\"2.0\",\"id\":{\"a\":1},\"method\":\"tools/call\"" + params, 500,
				-32603);
		내부_정보_없는_오류다("id 배열", "{\"jsonrpc\":\"2.0\",\"id\":[1],\"method\":\"tools/call\"" + params, 500, -32603);
		내부_정보_없는_오류다("jsonrpc 없음", "{\"id\":2,\"method\":\"tools/call\"" + params, 500, -32603);
		내부_정보_없는_오류다("method 없는 응답 모양", "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}", 400, -32600);
	}

	@Test
	void 다른_method의_params가_틀린_모양이어도_내부_정보_없는_JSON_RPC_오류다() throws Exception {
		내부_정보_없는_오류다("prompts/get params 배열", "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"prompts/get\",\"params\":[1]}",
				500, -32603);
		내부_정보_없는_오류다("resources/read uri object",
				"{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/read\",\"params\":{\"uri\":{\"a\":1}}}", 500, -32603);
		내부_정보_없는_오류다("completion/complete params 문자열",
				"{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"completion/complete\",\"params\":\"x\"}", 500, -32603);
	}
}
