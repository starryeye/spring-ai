# agent-mcps — MCP 서버 여러 개와 툴 필터링 설계

작성일: 2026-08-13

## 배경

`practice/agent-mcp` 는 **기존 REST 서비스를 MCP 서버로 감싸 에이전트에 붙이는 것**이 주제였다.
MCP 서버가 하나뿐이라 다루지 못한 주제가 둘 남았다:

1. MCP 서버가 여러 개일 때 툴이 어떻게 합쳐지는가
2. 그중 일부만 모델에게 노출하려면 어떻게 하는가

`practice/agent-mcps` 는 그 둘을 다룬다.

### 사전 확인 (실측)

설계 전에 `agent-mcp` 에 커넥션을 하나 더 추가해 확인한 사실이다.

- MCP 서버가 N개여도 **`ToolCallbackProvider` 빈은 1개**다. 자동설정
  (`McpToolCallbackAutoConfiguration.mcpAsyncToolCallbacks`)이
  `ObjectProvider<List<McpAsyncClient>>` 를 받아 하나의 provider 로 합친다.
  따라서 `toolCallbackProvider.ifAvailable(configured::defaultTools)` 한 줄로 전부 붙는다.
- 툴 이름이 충돌하면 `alt_1_`, `alt_2_` 접두사가 붙는다. **서버 이름이 아니라 충돌 순번**이라
  서버를 추가/제거하면 기존 툴 이름이 바뀔 수 있다. (`McpToolNamePrefixGenerator` 로 교체 가능)
- 확장점은 `McpToolFilter` — `BiPredicate<McpConnectionInfo, McpSchema.Tool>` 이며
  빈으로 등록하면 자동설정이 주워간다. 접두사가 붙기 **전** 원본 이름으로 판단한다.
- `getToolCallbacks()` 는 요청마다 호출된다. 즉 필터도 매 요청 평가된다.

## 목표

- MCP 서버 2개를 한 에이전트에 붙이고, 두 서버를 연달아 써야 풀리는 질문을 처리한다.
- `McpToolFilter` 로 노출 툴을 제어하고, **같은 질문이 필터 설정에 따라 달라지는 것**을 관찰한다.
- 프롬프트로 "하지 마"라고 부탁하는 것과 **애초에 툴을 주지 않는 것**의 차이를 보여준다.

비목표: 백엔드 REST 서비스 분리(agent-mcp 에서 다뤘다), RAG, ChatMemory, 인증.

## 레이아웃

```
practice/agent-mcps/
├── product-mcp-server/   :8091
├── order-mcp-server/     :8092
└── shop-agent/           :8090
```

- 포트는 `agent-mcp`(8080~8082)와 겹치지 않게 8090번대를 쓴다. 두 practice 를 동시에 띄울 수 있다.
- **MCP 서버가 데이터를 직접 보유한다.** 별도 REST 서비스를 두지 않는다 —
  "기존 서비스 감싸기"는 이미 배웠고, 여기서는 멀티 MCP 와 필터가 주제다.
- 세 프로젝트는 각각 `gradlew` / `settings.gradle` 를 갖는 독립 프로젝트다 (기존 스타일 유지).

## 공통 기술 스택

`agent-mcp` 와 동일하다. Java 21, Spring Boot 4.1.0, Spring AI BOM 2.0.0, Gradle 9.5.1,
WebFlux, MCP streamable-HTTP, Spring Initializr 생성.

## 컴포넌트

### 1. product-mcp-server (:8091)

`agent-mcp` 의 상품 데이터를 그대로 in-memory 로 들고 있다. WebClient 도, 백엔드 호출도 없다.

| 툴 | 반환 | 설명 |
|---|---|---|
| `searchProducts(keyword?)` | `Mono<String>` | 키워드 부분일치. 생략 시 전체 |
| `getStock(productId)` | `Mono<String>` | 재고 수량. 0이면 품절 문구 |

시드 데이터는 `agent-mcp` 와 같은 10건을 쓴다 (p1 게이밍 노트북 재고 7, p3 무선 기계식 키보드 재고 0 등).
같은 데이터를 쓰면 두 practice 를 오가며 비교하기 쉽다.

### 2. order-mcp-server (:8092)

주문 데이터를 in-memory 로 보유한다. 주문에는 **상품 ID가 들어 있어** 두 서버가 연결된다.

| 툴 | 반환 | 성격 |
|---|---|---|
| `searchOrders(customer?)` | `Mono<String>` | 읽기 |
| `getOrder(orderId)` | `Mono<String>` | 읽기. 주문한 **상품 ID**를 포함해 반환 |
| `cancelOrder(orderId)` | `Mono<String>` | **쓰기 — 위험** |

시드 주문은 정확히 다음 3건이다. 검증 시나리오가 이 값에 의존하므로 임의로 바꾸지 않는다.

| 주문 ID | 고객 | 상품 | 수량 | 상태 |
|---|---|---|---|---|
| `o1` | 홍길동 | `p1` (게이밍 노트북 15인치) | 1 | 결제완료 |
| `o2` | 홍길동 | `p5` (27인치 4K 모니터) | 2 | 배송중 |
| `o3` | 김영희 | `p3` (무선 기계식 키보드) | 1 | 결제완료 |

`o1` 이 `p1` 을 가리키는 것이 "내가 주문한 노트북 아직 재고 있어?" 시나리오의 연결고리다
(`p1` 재고는 7).

`cancelOrder` 는 실제로 상태를 `취소됨` 으로 바꾼다. 필터 없이 호출하면 정말로 바뀌므로,
필터의 효과가 말이 아니라 상태 변화로 드러난다. 재고를 되돌리지는 않는다 —
두 서버는 서로를 모르며, 그것이 분리된 MCP 서버의 정상적인 모습이다.

### 3. shop-agent (:8090)

MCP 클라이언트 2개 + LLM + 필터.

```yaml
spring:
  ai:
    mcp:
      client:
        streamable-http:
          connections:
            product: { url: "http://localhost:8091" }
            order:   { url: "http://localhost:8092" }
```

`ChatClientConfig` 의 툴 배선 코드는 `agent-mcp` 와 **동일하다** — 서버가 둘이어도 바뀌지 않는다는
사실 자체가 학습 포인트다.

## 필터 설계

`McpToolFilter` 는 빈 하나뿐이라 프로파일을 필터 축으로 또 쓰면
`--spring.profiles.active=ollama,safe` 처럼 축이 둘로 늘어 지저분해진다.
**속성 하나로 모드를 고른다.**

```java
@Bean
McpToolFilter toolFilter(@Value("${shop.tool-filter:safe}") String mode) {
    return switch (mode) {
        case "none"         -> (conn, tool) -> true;
        case "product-only" -> (conn, tool) -> conn.clientInfo().name().endsWith(" - product");
        default             -> (conn, tool) -> !tool.name().equals("cancelOrder");   // safe
    };
}
```

기본값은 `safe`. 알 수 없는 값이 오면 `safe` 로 동작한다(안전한 쪽으로 기운다).

`conn.clientInfo().name()` 은 `"spring-ai-mcp-client - product"` 형태다 — 커넥션 이름이 접미로 붙는다.

### 관찰할 결과

| 질문 | `none` | `safe` (기본) | `product-only` |
|---|---|---|---|
| "o1 주문 취소해줘" | 취소됨 (`getOrder(o1)` 로 상태가 `취소됨` 인 것을 확인 가능) | **툴이 없어 못 함** | 못 함 |
| "홍길동 주문 보여줘" | o1·o2 조회됨 | o1·o2 조회됨 | **툴이 없어 못 함** |
| "노트북 재고 있어?" | 조회됨 | 조회됨 | 조회됨 |

노출 툴 수로도 확인된다 — `none` 5개, `safe` 4개, `product-only` 2개.

## 데이터 흐름

```
"내가 주문한 노트북 아직 재고 있어?"
  → shop-agent
  → LLM: getOrder(o1) 호출 결정        → order-mcp-server   (상품 ID p1 획득)
  → LLM: getStock(p1) 호출 결정        → product-mcp-server (재고 7 획득)
  → 두 결과를 합쳐 답변
```

두 서버를 연달아 써야 풀린다는 점이 멀티 MCP 의 존재 이유를 보여준다.
로컬 8B 모델은 이 2단계 추론에 실패할 수 있다 — 실패하면 그 자체를 관찰 결과로 기록한다.

## 에러 처리

`agent-mcp` 와 동일한 원칙이다. 툴은 예외를 던지지 않고 LLM 이 읽을 문장을 반환한다.

- 존재하지 않는 주문/상품 ID → "찾을 수 없습니다"
- 이미 취소된 주문을 다시 취소 → "이미 취소된 주문입니다"
- 각 툴은 호출 시 `log.info`, 실패 시 `log.error` 를 남긴다 (필터 동작 확인에 필요하다)

## 테스트

- **필터 단위 테스트** — 이 예제에서 가장 값진 테스트다. `McpToolFilter` 는 `BiPredicate` 라
  서버를 띄우지 않고 3개 모드 × 툴 조합을 직접 검증할 수 있다.
  `McpConnectionInfo` 와 `McpSchema.Tool` 을 직접 만들어 넣는다.
- 각 MCP 서버의 툴 메서드 단위 테스트 (저장소를 직접 쓰므로 스텁도 필요 없다)
- shop-agent 배선 테스트 — 스텁 `ToolCallbackProvider` 로 `defaultTools` 연결 검증
  (`agent-mcp` 의 `ChatClientToolWiringTest` 와 동일한 형태)

LLM 실호출은 테스트하지 않는다.

## agent-mcp 에서 이미 값을 치른 것들

다시 발견하지 않는다. 구현 계획에 제약으로 명시한다.

| 항목 | 내용 |
|---|---|
| 툴 배선 | `defaultTools(provider)` 로 직접 꽂아야 한다. 자동으로 안 붙는다 |
| 블로킹 | `.stream()` 을 `Mono.fromCallable(...).subscribeOn(boundedElastic())` 으로 감쌀 것 |
| 모델 타입 | `spring.ai.model.{audio.speech, audio.transcription, embedding, image, moderation}: none` |
| 응답 | `text/plain` (SSE 는 토큰마다 `data:` 가 붙어 읽을 수 없다) |
| ollama | `think: low` (미지정은 수 분, `false` 는 툴 설명을 못 따른다) |
| 애노테이션 | `org.springframework.ai.mcp.annotation.{McpTool, McpToolParam}` |
| 반환 타입 | ASYNC 서버는 `Mono`/`Flux` 만 등록된다 |
| 속성 | `spring.ai.<provider>.chat.model` (`chat.options.*` 아님) |
| 테스트 | `spring-boot-starter-webflux-test`, WebTestClient 쓸 땐 `@AutoConfigureWebTestClient` |
| 키 | `secrets.yml` + `spring.config.import`, `.gitignore` 등록 |
| 빌드 | Initializr 로 생성, `bootVersion=4.1.0` |

## 실행

`agent-mcp` 와 같은 방식으로 `run.sh` / `stop.sh` 를 제공한다. 필터 모드를 인자로 받는다.

```bash
./run.sh                      # ollama + safe
./run.sh ollama none          # 필터 없음 — cancelOrder 노출
./run.sh ollama product-only  # order 서버 툴 전부 차단
./stop.sh
```

## 학습 과제로 남길 것

README 에 과제로 적고 코드로는 만들지 않는다.

- 두 서버의 툴 이름을 같게 바꿔보면 `alt_1_` 접두사가 붙는 것을 관찰할 수 있다.
- `McpToolNamePrefixGenerator` 빈을 등록해 접두사 규칙을 서버 이름 기반으로 바꿔볼 수 있다.
- 필터 없이(`none`) 프롬프트로만 "취소하지 마"라고 지시했을 때 모델이 지키는지 시험해볼 수 있다.
  이것이 필터가 필요한 이유를 가장 직접적으로 보여준다.

## 참고

- [agent-mcp 설계](2026-08-12-agent-mcp-design.md)
- [MCP Client Boot Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html)
- [MCP Annotations (Server)](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-server.html)
