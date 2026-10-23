# MCP 안내서 — official practice로 배우는 MCP와 OAuth

이 안내서는 LLM 앱이 외부 tool을 부르는 규약 MCP와, 원격 MCP Server를 OAuth로 보호하는 MCP authorization을 설명한다.
OAuth의 기본(authorization code grant, access token)은 알지만 MCP와 MCP의 discovery는 처음인 독자를 위해 썼다.
다 읽고 나면 OAuth로 보호한 MCP Server와 MCP client를 직접 만들고 운영할 수 있다.

장마다 그 단계가 왜 있는지를 먼저 보고, practice를 실제로 띄워 받은 요청·응답과 코드를 본 뒤, 직접 해 본다.
장 끝의 "명세 근거" 표는 본문의 규칙이 MCP 명세와 RFC의 어느 절에서 왔는지를 요구 수준과 함께 보여 준다.
다이어그램은 mermaid로 그렸고, GitHub와 IntelliJ는 이를 그림으로 보여 준다.
mermaid를 그리지 못하는 viewer에서는 다이어그램 바로 아래의 "다이어그램 그림으로 보기" 링크로 같은 그림(PNG)을 연다.

## 읽는 순서

- 1장부터 14장까지 차례로 읽는다.
  뒤 장은 앞 장에서 본 요청과 용어를 다시 설명하지 않는다.
- 시간이 없으면 2·3·5·6장만 읽는다.
  전체 흐름(2장), discovery(3장), token 발급(5장), token 검증(6장)이 흐름의 중심이다.
- 10장은 5\~7장의 흐름에 scope와 step-up을 더하므로, 7장 다음에 읽어도 된다.
- 11\~14장은 앞 장의 practice를 이어받으므로 10장 다음에 차례로 읽는다.
- 부록은 처음부터 읽지 않고, field 하나나 명세 항목 하나를 찾아볼 때 연다.

## 실행

각 장의 요청·응답 예시는 아래 표의 practice를 실제로 띄워 받은 값이다.
"직접 해 보기" 절의 명령도 그 practice를 띄운 상태에서 보낸다.
띄우는 방법과 필요한 도구는 각 practice README의 "실행" 절에 있다.

| 장 | practice | 더하는 것 |
|---|---|---|
| 1\~9 | [official](../mcp-security-authn-official/README.md) ([실행](../mcp-security-authn-official/README.md#실행)) | 기준 practice |
| 10 | [mcp-security-authz](../mcp-security-authz/README.md) ([실행](../mcp-security-authz/README.md#실행)) | tool별 scope와 step-up |
| 11 | [mcp-stateless-handle](../mcp-stateless-handle/README.md) ([실행](../mcp-stateless-handle/README.md#실행)) | session 없는 MCP Server와 장바구니 handle |
| 12 | [mcp-tool-visibility](../mcp-tool-visibility/README.md) ([실행](../mcp-tool-visibility/README.md#실행)) | 사용자 역할에 따라 다른 tool 목록 |
| 13 | [mcp-cimd](../mcp-cimd/README.md) ([실행](../mcp-cimd/README.md#실행)) | 미리 등록하지 않은 client를 받는 CIMD |
| 14 | [mcp-2026-07-28](../mcp-2026-07-28/README.md) ([실행](../mcp-2026-07-28/README.md#실행)) | MCP 요청 형식 2026-07-28 |

10장의 practice는 official에, 11\~14장의 practice는 바로 앞 장의 practice에 표의 것을 더한다.

## 장 목록

| 장 | 다루는 것 | 직접 해 보는 것 |
|---|---|---|
| [1. MCP 기초](01-mcp-basics.md) | JSON-RPC로 tool 부르기, session과 버전 header | agent와 채팅하고 MCP 요청·응답 보기 |
| [2. MCP와 OAuth](02-why-oauth.md) | 역할과 전체 흐름, 일반 OAuth와 다른 점 | token 없이 보내 `401` 받기 |
| [3. Discovery](03-discovery.md) | `401` → PRM → metadata로 endpoint 찾기 | curl 세 번으로 따라가기 |
| [4. Client 등록](04-client-registration.md) | pre-registration·CIMD·DCR, public client의 규칙 | metadata에서 등록 방식 보기 |
| [5. Authorization request와 token](05-authorization-and-token.md) | PKCE, `resource`, `state`·`iss` 확인, refresh | `code_challenge` 계산, `invalid_target` 받기 |
| [6. MCP 호출과 token 검증](06-mcp-call-and-validation.md) | MCP Server의 검사 순서, agent가 token을 붙이는 방법, session 만료와 상한 | `403`·`421`·`401` 받기 |
| [7. 로컬 MCP client](07-local-client.md) | 사용자 기기의 public client와 loopback callback | `local-client`로 login부터 호출까지 |
| [8. 보안](08-security.md) | MCP authorization을 노리는 공격과 막는 장치 | 다른 `iss`로 `401` 받기 |
| [9. 버전](09-versions.md) | 2025-03-26부터 2026-07-28까지 바뀐 것 | `2026-07-28` 요청에 대한 답 보기 |
| [10. scope와 step-up](10-scope-and-step-up.md) | tool별 scope, `403 insufficient_scope`와 step-up | 조회 token으로 쓰기 tool 부르기 |
| [11. stateless와 handle](11-stateless-and-handle.md) | session 없는 서버, 사용자에 묶은 handle | 장바구니 결제, 남의 handle 써 보기 |
| [12. tool 목록과 권한](12-tool-visibility.md) | 사용자마다 다른 tool 목록과 그 cache | 손님과 점원의 `tools/list` 비교 |
| [13. CIMD](13-cimd.md) | 문서 주소를 `client_id`로 쓰는 등록, refresh rotation | 두 client type으로 login하기 |
| [14. MCP 2026-07-28](14-modern-protocol.md) | `initialize` 없는 요청, header 검사와 scope | header와 본문이 다른 요청의 `400` 보기 |

## 부록

| 부록 | 내용 |
|---|---|
| [API 레퍼런스](reference-api.md) | endpoint마다 명세가 정한 parameter·header·field를 모은 사전이다 |
| [명세 준수표](reference-compliance.md) | practice마다 명세 항목을 어디까지 지키는지 판정한 표다. Authorization Server 점검표도 있다 |

## official과 예시 값

official이라는 이름은 Spring 공식 프로젝트(Spring Security, Spring Authorization Server, Spring AI)와 MCP Java SDK만으로 만들었다는 뜻이다.
spring-ai-community의 module로 같은 흐름을 만든 [community practice](../mcp-security-authn-community/README.md)와 구별하려고 붙였다.
MCP가 정한 표준 구조나 일반적인 agent 구조라는 뜻은 아니다.
예를 들어 official의 agent는 흐름을 단순하게 보이려고 MCP client 하나를 모든 사용자가 같이 쓴다.
MCP 명세가 전제하는 구조에서는 client 하나가 사용자 한 명의 것이고, 그 차이는 [6장](06-mcp-call-and-validation.md)에서 본다.

practice 밖의 서버를 가정한 예시는 명세의 예시이거나 설명을 위해 만든 값이다.
3장의 issuer `auth.example.com/tenant1`, 4장에서 `app.example.com`에 올린 client 정보 문서, 8장에서 공격자가 넣는 내부망 주소 `169.254.169.254`가 그런 예다.

## 다른 practice

- [mcp-security-authn-chat-memory](../mcp-security-authn-chat-memory/README.md): official에 사용자별 대화 기억을 더하고, MCP session을 사용자에 묶는다.
- [mcp-security-authn-community](../mcp-security-authn-community/README.md): 같은 흐름을 spring-ai-community의 MCP 보안 module 자동 설정으로 만든다.
- [agent-mcp](../agent-mcp/README.md)와 [agent-mcps](../agent-mcps/README.md): authorization 없이 MCP Server와 agent만 다룬다.
  1장의 내용을 더 작은 예제로 볼 수 있다.
