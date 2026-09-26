package dev.starryeye.officialmcpserver;

import io.modelcontextprotocol.spec.ProtocolVersions;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Set;

/**
 * MCP 2025-11-25 Transports 명세의 "Protocol Version Header" 절을 구현한다.
 *
 * <p>Spring AI MCP 2.0.0의 {@code WebMvcStreamableServerTransportProvider}는 이 header를 전혀 검증하지 않는다.
 * 지원하지 않는 값을 보내도 200이 돌아온다.
 * SDK가 하지 않는 이 검증을 filter로 채운다.
 *
 * <p>명세가 정한 규칙은 두 가지다.
 * <ul>
 *   <li>header가 없고 버전을 알 다른 방법(예: session에서 협상한 버전)도 없다면, 서버는
 *       {@code 2025-03-26}으로 가정한다("SHOULD assume protocol version 2025-03-26").
 *       이 서버는 버전에 따라 동작을 나누지 않는다.
 *       그래서 그렇게 가정하는 것은 요청을 그대로 통과시키는 것과 같다.
 *       따로 거부할 이유가 없다.</li>
 *   <li>header가 있는데 값이 잘못됐거나 지원하지 않는 버전이면, 서버는 반드시(MUST)
 *       {@code 400 Bad Request}로 응답한다.</li>
 * </ul>
 *
 * <p>지원 버전 목록은 SDK가 아는 버전 전부다({@link ProtocolVersions}).
 * 이 서버가 실제로 협상하는 버전은 {@code 2025-11-25} 하나뿐이다.
 * 하지만 header 검증이 묻는 것은 "이 서버가 이해할 수 있는 protocol 버전인가"다.
 * "이 session에서 쓰기로 한 버전과 같은가"를 묻는 것이 아니다.
 * 그래서 SDK가 정의한 전체 버전 목록을 기준으로 삼는다.
 *
 * @see <a href="https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#protocol-version-header">Protocol Version Header</a>
 */
public class McpProtocolVersionFilter implements Filter {

	static final String HEADER_NAME = "MCP-Protocol-Version";

	private static final Set<String> SUPPORTED_VERSIONS = Set.of(
			ProtocolVersions.MCP_2024_11_05,
			ProtocolVersions.MCP_2025_03_26,
			ProtocolVersions.MCP_2025_06_18,
			ProtocolVersions.MCP_2025_11_25);

	private final JsonMapper jsonMapper;

	/**
	 * MCP 메시지를 직렬화할 때 쓰는 것과 같은 mapper를 받는다.
	 * 자동 구성 bean인 {@code mcpServerJsonMapper}다({@link McpTransportConfig} 참고).
	 * 오류 응답만 다른 규칙으로 직렬화할 이유가 없다.
	 */
	public McpProtocolVersionFilter(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	@Override
	public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
			throws IOException, ServletException {
		HttpServletRequest request = (HttpServletRequest) servletRequest;
		HttpServletResponse response = (HttpServletResponse) servletResponse;

		String version = request.getHeader(HEADER_NAME);
		if (version != null && !SUPPORTED_VERSIONS.contains(version)) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			// getWriter()는 Content-Type에 charset이 없으면 ISO-8859-1로 쓴다. 그래서 charset을 적는다.
			response.setContentType("application/json;charset=UTF-8");
			// SDK의 다른 오류 응답처럼 id 없는 JSON-RPC 오류 형식을 쓴다
			// (명세: "The HTTP response body MAY comprise a JSON-RPC error response
			// that has no id").
			// header 값을 문자열 formatting으로 끼워 넣으면, 값에 섞인 따옴표가 JSON 구조를 깨고
			// 최상위 member를 멋대로 더할 수 있다.
			// 그래서 JSON tree를 만들어 직렬화하고, 값의 escape는 Jackson에 맡긴다.
			ObjectNode body = this.jsonMapper.createObjectNode();
			body.put("jsonrpc", "2.0");
			body.putNull("id");
			ObjectNode error = body.putObject("error");
			error.put("code", -32600);
			error.put("message", "Unsupported MCP-Protocol-Version: " + version);
			response.getWriter().write(this.jsonMapper.writeValueAsString(body));
			return;
		}
		chain.doFilter(servletRequest, servletResponse);
	}
}
