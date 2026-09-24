# mcp-security-authn-chat-memory

`mcp-security-authn-official` 의 인증 흐름에 `chat-memory` 의 대화 기억을 얹어, `conversationId` 를 client 가 아니라 server 가 `Authentication` 에서 만드는 practice 다.

> 인증이 포함된 MCP 표준 자체를 배우려면 [MCP-AUTHORIZATION.md](../MCP-AUTHORIZATION.md) 를 먼저 읽는다. 이 README 는 그 표준을 이 practice 가 어떻게 구현했는지를 다룬다.

## 다루는 것

`mcp-security-authn-official` 이 만든 "로그인한 사용자를 대신해 agent 가 보호된 MCP Server 를 호출"하는 흐름에, `chat-memory` 의 `ChatMemory` 로 대화를 기억하는 기능을 얹는다. 다른 점은 `conversationId` 를 만드는 주체다 — client 가 아니라 server 가, 로그인한 사용자의 `Authentication` 에서 파생시킨다. 그 결과가 사용자별 대화 격리다.

## 구성과 포트

| 모듈 | 포트 | 역할 |
|---|---|---|
| `auth-server` | `9020` | 사용자를 로그인시키고 token 을 발급한다 |
| `shop-mcp-server` | `8131` | token 을 검증하고 MCP tool(`searchProducts`·`getStock`)을 제공한다 |
| `shop-agent` | `8130` | token 을 얻어 MCP 호출에 붙이고, 사용자별 대화를 기억한다 |

사용자는 `alice`/`alice`, `bob`/`bob` 두 명이다. 격리를 검증하려면 최소 두 명이 필요하다.

## 모듈별 역할

### Authorization Server

`auth-server` 는 official 과 같은 클래스로 사용자를 로그인시키고 MCP Server 용 access token 을 발급한다. 다른 것은 포트·계정(`alice`·`bob`)·confidential client_id(`memory-agent`)뿐이다.
배선은 [official 의 Authorization Server](../mcp-security-authn-official/README.md#authorization-server) 에 있다.

### MCP Server

`shop-mcp-server` 는 official 과 같은 클래스로 token 을 검증하고 MCP tool 을 제공하는 resource server 다. 다른 것은 포트와 resource 식별자(`http://localhost:8131/mcp`)뿐이다.
배선은 [official 의 MCP Server](../mcp-security-authn-official/README.md#mcp-server) 에 있다.

### Agent

`shop-agent` 의 discovery·로그인·token 부착은 official 과 같은 클래스다([official 의 Agent](../mcp-security-authn-official/README.md#agent)). 그 위에 `ChatMemoryConfig`(`MessageWindowChatMemory`)·`ChatClientConfig`(`MessageChatMemoryAdvisor`)가 대화 기억을 켜고, `ConversationId`·`ConversationController` 가 사용자별 conversationId 와 대화 API 를 맡는다.
API: [API-SPEC.md](API-SPEC.md#api-chat) · 흐름: [SEQUENCES.md](SEQUENCES.md#conversation-id)

## official 과 같은 것 · 다른 것

discovery·PKCE·`resource`·`aud`·`Origin`/`Host` 검증을 포함한 인증 흐름 전체는 [`mcp-security-authn-official`](../mcp-security-authn-official/README.md)과 같다. 이 문서는 그 위에 얹은 대화 기억·격리만 다룬다.

값이 다른 자리는 아래 표다.

| 항목 | official | 이 practice |
|---|---|---|
| 포트(`auth-server`/`shop-mcp-server`/`shop-agent`) | `9010`/`8111`/`8110` | `9020`/`8131`/`8130` |
| 계정 | `user`/`password` 1명 | `alice`/`alice`, `bob`/`bob` 2명 |
| confidential client_id | `official-shop-agent` | `memory-agent` |
| agent 고유 대화 API | 없음(`POST /api/chat` 뿐, 메모리 없음) | `POST /api/chat?label=` · `GET /api/conversations` · `GET /api/conversations/{label}` · `DELETE /api/conversations/{label}` |

## 핵심 설계 — conversationId 는 서버가 만든다

`ConversationId.of(authentication, label)` 이 `<username>:<sanitize(label)>` 를 만들어 `ChatMemory.CONVERSATION_ID` 로 쓴다. client 는 `label` 만 고르고, 접두사(`<username>:`)는 항상 서버가 붙인다. 부모 `chat-memory` 와의 차이는 아래 표다.

| 항목 | `chat-memory`(부모) | 이 practice |
|---|---|---|
| conversationId 결정 | client 가 전체를 지정(`POST /api/chat?conversationId=alpha`) | server 가 `Authentication` 에서 파생(`POST /api/chat?label=default`) |
| 대화 조회 | 전체 ID 그대로 조회(`GET /api/conversations/{conversationId}`) — 남의 ID 를 넣으면 그대로 읽힘 | `label` 만 받음(`GET /api/conversations/{label}`), 서버가 항상 호출자 접두사를 강제 |
| 대화 목록 | `findConversationIds()` 결과를 거르지 않고 반환 | 호출자 접두사로 필터링 |

`sanitize(label)` 은 구분자(`:`)를 포함해 영숫자·한글·`_`·`-` 이외 문자를 모두 `_` 로 바꾼다. 이 치환이 없으면 label 에 `alice:default` 처럼 넣어 접두사 자체를 위조할 수 있다.

`Authentication.getName()` 하나만 conversationId 로 쓰지 않은 이유는 그러면 한 사용자가 대화를 하나만 가질 수 있기 때문이다 — 같은 사용자가 `work`·`default` 두 대화를 따로 갖는 경우가 애초에 불가능해진다. 그래서 `label` 은 남기고 접두사만 서버가 강제한다.

자세히: [API-SPEC.md](API-SPEC.md#api-chat) · [SEQUENCES.md](SEQUENCES.md#conversation-id)

## 실행과 확인

준비물과 기동 순서(`auth-server`→`shop-mcp-server`→`shop-agent`)는 official 과 같다([README.md](../mcp-security-authn-official/README.md)). `http://localhost:8130/` 에서 `alice`/`alice` 또는 `bob`/`bob` 으로 로그인한다.

```bash
cd practice/mcp-security-authn-chat-memory
./run.sh
```

두 사용자를 동시에 로그인하려면 브라우저 프로필을 분리해야 한다. 같은 프로필의 일반 창 두 개는 session cookie(`MEMAGENTSESSIONID`)를 공유해 나중에 로그인한 사용자가 앞 사용자를 덮어쓴다.

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
| alice(label=`default`)가 "내 이름은 앨리스야" 전송 후 bob(label=`default`)이 "내 이름 뭐야?" 질문 | bob 의 답변이 이름을 모른다고 답하고, bob session 의 `GET /api/conversations/default` 에 alice 의 내용이 없음 |
| bob 이 label 에 `alice:default` 를 넣고 요청 | 실제 저장 ID는 `bob:alice_default`(`GET /api/conversations` → `["bob:alice_default"]`), alice 의 진짜 `default` 대화는 그대로 |
| bob·alice 각각 `GET /api/conversations` 호출 | bob → `["bob:default","bob:alice_default"]`, alice → `["alice:work","alice:default","alice:tools"]` — 서로 안 보임 |
| alice가 label=`work` 로 "내 취미는 등산이야" 전송 후 label=`default` 로 "내 취미 뭐야?" 질문 | 모른다는 답변 — 같은 사용자라도 label 이 다르면 안 섞임 |
| `grep '호출' logs/shop-mcp-server.log` | `사용자=alice` — MCP Server 에 도착한 신원은 로그인한 사람 |
| `./stop.sh` 후 포트·`git status` 확인 | `9020`/`8130`/`8131` 모두 해제, `git status` 클린 |

## tool 호출 결과가 memory 에 남는가

`role` 은 `user`·`assistant` 두 종류만 저장되고 `tool`(`ToolResponseMessage`) 메시지는 저장소에 없다. 그런데도 `assistant` 텍스트에는 조회된 수치가 그대로 남는다 — `MessageChatMemoryAdvisor` 가 최종 사용자 메시지와 최종 assistant 텍스트만 저장하기 때문이다. 원본 tool 프로토콜 형식으로는 남지 않지만, 모델이 그 값을 자연어로 옮겨 적은 텍스트로는 결과적으로 남는다.

예시: [API-SPEC.md](API-SPEC.md#api-conversation-get).

## 학습 포인트

### 전체 ID 를 받는 API 를 두지 않는 것이 가장 확실한 방어다

`GET /api/conversations/{label}` 은 label 만 받고, 서버가 항상 호출자 접두사를 강제로 붙인다.
"필터링을 잘 하는 API" 보다 "잘못 부를 방법 자체가 없는 API" 가 강하다.

### `findConversationIds()` 는 저장소 전체를 안다

거르지 않으면 남의 ID 가 샌다.
부모 `chat-memory` 에 이 필터가 없는 것은 사용자 1명을 가정하기 때문이다.

### tool 호출 결과는 다른 말로 바뀌어 남는다

`ToolResponseMessage` 는 저장되지 않고, 모델이 조회 값을 옮겨 적은 assistant 텍스트로만 남는다.
자세히는 위 "tool 호출 결과가 memory 에 남는가" 절이다.

### CSRF 는 예외 없이 전면 적용된다

부모 `mcp-security-authn-official` 은 `/api/chat` 을 CSRF 검사에서 면제하지만, 여기는 상태를 바꾸는 `DELETE /api/conversations/{label}` 을 포함해 어떤 endpoint 도 면제하지 않는다.
`CookieCsrfTokenRepository.withHttpOnlyFalse()` 로 발급한 `XSRF-TOKEN` 쿠키를 `X-XSRF-TOKEN` 헤더로 되돌려 보내는 표준 SPA 패턴을 쓴다.

### 로그아웃은 `shop-agent` 만으로 끝나지 않는다

`auth-server`(`:9020`)의 OIDC session 이 남아 있으면 다음 로그인 때 같은 사용자로 조용히 재인증된다.
사용자를 바꾸려면 `shop-agent` 와 `auth-server` 양쪽에서 모두 로그아웃해야 한다.

### Spring Boot 4.1 은 `springSecurity()` 를 자동으로 붙이지 않는다

`spring-boot-webmvc-test` 에는 `MockMvcSecurityConfiguration` 대응물이 없어, 이 배선이 없으면 보안이 걸린 `MockMvc` 테스트가 모두 redirect 로 실패한다.
`ConversationControllerTest` 가 `@TestConfiguration` 과 `MockMvcBuilderCustomizer` 로 이 배선을 직접 되살린다.

### official 과 같은 운영 관례는 그대로다

기동 순서, `spring.ai.mcp.client.initialized: false` 로 인한 첫 요청 지연, 앱마다 session cookie 이름을 분리하는 관례는 official 과 같다.

## 비목표

- tool 단위 authorization(scope 검사) → 후속 `mcp-security-authz`. 이 practice 는 "누가 요청했는가" 만 다룬다.
- token 만료 후 이미 대화에 남은 데이터의 보존 기간
- 사용자의 권한이 축소된 뒤 이미 기억된 데이터의 처리
- `maxMessages` 창이 밀어낸 메시지가 저장소에서 실제로 삭제되는지 여부
- 사용자 3명 이상, 역할 분리
- token 저장소 영속화(in-memory 로 충분), UI 완성도

## 링크

- [MCP-AUTHORIZATION.md](../MCP-AUTHORIZATION.md) · [MCP-API-SPEC.md](../MCP-API-SPEC.md) · [MCP-SEQUENCES.md](../MCP-SEQUENCES.md)
- 부모: [`mcp-security-authn-official`](../mcp-security-authn-official) · [`chat-memory`](../chat-memory)
- 설계: [설계 스펙](../../docs/superpowers/specs/2026-09-07-mcp-security-authn-chat-memory-design.md) · [구현 계획](../../docs/superpowers/plans/2026-09-07-mcp-security-authn-chat-memory.md)
- 캡처: [2026-09-12-chat-memory.txt](../../docs/superpowers/captures/2026-09-12-chat-memory.txt) · [2026-09-25-chat-memory-public-client.txt](../../docs/superpowers/captures/2026-09-25-chat-memory-public-client.txt)
