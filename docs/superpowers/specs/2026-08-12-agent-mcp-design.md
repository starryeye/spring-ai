# Spring AI 2.0 Agent + MCP 학습 프로젝트 설계

작성일: 2026-08-12

## 배경

이 저장소는 2024년 5월 Spring AI **0.8.1** 기준으로 만들어졌고 이후 방치되었다.
그 사이 Spring AI는 1.0 GA(2025-05)를 거쳐 **2.0.0 GA(2026-06-12)** 에 도달했다.

기존 코드(`introduction/joke`, `prompt`)는 현재 **컴파일되지 않는다**:

| 항목 | 기존 (0.8.1) | 현재 (2.0.0) |
|---|---|---|
| 진입점 | `org.springframework.ai.chat.ChatClient` | `ChatModel` 로 개명, `ChatClient` 는 fluent API로 재설계 |
| 호출 | `chatClient.call(prompt).getResult().getOutput().getContent()` | `chatClient.prompt().user(...).call().content()` |
| 텍스트 접근자 | `getContent()` | `getText()` |
| 스타터 | `spring-ai-openai-spring-boot-starter` | `spring-ai-starter-model-openai` |
| 저장소 | `repo.spring.io/milestone` 필요 | Maven Central만 |
| Spring Boot | 3.2.6 | 4.0.x / 4.1.x |
| Jackson | 2.x (`com.fasterxml.jackson`) | 3.x (`tools.jackson`) |

마이그레이션할 만한 자산이 없으므로 기존 코드는 참고용으로 격리하고 새로 시작한다.

## 목표

Spring AI 2.0의 무게중심인 **agentic + MCP**를 최소 예제로 학습한다.
"평범한 Spring Boot 서비스"를 MCP 서버로 감싸고, agent가 MCP를 통해 그 기능을 사용하는
전체 경로를 눈으로 확인하는 것이 목적이다.

비목표: RAG/VectorStore, ChatMemory, 프로덕션 수준의 인증·관측성.

## 저장소 레이아웃

```
spring-ai/
├── legacy-0.8/                  # git mv 로 이동, 빌드 대상 아님
│   ├── introduction/joke
│   └── prompt
└── practice/agent-mcp/
    ├── product-service/         # :8081
    ├── product-mcp-server/      # :8082
    └── product-agent/           # :8080
```

- `legacy-0.8/README.md` 에 "Spring AI 0.8.1 기준, 현재 컴파일 불가, 참고용" 명시.
- 세 프로젝트는 각각 `gradlew` / `settings.gradle` 를 갖는 **독립 프로젝트**
  (기존 `introduction/joke`, `prompt` 와 동일한 스타일 유지).

## 공통 기술 스택

- Java 21 (Gradle toolchain)
- Spring Boot 4.1.0 / Gradle 9.5.1 wrapper
- `org.springframework.ai:spring-ai-bom:2.0.0`
- 저장소는 Maven Central만 (milestone 저장소 불필요)
- 세 프로젝트 모두 Spring Initializr(`start.spring.io/starter.zip`)로 생성한다.
  `webflux` 가 포함되면 Spring AI 의존성이 자동으로 **webflux 변형**으로 해석되고
  `spring-ai-bom:2.0.0` 이 함께 구성되므로 build.gradle 을 손으로 쓸 일이 없다.
- Spring Boot 4 에서 테스트 스타터가 분리되었다 — WebFlux 프로젝트는
  `spring-boot-starter-test` 가 아니라 **`spring-boot-starter-webflux-test`** 를 쓴다.
- 리액티브 스택 (WebFlux) — Spring AI 2.0이 MCP 서버/클라이언트 모두 WebFlux 변형을 제공하고,
  `ChatClient.stream()` 이 `Flux` 를 반환하므로 전 구간을 리액티브로 통일한다.

## 컴포넌트

### 1. product-service (:8081)

Spring AI 의존성이 **없는** 평범한 리액티브 REST 서비스. "AI와 무관한 기존 시스템" 역할.

- 의존성: `spring-boot-starter-webflux`
- 저장소: in-memory (`Map` 기반), 상품 10개 내외 시드 데이터
- 도메인: `Product(id, name, category, price, stock)`

```
GET /api/products?keyword=      → Flux<Product>
GET /api/products/{id}          → Mono<Product>   (없으면 404)
GET /api/products/{id}/stock    → Mono<StockResponse>
```

### 2. product-mcp-server (:8082)

`product-service` 의 기능을 MCP 툴로 노출한다. **LLM을 호출하지 않으므로 모델 의존성이 없다.**

- 의존성: `spring-ai-starter-mcp-server-webflux`, `spring-boot-starter-webflux`
- 설정:
  ```yaml
  spring:
    ai:
      mcp:
        server:
          protocol: STREAMABLE   # 2.0 권장 (SSE는 deprecated)
          type: ASYNC            # 리액티브
  ```
- `WebClient` 로 `product-service` 호출
- 툴:
  ```java
  @Component
  class ProductTools {
      @McpTool(name = "searchProducts", description = "키워드로 상품을 검색한다")
      Flux<Product> search(@McpToolParam(description = "검색 키워드") String keyword);

      @McpTool(name = "getStock", description = "상품 ID로 현재 재고 수량을 조회한다")
      Mono<Integer> stock(@McpToolParam(description = "상품 ID") String productId);
  }
  ```

`description` 이 LLM의 툴 선택 근거이므로 이 프로젝트의 핵심 학습 지점이다.
학습 과제로 description을 의도적으로 부실하게 바꿔 LLM이 툴을 고르지 않는 것을 관찰한다.

> **제약**: `type: ASYNC` 서버는 `Mono` / `Flux` / `Publisher` 반환만 툴로 등록하고,
> 비리액티브 반환 타입은 경고 로그만 남기고 **조용히 제외한다**. 따라서 이 프로젝트의
> `@McpTool` 메서드는 예외 없이 리액티브 타입을 반환해야 한다. 툴이 등록되지 않는
> 증상이 보이면 기동 로그의 필터링 경고를 먼저 확인한다.

### 3. product-agent (:8080)

- 의존성:
  - `spring-ai-starter-mcp-client-webflux`
  - `spring-ai-starter-model-openai`
  - `spring-ai-starter-model-anthropic`
  - `spring-boot-starter-webflux`

**프로바이더는 Spring 프로파일로 하나만 활성화한다.** 의존성은 프로파일로 가를 수 없으므로
두 스타터 모두 클래스패스에 두되, `spring.ai.model.chat` 속성으로 활성 자동설정을 고른다.
활성 `ChatModel` 빈이 항상 하나뿐이므로 자동설정된 `ChatClient.Builder` 를 그대로 사용하며,
별도의 `ChatClientConfig` 나 `@Qualifier` 가 필요 없다.

```yaml
# application.yml (공통)
spring:
  ai:
    model:
      chat: openai          # 기본값
    mcp:
      client:
        streamable-http:
          connections:
            product:
              url: "http://localhost:8082"   # base URL, endpoint 는 기본 /mcp
```

```yaml
# application-anthropic.yml
spring:
  ai:
    model:
      chat: anthropic
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      chat:
        model: claude-opus-5
```

```yaml
# application-openai.yml
spring:
  ai:
    model:
      chat: openai
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        model: gpt-5-mini
```

> Spring AI 2.0 에서 옵션 속성이 평탄화되었다 — 1.x 의 `spring.ai.<provider>.chat.options.model`
> 이 아니라 **`spring.ai.<provider>.chat.model`** 이다. api-key 는 `spring.ai.<provider>.api-key`.

컨트롤러:

```java
@RestController
class ChatController {
    private final ChatClient chatClient;

    ChatController(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<String> chat(@RequestBody String message) {
        return chatClient.prompt().user(message).stream().content();
    }
}
```

MCP 툴은 `ToolCallbackProvider` 를 통해 자동 등록되므로 agent에는 툴 코드가 없다.

프로바이더 전환:

```bash
./gradlew bootRun --args='--spring.profiles.active=anthropic'
```

활성 프로파일 쪽 API 키만 있으면 된다.

## 데이터 흐름

```
사용자 질문
  → product-agent  (ChatClient.stream())
  → LLM: 툴 호출 결정
  → MCP client → streamable HTTP → product-mcp-server
  → WebClient → product-service
  → 결과 역순 전달 → LLM 최종 답변
  → Flux<String> 으로 클라이언트에 스트리밍
```

## 에러 처리

- **product-service 장애**: `@McpTool` 메서드가 예외를 던지지 않고 LLM이 이해할 수 있는
  문장을 반환한다 (예: `"상품 서비스에 연결할 수 없습니다"`). 툴 실패도 대화의 일부다.
- **존재하지 않는 상품 ID**: 마찬가지로 설명 문장을 반환한다. 404를 그대로 전파하지 않는다.
- **API 키 미설정**: 활성 프로파일의 키가 없으면 기동 시점에 실패하도록 둔다
  (요청 시점에 실패하면 원인 파악이 어렵다).
- **MCP 서버 미기동**: agent 기동은 성공하되 툴 목록이 비어 LLM이 일반 답변만 하게 된다.
  이 상태를 로그로 식별할 수 있어야 한다.

## 테스트

- **product-service**: `WebTestClient` 로 각 엔드포인트 검증
- **product-mcp-server**: `ProductTools` 메서드 단위 테스트(`WebClient` 스텁) +
  MCP 클라이언트로 툴 목록/호출 통합 테스트
- **product-agent**: 실제 LLM 호출은 비용과 비결정성 때문에 테스트하지 않는다.
  `ChatModel` 을 스텁으로 대체해 컨트롤러의 스트리밍 응답 형태만 검증한다.

## 실행 순서

1. `product-service` 기동 (:8081)
2. `product-mcp-server` 기동 (:8082)
3. `product-agent` 기동 (:8080, 프로파일 지정)
4. `POST /api/chat` 에 `"노트북 재고 있어?"` 같은 질문

## 확인 완료된 항목

계획 작성 전에 공식 문서와 Initializr 메타데이터로 검증한 값들:

| 항목 | 확인 결과 |
|---|---|
| MCP 클라이언트 속성 경로 | `spring.ai.mcp.client.streamable-http.connections.<name>.url` (base URL) / `.endpoint` (기본 `/mcp`) |
| Spring Boot | 4.1.0 (2026-06-10 릴리스), Gradle 플러그인 `4.1.0`, `io.spring.dependency-management` `1.1.7`, wrapper Gradle 9.5.1 |
| 모델 ID | OpenAI 기본값 `gpt-5-mini` / Anthropic 기본값은 `claude-haiku-4-5` 이나 본 프로젝트는 `claude-opus-5` 를 명시 |
| 옵션 속성 경로 | `spring.ai.<provider>.chat.model` (2.0에서 `chat.options.*` → `chat.*` 평탄화) |
| `@McpTool` 반환 타입 | ASYNC 서버는 `Mono`/`Flux`/`Publisher` 만 등록, 비리액티브 타입은 경고 후 제외 |
| Initializr 파라미터 | `bootVersion=4.1.0` (메타데이터의 `4.1.0.RELEASE` 를 그대로 넘기면 500) |

## 참고

- [Spring AI 2.0.0 GA](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)
- [Getting Started](https://docs.spring.io/spring-ai/reference/getting-started.html)
- [Upgrade Notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html)
- [MCP Server Boot Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)
- [MCP Client Boot Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html)
- [ChatClient API](https://docs.spring.io/spring-ai/reference/api/chatclient.html)
