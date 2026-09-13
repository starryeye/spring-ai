package dev.starryeye.officialmcpserver;

import io.modelcontextprotocol.spec.ProtocolVersions;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Set;

/**
 * MCP 2025-11-25 전송 명세의 "Protocol Version Header" 절을 구현한다.
 *
 * <p>Spring AI MCP 2.0.0 의 {@code WebMvcStreamableServerTransportProvider} 는
 * 이 헤더를 전혀 검증하지 않는다(지원하지 않는 값을 보내도 200 이 돌아온다). SDK 가
 * 하지 않는 검증을 이 필터로 보충한다.
 *
 * <p>명세가 정한 두 가지 규칙:
 * <ul>
 *   <li>헤더가 없고 버전을 알 방법이 달리 없다면(예: 세션에서 협상된 버전), 서버는
 *       {@code 2025-03-26} 을 가정해야 한다("SHOULD assume protocol version 2025-03-26").
 *       이 서버는 버전에 따라 동작을 분기하지 않으므로, 그 "가정"은 곧 요청을 그대로
 *       통과시키는 것과 같다 — 별도로 거부할 이유가 없다.</li>
 *   <li>헤더가 있는데 유효하지 않거나 지원하지 않는 값이면, 서버는 반드시(MUST)
 *       {@code 400 Bad Request} 로 응답해야 한다.</li>
 * </ul>
 *
 * <p>지원 버전 목록은 SDK 가 아는 버전 전부다({@link ProtocolVersions}). 이 서버가
 * 실제로 협상하는 버전은 {@code 2025-11-25} 하나뿐이지만, 헤더 검증은 "이 서버가 이해할
 * 수 있는 프로토콜 버전인가"를 묻는 것이지 "이 서버가 지금 이 세션에서 쓰기로 한 버전과
 * 같은가"를 묻는 것이 아니므로 SDK 가 정의한 전체 버전 목록을 기준으로 삼는다.
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

	@Override
	public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
			throws IOException, ServletException {
		HttpServletRequest request = (HttpServletRequest) servletRequest;
		HttpServletResponse response = (HttpServletResponse) servletResponse;

		String version = request.getHeader(HEADER_NAME);
		if (version != null && !SUPPORTED_VERSIONS.contains(version)) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			response.setContentType("application/json");
			// 기존 SDK 오류 응답과 마찬가지로 id 없는 JSON-RPC 오류 형태를 쓴다
			// (명세: "The HTTP response body MAY comprise a JSON-RPC error response
			// that has no id").
			response.getWriter().write("""
					{"jsonrpc":"2.0","id":null,"error":{"code":-32600,\
					"message":"Unsupported MCP-Protocol-Version: %s"}}""".formatted(version));
			return;
		}
		chain.doFilter(servletRequest, servletResponse);
	}
}
