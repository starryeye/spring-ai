# spring-ai

Spring AI 학습 저장소. 버전대별로 디렉터리가 나뉜다.

| 디렉터리 | 내용 |
|---|---|
| [`practice/agent-mcp/`](practice/agent-mcp) | Spring AI 2.0 — agent + MCP 최소 예제. 평범한 REST 서비스를 MCP 서버로 감싸고, LLM 에이전트가 MCP 툴로 그것을 호출한다. 3개 독립 Gradle 프로젝트 (`product-service` :8081 / `product-mcp-server` :8082 / `product-agent` :8080) |
| [`practice/agent-mcps/`](practice/agent-mcps) | MCP 서버 여러 개를 한 에이전트에 붙이고, `McpToolFilter` 로 노출 툴을 제어하는 예제. `product-mcp-server` :8091 / `order-mcp-server` :8092 / `shop-agent` :8090 |
| [`practice/mcp-security-authn-community/`](practice/mcp-security-authn-community) | 사용자가 브라우저로 로그인하고 에이전트가 그 사용자를 대신해 보호된 MCP 서버를 호출하는 예제. `org.springaicommunity` MCP 보안 모듈 3종(비공식)으로 구현. `auth-server` :9000 / `shop-mcp-server` :8101 / `shop-agent` :8100 |
| [`practice/mcp-security-authn-official/`](practice/mcp-security-authn-official) | 위와 같은 것을 `org.springaicommunity` 없이 공식 라이브러리(Spring Security + Spring AI + MCP Java SDK)만으로 구현. `auth-server` :9010 / `shop-mcp-server` :8111 / `shop-agent` :8110 |
| [`legacy-0.8/`](legacy-0.8) | Spring AI 0.8.1 시절 예제 (`introduction`, `prompt`). 2.0 기준으로는 컴파일되지 않는다 — 참고용 |

새로 보는 사람은 `practice/agent-mcp/README.md` 부터 읽으면 된다.

`docs/superpowers/` 에는 `practice/agent-mcp` 의 설계 문서와 구현 계획이 있다.
