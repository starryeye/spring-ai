# MCP stateless와 handle practice (`mcp-stateless-handle`) — 설계

## 목표

MCP Server를 **session 없이(stateless)** 돌리고, 호출 사이에 남는 상태(장바구니)를 **서버가 만든 handle**로 다루는 practice와 학습 문서를 만든다.
독자는 안내서 1~10장을 읽은 사람이다. 다 보고 나면 session 없는 MCP Server를 만들고, handle을 사용자에게 묶어 안전하게 쓰고, 두 종류의 client에서 handle을 이어 쓸 줄 알게 된다.

최종 목표(2026-09-28 사용자 결정)로 가는 로드맵의 두 번째 단계다.
로드맵: scope와 step-up(완료, PR #6) → **stateless와 handle** → scope별 tool 목록 → CIMD → SDK 2.2(MCP 2026-07-28)로 올리기.

이 practice로 official·authz의 공유 MCP client가 가진 문제(준수표 36번: 종료 `DELETE`에 token 없음, session에 사용자 상태를 두면 섞임)가 구조적으로 사라지는 것을 보인다.

## 결정

| # | 항목 | 결정 | 누가 |
|---|---|---|---|
| A | 시작점 | `mcp-security-authz`를 복사한다. scope·step-up을 이어받아 다음 practice(scope별 tool 목록)로 누적한다 | 사용자 |
| B | 장바구니 tool의 scope | 담기·보기는 기본 scope `products:read`, 주문을 확정하는 `checkout`만 새 scope `orders:write`. 결제 직전에 사람이 확인하는 step-up이 된다 | 사용자 |
| C | agent의 대화 기억 | 사용자별 대화 기억에 tool 호출과 결과까지 저장한다. ChatGPT·Claude처럼 모델이 이전 tool 결과의 handle을 본다 | 사용자 |
| D | 장바구니 상태 | 서버 메모리 저장소 + 불투명 handle, `<sub>:<handle>` key, TTL. 서명한 handle(B안)과 session 저장(C안)은 안내서에서 비교만 한다 | 사용자 |
| E | 버전 | 새 practice만 patch를 올린다: Spring Boot 4.1.1, Spring AI 2.0.1, MCP Java SDK 2.0.1. 기존 practice는 그대로 둔다 | 사용자 |
| F | 표준 기준 | 요청 형식은 2025-11-25 그대로(SDK 2.0.x가 2026-07-28 형식을 모름). 서버는 2025-11-25가 허용하는 session 없는 모드로 돈다. handle은 2026-07-28의 설계 패턴(SEP-2567)과 Security Best Practices의 State Handle Hijacking 규칙을 따른다 | Claude |
| G | tool 이름 | 기존 tool과 같은 camelCase(`createBasket` 등). 안내서에서 SEP-2567의 `create_basket`과 같은 것임을 밝힌다 | Claude |
| H | handle 오류 | 다른 사용자·모르는 handle은 "찾을 수 없다", 본인의 만료·결제 완료 handle은 그 사실을 알린다. 모두 tool 오류(`isError: true`)라 모델이 새 장바구니를 만들어 이어 갈 수 있다 | Claude |

## 1. 개념 (학습 문서가 설명할 것)

**session의 문제**
- 2025-11-25의 session은 서버가 `initialize` 응답에 `Mcp-Session-Id`를 주고, client가 이후 요청마다 돌려보내는 것이다. 명세에서 선택 사항이다.
- official·authz의 agent는 MCP client 하나를 모든 사용자가 함께 쓴다. session도 하나라서, session에 사용자 상태를 두면 사용자끼리 섞인다. 종료 `DELETE`에는 붙일 사용자 token이 없다(준수표 36번).
- session이 있는 서버는 요청이 그 session을 가진 서버로 가야 해서, 여러 대로 늘리기 어렵다.
- 2026-07-28은 session과 `Mcp-Session-Id`를 없앴다(SEP-2567). `initialize` handshake도 없애고 요청마다 `_meta`로 버전을 싣게 했다(SEP-2575). 이 practice는 SDK 사정으로 앞의 것만 먼저 한다.

**handle**
- 상태가 필요한 tool은 서버가 만든 식별자를 돌려주고(`createBasket` → `bsk_…`), 모델이 그것을 다음 tool의 인자로 넘긴다. protocol 개념이 아니라 tool 설계 패턴이다(SEP-2567).
- handle을 가졌다는 것만으로 인증하지 않는다. 서버는 요청 token의 사용자(`sub`)로 key를 만들어(`<sub>:<handle>`) 저장하고 찾는다. handle을 알아낸 다른 사용자는 자기 key로 찾게 되므로 아무것도 얻지 못한다(2026-07-28 Security Best Practices, State Handle Hijacking).
- handle은 무작위 128bit 이상으로 만들고, 수명을 둔다. 수명은 tool 설명에 적는다.
- 만료된 handle은 "invalid argument"가 아니라 "만료되었다"고 알려서, 모델이 새로 만들어 이어 가게 한다(SEP-2567 권고).

**왜 서명한 handle이나 session이 아닌가** (안내서의 비교 절)
- 서명한 handle(내용을 handle에 담아 client가 매번 보냄): 서버 저장소가 필요 없어 보이지만, 결제를 두 번 막으려면 결국 저장소가 필요하고, 취소할 수 없고, 커지고, 내용이 모델에게 그대로 보인다.
- session: 2026-07-28에서는 불가능하고, 공유 client에서는 사용자끼리 섞인다.

**handle을 이어 쓰는 쪽**
- 웹 agent: 모델이 이전 turn의 tool 결과를 봐야 handle을 다시 쓸 수 있다. 그래서 대화 기억에 tool 호출과 결과를 저장한다. Spring AI의 기본 배치(대화 기억이 tool loop 밖)는 사용자·assistant 메시지만 남긴다.
- 사용자 기기의 앱: 한 번 실행하는 동안 코드가 handle을 들고 다닌다.

## 2. 구성

| module | 포트 | package | 비고 |
|---|---|---|---|
| auth-server | 9040 | `dev.starryeye.stateless.authserver` | client `stateless-shop-agent`(confidential), `local-mcp-client`(public). 계정 `user`, `user2`(handle 소유권 시연용) |
| shop-mcp-server | 8151 | `dev.starryeye.stateless.mcpserver` | `protocol: STATELESS` |
| shop-agent | 8150 | `dev.starryeye.stateless.agent` | 사용자별 대화 기억 |
| local-client | — | `dev.starryeye.stateless.localclient` | 장바구니 시나리오 |

위치는 `practice/mcp-stateless-handle`. `run.sh`·`stop.sh`는 authz를 따른다.

## 3. scope와 tool

| tool | 인자 | 하는 일 | scope |
|---|---|---|---|
| `searchProducts`, `getStock` | (authz와 같음) | 조회 | `products:read` |
| `updateStock` | (authz와 같음) | 재고 변경 | `products:write` |
| `createBasket` | 없음 | 새 장바구니를 만들고 handle을 돌려준다. 설명에 "30분 뒤 만료"를 적는다 | `products:read` |
| `addItem` | `basketId`, `productId`, `quantity` | 담는다. 모르는 상품·잘못된 수량은 tool 오류 | `products:read` |
| `getBasket` | `basketId` | 담긴 상품, 수량, 합계, 만료 시각 | `products:read` |
| `checkout` | `basketId` | 재고를 확인해 줄이고 주문 번호를 돌려준다. 장바구니를 닫는다 | `orders:write` |

- auth-server는 두 client에 `orders:write`를 더한다. PRM의 `scopes_supported`와 `401`의 `scope`는 authz처럼 `products:read`만 둔다.
- `orders:write`는 `products:read`를 포함하지 않는다. client는 authz처럼 합쳐서 요청한다.

## 4. 흐름

### 4.1 stateless 서버
- `initialize` 응답에 `Mcp-Session-Id`가 없다. client는 이후 요청에 session header를 보내지 않는다.
- `GET /mcp`는 `405`다. SDK client는 `405`를 받으면 요청·응답 방식으로만 동작한다.
- DELETE 경로가 없고, client는 session이 없으면 종료 `DELETE`를 보내지 않는다.

### 4.2 장바구니
1. `createBasket` → `bsk_…`(text와, 가능하면 `structuredContent`로도).
2. `addItem(bsk_…, p1, 1)`, `addItem(bsk_…, p2, 2)` → `getBasket(bsk_…)`.
3. `checkout(bsk_…)` → 조회 token이면 `403 insufficient_scope, scope="orders:write"` → step-up(합친 scope) → 새 token으로 다시 → 주문 번호. 장바구니는 닫힌다.
4. 같은 handle로 다시 `checkout` → "이미 주문했다". 다른 사용자(`user2`)의 token으로 같은 handle → "찾을 수 없다".

### 4.3 handle 오류

| 경우 | 응답 |
|---|---|
| 모르는 handle, 다른 사용자의 handle | "장바구니 `bsk_…`를 찾을 수 없다. `createBasket`으로 새로 만든다" |
| 본인 handle, 만료 | "장바구니 `bsk_…`는 만료되었다(30분). 새로 만든다" |
| 본인 handle, 결제 완료 | "장바구니 `bsk_…`는 이미 주문했다(주문 번호)" |
| 사용자당 장바구니 수 초과 | "장바구니는 5개까지 만들 수 있다" |

다른 사용자의 handle은 그 사용자 key로 찾으므로 "모르는 handle"과 구별되지 않는다. 그래서 오류를 나눠도 다른 사용자에게 handle의 존재가 드러나지 않는다.

## 5. module별 변경 (authz 대비)

### auth-server
- 포트·issuer·client 이름. 두 client의 scope에 `orders:write`. 두 번째 계정 `user2`.

### shop-mcp-server
- `spring.ai.mcp.server.protocol: STATELESS`.
- `BasketStore`: `ConcurrentHashMap<String, Basket>`, key `<sub>:<handle>`. handle은 `SecureRandom` 128bit를 base64url로 만들고 `bsk_`를 붙인다. `Clock` 주입(만료 테스트). 사용자당 열린 장바구니 5개. 만료·결제된 항목은 30분 더 남겨 두어 "만료"·"이미 주문"을 알려 주고, 그 뒤 지운다.
- `BasketTools`(`@McpTool` 네 개, `@RequiredScope`). `checkout`은 `ProductRepository`의 재고를 원자적으로 줄인다.
- 사용자 식별: stateless transport의 `contextExtractor`로 token의 `sub`·`client_id`를 `McpTransportContext`에 넣고, tool 메서드가 `McpTransportContext` 인자로 받는다. Spring AI 자동 설정에서 extractor를 연결할 수 없으면 `SecurityContextHolder`를 쓴다(stateless WebMVC는 요청 thread에서 tool을 부른다). 어느 쪽인지는 구현 첫 task에서 확인하고 안내서에 이유를 적는다.
- `ToolScopeFilter`·`ScopeChallengeEntryPoint`·PRM·`McpProtocolVersionFilter`·`Origin`/`Host` 검사는 authz 그대로.

### shop-agent (웹 agent)
- 공유 MCP client는 그대로 둔다. session이 없어서 섞일 상태가 없다.
- 대화 기억: `MessageWindowChatMemory`(최근 20개 메시지) + `InMemoryChatMemoryRepository`, conversation ID는 로그인 사용자의 `sub`. `ToolCallingAdvisor.builder().disableInternalConversationHistory()`와, 그 안쪽(order > HIGHEST_PRECEDENCE+300)에 둔 `MessageChatMemoryAdvisor`로 tool 호출·결과까지 저장한다.
- step-up으로 끊긴 turn 되돌리기: `checkout`이 `StepUpRequiredException`으로 끝나면 그 turn에서 기억에 들어간 메시지(사용자 질문, 결과 없는 tool 호출)를 되돌린다. consent 뒤 다시 보낸 질문은 깨끗한 turn으로 시작한다.
- 화면: "새 대화" 버튼(대화 기억 비우기). system prompt: 장바구니가 없으면 `createBasket`, 이전 결과의 `basketId`를 이어 쓰고, "찾을 수 없음·만료"면 새로 만든다.
- 로컬 모델 context: 필요하면 `spring.ai.ollama.chat.options.num-ctx`를 늘린다(실행하며 정함).

### local-client (사용자 기기의 앱)
- 시나리오를 바꾼다: `getStock(p1)` → `createBasket` → `addItem` 두 번 → `getBasket` → `getBasket(모르는 handle)`(오류 보기) → `checkout`(`403 orders:write` → 그 자리에서 step-up → 새 요청으로 다시) → 주문 번호. authz의 `updateStock` 호출은 뺀다(browser가 열리는 횟수를 줄이려고).
- 출력에 handle과 오류 문구가 보이게 한다.

## 6. 학습 문서

- practice README: "`mcp-security-authz`와 다른 점" 중심. 실행, 코드 지도, 직접 확인할 것.
- 안내서 11장 "stateless와 handle": 필요성(공유 client와 session, 2026-07-28, 여러 대로 늘리기) → 시퀀스 다이어그램 → stateless 서버의 실제 요청·응답(header 없음, GET `405`, DELETE 없음) → handle 만들기·넘기기 → 소유권과 만료(`<sub>:<handle>`, 오류 표) → 서명 handle·session을 쓰지 않는 이유 → agent의 대화 기억(tool loop 안의 기억, 끊긴 turn) → local-client → 코드 → 다루지 않는 것 → 직접 해 보기 → 정리 → 명세 근거.
- 함께 고칠 곳: 6.7(session과 사용자)과 9장(버전)에서 11장으로 잇기, 준수표 "`mcp-stateless-handle`에서 달라지는 행"(36번 등), 안내서 목차, 저장소 README, authz README "더 읽을 것", 문서 스킬의 "예시 값" 예외에 11장.
- 캡처: curl walkthrough(header 없음, GET `405`, handle 흐름, `user2`의 handle 거절, `checkout` step-up, 닫힌 handle), local-client 실행.
- 스킬 `writing-practice-docs` 규칙을 따른다.

## 7. 테스트

- MCP Server
  - `BasketStore`: 소유권(다른 `sub`는 못 찾음), 만료(`Clock`), 결제 뒤 닫힘, 사용자당 개수, handle 형식과 엔트로피 길이.
  - 실제 filter chain: `initialize` 응답에 `Mcp-Session-Id` 없음, `GET /mcp` `405`, tool 흐름(`createBasket`→`addItem`→`getBasket`), 다른 사용자 token의 handle → tool 오류, `orders:write` 없는 `checkout` → `403`, 있으면 재고가 준다, 같은 handle 두 번째 `checkout` → "이미 주문".
- agent
  - handle이 담긴 tool 결과가 대화 기억에 남는다(가짜 ChatModel로 실제 advisor chain).
  - 사용자끼리 대화 기억이 섞이지 않는다.
  - step-up으로 끊긴 turn이 기억에서 되돌려진다.
  - authz에서 가져온 token 붙이기가 Spring AI 2.0.1에서도 요청마다 한 번 붙는다.
- local-client: 가짜 MCP Server로 `checkout` `403` → step-up → 새 token으로 재시도, 장바구니 흐름의 handle 전달.

## 8. 구현 전에 확인할 것

- Spring AI 2.0.1 + MCP SDK 2.0.1 조합에서 authz 코드가 그대로 도는지. 특히 "MCP HTTP client request customizer bean 적용"(spring-ai#6716)으로 token 붙이기가 두 번 되지 않는지, "`@McpTool` 예외 처리를 `@Tool`과 맞춤"(spring-ai#6534)으로 tool 오류 모양이 바뀌지 않는지.
- stateless 모드에서 `contextExtractor`를 Spring AI 자동 설정에 연결하는 방법(bean·customizer). 없으면 `SecurityContextHolder`.
- stateless 모드에서 `@McpTool`의 `McpTransportContext` 인자 해석과 `structuredContent` 반환 방법.
- `ToolCallingAdvisor`의 `disableInternalConversationHistory()` + 안쪽 `MessageChatMemoryAdvisor`가 streaming 경로(`.stream()`)에서도 tool 메시지를 저장하는지(spring-ai#6737 수정 포함).
- step-up 예외가 난 turn에서 대화 기억에 무엇이 들어갔는지, 어떻게 되돌릴지(`ChatMemory` API).
- qwen3:8b가 tool 7개와 기억을 context 안에서 다루는지, `num-ctx`.
- SDK client가 GET `405`를 받은 뒤의 동작과 로그.

## 다루지 않는 것

- 2026-07-28의 요청 형식: `initialize` 제거와 `_meta`, `server/discover`, `Mcp-Name`, `subscriptions/listen`, SSE 재개 제거. SDK 2.2 practice에서 한다.
- scope별 tool 목록(다음 practice).
- 장바구니·대화 기억의 영속 저장(Redis·DB)과 서버 여러 대 배포. stateless 서버는 여러 대로 늘리기 쉽지만, 이 practice는 메모리 저장소라 한 대만 된다(안내서에 밝힌다).
- 실제 결제, 주문 취소·조회.
- 기존 practice의 버전 올리기.
