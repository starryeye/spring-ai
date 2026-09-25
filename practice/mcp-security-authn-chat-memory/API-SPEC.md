# mcp-security-authn-chat-memory API 명세

표준 endpoint 는 [MCP-API-SPEC.md](../MCP-API-SPEC.md) 가 필드 단위로 정의하고, 그 값은 [official 의 API-SPEC.md](../mcp-security-authn-official/API-SPEC.md) 와 같다. 이 문서는 MCP session binding 과 로그아웃처럼 official 과 다르게 동작하는 자리, 그리고 대화 기억을 위한 고유 endpoint 전체 명세를 담는다.

## 목차

| 앵커 | 내용 |
|---|---|
| [`endpoints`](#endpoints) | 표준 endpoint — official 과 같음 |
| [`mcp-session`](#mcp-session) | `/mcp` 의 session binding 오류(`403`·`404`) |
| [`logout`](#logout) | `POST /logout` — 그 사용자의 MCP client 를 닫는다 |
| [`api-chat`](#api-chat) | `POST /api/chat` 전체 명세 |
| [`api-conversations`](#api-conversations) | `GET /api/conversations` 전체 명세 |
| [`api-conversation-get`](#api-conversation-get) | `GET /api/conversations/{label}` 전체 명세 |
| [`api-conversation-delete`](#api-conversation-delete) | `DELETE /api/conversations/{label}` 전체 명세 |

---

<a id="endpoints"></a>

## 표준 endpoint

Authorization Server metadata·authorize·token, agent 로그인, MCP Server 의 PRM·`/mcp` 는 official 과 같은 클래스로 같은 표준을 구현한다.
필드·오류·예시는 official 의 [`auth-server`](../mcp-security-authn-official/API-SPEC.md#auth-server) · [`mcp-server`](../mcp-security-authn-official/API-SPEC.md#mcp-server) · [`agent-login`](../mcp-security-authn-official/API-SPEC.md#agent-login) 에 있다.
포트·issuer·client_id·redirect URI 는 [허브의 포트·계정 표](../MCP-AUTHORIZATION.md#s2-ports) 에 있다.

official 과 다른 것은 두 가지다.

- `/mcp`: 다른 사용자가 연 session 에 보낸 요청은 token 이 유효해도 `403` 이다([`mcp-session`](#mcp-session)). official 은 이 요청을 통과시킨다.
- `POST /logout`: 로그아웃한 사용자의 MCP client 를 닫고, 그 client 가 `DELETE /mcp` 로 session 을 끝낸다([`logout`](#logout)).

---

<a id="mcp-session"></a>

## `POST·GET·DELETE /mcp` — session binding

`McpSessionBindingFilter` 는 인증을 마친 `/mcp` 요청의 `Mcp-Session-Id` 를 token 의 `sub` 와 비교한다. 요청·응답 필드는 표준 [`mcp-post`](../MCP-API-SPEC.md#mcp-post) · [`mcp-get`](../MCP-API-SPEC.md#mcp-get) · [`mcp-delete`](../MCP-API-SPEC.md#mcp-delete) 와 같고, 여기에는 이 filter 가 더하는 동작만 적는다.

근거: [Security Best Practices — Session Hijacking](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices#session-hijacking) · 허브 [5.8](../MCP-AUTHORIZATION.md#s5-8) · 시퀀스 [`mcp-server-validation`](SEQUENCES.md#mcp-server-validation)

**묶기와 풀기**

| 시점 | 동작 | 근거 |
|---|---|---|
| transport 가 응답에 `Mcp-Session-Id` 를 쓸 때(`initialize`) | 그 session ID 를 요청한 사용자(`sub`)에 묶는다. 응답 본문보다 먼저 묶인다 | `McpSessionBindingFilter` 의 응답 wrapper |
| 같은 사용자가 여러 session 을 열 때 | session 마다 같은 사용자에 묶인다 | 테스트 `한_사용자가_연_여러_session_은_모두_그_사용자에_묶인다` |
| 묶은 사용자의 `DELETE` 가 `2xx` 로 끝날 때 | 묶음을 푼다 | 테스트 `DELETE_로_끝난_session_은_묶음이_풀리고_transport_가_모르는_session_으로_404_를_준다` |

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| 다른 사용자가 연 `Mcp-Session-Id` 로 요청 | `403`. filter 는 method 를 가리지 않는다 | 테스트 `McpAuthorizationStandardTest#다른_사용자가_남의_MCP_session_ID_를_쓰면_403이다` |
| 다른 사용자가 연 session 에 `DELETE` | `403`. session 과 묶음은 그대로 남는다 | 테스트 `#다른_사용자는_남의_MCP_session_을_끝낼_수_없다` |
| `DELETE` 로 끝난 session ID 로 요청 | `404`. 묶음이 풀려 filter 를 지나고, transport 가 모르는 session 으로 본다 | 테스트 `#DELETE_로_끝난_session_은_묶음이_풀리고_transport_가_모르는_session_으로_404_를_준다` |
| 묶인 적 없는 session ID | `404`. filter 는 통과시키고 transport 가 판단한다 | [`mcp-post`](../MCP-API-SPEC.md#mcp-post) |

---

<a id="logout"></a>

## `POST /logout` — 로그아웃

Spring Security 기본 로그아웃 endpoint 다. `SecurityConfig` 가 더한 logout handler 가 그 사용자의 MCP client 를 닫는다.

근거: `SecurityConfig.java`, `UserMcpClients.java` · 허브 [5.8](../MCP-AUTHORIZATION.md#s5-8) · 시퀀스 [`mcp-session-end`](SEQUENCES.md#mcp-session-end)

**요청**

| 이름 | 위치 | 표시 | 설명 | 이 practice |
|---|---|---|---|---|
| `X-XSRF-TOKEN` | 헤더 | 필수 — Spring Security `csrf.spa()` | `XSRF-TOKEN` 쿠키 값 | 씀 |

원문 필드: 없음(Spring Security 기본 endpoint)

**응답**

| 이름 | 표시 | 설명 | 이 practice |
|---|---|---|---|
| `302` redirect | 표시 없음 | HTTP session 을 끝내고 로그아웃 성공 URL 로 보낸다 | 씀 — Spring 기본 |

원문 필드: 없음(Spring Security 기본 endpoint)

부수 효과: `UserMcpClients#close(username)` 가 client 를 지우고 `closeGracefully` 를 부른다. 그 client 가 주인의 token 을 실어 `DELETE /mcp` 를 보낸다.

client 는 principal 이름마다 하나라, 같은 사용자가 다른 browser 에서 쓰던 client 도 함께 닫힌다. 다음 채팅은 새 client 와 새 session 을 연다.

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| `X-XSRF-TOKEN` 헤더 누락·불일치 | `403` | `csrf.spa()` |

테스트: `ChatControllerTest#로그아웃하면_그_사용자의_MCP_client_를_닫는다`.

---

<a id="api-chat"></a>

## `POST /api/chat` — 채팅과 대화 기억

채팅 메시지를 받아 `ChatClient` 로 LLM 에 전달하고, 답을 스트리밍으로 돌려준다. `label` 로 대화를 구분하고, `conversationId` 는 client 가 아니라 서버가 만든다.

MCP tool 은 요청한 사용자의 MCP client 에서 받는다. 그 사용자의 첫 채팅이면 여기서 client 를 만들고 `initialize` 한다.

근거: `ChatController.java`, `ConversationId.java`, `UserMcpClients.java`, `SecurityConfig.java` · 허브 [5.8](../MCP-AUTHORIZATION.md#s5-8) · 시퀀스 [`conversation-id`](SEQUENCES.md#conversation-id) · [`mcp-call`](SEQUENCES.md#mcp-call)

**요청**

| 이름 | 위치 | 표시 | 설명 | 이 practice |
|---|---|---|---|---|
| `label` | 쿼리 | 선택 — 이 practice 고유 API | 대화를 구분하는 이름. 비어 있으면 `default` | 씀 — `@RequestParam(required = false) String label` |
| 본문 | 본문 | 필수 — 이 practice 고유 API | 사용자 메시지 평문(JSON 아님) | 씀 — `@RequestBody String message` |

원문 필드: 없음(이 practice 고유 API)

`conversationId` 파생 규칙: `ConversationId.of(authentication, label)` 이 `authentication.getName() + ":" + sanitize(label)` 를 돌려준다. `sanitize` 는 `label` 이 `null`·blank 면 `default` 를 쓰고, 아니면 trim 한 값에서 영숫자·한글·`_`·`-` 이외 문자를 모두 `_` 로 바꾼다(치환 결과가 blank 여도 `default`).

**응답**

| 이름 | 표시 | 설명 | 이 practice |
|---|---|---|---|
| `200` + `Content-Type: text/plain;charset=UTF-8` | 표시 없음 | `ChatClient.stream().content()` 텍스트를 그대로 스트리밍 | 씀 — `ChatController#chat` |

원문 필드: 없음(이 practice 고유 API)

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| 미로그인 호출 | `302 Location: /oauth2/authorization/authserver` | `SecurityConfig` 의 `anyRequest().authenticated()` |
| `X-XSRF-TOKEN` 헤더 또는 `XSRF-TOKEN` 쿠키 누락·불일치 | `403` | `csrf.spa()` 는 `/api/chat` 을 CSRF 예외로 두지 않는다. 테스트 `ConversationControllerTest#CSRF_토큰_없이_채팅하면_403` |

**예시** (bob, label 생략 → `default`)

```http
POST /api/chat
내 이름 뭐야?
```

```
저는 고객님의 이름을 저장하거나 조회할 수 있는 시스템이 없습니다. 도움이 필요하시면 말씀해주세요!
```

---

<a id="api-conversations"></a>

## `GET /api/conversations` — 대화 ID 목록

호출자 접두사로 거른 대화 ID 목록을 돌려준다. `ConversationId.prefixOf(authentication)` 로 접두사를 구하고, `ChatMemoryRepository.findConversationIds()` 결과를 그 접두사로 시작하는 것만 남긴다.

근거: `ConversationController.java`, `ConversationId.java` · 시퀀스 [`memory-read-write`](SEQUENCES.md#memory-read-write)

**요청**

원문 필드: 없음(이 practice 고유 API) — 경로·쿼리 파라미터 없음

**응답**

| 이름 | 표시 | 설명 | 이 practice |
|---|---|---|---|
| 본문(JSON 배열) | 표시 없음 | 호출자 접두사로 시작하는 conversationId 목록 | 씀 — `List<String>` |

원문 필드: 없음(이 practice 고유 API)

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| 미로그인 호출 | `302 Location: /oauth2/authorization/authserver` | `SecurityConfig` |

**예시** (bob session)

```http
GET /api/conversations
```

```json
["bob:default","bob:alice_default"]
```

---

<a id="api-conversation-get"></a>

## `GET /api/conversations/{label}` — 한 대화의 메시지

호출자의 대화 하나에 쌓인 메시지를 순서대로 돌려준다. `label` 만 받으므로 전체 conversationId 를 직접 지목할 수 없다 — `ConversationId.of(authentication, label)` 로 항상 호출자 접두사가 붙는다.

근거: `ConversationController.java`, `MessageView.java` · 시퀀스 [`tool-memory`](SEQUENCES.md#tool-memory)

**요청**

| 이름 | 위치 | 표시 | 설명 | 이 practice |
|---|---|---|---|---|
| `label` | 경로 | 필수 — 이 practice 고유 API | 조회할 대화 이름. `POST /api/chat` 과 같은 sanitize 규칙 | 씀 — `@PathVariable String label` |

원문 필드: 없음(이 practice 고유 API)

**응답**

`MessageView` 목록(JSON 배열)을 돌려준다.

| 이름 | 타입 | 설명 | 이 practice |
|---|---|---|---|
| `role` | `string` | `message.getMessageType().getValue()` — 이 흐름에서 관측되는 값은 `user`·`assistant` 뿐(`tool` 메시지는 없음) | 씀 |
| `text` | `string` | `message.getText()` — 메시지 내용 | 씀 |

원문 필드: 없음(이 practice 고유 API)

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| 미로그인 호출 | `302 Location: /oauth2/authorization/authserver` | `SecurityConfig` |

**예시** (alice, label=`tools`, `노트북 재고 있어?` 질문 후 조회)

```http
GET /api/conversations/tools
```

```json
[
  {"role": "user", "text": "노트북 재고 있어?"},
  {"role": "assistant", "text": "노트북 관련 상품 재고 정보입니다:\n\n1. [p1] 게이밍 노트북 15인치 - 1,890,000원 / 재고 7개  \n2. [p2] 사무용 노트북 14인치 - 990,000원 / 재고 23개  \n\n재고가 있는 상품입니다. 구체적인 모델이나 추가 정보가 필요하시면 말씀해주세요."}
]
```

---

<a id="api-conversation-delete"></a>

## `DELETE /api/conversations/{label}` — 대화 비우기

호출자의 대화 하나를 비운다. LLM 을 부르지 않고 저장소만 지운다.

근거: `ConversationController.java` · 시퀀스 [`memory-read-write`](SEQUENCES.md#memory-read-write)

**요청**

| 이름 | 위치 | 표시 | 설명 | 이 practice |
|---|---|---|---|---|
| `label` | 경로 | 필수 — 이 practice 고유 API | 비울 대화 이름. `POST /api/chat` 과 같은 sanitize 규칙 | 씀 — `@PathVariable String label` |

원문 필드: 없음(이 practice 고유 API)

**응답**

| 이름 | 표시 | 설명 | 이 practice |
|---|---|---|---|
| `204 No Content` | 표시 없음 | 본문 없음 | 씀 — `ResponseEntity.noContent().build()` |

원문 필드: 없음(이 practice 고유 API)

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| 미로그인 호출 | `302 Location: /oauth2/authorization/authserver` | `SecurityConfig` |
| `X-XSRF-TOKEN` 헤더 또는 `XSRF-TOKEN` 쿠키 누락·불일치 | `403` | 상태를 바꾸는 endpoint 라 CSRF 예외가 없음 |

**예시**

```http
DELETE /api/conversations/default
```

```http
HTTP/1.1 204
```
