# mcp-security-authn-chat-memory

[`mcp-security-authn-official`](../mcp-security-authn-official) 의 authorization 흐름에 대화 기억을 얹고, 대화와 MCP session 을 사용자마다 나눈다.

> 인증이 포함된 MCP 표준 자체를 배우려면 [MCP-AUTHORIZATION.md](../MCP-AUTHORIZATION.md) 를 먼저 읽는다. 이 README 는 그 표준을 이 practice 가 어떻게 구현했는지를 다룬다.

## 다루는 것

로그인한 사용자를 대신해 agent 가 보호된 MCP Server 를 부르는 흐름은 official 과 같은 클래스로 만든다.
그 위에 `ChatMemory` 로 대화를 기억하고, `conversationId` 는 client 가 아니라 server 가 `Authentication` 에서 만든다.
MCP session 도 사용자별이다: agent 는 사용자마다 MCP client 를 따로 열고, MCP Server 는 session 을 그 session 을 연 사용자에 묶는다.

## 구성

| module | 역할 |
|---|---|
| `auth-server` | 사용자(`alice`·`bob`)를 로그인시키고 token 을 발급한다 |
| `shop-mcp-server` | token 을 검증하고 MCP tool(`searchProducts`·`getStock`)을 제공한다. MCP session 을 사용자에 묶는다 |
| `shop-agent` | 사용자별 MCP client 로 token 을 붙여 MCP 를 부르고, 사용자별 대화를 기억한다 |

포트·issuer·client_id·로그인 계정은 [허브의 포트·계정 표](../MCP-AUTHORIZATION.md#s2-ports) 한 곳에 있다.
사용자가 둘이어야 대화와 MCP session 의 격리를 확인할 수 있다. 패키지는 `dev.starryeye.memoryauthn.*` 다.

## module 별 역할

### Authorization Server

`auth-server` 는 official 과 같은 클래스로 사용자를 로그인시키고 MCP Server 용 access token 을 발급한다.
배선은 [official 의 Authorization Server](../mcp-security-authn-official/README.md#authorization-server) 에 있다.

### MCP Server

`shop-mcp-server` 의 token 검증과 `Origin`·`Host`·`MCP-Protocol-Version` 검증은 official 과 같은 클래스다([official 의 MCP Server](../mcp-security-authn-official/README.md#mcp-server)).
더한 것은 `McpSessionBindingFilter` 하나로, MCP session 을 token 의 `sub` 에 묶는다([5.8](../MCP-AUTHORIZATION.md#s5-8)).
API: [API-SPEC.md](API-SPEC.md#mcp-session) · 흐름: [SEQUENCES.md](SEQUENCES.md#mcp-server-validation)

### Agent

`shop-agent` 의 discovery·로그인·token request 는 official 과 같은 클래스다([official 의 Agent](../mcp-security-authn-official/README.md#agent)).
`UserMcpClients` 가 사용자마다 MCP client 를 열고 닫으며, `ChatMemoryConfig`·`ChatClientConfig` 가 대화 기억을 켠다.
`ConversationId`·`ConversationController` 가 사용자별 `conversationId` 와 대화 API 를 맡는다.
API: [API-SPEC.md](API-SPEC.md#api-chat) · 흐름: [MCP 호출](SEQUENCES.md#mcp-call) · [conversationId](SEQUENCES.md#conversation-id)

## 직접 쓴 코드

official 과 같은 클래스는 [official 의 직접 쓴 코드 표](../mcp-security-authn-official/README.md#직접-쓴-코드)에 있다.
아래는 이 practice 가 더하거나 바꾼 클래스다.

`shop-agent` — 대화 기억과 사용자별 MCP client:

| 클래스 | 역할 | 명세 |
|---|---|---|
| `ConversationId` | `<username>:<label>` 을 만든다. label 의 영숫자·한글·`_`·`-` 외 문자는 `_` 로 바꾼다 | 이 practice 고유 |
| `ConversationController` | 대화 목록·조회·비우기 API. 호출자 접두사를 서버가 강제한다 | 이 practice 고유 |
| `ChatMemoryConfig` | `MessageWindowChatMemory`(`max-messages` 기본 20) + 자동 구성의 `InMemoryChatMemoryRepository` | 이 practice 고유 |
| `ChatClientConfig` | `MessageChatMemoryAdvisor` 를 기본 advisor 로 건다. MCP tool 은 기본값으로 두지 않는다 | 이 practice 고유 |
| `ChatController` | 요청마다 `conversationId` 를 만들고, 그 사용자의 MCP client 에서 tool callback 을 받아 넘긴다 | [5.8](../MCP-AUTHORIZATION.md#s5-8) |
| `UserMcpClients` | principal 이름마다 `McpSyncClient` 를 하나 만들어 첫 채팅 때 `initialize` 한다. 로그아웃·HTTP session 종료·종료 때 닫는다 | [4.8](../MCP-AUTHORIZATION.md#s4-8), [5.8](../MCP-AUTHORIZATION.md#s5-8) |
| `McpSecurityConfig` | `UserMcpClients` bean 과 client 마다의 transport, `HttpSessionEventPublisher` 를 등록한다 | [4.7](../MCP-AUTHORIZATION.md#s4-7), [5.8](../MCP-AUTHORIZATION.md#s5-8) |
| `OAuth2TokenAttachingRequestCustomizer` | client 를 만들 때 받은 주인(`Authentication`)의 token 을 붙인다. 요청의 `SecurityContext` 를 보지 않는다 | [5.1](../MCP-AUTHORIZATION.md#s5-1), [5.8](../MCP-AUTHORIZATION.md#s5-8) |
| `SecurityConfig` | official 배선에 로그아웃 handler 를 더해 그 사용자의 MCP client 를 닫는다 | [5.8](../MCP-AUTHORIZATION.md#s5-8) |

`shop-mcp-server` — session binding:

| 클래스 | 역할 | 명세 |
|---|---|---|
| `McpSessionBindingFilter` | `AuthorizationFilter` 뒤에서 `Mcp-Session-Id` 를 token 의 `sub` 에 묶는다. 다른 사용자의 요청은 `DELETE` 를 포함해 `403` 이다 | [5.8](../MCP-AUTHORIZATION.md#s5-8) |

## 핵심 설계 — conversationId 는 서버가 만든다

`ConversationId.of(authentication, label)` 이 `<username>:<sanitize(label)>` 를 만들어 `ChatMemory.CONVERSATION_ID` 로 쓴다.
client 는 `label` 만 고르고, 접두사(`<username>:`)는 항상 서버가 붙인다.
MCP 없는 [`chat-memory`](../chat-memory) practice 와의 차이는 아래 표다.

| 항목 | `chat-memory` practice | 이 practice |
|---|---|---|
| conversationId 결정 | client 가 전체를 지정(`POST /api/chat?conversationId=alpha`) | server 가 `Authentication` 에서 만든다(`POST /api/chat?label=default`) |
| 대화 조회 | 전체 ID 로 조회(`GET /api/conversations/{conversationId}`). 남의 ID 를 넣으면 그대로 읽힌다 | `label` 만 받고(`GET /api/conversations/{label}`), 서버가 호출자 접두사를 강제한다 |
| 대화 목록 | `findConversationIds()` 결과를 거르지 않는다 | 호출자 접두사로 거른다 |

`sanitize(label)` 은 영숫자·한글·`_`·`-` 외 문자를 모두 `_` 로 바꾸고, 구분자 `:` 도 여기에 들어간다.
이 치환이 없으면 label 에 `alice:default` 를 넣어 접두사를 위조할 수 있다.
`label` 을 남긴 이유는 같은 사용자가 `work`·`default` 처럼 대화를 여러 개 갖게 하기 위해서다.

자세히: [API-SPEC.md](API-SPEC.md#api-chat) · [SEQUENCES.md](SEQUENCES.md#conversation-id)

## 실행과 확인

준비물은 official 과 같다([official 실행과 확인](../mcp-security-authn-official/README.md#실행과-확인)).
`run.sh` 가 `auth-server`→`shop-mcp-server`→`shop-agent` 순서로 띄우지만, 세 앱은 순서와 관계없이 뜬다.
browser 에서 `http://localhost:8130/` 을 열고 `alice`/`alice` 또는 `bob`/`bob` 으로 로그인한다.

```bash
cd practice/mcp-security-authn-chat-memory
./run.sh
```

두 사용자를 동시에 로그인하려면 browser 프로필을 나눈다.
같은 프로필의 창 두 개는 session cookie(`MEMAGENTSESSIONID`)를 공유해, 나중에 로그인한 사용자가 앞 사용자를 덮어쓴다.
앱마다 cookie 이름(`MEMAUTHSESSIONID`·`MEMAGENTSESSIONID`)을 나누는 이유는 [official](../mcp-security-authn-official/README.md#localhost-의-두-oauth2-앱은-session-cookie-이름을-나눈다) 과 같다.

public client(`local-mcp-client`) 흐름은 curl 캡처 스크립트로 밟는다. Authorization Server 와 MCP Server 만 떠 있으면 된다.

```bash
AS=http://localhost:9020 MCP_BASE=http://localhost:8131 \
  LOGIN_USERNAME=alice LOGIN_PASSWORD=alice \
  CONFIDENTIAL_CLIENT_ID=memory-agent CONFIDENTIAL_CLIENT_SECRET=memory-agent-secret \
  CONFIDENTIAL_REDIRECT_URI=http://localhost:8130/login/oauth2/code/authserver \
  ../../docs/superpowers/captures/mcp-authorization-public-client.sh
```

```bash
./stop.sh
```

| 확인 방법 | 기대 결과 |
|---|---|
| alice(label=`default`)가 "내 이름은 앨리스야" 전송 후 bob(label=`default`)이 "내 이름 뭐야?" 질문 | bob 은 이름을 모른다고 답하고, bob 의 `GET /api/conversations/default` 에 alice 의 내용이 없다 |
| bob 이 label 에 `alice:default` 를 넣고 요청 | 저장 ID 는 `bob:alice_default` 이고, alice 의 `default` 대화는 그대로다 |
| bob·alice 가 각각 `GET /api/conversations` 호출 | bob → `["bob:default","bob:alice_default"]`, alice → 자기 대화만. 서로의 ID 가 보이지 않는다 |
| alice 가 label=`work` 로 "내 취미는 등산이야" 전송 후 label=`default` 로 "내 취미 뭐야?" 질문 | 모른다고 답한다. 같은 사용자라도 label 이 다르면 섞이지 않는다 |
| `grep '호출' logs/shop-mcp-server.log` | `사용자=alice` — MCP Server 에 도착한 신원은 로그인한 사람이다 |

## 학습 포인트

### 전체 ID 를 받는 API 를 두지 않는 것이 가장 확실한 방어다

`GET /api/conversations/{label}` 은 label 만 받고, 서버가 항상 호출자 접두사를 붙인다.
"잘 거르는 API" 보다 "잘못 부를 방법이 없는 API" 가 강하다.
테스트: `ConversationControllerTest#경로에_남의_ID_를_넣어도_소용없다`.

### `findConversationIds()` 는 저장소 전체를 안다

`ChatMemoryRepository#findConversationIds()` 는 모든 사용자의 ID 를 돌려주므로, 거르지 않으면 남의 ID 가 샌다.
`ConversationController` 는 `ConversationId.prefixOf(authentication)` 으로 시작하는 ID 만 남긴다.
테스트: `ConversationControllerTest#목록에_남의_대화_ID_가_보이지_않는다`.

### 대화에 이어 MCP session 도 사용자별이다

MCP client 하나를 모든 사용자가 나눠 쓰면 한 MCP session 에 여러 사용자의 token 이 실린다.
그래서 `spring.ai.mcp.client.enabled: false` 로 Spring AI 자동 구성의 공유 client 를 끄고, `UserMcpClients` 가 사용자마다 client 를 연다.
MCP Server 의 `McpSessionBindingFilter` 는 session 을 연 사용자와 요청한 사용자를 비교해, session 을 사용자에 묶는다(Security Best Practices **SHOULD**, [5.8](../MCP-AUTHORIZATION.md#s5-8)).

테스트: `UserMcpClientsTest#사용자마다_다른_client_를_열고_initialize_한다`, `McpAuthorizationStandardTest#다른_사용자가_남의_MCP_session_ID_를_쓰면_403이다`.

### token 은 client 의 주인에게 묶는다

`McpSyncClient#closeGracefully` 가 보내는 session 종료 `DELETE` 에는 MCP SDK 가 transport context 를 넘기지 않는다.
그래서 `OAuth2TokenAttachingRequestCustomizer` 는 요청의 `SecurityContext` 대신, client 를 만들 때 받은 주인의 token 을 붙인다.
`DELETE` 에도 token 이 붙고, 한 사용자의 session 에 다른 사용자의 token 이 실릴 수 없다.

테스트: `OAuth2TokenAttachingRequestCustomizerTest#transport_context_없이_나가는_session_종료_DELETE_에도_토큰을_붙인다`.

주인이 정해져 있으므로 official 의 `SecurityMcpTransportContextProvider` 와 `Hooks.enableAutomaticContextPropagation()` 이 필요 없다.
reactor thread 에서 도는 tool 호출도 같은 주인의 token 을 쓴다.

### session binding 의 대가

- 서버의 binding 표(`McpSessionBindingFilter` 의 map)는 성공한 `DELETE` 로만 지워진다. agent 가 `DELETE` 없이 끝나면 그 항목은 서버가 끝날 때까지 남는다.
- client 는 principal 이름마다 하나다. 한 browser 에서 로그아웃하거나 HTTP session 이 끝나면, 같은 사용자가 다른 browser 에서 쓰던 client 도 닫힌다.
- 닫힌 뒤의 다음 채팅은 새 client 를 열고 새 `initialize` 로 새 session 을 받는다. 테스트: `UserMcpClientsTest#닫으면_session_을_끝내고_다음_요청은_새_client_를_연다`.

### tool 호출 결과는 다른 말로 바뀌어 남는다

`MessageChatMemoryAdvisor` 는 user 메시지와 최종 assistant 텍스트만 저장하고, `ToolResponseMessage` 는 저장하지 않는다.
tool 이 조회한 값은 모델이 옮겨 적은 assistant 텍스트로만 남는다.
흐름: [SEQUENCES.md](SEQUENCES.md#tool-memory) · 예시: [API-SPEC.md](API-SPEC.md#api-conversation-get)

### CSRF 는 예외 없이 검사한다

`SecurityConfig` 의 `csrf.spa()` 는 JS 가 읽을 수 있는 `XSRF-TOKEN` 쿠키를 싣고, `index.html` 이 그 값을 `X-XSRF-TOKEN` 헤더로 되돌려 보낸다.
`POST /api/chat`·`DELETE /api/conversations/{label}`·`POST /logout` 모두 이 헤더가 있어야 한다.
테스트: `ConversationControllerTest#CSRF_토큰_없이_채팅하면_403`, `#페이지가_준_XSRF_TOKEN_을_헤더로_보내면_지울_수_있다`.

### 로그아웃은 `shop-agent` 만으로 끝나지 않는다

`shop-agent` 의 로그아웃은 그 사용자의 MCP client 를 닫지만, `auth-server` 의 로그인 session 은 남는다.
그 session 이 남아 있으면 다음 로그인 때 같은 사용자로 다시 인증된다.
사용자를 바꾸려면 `auth-server` 쪽 session 도 끝내야 한다.

### MCP `initialize` 는 첫 채팅 때 한다

기동 시점에는 대신 호출할 사용자가 없어 MCP Server 가 `401` 을 준다([4.8](../MCP-AUTHORIZATION.md#s4-8)).
`UserMcpClients` 는 사용자의 첫 채팅 때 client 를 만들고 `initialize` 하므로, 사용자마다 첫 질문이 느리다.

## 비목표

- tool 단위 authorization(scope 검사) → 후속 `mcp-security-authz`. 이 practice 는 "누가 요청했는가" 만 다룬다.
- token 만료 후 이미 대화에 남은 데이터의 보존 기간
- 사용자의 권한이 축소된 뒤 이미 기억된 데이터의 처리
- `maxMessages` 창이 밀어낸 메시지가 저장소에서 실제로 삭제되는지 여부
- 사용자 3명 이상, 역할 분리
- token·대화 저장소 영속화(in-memory 로 충분), UI 완성도

## 링크

- [MCP-AUTHORIZATION.md](../MCP-AUTHORIZATION.md) · [MCP-API-SPEC.md](../MCP-API-SPEC.md) · [MCP-SEQUENCES.md](../MCP-SEQUENCES.md)
- 기준 practice: [`mcp-security-authn-official`](../mcp-security-authn-official) · 대화 기억: [`chat-memory`](../chat-memory)
- 캡처: [2026-09-12-chat-memory.txt](../../docs/superpowers/captures/2026-09-12-chat-memory.txt) · [2026-09-25-chat-memory-public-client.txt](../../docs/superpowers/captures/2026-09-25-chat-memory-public-client.txt) · [2026-09-25-listen-addresses.txt](../../docs/superpowers/captures/2026-09-25-listen-addresses.txt)
