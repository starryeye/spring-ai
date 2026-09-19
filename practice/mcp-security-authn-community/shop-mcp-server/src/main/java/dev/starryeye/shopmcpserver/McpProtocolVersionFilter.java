package dev.starryeye.shopmcpserver;

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

    private final JsonMapper jsonMapper;

    /**
     * SDK 가 MCP 트래픽 직렬화에 쓰는 것과 같은 매퍼(자동설정 빈 {@code mcpServerJsonMapper})를
     * 그대로 받는다 — 오류 응답만 다른 규칙으로 직렬화할 이유가 없다.
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
            // getWriter() 는 Content-Type 에 charset 이 없으면 ISO-8859-1 로 쓴다 — 명시한다.
            response.setContentType("application/json;charset=UTF-8");
            // 기존 SDK 오류 응답과 마찬가지로 id 없는 JSON-RPC 오류 형태를 쓴다
            // (명세: "The HTTP response body MAY comprise a JSON-RPC error response
            // that has no id"). 헤더 값을 문자열 포맷팅으로 끼워 넣으면 값에 섞인 따옴표가
            // JSON 구조를 깨고 임의의 최상위 멤버를 주입할 수 있으므로, 트리를 만들어
            // 직렬화한다 — Jackson 이 값 이스케이프를 책임진다.
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
