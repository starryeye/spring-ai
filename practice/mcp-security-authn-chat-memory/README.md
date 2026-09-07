# mcp-security-authn-chat-memory

**두 부모 practice를 합친다.** [`mcp-security-authn-official`](../mcp-security-authn-official)이
만든 "브라우저 로그인 → 에이전트가 그 사용자를 대신해 보호된 MCP 서버를 호출"하는 흐름에,
[`chat-memory`](../chat-memory)가 만든 "`ChatMemory`로 대화를 기억"하는 흐름을 얹는다. 다른 점은
`conversationId`를 만드는 주체다 — 클라이언트가 아니라 서버가, 로그인한 사용자의
`Authentication`에서 파생시킨다. 그 결과가 사용자별 대화 격리다.

`auth-server` :9020 / `shop-mcp-server` :8131 / `shop-agent` :8130. 사용자는 `alice`/`alice`,
`bob`/`bob` 두 명.

## 부모와 달라지는 것

| 항목 | 부모 (`chat-memory` 단독일 때) | 여기 (합친 것) |
|---|---|---|
| `conversationId` | 클라이언트가 통째로 지정한다(`POST /api/chat?conversationId=alpha`) — 사용자가 한 명이라 성립했다 | 서버가 `Authentication`에서 파생시킨다(`<username>:<sanitize(label)>`). 클라이언트는 `label`만 고른다(`POST /api/chat?label=default`) |
| 대화 조회 | 전체 ID를 그대로 받아 조회한다(`GET /api/conversations/{id}`) — 남의 ID를 넣으면 그대로 읽힌다 | **전체 ID를 받는 API가 아예 없다.** `GET /api/conversations/{label}`은 `label`만 받고, 서버가 항상 호출자 자신의 접두사를 강제로 붙인다 |
| 대화 목록 | `ChatMemoryRepository.findConversationIds()`를 거르지 않고 그대로 반환한다 — 저장소 전체를 안다는 뜻이다 | `GET /api/conversations`가 호출자 접두사로 **필터링**한다 |
| 사용자 수 | 1명(가정) | 2명(`alice`, `bob`) — 격리를 검증하려면 최소 2명이 필요하다 |
| 포트 | `memory-agent` :8120 (단일 앱) | `auth-server` :9020 / `shop-mcp-server` :8131 / `shop-agent` :8130 (3앱, `official`과 동일 구조) |

## 핵심 — `conversationId`는 클라이언트 것이 아니다

`chat-memory`의 컨트롤러는 이랬다.

```java
// chat-memory — 클라이언트가 conversationId 를 통째로 보낸다
public Flux<String> chat(@RequestParam String conversationId, @RequestBody String message)
```

여기서는 클라이언트가 `conversationId` 자체를 볼 일이 없다. 대신 `label`을 보내면 서버가
`Authentication`으로부터 실제 ID를 만든다.

```java
// mcp-security-authn-chat-memory — 서버가 Authentication 에서 파생시킨다
public Flux<String> chat(Authentication authentication,
                         @RequestParam(required = false) String label,
                         @RequestBody String message) {
    String conversationId = ConversationId.of(authentication, label);
    ...
}
```

그 파생 로직 전체가 `ConversationId.sanitize` 한 곳에 있다 — 이 practice의 격리는 사실상 이
메서드 하나다.

```java
private static String sanitize(String label) {
    if (label == null || label.isBlank()) {
        return DEFAULT_LABEL;
    }
    String cleaned = label.trim().replaceAll("[^A-Za-z0-9가-힣_-]", "_");
    return cleaned.isBlank() ? DEFAULT_LABEL : cleaned;
}
```

`:`를 포함해 영숫자·한글·`_`·`-` 이외의 모든 문자를 `_`로 바꾼다. 이걸 지우지 않으면 label에
`alice:default`처럼 구분자를 넣어 접두사 자체를 위조할 수 있다.

**이 테스트가 공허하지 않다는 것을 직접 확인했다** (Task 2에서). `replaceAll(...)` 호출을
`label.trim()`으로 바꿔치기하고 돌렸더니, `ConversationIdTest`의 6개 테스트 중 정확히 아래
2개만 실패했다.

- `label_에_구분자를_넣어도_남의_네임스페이스로_못_간다`
- `경로_문자를_넣어도_벗어나지_못한다`

나머지 4개(접두사 부여, 기본값 처리, 접두사 조회, 사용자 간 ID 구분)는 그대로 통과했다 — 이
2개만 정확히 `sanitize`의 존재 여부에 반응한다는 뜻이다. `replaceAll(...)`을 복원하니 6개
전부 다시 통과했다.

**왜 `Authentication.getName()` 하나만 conversationId로 쓰지 않았나.** 그러면 위조는 원천
차단되지만 사용자 한 명이 대화를 하나만 가질 수 있다 — 시나리오 4(같은 사용자가 `work`와
`default`를 따로 가짐)가 애초에 불가능해진다. 이 practice의 요점은 "클라이언트 입력을 아예
없앤다"가 아니라 **"클라이언트가 고르는 값(label)을 어떻게 안전하게 남의 네임스페이스로
못 나가게 가두는가"**를 배우는 것이다. 그래서 `label`은 남기고 접두사만 서버가 강제한다.

## 실행

준비물은 `official`과 같다.

```bash
brew install ollama
ollama serve &
ollama pull qwen3:8b
```

```bash
cd practice/mcp-security-authn-chat-memory
./run.sh
```

`auth-server(:9020) → shop-mcp-server(:8131) → shop-agent(:8130)` 순서로 뜬다. 순서가 강제되는
이유는 `official`과 동일 — `NimbusJwtDecoder.withIssuerLocation(...)`과 OIDC discovery가 빈
생성 시점에 즉시 issuer 메타데이터를 조회한다. `shop-mcp-server`와 `shop-agent`의
`./gradlew test`도 `auth-server`가 떠 있어야 통과한다.

브라우저에서 `http://localhost:8130/`을 열고 `alice`/`alice` 또는 `bob`/`bob`으로 로그인한다.
`spring.ai.mcp.client.initialized: false`이므로 **로그인 후 첫 채팅 요청은 그 안에서 MCP
핸드셰이크까지 함께 하느라 눈에 띄게 느리다**(실측 약 30초) — 두 번째 질문부터는 빨라진다.

**두 사용자를 동시에 로그인시켜 격리를 눈으로 보려면 창 두 개로는 부족하다.** 세션 쿠키
(`MEMAGENTSESSIONID`)는 브라우저 프로필 단위로 공유되므로, 같은 브라우저의 일반 창 두 개는
쿠키를 공유해 나중에 로그인한 사용자가 먼저 로그인한 사용자를 덮어쓴다. 시크릿/프라이빗
창 하나 + 일반 창 하나, 또는 서로 다른 브라우저를 써야 진짜 동시 세션 두 개가 된다. 이 문서의
검증도 이 제약 때문에 (자동화 브라우저 프로필 하나만 써서) **순차 로그인 + 저장소 조회**로
수행했다 — alice로 시나리오를 마치고 완전히 로그아웃한 뒤 bob으로 전환하는 식이다. 그래도
저장소를 상대방 세션에서 직접 조회해 교차 확인했으므로 격리 자체의 증거력은 동일하다.

**로그아웃은 한 곳만으로 끝나지 않는다.** `shop-agent`에서 `POST /logout`만 부르면
shop-agent의 세션은 끊기지만, **auth-server(:9020)의 OIDC 세션은 살아있어서** 다음 요청 때
`oauth2Login`이 사용자 상호작용 없이 같은 사용자로 조용히 재인증해버린다(실측: `/logout` 직후
`fetch('/api/conversations')`가 200으로 이전 사용자의 목록을 그대로 반환). 사용자를 바꾸려면
`http://localhost:8130/logout` → `http://localhost:9020/logout`(확인 페이지에서 "Log out" 클릭)
→ 다시 `http://localhost:8130/logout` 세 단계가 모두 필요하다. 이건 애플리케이션 결함이
아니라 백채널 로그아웃(`OidcClientInitiatedLogoutSuccessHandler`)을 설정하지 않은 표준 OAuth2
동작이다 — **브라우저 하나로 사용자를 바꿔가며 시험하는 사람은 반드시 이걸 겪고, 격리가 깨진
것으로 오해하기 쉽다.**

**직접 호출할 때 CSRF를 반드시 챙겨야 한다.** 이 practice는 CSRF를 예외 없이 전면 적용한다
(아래 학습 포인트 참고). 페이지는 `CookieCsrfTokenRepository`가 심어준 `XSRF-TOKEN` 쿠키를
읽어 `X-XSRF-TOKEN` 헤더로 되돌려 보낸다. `GET`은 토큰이 필요 없지만(실측: 헤더 없이 200),
`POST /api/chat`과 `DELETE /api/conversations/{label}`은 세션 쿠키와 이 헤더가 **둘 다** 없으면
403이 난다. 로그인 자체가 OAuth2 브라우저 흐름이라 curl 하나로 재현할 수 없으므로, 직접
호출하려면 로그인된 브라우저에서 쿠키(`MEMAGENTSESSIONID`, `XSRF-TOKEN`)를 복사해 와야 한다.

```bash
curl -s -N -X POST 'http://localhost:8130/api/chat?label=default' \
  -H 'Content-Type: text/plain' \
  -H 'Cookie: MEMAGENTSESSIONID=<브라우저에서 복사>; XSRF-TOKEN=<브라우저에서 복사>' \
  -H 'X-XSRF-TOKEN: <위 쿠키와 같은 값>' \
  -d '노트북 재고 있어?'
```

`Content-Type: text/plain`을 빠뜨리면 `chat-memory`와 같은 이유로 400이 난다 — curl의 기본
`Content-Type`(`application/x-www-form-urlencoded`)이 붙으면 Tomcat이 본문을 폼 파라미터로
먼저 소비해 `@RequestBody`가 빈 스트림을 받는다.

```bash
./stop.sh
```

## 검증 결과 (실측)

2026-09-08, 세 앱을 실제로 띄우고 브라우저(`mcp__Claude_Browser__*`)로 조작해 관측했다.
예측값이 아니다. 전체 원문은 [Task 3 리포트](../../.superpowers/sdd/2026-09-07-mcp-security-authn-chat-memory/task-3-report.md)에 있다.

### 시나리오 1 — 격리

alice(label=`default`)가 "내 이름은 앨리스야"를 보낸 뒤, bob(label=`default`, 별도 로그인
세션)이 "내 이름 뭐야?"를 보냈다.

> "저는 고객님의 이름을 저장하거나 조회할 수 있는 시스템이 없습니다. 도움이 필요하시면
> 말씀해주세요!"

bob 세션으로 `GET /api/conversations/default`를 조회해도 이 대화만 있고 alice의 내용은 전혀
없었다. **관측: 격리됨 — LLM 답변과 저장소 조회 결과가 일치한다.**

### 시나리오 2 — 네임스페이스 강제

bob이 label에 `alice:default`를 넣고 "내가 누구야?"를 보냈다. 재현을 위해 앱을 재기동해 처음
부터 다시 실행하고 원문 JSON을 양쪽 다 확보했다(아래는 그 재현의 원문).

**bob의 시도 전, alice의 실제 `default` 대화** (`GET /api/conversations/default`, alice 세션):

```json
[
  {"role": "user", "text": "내 이름은 앨리스야"},
  {"role": "assistant", "text": "안녕하세요, 앨리스님! 도움이 필요하시면 언제든지 말씀해주세요. 😊"}
]
```

bob이 label=`alice:default`로 "내가 누구야?"를 보낸 뒤, **bob 세션의 대화 목록**
(`GET /api/conversations`):

```json
["bob:alice_default"]
```

`ConversationId.sanitize`가 `alice:default`의 `:`를 `_`로 바꾸므로 실제 ID는
`bob:alice_default`가 된다 — `alice:`로 시작하는 항목은 bob의 네임스페이스에 없다.

**bob의 시도 후, alice의 `default` 대화를 재로그인해서 다시 조회**
(`GET /api/conversations/default`, alice 세션):

```json
[{"role":"user","text":"내 이름은 앨리스야"},{"role":"assistant","text":"안녕하세요, 앨리스님! 도움이 필요하시면 언제든지 말씀해주세요. 😊"}]
```

**bob의 시도 전후로 바이트 단위로 동일하다.** 두 절반이 함께 있어야 증거가 된다 — "bob이 못
읽었다"만으로는 bob의 네임스페이스가 그냥 비어 있었을 뿐이라는 가능성을 배제하지 못한다. 여기서는
(1) bob의 시도가 bob 자신의 네임스페이스 안(`bob:alice_default`)으로 귀결되었고, (2) alice의
진짜 대화는 손상 없이 그대로였다는 것을 둘 다 원문 JSON으로 확인했다.

부가 관측(본문 실행에서): bob이 문자 그대로 `GET /api/conversations/alice:default`를 요청하면
서버는 이를 항상 "호출자 자신의 접두사 + sanitize(경로변수)"로 재해석하므로, `alice:default`라는
경로변수도 결국 `bob:alice_default`로 귀결된다 — bob이 요청한 문자열이 무엇이든 절대 alice의
네임스페이스에 도달하지 못한다.

### 시나리오 3 — 목록 필터

bob 세션 `GET /api/conversations` → `["bob:default","bob:alice_default"]`.
alice 세션(재로그인 후) `GET /api/conversations` → `["alice:work","alice:default","alice:tools"]`.

서로의 ID가 전혀 섞이지 않는다. **관측: 각자 자기 접두사만 보인다.**

### 시나리오 4 — 한 사용자의 여러 대화

alice가 label=`work`로 "내 취미는 등산이야"를 보낸 뒤, label을 `default`로 되돌려 "내 취미
뭐야?"를 물었다.

> "저는 앨리스님의 취미에 대한 정보를 제공할 수 없습니다. 😊 도움이 필요하시면 쇼핑 관련
> 질문을 언제든지 주세요!"

**관측: 같은 사용자라도 label(대화)이 다르면 내용이 섞이지 않는다.**

### 시나리오 5 — 서버 로그 교차 확인

```
$ grep '호출' logs/shop-mcp-server.log
searchProducts 호출 (keyword=노트북, 사용자=alice)
```

**관측: MCP 서버에 도착한 신원은 `alice`다.** `official`의 결론(신원이 에이전트가 아니라
로그인한 사람)이 메모리 계층을 얹은 뒤에도 그대로 유지된다.

### 시나리오 6 — 종료 확인

`./stop.sh` 후 9020/8130/8131 세 포트 모두 해제 확인, `git status` 클린 확인. 애플리케이션
코드는 검증 과정에서 전혀 수정하지 않았다.

## 툴 결과가 메모리에 남는가 — 이 practice의 핵심 발견

`chat-memory`는 이 질문을 미해결 상태로 다음 practice에 넘겼다. 여기서 실제로 측정했다.

alice(label=`tools`)가 "노트북 재고 있어?"를 보냈다(첫 요청이라 MCP 핸드셰이크 포함, 약 30초).

> "노트북 관련 상품 재고 정보입니다:
>
> 1. [p1] 게이밍 노트북 15인치 - 1,890,000원 / 재고 7개
> 2. [p2] 사무용 노트북 14인치 - 990,000원 / 재고 23개
>
> 재고가 있는 상품입니다. 구체적인 모델이나 추가 정보가 필요하시면 말씀해주세요."

`GET /api/conversations/tools` 원문:

```json
[
  {"role": "user", "text": "노트북 재고 있어?"},
  {"role": "assistant", "text": "노트북 관련 상품 재고 정보입니다:\n\n1. [p1] 게이밍 노트북 15인치 - 1,890,000원 / 재고 7개  \n2. [p2] 사무용 노트북 14인치 - 990,000원 / 재고 23개  \n\n재고가 있는 상품입니다. 구체적인 모델이나 추가 정보가 필요하시면 말씀해주세요."}
]
```

**관측 (예측이 아니다) — 두 가지가 동시에 참이다.**

- `role`은 `user`, `assistant` **두 종류만** 존재한다. `tool`(`ToolResponseMessage`) 역할의
  메시지는 **없다** — 원본 툴 응답 자체는 저장소에 남지 않는다.
- 그런데도 `assistant` 메시지의 텍스트 안에 조회된 수치 **7과 23이 그대로** 들어 있다.

Spring AI 2.0의 기본 동작에서 툴 호출/응답은 `ChatModel` 내부 루프에서 소비되고,
`MessageChatMemoryAdvisor.before()`는 최종 사용자 메시지와 최종 assistant 텍스트만 저장한다.
**원본 `ToolResponseMessage`는 메모리에 남지 않지만, 모델이 그 수치를 최종 답변 텍스트로
옮겨 적었기 때문에 데이터 자체는 결과적으로 대화 기록에 살아남아 이후 턴마다 다시 LLM에
실린다** — 다만 원본 툴 프로토콜 형식이 아니라 자연어로 파라프레이즈된 형태로.

유출 표면은 "완전히 없음"과 "원본 그대로 남음"의 중간이다: **정형 데이터(JSON, 툴 스키마)로는
안 남지만, 텍스트로는 남는다.** `chat-memory`가 다음 practice로 넘기며 예상했던 "MCP 툴 호출
결과는 대화 기록에 그대로 남는다"는 절반만 맞았다 — "그대로(raw)"는 틀렸고 "남는다"는 맞았다.

## 학습 포인트

- **대화를 가르는 키가 곧 격리 경계다. 그 키를 누가 정하느냐가 전부다.** `chat-memory`는
  클라이언트가 키 전체를 정했다. 여기서는 서버가 키의 접두사를 정하고 클라이언트는 나머지
  (label)만 고른다 — 그 차이 하나가 격리의 유무를 가른다.
- `findConversationIds()`는 저장소 전체를 안다 — 거르지 않으면 남의 ID가 샌다. 부모
  `chat-memory`에 그 필터가 없었던 것은 버그가 아니라 **사용자 1명 가정의 결과**였다(그
  가정이 깨지는 순간 드러나는 종류의 문제).
- 전체 ID를 받는 API를 애초에 두지 않는 것이 가장 확실한 방어다. "필터링을 잘 하는 API"보다
  "잘못 부를 방법 자체가 없는 API"가 강하다 — `GET /api/conversations/{label}`은 label만
  받고, 그 값이 무엇이든 서버가 항상 호출자 접두사를 강제로 붙인다.
- **툴 호출 결과는 원본 형식으로는 저장소에 남지 않지만, 최종 답변 텍스트로 파라프레이즈되어
  결과적으로 남는다** — 위 "툴 결과가 메모리에 남는가" 절 참고. 이건 이 practice가 실제로
  측정하기 전까지는 추측일 뿐이었다.
- `official`에서 그대로 유지된 것들: 기동 순서(`auth-server → shop-mcp-server → shop-agent`,
  JWT 디코더와 OIDC discovery가 빈 생성 시점에 즉시 조회하기 때문), `spring.ai.mcp.client.
  initialized: false`(첫 채팅 요청이 그만큼 느려짐), 조용히 실패하는 스위치들(`official`
  README 7.1절 실험 그대로 재현 가능한 구조), 세션 쿠키 이름을 앱마다 분리하는 관례.
- **CSRF를 예외 없이 전면 적용했다.** 두 부모 practice(`community`, `official`)는 둘 다
  `/api/chat`을 CSRF 검사에서 면제했다. 여기서는 어떤 엔드포인트도 면제하지 않는다 — 대신
  `CookieCsrfTokenRepository.withHttpOnlyFalse()`로 `XSRF-TOKEN` 쿠키를 발급하고, 페이지가
  그 값을 읽어 `X-XSRF-TOKEN` 헤더로 되돌려 보내는 표준 SPA 패턴을 쓴다. 상태를 바꾸는
  `DELETE /api/conversations/{label}`을 조회성 `/api/chat`과 같은 이유로 면제하는 것이
  보안 경계를 다루는 practice의 취지와 맞지 않는다고 판단했다.
- **Spring Boot 4.1은 `@AutoConfigureMockMvc`에 `springSecurity()`를 더 이상 자동으로
  붙이지 않는다.** `spring-boot-test-autoconfigure` 3.3~3.5에는 있던
  `MockMvcSecurityConfiguration`이 Boot 4.1의 `spring-boot-webmvc-test`에는 대응물이 없다
  (바이트코드로 직접 확인). 이 배선이 없으면 `SecurityContextHolderFilter`가 요청마다
  컨텍스트를 다시 읽어 `@WithMockUser`가 심어둔 인증을 덮어써서 모든 요청이 302로
  리다이렉트된다. `ConversationControllerTest`가 `@TestConfiguration` +
  `MockMvcBuilderCustomizer`로 이 배선을 직접 되살린다 — Boot 4.1에서 보안이 걸린 MockMvc
  테스트를 처음 짜는 사람이라면 반드시 걸리게 될 지점이다.

## 답하지 않는 질문

스펙이 정책을 세우지 않고 열어둔 질문 세 가지다. 이 practice는 아래를 **답하지 않는다** —
관측된 사실(위 "핵심 발견")만 다루고, 정책은 만들지 않는다.

- 토큰이 만료돼도 그 토큰으로 조회한 데이터는 대화 기록에 남는다. 얼마나 남아야 하나?
- 사용자의 권한이 축소되면 이미 기억된 데이터는 어떻게 되나?
- `maxMessages` 창이 밀어낸 메시지는 저장소에서 실제로 사라지나, 아니면 조회에서만 빠지나?

## 트러블슈팅

| 증상 | 확인할 곳 |
|---|---|
| `shop-mcp-server`/`shop-agent` 기동 실패 (`ConnectException`) | `auth-server`(:9020)를 먼저 띄웠는가 |
| `./gradlew test`가 컨텍스트 로드부터 실패 | 같은 이유 — `auth-server`가 떠 있어야 한다 |
| 로그아웃했는데 여전히 같은 사용자로 보인다 | `shop-agent`만 로그아웃했을 것이다. `http://localhost:9020/logout`까지 확인 클릭해야 auth-server 세션도 끊긴다(위 실행 절 참고) |
| `POST /api/chat`, `DELETE /api/conversations/{label}`이 403 | CSRF 예외가 없다. `X-XSRF-TOKEN` 헤더와 `XSRF-TOKEN` 쿠키 값이 일치하는지 확인 |
| curl로 채팅을 보내면 400, `Required request body is missing` | `-H 'Content-Type: text/plain'`을 빠뜨렸다 |
| 두 사용자를 한 브라우저 창 두 개로 동시 로그인했는데 서로 덮어쓴다 | 쿠키는 프로필 단위로 공유된다 — 시크릿 창 하나 + 일반 창 하나, 혹은 다른 브라우저를 써야 한다 |
| 보안이 걸린 `MockMvc` 테스트가 전부 302 | Boot 4.1은 `springSecurity()`를 자동으로 안 붙인다 — `ConversationControllerTest`의 `SecurityMockMvcSupport` 패턴 참고 |
| 로그인 후 첫 질문이 유난히 느리다(약 30초) | 정상이다 — `initialized: false`라 첫 요청 안에서 MCP 핸드셰이크가 함께 일어난다 |
| 포트가 이미 사용 중 | `./stop.sh` 후 재실행 |

## 다음 practice로

이 practice는 "누가 요청했는가"(authn)만 다룬다. `shop-mcp-server`의 `searchProducts` 툴은
로그인한 사람이면 누구나 호출할 수 있고, 스코프나 역할에 따라 특정 툴을 막는 장치가 없다.
그건 인가(authz)의 영역이고, 다음 `mcp-security-authz` practice가 다룰 자리다 — 토큰의
스코프를 확인하고 툴 단위로 접근을 통제하는 것.

## 참고

- [설계 스펙](../../docs/superpowers/specs/2026-09-07-mcp-security-authn-chat-memory-design.md)
- [구현 계획](../../docs/superpowers/plans/2026-09-07-mcp-security-authn-chat-memory.md)
- 합친 두 부모: [`mcp-security-authn-official`](../mcp-security-authn-official) ·
  [`chat-memory`](../chat-memory)
