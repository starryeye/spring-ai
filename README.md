# spring-ai

Spring AI 학습 저장소. 버전대별로 디렉터리가 나뉜다.

| 디렉터리 | 내용 |
|---|---|
| [`practice/agent-mcp/`](practice/agent-mcp) | Spring AI 2.0 — agent + MCP 최소 예제. 평범한 REST 서비스를 MCP 서버로 감싸고, LLM 에이전트가 MCP 툴로 그것을 호출한다. 3개 독립 Gradle 프로젝트 (`product-service` :8081 / `product-mcp-server` :8082 / `product-agent` :8080) |
| [`legacy-0.8/`](legacy-0.8) | Spring AI 0.8.1 시절 예제 (`introduction`, `prompt`). 2.0 기준으로는 컴파일되지 않는다 — 참고용 |

새로 보는 사람은 `practice/agent-mcp/README.md` 부터 읽으면 된다.

`docs/superpowers/` 에는 `practice/agent-mcp` 의 설계 문서와 구현 계획이 있다.
