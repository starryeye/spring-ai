# MCP 안내서 — official practice로 배우는 MCP와 OAuth

이 안내서는 LLM 앱이 외부 tool을 부르는 규약 MCP와, 원격 MCP Server를 OAuth로 보호하는 MCP authorization을 설명한다.
OAuth의 기본(authorization code grant, access token)은 알지만 MCP와 MCP의 discovery는 처음인 독자를 위해 썼다.
다 읽고 나면 OAuth로 보호한 MCP Server와 MCP client를 직접 만들고 운영할 수 있다.

요청·응답 예시는 대부분 [official practice](../mcp-security-authn-official/README.md)를 실제로 띄워 받은 것이다.
official 밖의 서버를 가정한 예시는 명세의 예시이거나 설명을 위해 만든 값이다.
3장의 issuer `auth.example.com/tenant1`, 4장에서 `app.example.com`에 올린 client 정보 문서, 8장에서 공격자가 넣는 내부망 주소 `169.254.169.254`가 그런 예다.
장마다 그 단계가 왜 있는지를 먼저 보고, 실제 요청·응답과 official 코드를 본 뒤, 직접 해 본다.
장 끝의 "명세 근거" 표는 본문의 규칙이 MCP 명세와 RFC의 어느 절에서 왔는지를 요구 수준과 함께 보여 준다.

## 읽는 순서

1장부터 9장까지 차례로 읽는다.
뒤 장은 앞 장에서 본 요청과 용어를 다시 설명하지 않는다.

시간이 없으면 2·3·5·6장만 읽는다.
2장에서 전체 흐름을 보고, 3장의 discovery, 5장의 token 발급, 6장의 token 검증으로 흐름의 중심을 따라간다.

부록은 처음부터 읽지 않고, field 하나나 명세 항목 하나를 찾아볼 때 연다.

## 준비물

장마다 있는 "직접 해 보기" 절의 명령은 official practice를 띄운 상태에서 보낸다.
official을 띄우는 방법과 필요한 도구는 [official README의 실행](../mcp-security-authn-official/README.md#실행)에 있다.

다이어그램은 mermaid로 그렸다.
GitHub와 IntelliJ는 mermaid를 그림으로 보여 준다.
mermaid를 그리지 못하는 viewer에서는 다이어그램 바로 아래의 "다이어그램 그림으로 보기" 링크로 같은 그림(PNG)을 연다.

## 장 목록

| 장 | 한 줄 요약 | 직접 해 보는 것 |
|---|---|---|
| [1. MCP 기초](01-mcp-basics.md) | host 안의 MCP client가 JSON-RPC로 MCP Server의 tool을 부르는 과정, `initialize`부터 `DELETE`까지의 session과 버전 header | agent에 채팅하고 MCP Server 로그의 tool 호출 줄 보기, 캡처 스크립트로 MCP 요청과 응답 보기 |
| [2. MCP와 OAuth](02-why-oauth.md) | 원격 MCP Server가 access token으로 누구의 요청인지 아는 방법, 역할과 전체 흐름, 일반 OAuth와 다른 다섯 가지 | token 없이 `initialize`를 보내 `401` 받기 |
| [3. Discovery](03-discovery.md) | MCP Server 주소 하나에서 `401` → PRM → Authorization Server Metadata 순서로 endpoint를 찾아가는 과정과 client가 확인할 것 | curl 세 번으로 `401`의 `resource_metadata`, PRM, metadata 읽기 |
| [4. Client 등록](04-client-registration.md) | pre-registration·CIMD·DCR의 우선순위, public client의 규칙, credentials를 issuer에 묶는 이유 | metadata에서 `none`을 찾고, CIMD·DCR field가 없는 것 보기 |
| [5. Authorization request와 token](05-authorization-and-token.md) | authorization code grant에 PKCE, `resource`, callback의 `state`·`iss` 확인을 더한 흐름과 token request·refresh | `code_verifier`로 `code_challenge` 계산하기, 모르는 `resource`로 `invalid_target` 받기 |
| [6. MCP 호출과 token 검증](06-mcp-call-and-validation.md) | Bearer token을 붙인 MCP 요청, MCP Server의 검사 순서(`Origin`·`Host` → token → 버전 → session), agent가 사용자마다 token을 붙이는 방법 | `403`·`421`·`401 invalid_token` 받기, 검사 순서 보기, JWK Set 읽기 |
| [7. 로컬 MCP client](07-local-client.md) | 사용자 기기의 public client가 기본 browser와 loopback callback으로 전체 흐름을 혼자 밟는 과정 | `local-client`로 login부터 MCP 호출까지 하기, 등록되지 않은 issuer로 discovery에서 멈추기 |
| [8. 보안](08-security.md) | SSRF, code 가로채기, 사칭, mix-up, confused deputy, token passthrough, DNS rebinding, session hijacking과 각 공격을 막는 장치 | agent의 callback에 다른 `iss`를 넣어 `401` 받기 |
| [9. 버전](09-versions.md) | 2025-03-26부터 2026-07-28까지 authorization과 transport가 바뀐 이유, official이 따르는 기준 버전 | `2026-07-28`로 요청해 official이 `2025-11-25`로 답하고 `_meta` 요청을 `400`으로 거절하는 것 보기 |

## 부록

| 부록 | 내용 |
|---|---|
| [API 레퍼런스](reference-api.md) | endpoint마다 명세가 정한 parameter·header·field를 모두 모은 사전이다. 요구 수준과 official의 동작을 함께 적는다 |
| [명세 준수표](reference-compliance.md) | official·chat-memory·community 세 practice가 명세 항목을 어디까지 지키는지 판정한 표다. 구현 위치 지도와 남은 위반도 있다 |

## 다른 practice

- [mcp-security-authn-chat-memory](../mcp-security-authn-chat-memory/README.md): official에 사용자별 대화 기억을 더하고, MCP session을 사용자에 묶는다.
- [mcp-security-authn-community](../mcp-security-authn-community/README.md): 같은 흐름을 spring-ai-community의 MCP 보안 module 자동 설정으로 만든다.
- [agent-mcp](../agent-mcp/README.md)와 [agent-mcps](../agent-mcps/README.md): authorization 없이 MCP Server와 agent만 다룬다.
  1장의 내용을 더 작은 예제로 볼 수 있다.
