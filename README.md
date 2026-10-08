# spring-ai

Spring AI 학습 저장소다. 버전대별로 디렉터리가 나뉘고, practice 안의 module은 각각 독립된 Gradle 프로젝트다.

| 디렉터리 | 내용 |
|---|---|
| [`practice/agent-mcp/`](practice/agent-mcp) | Spring AI 2.0으로 만든 agent와 MCP의 최소 예제다. 평범한 REST 서비스를 MCP Server로 감싸고, LLM agent가 그 서비스를 MCP tool로 부른다. 구성: `product-service` :8081 / `product-mcp-server` :8082 / `product-agent` :8080 |
| [`practice/agent-mcps/`](practice/agent-mcps) | MCP Server 여러 개를 agent 하나에 붙이고, `McpToolFilter`로 agent에 보이는 tool을 고르는 예제다. 구성: `product-mcp-server` :8091 / `order-mcp-server` :8092 / `shop-agent` :8090 |
| [`practice/mcp-security-authn-community/`](practice/mcp-security-authn-community) | 사용자가 browser로 login하고, agent가 그 사용자를 대신해 보호된 MCP Server를 부르는 예제다. 비공식 `org.springaicommunity` MCP 보안 module 세 개로 만든다. 구성: `auth-server` :9000 / `shop-mcp-server` :8101 / `shop-agent` :8100 |
| [`practice/mcp-security-authn-official/`](practice/mcp-security-authn-official) | 위와 같은 것을 `org.springaicommunity` 없이 공식 library(Spring Security, Spring AI, MCP Java SDK)만으로 만든다. 구성: `auth-server` :9010 / `shop-mcp-server` :8111 / `shop-agent` :8110 / `local-client`(명령줄 public client) |
| [`practice/chat-memory/`](practice/chat-memory) | `ChatMemory`, `MessageChatMemoryAdvisor`, `conversationId`로 대화를 기억하는 예제다. MCP와 인증 없이 대화 기억만 다루고, 사용자는 한 명이라고 가정한다. 구성: `memory-agent` :8120 |
| [`practice/mcp-security-authn-chat-memory/`](practice/mcp-security-authn-chat-memory) | 위 둘을 합쳐 사용자별로 대화를 기억하는 예제다. `conversationId`를 client가 보낸 값이 아니라 `Authentication`에서 만들어, 사용자끼리 대화가 섞이지 않게 한다. 구성: `auth-server` :9020 / `shop-mcp-server` :8131 / `shop-agent` :8130 |
| [`practice/mcp-security-authz/`](practice/mcp-security-authz) | `mcp-security-authn-official`에 tool별 scope와 step-up을 더한 예제다. client는 조회 scope로 시작하고, 재고를 바꾸는 tool을 처음 부를 때 `403 insufficient_scope`를 받으면 사용자의 consent를 다시 받아 scope를 늘린다. 구성: `auth-server` :9030 / `shop-mcp-server` :8141 / `shop-agent` :8140 / `local-client`(명령줄 public client) |
| [`practice/mcp-stateless-handle/`](practice/mcp-stateless-handle) | `mcp-security-authz`의 MCP Server를 session 없이(stateless) 돌리는 예제다. 장바구니처럼 tool 호출 사이에 남는 상태는 MCP Server가 만든 handle로 주고받고, MCP Server는 그 handle이 요청한 사용자의 것인지 token으로 확인한다. 구성: `auth-server` :9040 / `shop-mcp-server` :8151 / `shop-agent` :8150 / `local-client`(명령줄 public client) |
| [`practice/mcp-tool-visibility/`](practice/mcp-tool-visibility) | `mcp-stateless-handle`에 사용자 역할을 더해, MCP Server가 사용자마다 다른 tool 목록을 주는 예제다. 사용자의 권한으로는 쓸 수 없는 tool은 목록에서 숨기고, 권한은 있지만 아직 scope를 받지 않은 tool은 보여 준 뒤 부를 때 step-up한다. 구성: `auth-server` :9050 / `shop-mcp-server` :8161 / `shop-agent` :8160 / `local-client`(명령줄 public client) |
| [`practice/mcp-cimd/`](practice/mcp-cimd) | `mcp-tool-visibility`에 CIMD를 더해, 미리 등록하지 않은 client가 `https` 문서의 주소를 `client_id`로 쓰는 예제다. agent는 ChatGPT형(`private_key_jwt`)이나 Claude형(`none`)으로 붙는다. 구성: `auth-server` :9060 / `shop-mcp-server` :8171 / `shop-agent` :8170(문서 host :8172) |
| [`legacy-0.8/`](legacy-0.8) | Spring AI 0.8.1 시절의 예제(`introduction`, `prompt`)다. Spring AI 2.0에서는 컴파일되지 않아 참고용으로만 둔다. |

처음 보는 사람은 `practice/agent-mcp/README.md`부터 읽으면 된다.

- [MCP 안내서](practice/mcp-guide/README.md) — MCP와 MCP authorization(OAuth)을 official practice로 설명한다. 읽는 순서와 장 목록이 있다

`docs/superpowers/`에는 practice마다 만든 설계 문서(`specs/`)와 구현 계획(`plans/`), 안내서가 인용하는 실행 캡처(`captures/`)가 있다.
