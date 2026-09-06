# chat-memory — 대화 기억 하나만 다룬다

작성일: 2026-09-05

## 배경

앞선 네 practice 의 에이전트는 **매 요청이 완전히 독립적**이었다.

```java
chatClient.prompt().user(message).stream().content();   // 이번 질문 한 마디뿐
```

`ChatMemory` 도 `MessageChatMemoryAdvisor` 도 없었으므로, LLM 에게 가는 것은
시스템 프롬프트 + 이번 질문 + 툴 정의가 전부였다. `노트북 재고 있어?` 다음에
`그 중에 더 싼 건?` 이라고 물으면 "그 중에"가 무엇인지 모른다.

의도한 것이었다 — 주제가 인증·인가라 메모리를 넣으면 관측이 흐려진다.
답변에 재고 숫자가 없을 때 "툴을 안 불렀나, 기억에서 답했나"를 구분하기 어려워지기 때문이다.

이 practice 는 그 미뤄둔 것을 **단독으로** 다룬다.

## 목표

`ChatMemory`, `MessageChatMemoryAdvisor`, `conversationId` — 이 셋이 어떻게 맞물리는지
직접 배선하고 눈으로 확인한다.

| # | 목표 | 확인 방법 |
|---|---|---|
| 1 | 같은 대화 안에서 앞 맥락이 이어진다 | 같은 `conversationId` 로 "내 이름은 X" → "내 이름 뭐야?" |
| 2 | 다른 대화끼리는 **섞이지 않는다** | 다른 `conversationId` 로 같은 질문 → 모른다고 답한다 |
| 3 | 저장소에 실제로 무엇이 쌓이는지 안다 | 조회 엔드포인트로 메시지 목록 덤프 |
| 4 | 창(window)이 넘치면 무슨 일이 나는지 안다 | `maxMessages` 를 줄이고 대화를 길게 |

**MCP 도 인증도 넣지 않는다.** 앱 하나, 포트 하나다.

## 사용자는 한 명이라고 가정한다

이 practice 에서 `conversationId` 는 **클라이언트가 보낸다.** 인증이 없으므로 서버가
"누구인지"로 대화를 가를 수 없기 때문이다.

이건 한계가 아니라 **의도적으로 비워둔 자리**다. 후속 practice 에서
`mcp-security-authn-official` 과 합칠 때 이 자리에 `Authentication.getName()` 이
들어가면 그대로 사용자별 격리가 된다. 지금은 그 자리를 손으로 채워보면서
"대화를 가르는 키가 무엇이냐가 곧 격리 경계"라는 것을 먼저 이해한다.

## 확인된 API — 실측

`spring-ai-model-2.0.0.jar`, `spring-ai-client-chat-2.0.0.jar`,
`spring-ai-autoconfigure-model-chat-memory-2.0.0.jar` 를 `javap` 로 확인했다.

```java
public interface ChatMemory {
    String CONVERSATION_ID = "chat_memory_conversation_id";   // 실제 상수 값
    void add(String conversationId, List<Message> messages);
    List<Message> get(String conversationId);
    void clear(String conversationId);
}

public interface ChatMemoryRepository {
    List<String> findConversationIds();
    List<Message> findByConversationId(String conversationId);
    void saveAll(String conversationId, List<Message> messages);
    void deleteByConversationId(String conversationId);
}

MessageWindowChatMemory.builder()
        .chatMemoryRepository(repository)
        .maxMessages(int)
        .build();

MessageChatMemoryAdvisor.builder(chatMemory).order(int).scheduler(...).build();

// 요청별 대화 지정
chatClient.prompt().advisors(a -> a.param(ChatMemory.CONVERSATION_ID, id))
```

**`ChatMemory` 와 `ChatMemoryRepository` 빈은 자동설정이 공짜로 준다**
(`ChatMemoryAutoConfiguration`, 둘 다 `@ConditionalOnMissingBean`).
그리고 `spring-ai-autoconfigure-model-chat-memory` 는 모델 스타터가 이미
전이 의존으로 끌어온다 — 의존성을 추가할 필요가 없다
(`mcp-security-authn-official/shop-agent` 의 `runtimeClasspath` 로 확인).

기본 저장소는 `InMemoryChatMemoryRepository` 다.

## 레이아웃

```
practice/chat-memory/
└── memory-agent/   :8120
```

포트 8120 은 앞선 네 practice(8080~8082, 8090~8092, 9000·8100·8101, 9010·8110·8111)와
겹치지 않는다. 다섯 practice 를 동시에 띄울 수 있다.

## 컴포넌트

앱 하나. 파일 구성:

| 파일 | 책임 |
|---|---|
| `MemoryAgentApplication` | 기동 |
| `ChatMemoryConfig` | `ChatMemory` 빈을 **직접 정의**해 `maxMessages` 를 통제한다 |
| `ChatClientConfig` | 시스템 프롬프트 + `defaultAdvisors(MessageChatMemoryAdvisor)` |
| `ChatController` | `/api/chat`, 대화 조회·삭제 |
| `static/index.html` | 대화 ID 입력칸 + 질문칸 + 출력 |

### ChatMemory 를 직접 정의하는 이유

자동설정이 주는 것을 그대로 써도 동작하지만, `maxMessages` 를 바꿔가며 관측해야 하므로
직접 정의한다. **자동설정이 `@ConditionalOnMissingBean` 이라 내가 정의하면 물러난다** —
이 관계 자체가 학습 포인트다(앞 practice 들에서 계속 나온 패턴이다).

```java
@Bean
public ChatMemory chatMemory(ChatMemoryRepository repository,
                             @Value("${chat.memory.max-messages:20}") int maxMessages) {
    return MessageWindowChatMemory.builder()
            .chatMemoryRepository(repository)
            .maxMessages(maxMessages)
            .build();
}
```

`maxMessages` 를 설정으로 뺀 이유는 목표 4번을 재기동만으로 실험하기 위해서다.

### 엔드포인트

| 메서드 | 경로 | 하는 일 |
|---|---|---|
| `POST` | `/api/chat?conversationId=<id>` | 질문. 본문은 평문. 응답은 `text/plain` 스트리밍 |
| `GET` | `/api/conversations/{id}` | 저장된 메시지 목록 (역할 + 내용) |
| `DELETE` | `/api/conversations/{id}` | 해당 대화 비우기 |
| `GET` | `/api/conversations` | 저장된 대화 ID 목록 |

조회·삭제 엔드포인트가 이 practice 의 절반이다. **저장소 안을 들여다볼 수 있어야**
"기억한다"가 마법이 아니라 메시지 목록이라는 것이 드러난다.
`GET /api/conversations` 는 `ChatMemoryRepository.findConversationIds()` 를 쓴다 —
`ChatMemory` 인터페이스에는 없고 저장소에만 있는 기능이라, 둘의 역할 차이가 드러난다.

### 브라우저 페이지

대화 ID 입력칸 하나가 추가된 것 말고는 앞 practice 의 `index.html` 과 같다.
기본값을 하나 채워두고, "새 대화" 버튼이 랜덤 ID 를 넣는다.
**UI 연습이 아니다** — 프레임워크·빌드도구 없이 파일 한 장이다.

## 미확인 위험 — 1번 태스크로 실측한다

**스트리밍에서 메모리 저장이 언제 일어나는가.**

`MessageChatMemoryAdvisor` 는 `adviseStream(...)` 을 구현하고 `Scheduler` 를 갖는다.
응답이 다 끝난 뒤에 한 번 저장되는지, 스트리밍 도중 조각마다 저장되는지 확인하지 못했다.

이게 중요한 이유:

- 스트리밍이 끝나기 전에 조회하면 무엇이 보이는가
- 스트리밍이 중간에 끊기면(브라우저 닫기 등) 반쪽 답변이 저장에 남는가

**1번 태스크에서 실측한다.** 스트리밍 중간과 종료 후에 각각 `GET /api/conversations/{id}` 를
호출해 비교하고, 관측한 그대로 기록한다. 예측을 적지 않는다.

이번 저장소 작업에서 가정으로 여러 번 틀렸으므로(`ToolCallbackProvider` 자동배선,
JWT 디코더 지연 조회, "official 에는 조용한 스위치가 없다") 단정하지 않는다.

## 검증 시나리오

관측한 값만 README 에 적는다.

1. **이어짐** — `conversationId=alpha` 로 `내 이름은 스타리야` → `내 이름 뭐야?` → 이름을 답한다
2. **격리** — `conversationId=beta` 로 `내 이름 뭐야?` → 모른다고 답한다
3. **저장소 내용** — `GET /api/conversations/alpha` → user/assistant 메시지가 순서대로 보인다
4. **창 넘침** — `chat.memory.max-messages=2` 로 재기동 후 대화를 이어가면 앞부분이 잘린다
5. **삭제** — `DELETE /api/conversations/alpha` 후 다시 물으면 모른다고 답한다
6. **스트리밍 중 저장 시점** — 위 미확인 위험 항목

## 테스트

- `ChatMemory` 빈이 **내가 정의한 것**인지 (`MessageWindowChatMemory`), `maxMessages` 가 반영되는지
- 같은 ID 로 넣고 꺼내면 나오고, 다른 ID 로는 안 나온다 — `ChatMemory` 단위 테스트
- `maxMessages` 를 넘기면 오래된 것이 밀려난다
- 조회·삭제 엔드포인트가 저장소를 실제로 읽고 비운다 (`MockMvc`)
- 컨텍스트 로드 + `MessageChatMemoryAdvisor` 가 `ChatClient` 에 실제로 붙었는지

LLM 실호출은 테스트하지 않는다.

## 앞 practice 에서 값을 치른 것들

다시 발견하지 않는다.

| 항목 | 내용 |
|---|---|
| 모델 타입 | `spring.ai.model.{audio.*, embedding, image, moderation}: none` |
| 응답 | `text/plain;charset=UTF-8` (SSE 는 토큰마다 `data:` 가 붙어 못 읽는다) |
| ollama | `spring.ai.ollama.chat.think: low` |
| 속성 | `spring.ai.ollama.chat.model` |
| Boot 4.1 | `@AutoConfigureMockMvc` 는 `org.springframework.boot.webmvc.test.autoconfigure`, `spring-boot-webmvc-test` 의존 필요 |
| 빌드 | Initializr `bootVersion=4.1.0`, Java 21 toolchain, `gradle.properties` 에 JDK 절대경로 금지 |
| 스크립트 | 백그라운드는 `< /dev/null` + `disown`. `stop.sh` 는 `sleep` 후 `kill -9` |
| 테스트 | 한국어 메서드명, AssertJ |

servlet 스택이지만 MCP 서버가 없으므로 `@McpTool` 반환 타입 규칙은 해당 없다.

## 기존 practice 는 건드리지 않는다

`practice/agent-mcp`, `practice/agent-mcps`, `practice/mcp-security-authn-community`,
`practice/mcp-security-authn-official` 은 한 줄도 수정하지 않는다. 읽기만 허용한다.
넷 다 종단 검증이 끝나 있다.

예외: 작업 완료 후 루트 `README.md` 의 practice 목록에 **추가만** 한다.

## 비목표

- **사용자별 격리 → 후속 practice** (`mcp-security-authn-official` + 이것)
- 영속화(JDBC·Redis 등) — in-memory 로 충분하다
- 요약·압축 메모리, `PromptChatMemoryAdvisor` 와의 비교
- 대화 목록 UI, 제목 자동 생성
- MCP, 인증, 인가

## 후속 practice 로 이어지는 지점

이 practice 가 `conversationId` 를 **쿼리 파라미터로 받는다**는 것이 다음 단계의 출발점이다.
거기서는 그 값을 클라이언트가 정하게 두면 안 된다 — 남의 대화 ID 를 넣으면 그대로 읽히기 때문이다.
`Authentication.getName()` 에서 파생시키는 순간 그 문제가 사라지고, 동시에
"MCP 툴 호출 결과가 대화 기록에 남는다"는 새로운 문제가 생긴다.
A 가 조회한 데이터가 B 의 컨텍스트로 새면 인증을 아무리 잘해도 소용없다.

## 참고

- [mcp-security-authn-official 설계](2026-09-04-mcp-security-authn-official-design.md)
- [mcp-security-authn-community 설계](2026-08-25-mcp-security-authn-design.md)
