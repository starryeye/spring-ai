# chat-memory

Spring AI 2.0 — **대화 기억(`ChatMemory`)** 만 단독으로 다루는 최소 예제.

앞선 네 practice(`agent-mcp`, `agent-mcps`, `mcp-security-authn-community`,
`mcp-security-authn-official`)의 에이전트는 매 요청이 완전히 독립적이었다.

```java
chatClient.prompt().user(message).stream().content();   // 이번 질문 한 마디뿐
```

`ChatMemory` 도 `MessageChatMemoryAdvisor` 도 없었으므로 `노트북 재고 있어?` 다음에
`그 중에 더 싼 건?` 이라 물으면 "그 중에"가 무엇인지 모른다. 의도한 것이었다 — 앞 practice 들의
주제는 MCP·인증이라 메모리를 넣으면 관측이 흐려진다. 이 practice 는 그 미뤄둔 것을 **단독으로**
다룬다. MCP 도 인증도 없다. 앱 하나(`memory-agent`, :8120), 사용자 한 명 가정.

## 학습 목표

| # | 목표 | 확인 방법 | 관측 결과 |
|---|---|---|---|
| 1 | 같은 대화 안에서 앞 맥락이 이어진다 | 같은 `conversationId` 로 "내 이름은 X" → "내 이름 뭐야?" | `alpha` 에서 "내 이름은 스타리야" → "내 이름 뭐야?" 에 "당신의 이름은 스타리야입니다 😊" 로 답함 |
| 2 | 다른 대화끼리는 섞이지 않는다 | 다른 `conversationId` 로 같은 질문 | 새 대화(`c-4k8a6e`)에서 "내 이름 뭐야?" → "저는 당신의 이름을 모르겠습니다..." 로 답함 |
| 3 | 저장소에 실제로 무엇이 쌓이는지 안다 | `GET /api/conversations/{id}` | `alpha` 조회 시 user/assistant 메시지 4개가 순서대로 확인됨 |
| 4 | 창(window)이 넘치면 무슨 일이 나는지 안다 | `max-messages=2` 로 재기동 후 대화를 3턴 진행 | 1턴 후 저장소 2개, 2턴 후에도 2개(1턴 내용 소실), 3턴째 "좋아하는 색이 뭐라고 했지?" 에 모델이 "그런 내용이 없다"고 답함 |

네 항목 모두 브라우저로 직접 실행해 확인했다 — 아래 [직접 해보기](#직접-해보기) 참고.

## 세 조각이 어떻게 맞물리나

| 조각 | 역할 |
|---|---|
| `ChatMemoryRepository` | 실제 저장. 기본 구현은 `InMemoryChatMemoryRepository` |
| `ChatMemory` | 저장소 위의 정책. `MessageWindowChatMemory` 는 `maxMessages` 만큼만 유지 |
| `MessageChatMemoryAdvisor` | 요청 전 대화를 프롬프트에 끼워 넣고, 응답 후 저장 |
| `ChatMemory.CONVERSATION_ID` | 어느 대화인지 지정하는 요청 파라미터 (실측값 `"chat_memory_conversation_id"`) |

흐름은 이렇다. `ChatClientConfig` 가 `MessageChatMemoryAdvisor` 를 `ChatClient` 의 기본
어드바이저로 붙인다. 이 어드바이저는 **요청 전**에는 해당 대화에 쌓인 메시지를 프롬프트 앞에
끼워 넣고, **응답 후**에는 이번 턴의 user/assistant 메시지를 저장소에 새로 쓴다. 어느 대화인지는
어드바이저가 알아서 정하지 않는다 — `ChatController` 가 매 요청마다
`.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))` 로 알려줘야 한다.
**어드바이저만 붙여서는 부족하다.** 이 파라미터가 없으면 모든 요청이 하나의 기본 대화로 섞인다.

`MessageWindowChatMemory` 는 `ChatMemory` 인터페이스의 한 구현으로, 저장소(`ChatMemoryRepository`)
위에서 "최근 `maxMessages` 개만 유지한다"는 정책을 얹는다. 즉 실제 데이터는 저장소에 있고,
`ChatMemory` 는 그 위의 창(window) 규칙일 뿐이다.

## 자동설정과의 관계

`ChatMemory` 와 `ChatMemoryRepository` 빈은 사실 `ChatMemoryAutoConfiguration` 이 공짜로
준다 — 둘 다 `@ConditionalOnMissingBean` 이고, `spring-ai-starter-model-ollama` 가 전이
의존으로 끌어오므로 **별도 의존성을 추가할 필요가 없다.** 이 practice 는 `maxMessages` 를
설정값으로 통제하기 위해 `ChatMemory` 만 직접 정의하고(`ChatMemoryConfig`), 저장소는 자동설정이
주는 `InMemoryChatMemoryRepository` 를 그대로 주입받아 쓴다.

이 관계는 **양방향으로 실측했다**:

- `ChatMemoryConfig` 의 `@Bean` 을 지우면 자동설정 폴백이 대신 들어오고, 그 폴백은
  `chat.memory.max-messages` 프로퍼티를 읽지 않아 창 크기가 기본값 20으로 고정된다 — 창 넘침
  테스트(기대 3, 실제 4)가 정확히 그 지점에서 실패했다.
- `@Bean` 을 복원하면 같은 테스트가 다시 통과한다.

주의할 점 하나: 자동설정 폴백도 `MessageWindowChatMemory` 타입이고 기본 창 크기도 20이다.
그래서 "타입이 `MessageWindowChatMemory` 인가"만 확인하는 테스트는 `@Bean` 을 통째로 지워도
초록불이 나온다 — 이 practice 를 만들면서 실제로 걸렸던 함정이다. 검증은 반드시 **동작**(설정한
`maxMessages` 값이 실제로 반영되는지)으로 해야 한다.

## 실행

```bash
cd practice/chat-memory
./run.sh
```

Java 21 을 찾고, Ollama 가 안 떠 있으면 띄우고, `qwen3:8b` 가 없으면 받고, `memory-agent` 를
백그라운드로 기동한다. 준비되면 브라우저에서 `http://localhost:8120/` 을 연다.

```bash
./stop.sh
```

## 직접 해보기

브라우저 페이지는 대화 ID 입력칸 + 질문칸 + "새 대화"/"저장 내용 보기"/"이 대화 비우기" 버튼이다.

> **curl 로 직접 호출할 때 주의:** `curl -d '...'` 는 기본적으로
> `Content-Type: application/x-www-form-urlencoded` 를 붙이는데, 이 헤더가 있으면 Tomcat 이
> 본문을 폼 파라미터로 먼저 소비해버려 `@RequestBody` 가 읽을 스트림이 빈 채로 남는다
> (`HttpMessageNotReadableException: Required request body is missing`). 아래 모든 curl
> 예시에 `-H 'Content-Type: text/plain'` 을 넣은 이유다 — 빠뜨리면 400 이 난다.

1. **이어짐** — 대화 ID `alpha` 로 이어서 묻는다.

   ```bash
   curl -s -N -X POST 'http://localhost:8120/api/chat?conversationId=alpha' \
     -H 'Content-Type: text/plain' -d '내 이름은 스타리야'
   curl -s -N -X POST 'http://localhost:8120/api/chat?conversationId=alpha' \
     -H 'Content-Type: text/plain' -d '내 이름 뭐야?'
   ```

   관측: "당신의 이름은 스타리야입니다 😊" — 앞 턴을 기억한다.

2. **격리** — "새 대화" 로 다른 ID 를 받고 같은 질문을 한다. 관측: "저는 당신의 이름을
   모르겠습니다." — 다른 대화로는 넘어가지 않는다.

3. **저장소 내용** — `alpha` 로 돌아와 조회한다.

   ```bash
   curl -s http://localhost:8120/api/conversations/alpha | python3 -m json.tool
   ```

   관측: user/assistant 메시지 4개가 순서대로("내 이름은 스타리야" → 인사 →
   "내 이름 뭐야?" → "당신의 이름은...") 보인다.

4. **창 넘침** — `application.yml` 의 `chat.memory.max-messages` 를 `2` 로 바꿔 재기동한다
   (in-memory 저장소라 재기동하면 기존 대화는 전부 사라진다). 새 대화에서 3턴을 이어간다.
   관측: 1턴 후 저장소 2개(user+assistant), 2턴 후에도 여전히 2개지만 내용이 2턴 것으로
   교체되어 1턴 내용이 사라져 있다. 3턴째 "1턴에서 말한 걸 물어보면" 모델이 "그런 내용이
   없다"고 명확히 답해, 창 밖으로 밀려난 정보가 실제로 사라졌음을 확인했다. **실험 후
   `max-messages` 를 20으로 되돌린다** — 이 저장소의 커밋 상태는 20이다.

5. **삭제** — "이 대화 비우기" 후 같은 질문을 한다.

   ```bash
   curl -s -X DELETE http://localhost:8120/api/conversations/alpha
   ```

   관측: "모르겠습니다" + `GET /api/conversations/alpha` 가 `[]` 를 반환.

**검증 방법에 대한 교훈 하나.** 시나리오 2를 처음 브라우저로 실행했을 때 질문 입력창이 전송 후
비워지지 않아, 이전 텍스트 뒤에 새 텍스트가 그대로 이어 붙어 전송됐다("내 이름은 스타리야내
이름 뭐야?"). 그 결과 모델이 이름을 답해 마치 대화가 새는 것처럼 **보였다.** 실제로는 크로스
대화 누수가 아니라 입력 방법의 오류였다 — `GET /api/conversations/{id}` 로 저장소에 실제로
쌓인 문자열을 직접 확인하고서야 원인을 알았다. **화면에 보이는 답변이 아니라 저장소 조회
엔드포인트로 확인하는 것**이 이 practice 의 요점이라, 이 저장소에서는 원인이 된 버그(전송 후
입력창 미초기화)를 고쳐뒀다.

## 스트리밍 저장 시점

`MessageChatMemoryAdvisor` 는 `adviseStream(...)` 을 구현하고 `Scheduler` 를 갖는다 — 저장이
스트리밍 도중 조각마다 일어나는지, 끝난 뒤 한 번에 일어나는지는 소스만 봐서는 확정할 수 없었다.
두 실험으로 실측했다.

### 실험 A — 스트리밍 도중 조회

첫 시도는 브리프가 제시한 고정 3초 뒤 조회였는데, 이 모델/설정(`think: low`)은 첫 토큰이
나오기까지 8~9초가 걸려 3초 시점엔 아직 아무 바이트도 도착하지 않은 상태였다 — "아직 생각
중"만 잡았을 뿐 스트리밍 중은 검증하지 못했다. 그래서 고정 sleep 대신 **응답 바이트가 처음
도착하는 순간을 0.2초 간격으로 폴링**해 그 시점에 저장소를 다시 조회했다.

```
토큰이 처음 나타난 시점: 8.44초 후, 바이트=3
--- 토큰 흐르는 도중 저장소 조회 ---
[ {"role": "user", "text": "자기소개를 아주 길게 해줘"} ]
--- 현재 curl 프로세스 살아있는지: 살아있음 (86바이트 중 10바이트 전송됨) ---
--- 스트리밍 완전 종료 (총 9.84초) ---
[
  {"role": "user", "text": "자기소개를 아주 길게 해줘"},
  {"role": "assistant", "text": "이전 대화 내용이 없어 자세한 자기소개를 드릴 수 없습니다. 😊"}
]
```

**관측 결과 (표본 스냅샷 기준):** 클라이언트로 토큰이 실제로 흐르고 있는 순간에 저장소를
조회한 두 차례의 시도 모두에서 user 메시지만 있었고, assistant 메시지가 조각난 채로 보인
적은 없었다. assistant 메시지는 스트림이 완전히 끝난 뒤 한 번에 나타났다. 다만 이는 시행당
한 번씩 저장소를 조회한 결과이지 스트리밍 구간을 연속으로 샘플링한 것은 아니다 — "그 스냅샷
시점에는 조각난 상태가 관측되지 않았다"까지만 말할 수 있다.

### 실험 B — 스트리밍 중단

```bash
curl -s -N -X POST 'http://localhost:8120/api/chat?conversationId=aborted' \
  -H 'Content-Type: text/plain' -d '자기소개를 아주 길게 해줘' --max-time 3 || true
```

```
(curl exit code: 28 — --max-time 으로 중단됨)
--- 중간에 끊은 뒤 ---
[ {"role": "user", "text": "자기소개를 아주 길게 해줘"} ]
--- 15초 추가 대기 후 재확인 ---
[ {"role": "user", "text": "자기소개를 아주 길게 해줘"} ]
```

**관측 결과:** 중단 직후, 그리고 약 15초 뒤 재확인한 시점까지(총 관측 창 약 17초) assistant
메시지는 부분적으로도 나타나지 않았다 — user 메시지만 남았다. 그 이후 더 긴 시간이 지나도
그대로인지는 이 실험의 관측 범위 밖이다.

### 정리

두 실험을 함께 보면, 저장은 스트림이 정상적으로 끝난 시점에 한 번 일어나는 것으로 보이고
토큰 단위로 점진 저장되는 정황은 관측되지 않았다. 클라이언트가 중간에 끊으면 그 턴의
user 메시지는 남고 assistant 응답은 (관측한 창 안에서는) 남지 않는다 — 다음 턴에서 모델은
"질문은 했는데 답은 없는" 히스토리를 이어받는다.

## 학습 포인트

- **대화를 가르는 키가 곧 격리 경계다.** 지금은 클라이언트가 `conversationId` 를 보내므로
  남의 대화 ID 를 넣으면 그대로 읽힌다 — 사용자 한 명을 가정하기 때문에 성립하는 것이다.
- `MessageWindowChatMemory` 는 오래된 메시지부터 밀어낸다. 창 크기가 곧 기억의 길이다.
- 저장소에 쌓이는 것은 결국 메시지 목록이다 — 마법이 아니라 `GET /api/conversations/{id}` 로
  확인할 수 있는 평범한 데이터다.
- `findConversationIds()` 는 `ChatMemoryRepository` 에만 있고 `ChatMemory` 에는 없다.
  `ConversationController` 가 둘 다 주입받는 이유가 그것이다 — 대화 ID 목록 조회와 메시지
  조회/삭제는 서로 다른 인터페이스의 책임이다.
- 스트리밍 저장은 토큰마다가 아니라 스트림이 끝난 시점에 한 번(관측 범위 안에서) 일어난다 —
  자세한 내용은 [스트리밍 저장 시점](#스트리밍-저장-시점) 참고.
- **화면에 보이는 답변을 믿지 말고 저장소를 직접 조회해야 한다.** 입력창 미초기화로 "대화가
  샌다"는 거짓 양성을 겪었는데, 저장소를 직접 봤기 때문에 진짜 원인(입력 오염)을 찾을 수
  있었다. 답변만 보고 판단했다면 메모리 격리가 깨졌다고 오판했을 것이다.
- `ChatMemory`/`ChatMemoryRepository` 자동설정 우선순위(`@ConditionalOnMissingBean`)는
  타입만 확인해서는 증명되지 않는다 — 자동설정 폴백도 같은 타입, 같은 기본값을 만들 수
  있기 때문이다. 설정값이 실제로 반영되는지 **동작**으로 확인해야 한다 (위
  [자동설정과의 관계](#자동설정과의-관계) 참고).
- curl 로 `-d` 를 쓰면 기본 `Content-Type` 때문에 400 이 난다 — 원인은 미디어타입 불일치가
  아니라 Tomcat 이 form-urlencoded 본문을 먼저 소비해버리는 것이다 (위 [직접 해보기](#직접-해보기) 참고).

## 트러블슈팅

| 증상 | 원인 / 확인할 곳 |
|---|---|
| curl 로 보내면 400, 로그에 `Required request body is missing` | `-d` 사용 시 curl 이 기본으로 `Content-Type: application/x-www-form-urlencoded` 를 붙인다. Tomcat 이 본문을 폼 파라미터로 먼저 소비해 `@RequestBody` 가 읽을 스트림이 빈다. `-H 'Content-Type: text/plain'` 추가 |
| 브라우저에서 다른 대화로 넘어간 것처럼 보임(격리 실패로 의심됨) | 화면 답변만 보지 말고 `GET /api/conversations/{id}` 로 저장소를 직접 확인한다. 입력창에 이전 텍스트가 남아 있지 않은지도 함께 본다 |
| `ChatMemoryConfig` 의 `@Bean` 을 지웠는데도 앱이 잘 뜬다 | 자동설정 폴백이 대신 들어온다(`@ConditionalOnMissingBean`) — 기동은 되지만 `chat.memory.max-messages` 를 더는 반영하지 않는다(창 크기 기본값 20 고정) |
| 응답이 8~9초 동안 안 온다 | `application.yml` 의 `think: low` 때문에 모델이 첫 토큰 전 그만큼 "생각"한다. 정상이다 |
| 포트 8120 이 이미 사용 중 | `./run.sh` 는 감지하면 기동을 건너뛴다. `./stop.sh` 로 내린 뒤 다시 실행 |
| 처음 실행할 때 한동안 응답이 없음 | `qwen3:8b` 모델을 처음 내려받는 중이다. `run.sh` 가 자동으로 `ollama pull` 한다 |

## 다음 practice 로

지금은 `conversationId` 를 **클라이언트가 정한다.** 인증이 없으니 서버가 "누구인지"로 대화를
가를 수 없기 때문이다 — 이는 한계가 아니라 의도적으로 비워둔 자리다. 하지만 그대로 두면 남의
대화 ID 를 넣기만 해도 그대로 읽힌다. 이건 사용자 한 명을 가정하기 때문에만 성립하는 것이다.

다음 단계에서 `mcp-security-authn-official` 과 합치면 이 자리에 `Authentication.getName()` 이
들어가고, 그 순간 사용자별 격리가 된다 — 대화 ID가 더 이상 클라이언트가 주장하는 값이 아니라
서버가 인증된 신원에서 파생시킨 값이 되기 때문이다.

그런데 그것으로 끝이 아니다. **MCP 툴 호출 결과는 대화 기록에 그대로 남는다** — 이 practice가
보여준 것처럼, 저장소에 쌓이는 것은 결국 메시지 목록이고 어드바이저는 그것을 구분 없이
프롬프트에 다시 끼워 넣는다. 즉 사용자 A 가 MCP 툴로 조회한 데이터가 A 의 대화 기록에
메시지로 남고, 만약 대화 격리에 구멍이 있다면(혹은 세션/캐시가 사용자 사이에 잘못 공유되면)
그 데이터가 사용자 B 의 컨텍스트로 새어 들어갈 수 있다. **인증만으로는 이 문제를 풀 수 없다**
— "누가 요청했는가"를 아는 것과 "그 요청으로 얻은 데이터가 어느 대화에만 머무는가"를 보장하는
것은 서로 다른 문제이기 때문이다. 다음 practice 의 출발점이 여기다.
