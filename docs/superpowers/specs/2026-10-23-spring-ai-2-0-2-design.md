# 기존 practice를 Spring AI 2.0.2(MCP SDK 2.1.0)로 올리기 — 설계

## 목표

Spring AI를 쓰는 MCP 인가 practice 7개를 Spring AI 2.0.2(MCP Java SDK 2.1.0)로 올린다.
올리면서 SDK 2.1.0과 Spring AI 2.0.2가 새로 준 기능으로 직접 만든 우회 코드를 줄이고, 미뤄 둔 두 가지(session 상한·만료, 오류 본문의 stack trace)를 끝낸다.
안내서는 바뀐 동작을 지금 버전 기준으로 다시 설명한다.

**출발점(2026-10-23):** Spring AI 2.0.2가 2026-10-22 Maven Central에 나왔다(GitHub release 글은 아직 없다).
2.0.2는 Spring Boot 4.1.2 기준이고 MCP SDK 2.1.0을 끌어온다.
같은 날 spike(authz, tool-visibility, community·mcp-2026-07-28 agent를 scratchpad 사본으로 올려 봄)로 막히는 곳이 없음을 확인했다.
spike 보고서는 세션 scratchpad의 `spike-sdk21/spike-{a,b,c}-report.md`에 있다(버린 코드).

## 결정

| # | 항목 | 결정 | 누가 |
|---|---|---|---|
| A | 대상 | `mcp-security-authn-official`, `-chat-memory`, `-community`, `mcp-security-authz`, `mcp-stateless-handle`, `mcp-tool-visibility`, `mcp-cimd`의 모든 Spring module과 local-client 4개. `mcp-2026-07-28`은 agent의 Spring AI와 세 module의 Boot만 올린다(서버의 SDK 2.2.0-M1은 그대로) | 사용자(추천 수용) |
| B | 버전 | Spring Boot 4.1.2(모든 Spring module, auth-server 포함), Spring AI 2.0.2, local-client는 `io.modelcontextprotocol.sdk:mcp:2.1.0`. stateless·visibility·cimd의 `mcp-bom:2.0.1` override는 지운다(2.0.2가 2.1.0을 가져온다). community module은 0.1.14 그대로(새 버전이 없다) | 사용자(추천 수용) |
| C | deprecated API | `ServerTransportSecurityValidator` → `ServerHttpHeaderValidator`(practice가 `HeaderAccessor`를 직접 만든다. SDK의 servlet용은 package-private). `httpRequestCustomizer(...)` → `addHttpRequestCustomizer(...)`(옛 method는 다른 customizer를 모두 지운다) | 사용자(추천 수용) |
| D | local-client step-up | SDK 2.1.0은 `authorizationErrorHandler`가 `true`를 돌려주면 customizer를 다시 불러 새 token으로 재시도한다. authz·stateless·visibility local-client의 `StepUpCompletedException`과 `McpCalls.callOnce` 우회를 걷어내고, handler가 새 token을 받은 뒤 `true`를 돌려주게 한다 | 사용자(추천 수용) |
| E | tool 목록 거르기 | tool-visibility·cimd는 `tools/list`를 SDK 2.1.0의 tool filter(`McpSyncListFilter`, `addToolFilter`)로 거르고, Spring AI 2.0.2의 `McpStatelessSyncServerCustomizer` bean으로 등록한다. `ToolVisibilityTransport`는 숨긴 tool의 `tools/call`만 막는 감싸기로 줄인다 | 사용자(추천 수용) |
| F | session 상한·만료 | official·chat-memory·authz의 streamable 서버에 `WebMvcStreamableServerTransportProvider` bean을 직접 두고 `maxSessions`·`sessionIdleTimeout`을 `McpLimitProperties` 값으로 넣는다. community는 넣지 않는다(community module이 transport를 만든다) | 사용자(추천 수용) |
| F2 | 만료 뒤 agent | agent의 MCP tool 호출이 session 없음(`404`)으로 실패하면 같은 호출을 한 번 다시 보내, 만료 뒤 첫 질문도 성공하게 한다. 명세의 "client는 `404`를 받으면 새 session을 시작한다"를 agent가 직접 따른다. 다시 보낼 때의 `initialize`에는 그 사용자의 token이 붙어야 한다 | 사용자 |
| I | 설정 위치 | `max-sessions`·`session-idle-timeout`은 `mcp.limits` 아래에 둔다. authz는 기존 `McpLimitProperties`에 더하고, official·chat-memory는 같은 이름·prefix의 작은 record를 새로 만든다 | Claude |
| J | chat-memory의 만료 | chat-memory에는 이미 `McpSessionBindingFilter`의 1시간 만료가 있다. 둘이 어긋나지 않게 transport의 `sessionIdleTimeout`과 binding filter의 만료를 `mcp.limits.session-idle-timeout` 하나(기본 1시간)로 맞춘다. official·authz의 기본은 30분 | Claude |
| K | local-client 출력 | `[1]`\~`[7]` 단계 출력은 그대로 둔다. `[7]`은 지울 `McpCalls.callOnce` 대신 `StepUp` handler가 `true`를 돌려주기 직전에 찍는다. 그래서 캡처·10장·준수표가 인용하는 번호가 바뀌지 않는다 | Claude |
| L | 준수표 | 명세 Session Management의 세 규칙(서버는 session을 끝낼 수 있다 MAY, 끝난 session의 요청에는 `404` MUST, client는 `404`를 받으면 새 session을 시작한다 MUST)을 새 42번 행으로 둔다. session 상한 자체는 명세 요구가 아니라 practice의 선택이므로 그 행과 8장에 설명으로 적는다 | Claude |
| G | 오류 본문의 stack trace | streamable practice(official·chat-memory·authz, 되면 community)도 stateless practice의 `TransportErrorResponseFilter`처럼 transport의 오류 본문을 `code`·`message`만 남긴 JSON-RPC 오류로 바꾼다 | 사용자(추천 수용) |
| H | 문서 | 바뀐 동작을 쓰는 장·부록·README·캡처를 지금 버전 기준으로 고친다. 버전을 적은 문장은 2.0.2·2.1.0으로 바꾸되, "어느 버전에서 바뀌었다"는 학습 포인트가 되는 곳(10장 재시도, 12장 tool filter)에서만 옛 버전을 함께 적는다 | Claude |

## 1. 개념 (학습 문서가 설명할 것)

- **재시도는 이제 새 token을 붙인다**: SDK 2.0은 권한 오류 뒤 재시도할 때 이미 만든 요청을 다시 보내 옛 token이 갔다. 2.1.0은 재시도마다 요청을 새로 만들어 customizer를 다시 부른다. 그래서 local-client는 handler에서 새 token을 받고 `true`를 돌려주기만 하면 된다(10장).
- **tool filter는 목록만 거른다**: SDK의 tool filter는 `tools/list`에만 적용되고 `tools/call`은 막지 않는다. 숨긴 tool의 scope 검사를 건너뛰는 `ToolScopeFilter`와 함께 쓰면, filter만으로는 손님 token이 쓰기 tool을 실행한다(spike에서 재현). 그래서 호출을 막는 감싸기는 남는다(12장). 숨기는 것과 막는 것은 다른 일이라는 12장의 결론을 SDK가 그대로 따른다는 점이 학습 포인트다.
- **session에도 상한과 만료가 있어야 한다**: streamable 서버는 `initialize`마다 session을 메모리에 만든다. 상한이 없으면 token 하나로 메모리를 채울 수 있다. `maxSessions`를 넘으면 `initialize`가 `503`, `sessionIdleTimeout`이 지나면 그 session의 다음 요청이 `404`다. 상한은 서버 전체 값이라 사용자별 상한은 아니다(8장 또는 6장, 준수표).
- **오류 본문에는 내부 정보를 넣지 않는다**: 깨진 JSON-RPC 본문에 SDK 2.1.0은 500 대신 400 `-32600`으로 답하지만, Spring AI transport는 여전히 Java 예외 객체를 본문에 쓴다. filter로 `code`·`message`만 남긴다(준수표 38번).

## 2. module별 변경

### 공통(7개 practice)

- `build.gradle`: Boot 4.1.2, `springAiVersion` 2.0.2, `mcp-bom:2.0.1` override와 그 주석 삭제.
- `McpTransportSecurityFilter`: `ServerHttpHeaderValidator`와 직접 만든 `HeaderAccessor`. javadoc의 "SDK는 이 검사를 transport 안에서 한다"는 사실과 다르다(Spring AI transport의 기본 validator는 no-op) — 고친다.
- agent의 MCP client 설정(`McpSecurityConfig` 등): `addHttpRequestCustomizer`.
- "Spring AI 2.0.0/2.0.1", "SDK 2.0.x"를 근거로 적은 주석은 2.0.2·2.1.0에서 다시 확인한 값으로 바꾼다.

### streamable 서버(official·chat-memory·authz)

- `WebMvcStreamableServerTransportProvider` bean을 직접 만든다. auto-config가 하던 builder 호출(endpoint, json mapper, context extractor 등)을 그대로 옮기고 `maxSessions`·`sessionIdleTimeout`을 더한다.
- `mcp.limits.max-sessions`(기본 1000)와 `mcp.limits.session-idle-timeout`(기본 30분, chat-memory는 1시간)을 둔다(결정 I·J).
- `TransportErrorResponseFilter`(stateless practice의 것과 같은 역할)를 transport router에 건다.
- 오류 응답이 바뀐 곳: 깨진 `id`/`jsonrpc` → 400 `-32600`, session ID를 붙인 `initialize` → 400, 상한 초과 → 503, 만료된 session → 404.
- **만료 뒤 agent(결정 F2)**: SDK 2.1.0 client는 `404`를 받으면 session을 버리고 뒤에서 다시 `initialize`하지만, 실패한 그 호출은 다시 보내지 않는다(`LifecycleInitializer.handleException`).
  - official·authz agent는 MCP client 하나를 사용자들이 함께 쓰고, token을 요청한 사용자의 `Authentication`에서 꺼낸다. 뒤에서 하는 `initialize`에는 사용자가 없어 token 없이 나가 `401`을 받을 가능성이 크다(spike에서 확인하지 못했다).
  - chat-memory는 사용자별 client라 token이 client 주인에 묶여 있어 뒤의 `initialize`는 성공할 것으로 보인다. 그래도 실패한 호출은 다시 보내지 않으므로 같은 재시도가 필요하다.
  - agent는 MCP tool 호출이 session 없음으로 실패하면 사용자의 요청 context에서 같은 호출을 한 번 다시 보낸다. 다시 보낼 때 `initialize`에 그 사용자의 token이 붙어야 한다.
  - 계획의 첫 확인: SDK가 뒤에서 하는 token 없는 `initialize`가 재시도와 겹쳐 재시도까지 실패하게 만드는지 본다. 가짜 streamable 서버(JDK `HttpServer`: 오래된 session ID에 `404`, `initialize`의 `Authorization`을 기록)로 확인한다.
  - 재시도를 믿을 만하게 만들 수 없으면, 만료 뒤 첫 질문은 tool 오류로 끝나고 다음 질문부터 정상이라는 동작을 문서에 적는 쪽으로 물러선다(사용자가 고른 대안 순서).

### community

- 버전만 올린다. `TransportErrorResponseFilter`는 community module이 만든 transport의 router에 걸 수 있으면 넣고, 아니면 준수표에 남긴다.
- community issue #117(`resource_metadata` 따옴표) 우회는 그대로 둔다(아직 open).

### stateless 서버(stateless-handle·tool-visibility·cimd)

- `MalformedMessageTest`: 깨진 `id`/`jsonrpc`의 기대값을 500 `-32603`에서 400 `-32600`으로 바꾸고 javadoc을 고친다.
- `TransportErrorResponseFilter`는 그대로 둔다(transport가 여전히 stack trace를 쓴다).

### tool-visibility·cimd의 tool 목록

- `McpStatelessSyncServerCustomizer` bean이 `addToolFilter((context, tool) -> !visibility.hidden(subject, tool.name()))`를 등록한다.
- `ToolVisibilityTransport`는 `tools/call`의 숨긴 tool만 SDK의 "모르는 tool"과 같은 응답으로 막는다. `tools/list` 부분은 지운다.
- `ToolScopeFilter`가 숨긴 tool의 scope 검사를 건너뛰는 것과 이 감싸기가 짝이라는 것을 두 클래스의 javadoc에 적는다.
- 목록 로그(`tools/list — 사용자=…, 보인 tool=n/m`)는 filter가 tool마다 불려 사라진다. 필요하면 다른 곳에서 한 번 남긴다(Claude 판단).

### local-client(official·authz·stateless-handle·tool-visibility)

- SDK 2.1.0, `addHttpRequestCustomizer`.
- authz·stateless·visibility: `StepUp` handler는 새 token을 받고 `[7]`을 찍은 뒤 `true`를 돌려준다(결정 K). `StepUpCompletedException`과 `McpCalls.callOnce`를 지우고, 그것을 고정하던 테스트 3개(spike 기준)를 새 흐름으로 고친다. 재시도 성공을 가짜 MCP Server로 확인하는 테스트를 둔다(403 → 새 token으로 두 번째 POST).

### mcp-2026-07-28

- agent: Spring AI 2.0.2(SDK 2.2.0-M1 `mcp-core`·`mcp-json-jackson3`와 classpath 충돌 없음, spike 확인).
- 세 module Boot 4.1.2. 서버의 SDK 2.2.0-M1은 그대로 둔다.

## 3. 보안 규칙

- 숨긴 tool의 `tools/call`은 지금처럼 "모르는 tool"과 같은 응답이다. tool filter만 두고 감싸기를 빼면 인가 우회가 생기므로, 손님 token의 쓰기 호출이 재고를 바꾸지 않는 테스트를 유지한다.
- session 상한은 서버 전체 값이다. 사용자별 상한이 아니라는 것을 문서와 준수표에 적는다.
- 오류 본문에는 `code`·`message`만 나간다. 오류 응답의 status와 code는 SDK가 정한 값을 그대로 둔다.

## 4. 학습 문서

- **10장**: local-client의 step-up이 SDK 재시도로 끝나는 흐름. SDK 2.0에서는 옛 token이 다시 가서 앱이 직접 다시 불렀다는 것을 한 문단으로 남긴다(왜 handler가 `true`만 돌려주면 되는지 보이기 위해).
- **11장·12장**: 깨진 본문의 400, tool filter와 남는 호출 감싸기, 12장 "hook이 없다"는 문장 교체.
- **6장 또는 8장**: session 상한·만료(어느 장이 맞는지는 Claude 판단), Origin/Host 검사가 practice filter 하나뿐인 이유(Spring AI transport의 validator는 기본 no-op).
- **부록**: `reference-api.md`의 오류 표(400 `-32600`, session ID를 붙인 `initialize` 400, 503, 404), `reference-compliance.md` 38번과 새 42번 행(결정 L), 28번(chat-memory의 binding 만료) 문장, 버전 표기.
- **README**: 버전 줄과 바뀐 클래스의 코드 지도.
- **캡처**: 버전을 올린 뒤 각 practice의 캡처 스크립트를 다시 돌려 기존 캡처와 비교한다. 응답이 바뀐 캡처만 같은 파일 이름으로 덮어쓰고(단계 구성이 같을 때), 안내서 인용을 맞춘다.
- 문체와 검사는 writing-practice-docs skill을 따른다.

## 5. 테스트

- 모든 module의 기존 테스트가 통과한다(지금 수: official 32·38·50·36, chat-memory 42·38·77, community 29·41·42, authz 80·42·102·54, stateless 129·44·112·57, visibility 148·44·137·60, cimd 148·195·158, mcp-2026-07-28 115·195·184 — 순서는 shop-mcp-server·auth-server·shop-agent·local-client).
- 새로 둘 테스트:
  - `McpTransportSecurityFilter`가 새 validator로도 Origin 403·Host 421을 낸다(기존 테스트가 그대로 통과하면 된다).
  - streamable 서버: `max-sessions`를 넘은 `initialize`는 503, 만료된 session의 요청은 404, 깨진 `id`/`jsonrpc` 본문은 400 `-32600`이고 본문에 `stackTrace`·class 이름이 없다.
  - streamable agent(official·chat-memory·authz): 가짜 streamable 서버로, 만료(`404`) 뒤 같은 tool 호출을 한 번 다시 보내 성공하고, 다시 보낸 `initialize`에 그 사용자의 token이 붙는다(결정 F2).
  - local-client: 403 뒤 handler가 새 token을 받고 `true`를 돌려주면 SDK가 새 token으로 다시 보내 성공한다.
  - visibility·cimd: 기존 `HiddenToolCallTest`·`ToolListVisibilityTest`·`McpScopeTest`가 그대로 통과한다(감싸기를 빼면 실패하는 회귀 테스트다).

## 6. 확인

- 각 practice를 `run.sh`로 띄워 캡처 스크립트를 돌린다(결정 H, 4절).
- browser 확인은 동작이 바뀐 곳만 한다: authz agent의 step-up과 재시도, visibility agent의 손님·점원 목록. local-client는 캡처 스크립트로 확인한다.

## 다루지 않는 것

- `mcp-2026-07-28` 서버의 SDK 올리기(2.2.0-M2 이후).
- 사용자별 session 상한(filter를 직접 만들어야 한다).
- `agent-mcp`, `agent-mcps`, `chat-memory`처럼 MCP 인가 practice가 아닌 것.
- Spring AI 2.1.0-RC1(Boot 4.2 계열 milestone).
- Claude Code 연결(다음 후보).

## 계획에 넘기는 것

- spike는 authz, tool-visibility, community, mcp-2026-07-28 agent만 했다. official·chat-memory·stateless-handle·cimd는 그 결과로 추정했으므로, 계획의 첫 단계에서 practice마다 버전만 올려 compile·테스트를 먼저 확인한다.
- 결정 F2의 재시도를 어디에 둘지(tool callback을 감싸기, client 호출을 감싸기)는 계획이 정한다.
