# MCP 안내서 — official practice로 배우는 MCP와 OAuth

이 안내서는 LLM 앱이 외부 tool을 부르는 규약 MCP와, 원격 MCP Server를 OAuth로 보호하는 MCP authorization을 설명한다.
OAuth의 기본(authorization code grant, access token)은 알지만 MCP와 MCP의 discovery는 처음인 독자를 위해 썼다.
다 읽고 나면 OAuth로 보호한 MCP Server와 MCP client를 직접 만들고 운영할 수 있다.

요청·응답 예시는 대부분 [official practice](../mcp-security-authn-official/README.md)를 실제로 띄워 받은 것이다.
10장의 예시는 official에 scope와 step-up을 더한 [mcp-security-authz practice](../mcp-security-authz/README.md)를 실제로 띄워 받은 것이다.
11장의 예시는 authz의 MCP Server를 session 없이 돌리고 장바구니 tool을 더한 [mcp-stateless-handle practice](../mcp-stateless-handle/README.md)를 실제로 띄워 받은 것이다.
12장의 예시는 stateless에 사용자 역할을 더해 사용자마다 다른 tool 목록을 주는 [mcp-tool-visibility practice](../mcp-tool-visibility/README.md)를 실제로 띄워 받은 것이다.
official 밖의 서버를 가정한 예시는 명세의 예시이거나 설명을 위해 만든 값이다.
3장의 issuer `auth.example.com/tenant1`, 4장에서 `app.example.com`에 올린 client 정보 문서, 8장에서 공격자가 넣는 내부망 주소 `169.254.169.254`가 그런 예다.
장마다 그 단계가 왜 있는지를 먼저 보고, 실제 요청·응답과 official 코드를 본 뒤, 직접 해 본다.
장 끝의 "명세 근거" 표는 본문의 규칙이 MCP 명세와 RFC의 어느 절에서 왔는지를 요구 수준과 함께 보여 준다.

official이라는 이름은 Spring 공식 프로젝트(Spring Security, Spring Authorization Server, Spring AI)와 MCP Java SDK만으로 만들었다는 뜻이다.
spring-ai-community의 module로 같은 흐름을 만든 [community practice](../mcp-security-authn-community/README.md)와 구별하려고 붙였다.
MCP가 정한 표준 구조나 일반적인 agent 구조라는 뜻은 아니다.
예를 들어 official의 agent는 흐름을 단순하게 보이려고 MCP client 하나를 모든 사용자가 같이 쓴다.
MCP 명세가 전제하는 구조에서는 client 하나가 사용자 한 명의 것이고, 그 차이는 [6장](06-mcp-call-and-validation.md)에서 본다.

## 읽는 순서

1장부터 12장까지 차례로 읽는다.
뒤 장은 앞 장에서 본 요청과 용어를 다시 설명하지 않는다.
10장은 5\~7장에서 본 흐름에 scope와 step-up을 더하므로, 7장 다음에 읽어도 된다.
11장은 10장의 scope와 step-up에 session 없는 서버와 handle을 더하므로, 10장 다음에 읽는다.
12장은 10장의 step-up과 11장의 session 없는 서버에 사용자별 tool 목록을 더하므로, 10·11장 다음에 읽는다.

시간이 없으면 2·3·5·6장만 읽는다.
2장에서 전체 흐름을 보고, 3장의 discovery, 5장의 token 발급, 6장의 token 검증으로 흐름의 중심을 따라간다.

부록은 처음부터 읽지 않고, field 하나나 명세 항목 하나를 찾아볼 때 연다.

## 준비물

장마다 있는 "직접 해 보기" 절의 명령은 official practice를 띄운 상태에서 보낸다.
official을 띄우는 방법과 필요한 도구는 [official README의 실행](../mcp-security-authn-official/README.md#실행)에 있다.
10장의 명령은 official 대신 [mcp-security-authz practice](../mcp-security-authz/README.md#실행)를 띄운 상태에서 보낸다.
11장의 명령은 [mcp-stateless-handle practice](../mcp-stateless-handle/README.md#실행)를 띄운 상태에서 보낸다.
12장의 명령은 [mcp-tool-visibility practice](../mcp-tool-visibility/README.md#실행)를 띄운 상태에서 보낸다.

다이어그램은 mermaid로 그렸다.
GitHub와 IntelliJ는 mermaid를 그림으로 보여 준다.
mermaid를 그리지 못하는 viewer에서는 다이어그램 바로 아래의 "다이어그램 그림으로 보기" 링크로 같은 그림(PNG)을 연다.

## 장 목록

| 장 | 한 줄 요약 | 직접 해 보는 것 |
|---|---|---|
| [1. MCP 기초](01-mcp-basics.md) | host 안의 MCP client가 JSON-RPC로 MCP Server의 tool을 부르는 과정, `initialize`부터 `DELETE`까지의 session과 버전 header | agent에 채팅하고 MCP Server 로그의 tool 호출 줄 보기, 캡처 스크립트로 MCP 요청과 응답 보기 |
| [2. MCP와 OAuth](02-why-oauth.md) | 원격 MCP Server가 access token으로 누구의 요청인지 아는 방법, 역할과 전체 흐름, 일반 OAuth와 다른 다섯 가지 | token 없이 `initialize`를 보내 `401` 받기 |
| [3. Discovery](03-discovery.md) | MCP Server 주소 하나에서 `401` → PRM → Authorization Server Metadata 순서로 endpoint를 찾아가는 과정과 client가 확인할 것 | curl 세 번으로 `401`의 `resource_metadata`, PRM, metadata 읽기 |
| [4. Client 등록](04-client-registration.md) | pre-registration·CIMD·DCR의 우선순위, public client의 규칙, credentials를 issuer에 묶는 이유, 미리 등록한 client의 첫 연결 순서 | metadata에서 `none`을 찾고, CIMD·DCR field가 없는 것 보기 |
| [5. Authorization request와 token](05-authorization-and-token.md) | authorization code grant에 PKCE, `resource`, callback의 `state`·`iss` 확인을 더한 흐름과 token request·refresh | `code_verifier`로 `code_challenge` 계산하기, 모르는 `resource`로 `invalid_target` 받기 |
| [6. MCP 호출과 token 검증](06-mcp-call-and-validation.md) | Bearer token을 붙인 MCP 요청, MCP Server의 검사 순서(`Origin`·`Host` → token → 버전 → session), agent가 사용자마다 token을 붙이는 방법 | `403`·`421`·`401 invalid_token` 받기, 검사 순서 보기, JWK Set 읽기 |
| [7. 로컬 MCP client](07-local-client.md) | 사용자 기기의 public client가 기본 browser와 loopback callback으로 전체 흐름을 혼자 밟는 과정 | `local-client`로 login부터 MCP 호출까지 하기, 등록되지 않은 issuer로 discovery에서 멈추기 |
| [8. 보안](08-security.md) | SSRF, code 가로채기, 사칭, mix-up, confused deputy, token passthrough, DNS rebinding, session hijacking과 각 공격을 막는 장치 | agent의 callback에 다른 `iss`를 넣어 `401` 받기 |
| [9. 버전](09-versions.md) | 2025-03-26부터 2026-07-28까지 authorization과 transport가 바뀐 이유, official이 따르는 기준 버전 | `2026-07-28`로 요청해 official이 `2025-11-25`로 답하고 `_meta` 요청을 `400`으로 거절하는 것 보기 |
| [10. scope와 step-up](10-scope-and-step-up.md) | tool별 scope, 조회 scope로 시작해 쓰기 tool을 처음 부를 때 `403 insufficient_scope`를 받아 scope를 늘리는 step-up, web agent와 사용자 기기의 앱이 사용자에게 다시 묻는 방법 | `401`의 `scope`와 PRM의 `scopes_supported` 읽기, 조회 token으로 `403 insufficient_scope` 받기, web agent의 consent 카드와 `local-client`의 step-up 해 보기 |
| [11. stateless와 handle](11-stateless-and-handle.md) | session 없이 요청마다 token으로 사용자를 구별하는 MCP Server, 호출 사이의 상태를 가리키는 handle과 그 handle을 사용자에게 묶는 방법, web agent의 모델과 사용자 기기의 앱이 handle을 다음 호출로 넘기는 방법 | web agent에서 장바구니에 상품을 담고 결제하기, 캡처 스크립트로 session 없는 `initialize`와 GET의 `405`, `user2`가 남의 handle로 받는 결과 보기, `local-client`로 handle을 넘기고 step-up하기 |
| [12. tool 목록과 권한](12-tool-visibility.md) | 사용자의 권한 밖의 tool은 숨기고 권한 안이지만 scope 밖의 tool은 step-up하는 MCP Server, 숨긴 tool의 호출에 없는 tool과 같은 오류로 답하는 이유, web agent가 사용자별 목록을 token마다 따로 cache하는 방법 | web agent에서 손님 `user2`와 점원 `user`로 재고 변경을 요청해 consent 카드가 점원에게만 뜨는 것 보기, 캡처 스크립트로 두 사용자의 `tools/list`와 숨긴 tool·없는 tool의 응답 비교하기, `local-client`로 숨긴 tool 불러 보기 |

## 부록

| 부록 | 내용 |
|---|---|
| [API 레퍼런스](reference-api.md) | endpoint마다 명세가 정한 parameter·header·field를 모두 모은 사전이다. 요구 수준과 official의 동작을 함께 적는다 |
| [명세 준수표](reference-compliance.md) | official·chat-memory·community 세 practice가 명세 항목을 어디까지 지키는지 판정한 표다. 구현 위치 지도, 남은 위반, mcp-security-authz·mcp-stateless-handle·mcp-tool-visibility에서 달라지는 행도 있다 |

## 다른 practice

- [mcp-security-authn-chat-memory](../mcp-security-authn-chat-memory/README.md): official에 사용자별 대화 기억을 더하고, MCP session을 사용자에 묶는다.
- [mcp-security-authn-community](../mcp-security-authn-community/README.md): 같은 흐름을 spring-ai-community의 MCP 보안 module 자동 설정으로 만든다.
- [mcp-security-authz](../mcp-security-authz/README.md): official에 tool별 scope와 step-up을 더한다.
  10장은 이 practice로 scope와 step-up을 설명한다.
- [mcp-stateless-handle](../mcp-stateless-handle/README.md): authz의 MCP Server를 session 없이 돌리고, 장바구니처럼 호출 사이에 남는 상태를 서버가 만든 handle로 주고받는다.
  11장은 이 practice로 stateless와 handle을 설명한다.
- [mcp-tool-visibility](../mcp-tool-visibility/README.md): stateless에 사용자 역할을 더해, 사용자의 권한으로는 쓸 수 없는 tool은 목록에서 숨기고 권한은 있지만 아직 scope를 받지 않은 tool은 step-up하게 한다.
  12장은 이 practice로 사용자별 tool 목록과 그 목록의 cache를 설명한다.
- [agent-mcp](../agent-mcp/README.md)와 [agent-mcps](../agent-mcps/README.md): authorization 없이 MCP Server와 agent만 다룬다.
  1장의 내용을 더 작은 예제로 볼 수 있다.
