# MCP scope와 step-up practice (`mcp-security-authz`) — 설계

## 목표

MCP client가 **꼭 필요한 scope만 받고, 권한이 필요한 작업을 처음 시도할 때만 늘리는** 흐름을 practice와 학습 문서로 보여 준다.
독자는 안내서 1~9장을 읽은 사람이다. 다 보고 나면 tool마다 scope를 정하고, 서버에서 검사하고, client에서 step-up을 처리할 줄 알게 된다.

최종 목표(2026-09-28 사용자 결정)로 가는 로드맵의 첫 단계다.
로드맵: **scope와 step-up** → stateless와 handle → scope별 tool 목록 → CIMD → SDK 2.2(MCP 2026-07-28)로 올리기.
최종 목표는 가장 많이 쓰이는 두 형태(여러 사용자가 쓰는 웹 agent, 사용자 기기의 앱)를 최신 표준으로 만들고, 서버가 scope에 따라 다른 tool 목록을 주는 것이다.

## 결정

| # | 항목 | 결정 | 누가 |
|---|---|---|---|
| A | 시나리오 | 조회 + 재고 변경. scope `products:read`(조회), `products:write`(변경), 새 tool `updateStock` | 사용자 |
| B | 웹 agent의 step-up 화면 | 대화 안 consent 카드. ChatGPT(Apps SDK의 `mcp/www_authenticate`), Claude.ai(Connect 카드), Copilot Studio(consent card)와 같은 방식 | 사용자 |
| C | consent 뒤 | 멈췄던 질문을 browser가 자동으로 다시 보낸다 | Claude 추천, 사용자 "진행" |
| D | 시작점 | official을 복사한다(계층별 package 그대로). official의 agent는 요청마다 사용자의 authorized client를 찾으므로 step-up 뒤 새 token이 바로 붙는다 | Claude |
| E | tool 목록 | 모두에게 같다. 목록 거르기는 로드맵 3번째 practice에서 한다 | Claude |
| F | scope 계층 | 두지 않는다. `products:write`는 `products:read`를 포함하지 않고, client가 합쳐서 요청한다 | Claude |
| G | consent 화면 | Spring Authorization Server 기본 화면. scope마다 checkbox가 있어 일부만 허락할 수 있고, 전에 허락한 scope는 따로 표시된다 | Claude |
| H | 표준 기준 | transport는 official과 같다(2025-11-25). authorization은 2025-11-25와 2026-07-28 추가분에 2026-07-28의 scope 규칙(합쳐서 요청, 계층 명시, 재시도 제한)을 더한다 | Claude |

## 1. 개념 (학습 문서가 설명할 것)

**agent의 권한 = 사용자가 그 client에 맡긴 범위**
- Claude·Codex 같은 AI client에는 agent 자기 신원이 따로 없다. 사용자가 consent해서 client에 발급된 token 하나로 호출한다.
- 그 token은 사용자의 모든 권한이 아니다. "이 client가 이 사용자 대신 할 수 있는 일"이고, scope가 그 상한이다. token에는 `client_id`가 있어 서버는 어느 client를 거쳤는지 안다.
- 사용자는 웹 화면에서 재고를 바꿀 수 있어도, AI client에는 조회만 맡길 수 있다.

**최소 권한으로 시작해 필요할 때 늘린다 (MCP Security Best Practices — Scope Minimization)**
- 처음에는 위험이 낮은 조회 scope만 받는다.
- 권한이 필요한 작업을 처음 시도할 때, 서버가 그 작업에 필요한 scope만 `403`의 `WWW-Authenticate`로 알린다.
- client는 그 scope를 더해 다시 authorization을 받는다(step-up).
- 흔한 실수: `scopes_supported`에 모든 scope를 싣기, 통째 scope(`*`), 미리 다 받기, challenge마다 scope 목록 전체를 돌려주기.

**step-up consent는 사람이 확인하는 관문이다**
- LLM은 prompt injection에 속을 수 있다. 쓰기 권한을 처음부터 맡겼다면, 속은 호출이 그대로 실행된다.
- 쓰기 권한을 step-up으로 받으면, 그 순간 사용자 앞에 consent 카드가 뜬다. 사용자는 자기가 시키지 않은 일을 알아채고 거절할 수 있다.

**이 practice가 다루지 않는 인접 개념** (문서에 한 줄씩 적는다)
- agent 자기 신원: MCP ext-auth의 client credentials 확장(Draft), 조직 정책이 대신 승인하는 Enterprise-Managed Authorization(SEP-990, ID-JAG).
- 위임 사슬에서 권한 줄이기: MCP Server가 뒤쪽 API를 부를 때 RFC 8693 token exchange로 좁은 token을 받고 `act`에 대리자를 적는다. MCP 명세 본문은 token passthrough 금지까지만 정한다.
- tool 정의에 필요한 scope를 적는 표준 field는 없다. OpenAI Apps SDK의 `securitySchemes`를 표준으로 넣자는 SEP-1488은 Draft다.

## 2. 구성

`practice/mcp-security-authz/` — official을 복사한 네 module.

| module | 포트 | package |
|---|---|---|
| `auth-server` | 9030 | `dev.starryeye.authz.authserver` |
| `shop-mcp-server` | 8141 (`/mcp`) | `dev.starryeye.authz.mcpserver` |
| `shop-agent` | 8140 | `dev.starryeye.authz.agent` |
| `local-client` | loopback 임시 포트 | `dev.starryeye.authz.localclient` |

- `client_id`: `authz-shop-agent`(confidential), `local-mcp-client`(public, redirect `http://127.0.0.1:8123/callback`).
- cookie 이름은 official과 겹치지 않게 `AUTHZAUTHSESSIONID`, `AUTHZAGENTSESSIONID`.
- `run.sh`·`stop.sh`는 official 것을 포트만 바꿔 쓴다.
- 계정: `user`/`password`.

## 3. scope와 tool

| scope | 뜻 | tool |
|---|---|---|
| `products:read` | 상품과 재고를 본다 | `searchProducts`, `getStock`, 그리고 `tools/list` 같은 모든 MCP 요청의 기본 scope |
| `products:write` | 재고를 바꾼다 | `updateStock(productId, quantity)` — 재고를 `quantity`로 바꾸고 바뀐 값을 돌려준다 |

- tool에 필요한 scope는 tool 메서드 옆에 annotation(`@RequiredScope("products:write")`)으로 적는다. 서버는 기동할 때 이를 모아 tool 이름 → scope 표를 만든다.
- `offline_access`는 challenge와 `scopes_supported`에 넣지 않는다(2026-07-28).

## 4. 흐름

### 4.1 첫 authorization — 최소 scope

1. client가 token 없이 `/mcp`를 부르면 `401`과 `WWW-Authenticate: Bearer resource_metadata="…", scope="products:read"`를 받는다.
2. PRM의 `scopes_supported`는 `["products:read"]`만 싣는다(쓰기 scope는 알리지 않는다).
3. client는 scope 고르는 순서를 따른다: `401`의 `scope` → PRM의 `scopes_supported` → 생략.
   - `local-client`: `products:read`를 요청한다. 지금 official의 고정값 `openid profile`을 없앤다.
   - agent: login에 필요한 `openid`에 `products:read`를 더해 요청한다.
4. token에는 `scope: "products:read"`(agent는 `openid products:read`)가 들어간다.

### 4.2 쓰기 시도 → `403` → step-up

1. 사용자가 "p1 재고를 10개로 바꿔 줘"라고 하면 LLM이 `updateStock`을 고른다.
2. MCP Server는 `tools/call`의 tool 이름으로 필요한 scope를 찾고, token에 없으면 `403`으로 답한다.
   `WWW-Authenticate: Bearer error="insufficient_scope", scope="products:write", resource_metadata="…"`
   challenge에는 이 요청에 필요한 scope만 넣는다(목록 전체를 넣지 않는다).
3. client는 이미 받은 scope와 합쳐 `products:read products:write`(agent는 `openid`도)로 authorization을 다시 받는다.
4. consent 화면에는 새 scope `products:write`만 선택 항목으로 나오고, `products:read`는 "이미 허락함"으로 표시된다(confidential agent). public client는 매번 전체를 묻는다(official과 같은 `PublicClientConsentService`).
5. 새 token으로 같은 호출을 한 번 다시 한다.

### 4.3 거절과 일부 허락 — 되풀이하지 않기

- 사용자가 consent를 취소하면(`access_denied`), 또는 `products:write`를 체크하지 않아 새 token에 없으면, 그 scope는 "거절됨"으로 기록한다.
- 거절된 scope에 다시 `403`을 받으면 step-up을 다시 시작하지 않는다. 사용자가 명시적으로 다시 요청할 때만 연다.
- 한 번의 요청에 step-up은 최대 한 번이다(재시도 제한).

## 5. module별 변경 (official 대비)

### auth-server
- 두 client에 `products:read`, `products:write`를 등록한다. agent는 `openid`도.
- agent(`authz-shop-agent`)에도 `require-authorization-consent: true`를 켠다. official은 `false`라 consent 화면이 없고, 그러면 step-up의 추가분 consent도 보이지 않는다.
- access token에 `client_id` claim을 넣는다(RFC 9068 §2.2). official의 token은 `aud`를 resource로 바꾸면서 client를 가리키는 값이 없다. 1절의 "서버는 어느 client를 거쳤는지 안다"가 이 claim으로 성립한다.
- 나머지(`resource`/`aud`, `iss`, PKCE, public client 규칙)는 official과 같다.

### shop-mcp-server
- **scope 표**: `@RequiredScope`를 모은 `ToolScopeRegistry`.
- **scope 검사 filter**(`filter/ToolScopeFilter`): token 검증 뒤, transport 앞.
  - 모든 MCP 요청: `products:read`가 없으면 `403`.
  - `tools/call`: 요청 본문의 JSON-RPC `params.name`으로 tool의 scope를 찾아 없으면 `403`.
  - 본문을 한 번 읽어 두고 transport가 다시 읽을 수 있게 감싼다.
  - 2026-07-28에서는 `Mcp-Name` header로 본문 없이 tool 이름을 알 수 있다는 점을 주석과 문서에 적는다.
- **`403` 응답**: `WWW-Authenticate`에 `error="insufficient_scope"`, 필요한 `scope`, `resource_metadata`를 넣는다. 본문은 비운다.
- **`401`과 PRM**: `401` challenge에 `scope="products:read"`, PRM에 `scopes_supported: ["products:read"]`.
- **권한 상승 로그**: `403`마다 `sub`, `client_id`, tool, 필요한 scope, 가진 scope를 남긴다.
- `ProductTools`에 `updateStock`, `ProductRepository`에 재고 변경을 더한다.

### shop-agent (웹 agent)
- **scope 고르기**: discovery가 `401`의 `scope`와 PRM의 `scopes_supported`를 읽어 `DiscoveredAuthorization`에 둔다. login 요청의 scope는 `openid` + 고른 scope.
- **`403` 감지**: MCP 호출의 `403 insufficient_scope`를 잡아 필요한 scope를 꺼낸다. 이 호출은 LLM에게 tool 결과로 넘기지 않고, 채팅 응답을 멈춘다.
- **consent 카드**: 채팅 stream에 카드 event를 보낸다.
  - 카드 내용: 필요한 scope와 그 뜻, 이 권한이 필요한 tool, [권한 허용] 버튼.
  - 채팅 응답 형식을 event를 구분할 수 있는 형식(SSE event 또는 NDJSON)으로 바꾼다.
- **step-up authorization request**: [권한 허용]은 `/oauth2/authorization/authz-shop-agent?step_up=products:write`로 간다. 이 요청의 scope는 지금 가진 scope ∪ 요청 scope(`OAuth2AuthorizationRequestResolver` 확장).
- **돌아온 뒤**:
  - login이 끝나면 채팅 화면으로 돌아온다.
  - browser는 redirect 전에 `sessionStorage`에 넣어 둔 질문을 한 번 자동으로 다시 보낸다.
  - 새 token에 요청 scope가 없거나 사용자가 취소했으면, 그 scope를 HTTP session에 "거절됨"으로 기록한다.
  - 거절된 scope에 다시 `403`이 오면 카드 대신 안내 문장과 "다시 요청" 링크를 보여 주고, 자동 재전송은 하지 않는다.
- **token 붙이기**는 official과 같다. 요청마다 authorized client를 찾으므로 step-up 뒤에는 새 token이 붙는다.

### local-client (사용자 기기의 앱)
- **scope 고르기**: `401`의 `scope` → PRM의 `scopes_supported` → 생략 순서를 따른다. 고정값 `openid profile`을 없앤다.
- **token 갱신**: 호출에 쓸 token을 교체할 수 있게 한다. 지금은 transport 기본 요청에 한 번 넣는 방식이라, 요청마다 현재 token을 읽는 방식으로 바꾼다.
- **실행 순서**: `tools/list` → `getStock(p1)` → `updateStock(p1, 10)`.
  - `updateStock`이 `403`을 받으면, 합친 scope로 browser authorization을 한 번 더 하고 다시 호출한다.
  - 새 token에도 scope가 없으면 `실패: products:write 권한을 받지 못했다`를 찍고 끝난다.
- `--no-browser`와 캡처 방식은 official과 같다.

## 6. 학습 문서

**안내서**
- 새 장: `practice/mcp-guide/10-scope-and-step-up.md` — "scope와 step-up".
  - 틀: 필요성 → 시퀀스 다이어그램 → 단계별 실제 요청·응답 → 확인하는 것 → 코드 → 직접 해 보기 → 정리 → 명세 근거.
  - 1절의 개념 세 가지와 인접 개념을 담는다.
  - 예시는 이 practice를 띄워 받은 값이다.
  - 6장 다음에 읽어도 된다고 README 읽는 순서에 적는다.
- 5장(scope 고르기)·6장(step-up) 개념 문단, 7장 7.10, 8장 8.11, 안내서 README의 장 목록에서 10장으로 링크한다.
- 부록:
  - `reference-api.md`: PRM `scopes_supported`, `401`의 `scope`, `403 insufficient_scope`에 이 practice의 동작을 더한다.
  - `reference-compliance.md`: "authz practice에서 달라지는 행" 표를 따로 두어 16·37번 등을 적는다. 세 practice 비교 표는 그대로 둔다.

**practice README** (`practice/mcp-security-authz/README.md`)
- chat-memory처럼 "official과 다른 점"을 먼저 쓴다.
- 그 뒤 실행, 코드 지도(클래스 → 10장 절), 직접 확인할 것(step-up, 일부 허락, 거절)을 둔다.

**캡처**: `docs/superpowers/captures/`에 curl 스크립트와 출력을 남긴다.
- curl로 보는 것: `401`의 scope, PRM, `products:read` token, `updateStock`의 `403`, 합친 scope의 step-up, 추가분만 묻는 consent, 다시 호출해 `200`, 일부 허락 뒤 `403`
- `local-client` 실행 출력

문체와 검사는 저장소 스킬 `writing-practice-docs`를 따른다. 검사기 위반 0, 다이어그램 PNG 최신.

## 7. 테스트

- auth-server: 등록된 scope, 일부 허락 시 subset token, confidential client의 추가분 consent, public client의 매번 consent.
- shop-mcp-server:
  - `401`의 `scope`, PRM의 `scopes_supported`.
  - `products:read` token으로 조회 `200`, `updateStock` `403`(header의 세 값).
  - 두 scope token으로 `updateStock` `200`.
  - scope 없는 token의 `tools/list` `403`.
  - 본문을 filter가 읽은 뒤에도 transport가 정상 처리하는지.
- shop-agent: scope 고르기, `403` 감지와 카드 event, 합친 scope의 authorization request, 거절 기록과 되풀이 방지, 재시도 한 번.
- local-client: scope 고르기 순서, step-up 뒤 한 번 재시도, 거절 시 멈춤.
- 실제 실행: 네 module을 띄워 캡처한다.

## 8. 구현 전에 확인할 것

- Spring Security 7.1의 PRM 설정으로 `scopes_supported`를 넣는 방법과, `401` challenge에 `scope`를 넣는 방법. 기본 entry point가 못 하면 직접 만든다.
- agent에서 MCP 호출의 `403`이 어디로 오는지:
  - SDK의 `McpHttpClientTransportAuthorizationErrorHandler`
  - Spring AI의 tool 실행 예외 처리(`ToolExecutionExceptionProcessor`)
  - 둘 중 어느 것으로 LLM 흐름을 멈추고 채팅 응답까지 알릴지 정한다.
- `oauth2Login`으로 다시 login할 때 같은 사용자의 authorized client가 새 token(합친 scope)으로 바뀌는지, 그리고 refresh가 그 scope를 유지하는지.
- 요청 본문을 읽는 filter가 Spring AI transport와 충돌하지 않는지(본문을 두 번 읽는 문제).
- 일부 허락 시 Spring Authorization Server가 준 scope가 token의 `scope`와 token 응답의 `scope`에 그대로 나오는지.

## 다루지 않는 것

- 사용자별 tool 목록(로드맵 3번째), stateless와 handle(2번째), CIMD(4번째).
- agent 자기 신원(client credentials, Enterprise-Managed Authorization), 위임 사슬의 token exchange.
- scope 계층, 사용자 역할에 따른 scope 제한, scope별 설명이 있는 custom consent 화면.
- official·chat-memory·community 코드 변경.
