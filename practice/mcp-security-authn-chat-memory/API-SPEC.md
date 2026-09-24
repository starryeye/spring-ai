# mcp-security-authn-chat-memory API 명세

`auth-server`·`shop-mcp-server`·agent 로그인 endpoint 의 표준 명세는 [MCP-API-SPEC.md](../MCP-API-SPEC.md) 와 [mcp-security-authn-official/API-SPEC.md](../mcp-security-authn-official/API-SPEC.md) 가 이미 필드 단위로 정의한다. 이 문서는 그 표준에서 값이 달라지는 자리와, 대화 기억을 위해 이 practice 가 추가한 고유 endpoint 전체 명세를 담는다.

## 목차

| 앵커 | 내용 |
|---|---|
| [`endpoints`](#endpoints) | Authorization Server·MCP Server·agent 표준 endpoint — official 과 값이 다른 자리 |
| [`api-chat`](#api-chat) | `POST /api/chat` 전체 명세 |
| [`api-conversations`](#api-conversations) | `GET /api/conversations` 전체 명세 |
| [`api-conversation-get`](#api-conversation-get) | `GET /api/conversations/{label}` 전체 명세 |
| [`api-conversation-delete`](#api-conversation-delete) | `DELETE /api/conversations/{label}` 전체 명세 |

---

<a id="endpoints"></a>

## Authorization Server·MCP Server·agent 표준 endpoint

Authorization Server metadata·discovery·authorize·token·agent 로그인(`GET /oauth2/authorization/authserver`, `GET /login/oauth2/code/authserver`)·MCP `/mcp` endpoint 는 이 practice 에서도 [`mcp-security-authn-official`](../mcp-security-authn-official/API-SPEC.md)과 완전히 같은 표준을 구현한다. 필드 단위 명세는 그 문서에 있고, 여기서는 값이 달라지는 자리만 적는다.

| 항목 | official | 이 practice |
|---|---|---|
| `auth-server` 포트 | `:9010` | `:9020` |
| `shop-mcp-server` 포트 | `:8111` | `:8131` |
| `shop-agent` 포트 | `:8110` | `:8130` |
| confidential client_id | `official-shop-agent` | `memory-agent` |
| confidential redirect URI | `http://localhost:8110/login/oauth2/code/authserver` | `http://localhost:8130/login/oauth2/code/authserver` |
| public client_id | `local-mcp-client` | `local-mcp-client`(같음) |

---

<a id="api-chat"></a>

## `POST /api/chat` — 채팅과 대화 기억

채팅 메시지를 받아 `ChatClient` 로 LLM 에 전달하고, 답을 스트리밍으로 돌려준다. `official` 과 달리 `label` 로 대화를 구분하고, `conversationId` 는 client 가 아니라 서버가 만든다.

근거: `ChatController.java`, `ConversationId.java`, `SecurityConfig.java`

**요청**

| 이름 | 위치 | 표시 | 설명 | 이 practice |
|---|---|---|---|---|
| `label` | 쿼리 | OPTIONAL — 이 practice 고유 API | 대화를 구분하는 이름. 비어 있으면 `default` | 씀 — `@RequestParam(required = false) String label` |
| 본문 | 본문 | 표시 없음 — 이 practice 고유 API | 사용자 메시지 평문(JSON 아님) | 씀 — `@RequestBody String message` |

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
| `X-XSRF-TOKEN` 헤더 또는 `XSRF-TOKEN` 쿠키 누락·불일치 | `403` | `SecurityConfig` 는 `/api/chat` 을 CSRF 예외로 두지 않는다 |

**예시** (bob, label 생략 → `default`)

```http
POST /api/chat?label=default
내 이름 뭐야?
```

```
저는 고객님의 이름을 저장하거나 조회할 수 있는 시스템이 없습니다. 도움이 필요하시면 말씀해주세요!
```

---

<a id="api-conversations"></a>

## `GET /api/conversations` — 대화 ID 목록

호출자 접두사로 거른 대화 ID 목록을 돌려준다. `ConversationId.prefixOf(authentication)` 로 접두사를 구하고, `ChatMemoryRepository.findConversationIds()` 결과를 그 접두사로 시작하는 것만 남긴다.

근거: `ConversationController.java`, `ConversationId.java`

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

근거: `ConversationController.java`, `MessageView.java`

**요청**

| 이름 | 위치 | 표시 | 설명 | 이 practice |
|---|---|---|---|---|
| `label` | 경로 | OPTIONAL — 이 practice 고유 API | 조회할 대화 이름. `POST /api/chat` 과 같은 sanitize 규칙 | 씀 — `@PathVariable String label` |

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

근거: `ConversationController.java`

**요청**

| 이름 | 위치 | 표시 | 설명 | 이 practice |
|---|---|---|---|---|
| `label` | 경로 | OPTIONAL — 이 practice 고유 API | 비울 대화 이름. `POST /api/chat` 과 같은 sanitize 규칙 | 씀 — `@PathVariable String label` |

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
