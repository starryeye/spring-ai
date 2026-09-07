# mcp-security-authn-chat-memory — 사용자별 대화 격리

작성일: 2026-09-07

## 배경

두 practice 를 합친다.

| 부모 | 남긴 것 |
|---|---|
| `mcp-security-authn-official` | 사용자가 로그인하고 에이전트가 그 사용자를 대신해 보호된 MCP 서버를 호출한다. 공식 라이브러리만 사용 |
| `chat-memory` | `ChatMemory` · `MessageChatMemoryAdvisor` · `conversationId` 로 대화를 기억한다. 사용자 1명 가정 |

각 practice 의 README 가 서로를 다음 단계로 지목했다. `chat-memory` 는 이렇게 끝난다:

> `conversationId` 를 지금은 클라이언트가 보낸다. 남의 대화 ID 를 넣으면 그대로 읽히는데,
> 사용자 한 명 가정이라 성립하는 것이다.

이 practice 가 그것을 닫는다. 그리고 닫는 순간 **새 문제가 열린다.**

> **베이스는 `-official` 이다.** 이름에는 안 들어가지만 `mcp-security-authn-official` 을
> 복사해 시작한다. 공식 라이브러리만 쓰는 쪽이 배선이 전부 소스에 보여서, 메모리를 얹을 때
> 무엇이 어디에 걸리는지 추적할 수 있다.

## 학습 목표

| # | 목표 | 확인 방법 |
|---|---|---|
| 1 | `conversationId` 를 **서버가** 인증에서 파생시킨다 | 클라이언트가 남의 ID 를 보내도 자기 것으로 강제된다 |
| 2 | 사용자끼리 대화가 섞이지 않는다 | 저장소를 **직접 조회**해 확인 (LLM 답변을 믿지 않는다) |
| 3 | 한 사용자가 여러 대화를 가지되 네임스페이스를 못 벗어난다 | `<user>:<label>` 에서 label 만 클라이언트가 고른다 |
| 4 | **툴 호출 결과가 대화 기록에 남는가** | 툴을 부르는 질문 뒤 저장소를 조회해 실측 |

4번이 이 practice 의 미확인 위험이자 존재 이유다. 아래에서 따로 다룬다.

## 핵심 설계 — conversationId 는 클라이언트 것이 아니다

```java
// 접두사는 서버가 강제한다. label 만 클라이언트가 고른다.
String conversationId = authentication.getName() + ":" + sanitize(label);
```

`bob` 이 `label=alice:default` 를 보내도 결과는 `bob:alice_default` 다 — 자기 네임스페이스를 벗어날 수 없다.

**단순히 `conversationId = authentication.getName()` 로 두지 않는 이유**: 그러면 격리는 보이지만
"클라이언트가 고를 수 있는 부분을 어떻게 안전하게 두느냐"를 못 배운다. 실제 제품은 사용자당
대화가 여럿이고, 위험은 거기서 생긴다.

`sanitize` 는 label 에서 구분자(`:`)와 경로 문자를 제거한다. 이것이 없으면 label 에 `:` 를 넣어
접두사를 위조할 여지가 생긴다 — **이 한 줄이 격리의 전부**이므로 테스트로 고정한다.

## 미확인 위험 — 툴 결과가 메모리에 남는가

`MessageChatMemoryAdvisor` 소스를 읽고 확인한 것:

```java
// before()
Message userMessage = prompt.getLastUserOrToolResponseMessage();   // ToolResponseMessage 도 반환한다
chatMemory.add(conversationId, userMessage);

// after()
assistantMessages = chatResponse.getResults().stream().map(g -> g.getOutput())
chatMemory.add(conversationId, assistantMessages);
```

`getLastUserOrToolResponseMessage()` 가 `ToolResponseMessage` 를 돌려줄 수 있다 —
**라이브러리는 툴 응답이 메모리에 들어가는 경우를 상정하고 있다.**

다만 Spring AI 2.0 의 기본 동작에서는 툴 호출 루프가 `ChatModel` **안에서** 돈다
(`ToolCallingManager`, `internalToolExecutionEnabled` 기본 true). 그렇다면 어드바이저는
바깥 요청 하나와 최종 답변만 보게 되고, 툴이 반환한 원본 데이터는 저장되지 않는다.

**어느 쪽인지 확인하지 못했다.** 두 경우의 의미가 크게 다르다:

| 저장되는 것 | 의미 |
|---|---|
| 사용자 질문 + 최종 답변만 | 툴 원본 데이터는 안 남는다. 유출 표면이 작다 |
| 툴 응답까지 | 조회 결과 원본이 대화 기록에 남는다. 토큰 수명이 끝나도 남고, 이후 턴마다 LLM 에 다시 실린다 |

**1번 태스크에서 실측한다.** 툴을 부르는 질문(`노트북 재고 있어?`)을 던지고
`GET /api/conversations/{id}` 로 저장소를 그대로 덤프한다. 관측한 것만 적는다.

`chat-memory` 에서 배운 방법이다 — 화면에 렌더된 답을 믿지 말고 저장소를 조회한다.

## 레이아웃

```
practice/mcp-security-authn-chat-memory/
├── auth-server/       :9020   ← 사용자 2명 (alice, bob)
├── shop-mcp-server/   :8131
└── shop-agent/        :8130   ← ChatMemory 추가
```

기존 포트 전수 확인: `8080-8082, 8090-8092, 8100-8101, 8110-8111, 8120, 9000, 9010`.
9020/8130/8131 은 충돌하지 않는다 — 여섯 practice 를 동시에 띄울 수 있다.

세션 쿠키 이름도 겹치면 안 된다(쿠키는 호스트만 보고 포트를 구분하지 않는다).
기존: `AUTHSERVERSESSIONID`, `SHOPAGENTSESSIONID`, `OFFICIALAUTHSESSIONID`, `OFFICIALAGENTSESSIONID`.
신규: `MEMAUTHSESSIONID`, `MEMAGENTSESSIONID`.

## 부모와 달라지는 것

`mcp-security-authn-official` 대비 변경점만 적는다. 나머지는 그대로 복사한다.

| 항목 | 변경 |
|---|---|
| auth-server | 사용자 **2명** (`alice`/`alice`, `bob`/`bob`). 격리를 보려면 필수 |
| shop-agent | `ChatMemoryConfig` 추가 (`chat-memory` 에서 가져옴) |
| shop-agent | `ChatClientConfig` 에 `MessageChatMemoryAdvisor` 추가 |
| shop-agent | `ChatController` 가 `Authentication` 을 받아 `conversationId` 를 파생 |
| shop-agent | `ConversationController` 추가 — **단, 인증 주체의 것만 조회·삭제 가능** |
| shop-agent | `index.html` 에 label 입력칸 + 현재 사용자 표시 |
| 포트·쿠키 | 위 표대로 |

`shop-mcp-server` 는 **변경 없다.** 툴도 시드 데이터도 그대로다.

### ConversationController 가 부모의 것과 다른 점

`chat-memory` 판은 아무 ID 나 조회할 수 있었다. 여기서는 안 된다.

```java
@GetMapping("/api/conversations/{label}")
public List<MessageView> messages(Authentication authentication, @PathVariable String label) {
    return read(conversationId(authentication, label));   // 접두사가 강제된다
}
```

경로에서 받는 것은 **label 뿐**이고 전체 ID 를 받지 않는다. 전체 ID 를 받는 API 는 두지 않는다 —
있으면 그것이 곧 구멍이다.

`GET /api/conversations` 는 `findConversationIds()` 를 호출하되 **현재 사용자 접두사로 거른다.**
저장소 자체는 전체를 알고 있으므로, 거르지 않으면 남의 대화 ID 목록이 노출된다.
`chat-memory` 에서는 그 필터가 없었다 — 사용자가 한 명이라 문제가 안 됐을 뿐이다.

## 검증 시나리오

관측한 값만 README 에 적는다.

1. **격리** — `alice` 로 로그인해 `내 이름은 앨리스야` → `bob` 으로 로그인해 `내 이름 뭐야?` → 모른다
2. **네임스페이스 강제** — `bob` 이 `label=alice:default` 로 요청 → 자기 것으로 강제됨.
   저장소 덤프로 `bob:alice_default` 가 생기고 `alice:default` 는 건드려지지 않았음을 확인
3. **목록 필터** — `bob` 의 `GET /api/conversations` 에 `alice:` 로 시작하는 것이 없다
4. **여러 대화** — `alice` 가 label 을 바꿔가며 두 대화를 따로 유지
5. **툴 결과 저장 여부** — 위 미확인 위험 항목. 툴 호출 뒤 저장소 덤프
6. **직접 조회로 교차 확인** — 1~4 를 LLM 답변이 아니라 저장소 조회로 확인

브라우저를 두 개(또는 시크릿 창)로 띄워 두 사용자를 동시에 로그인시킨다.
세션 쿠키 이름이 앞선 practice 들과 달라야 하는 이유가 여기서도 나온다.

## 테스트

- **`conversationId` 파생 단위 테스트** — 이 practice 의 가장 중요한 테스트다.
  `label` 에 `:`, `../`, 빈 문자열, `null` 을 넣어도 접두사를 벗어나지 못한다
- **조회 API 가 남의 대화를 못 읽는다** — `MockMvc` + `@WithMockUser`
- **목록 API 가 접두사로 거른다** — 두 사용자의 대화를 심고 한 명으로 조회
- `ChatMemory` 빈이 내가 정의한 것인지 (`chat-memory` 에서 가져온 행동 검증 테스트)
- MCP 서버 401, 툴 등록 (부모에서 가져옴)

LLM 실호출은 테스트하지 않는다.

## 앞 practice 에서 값을 치른 것들

다시 발견하지 않는다.

| 항목 | 내용 |
|---|---|
| 기동 순서 | auth-server 먼저. JWT 디코더가 issuer 메타데이터를 **즉시** 가져온다 |
| 테스트 전제 | 위 이유로 mcp-server·agent 테스트는 auth-server 가 떠 있어야 한다 |
| MCP 핸드셰이크 | `spring.ai.mcp.client.initialized: false` 없으면 기동이 죽는다 |
| 조용한 스위치 | `spring.ai.mcp.client.type: SYNC`, OAuth2 등록 정확히 1개, `Hooks.enableAutomaticContextPropagation()` |
| 세션 쿠키 | 앱마다 이름을 다르게 (쿠키는 포트를 구분 안 한다) |
| 스트리밍 저장 시점 | assistant 텍스트는 스트림 완료 후 원자적으로 기록된다 (`MessageAggregator` 가 `doOnComplete` 에서 저장). 중간에 끊으면 assistant 턴이 안 남는다 |
| ChatMemory 자동설정 | `ChatMemory`·`ChatMemoryRepository` 는 자동설정이 준다(`@ConditionalOnMissingBean`). `maxMessages` 통제를 위해 `ChatMemory` 만 직접 정의한다 |
| curl | `-H 'Content-Type: text/plain'` 필요. 없으면 Tomcat 이 본문을 폼 파라미터로 소비해 400 |
| Boot 4.1 | `@AutoConfigureMockMvc` 는 `org.springframework.boot.webmvc.test.autoconfigure`, `spring-boot-webmvc-test` 의존 필요 |
| MockMvc | 401 을 보려면 POST 에 `.with(csrf())` 필요 |
| 스크립트 | 백그라운드는 `< /dev/null` + `disown`. `stop.sh` 는 `sleep` 후 `kill -9`. `run.sh` 는 기동 실패 시 로그를 tail 하고 exit 1 |

## 기존 practice 는 건드리지 않는다

`practice/agent-mcp`, `practice/agent-mcps`, `practice/mcp-security-authn-community`,
`practice/mcp-security-authn-official`, `practice/chat-memory` — 다섯 개 전부 한 줄도 수정하지 않는다.
읽고 복사하는 것만 허용한다. 다섯 다 종단 검증이 끝나 있다.

예외: 작업 완료 후 루트 `README.md` 의 practice 목록에 **추가만** 한다.

## 비목표

- **스코프 검사, 툴 단위 인가** → 후속 `mcp-security-authz`
- 영속화(JDBC·Redis) — in-memory 로 충분
- 대화 목록 UI, 제목 자동 생성
- 사용자 3명 이상, 역할 분리
- 요약·압축 메모리
- **토큰 만료 후 기억의 수명 정책** — 관측만 하고 정책은 세우지 않는다 (아래 참고)

## 이 practice 가 답하지 않는 질문

실측 결과에 따라 드러날 수 있는 것들이다. 관측되면 README 에 **열린 질문으로** 적고,
정책을 만들지는 않는다.

- 토큰이 만료돼도 그 토큰으로 조회한 데이터는 대화 기록에 남는다. 얼마나 남아야 하나?
- 사용자의 권한이 축소되면 이미 기억된 데이터는 어떻게 되나?
- `maxMessages` 창이 밀어낸 메시지는 저장소에서 실제로 사라지나, 아니면 조회에서만 빠지나?

## 참고

- [mcp-security-authn-official 설계](2026-09-04-mcp-security-authn-official-design.md)
- [chat-memory 설계](2026-09-05-chat-memory-design.md)
