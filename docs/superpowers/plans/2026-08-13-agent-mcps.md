# agent-mcps 구현 계획 — MCP 서버 여러 개와 툴 필터링

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** MCP 서버 2개를 한 에이전트에 붙이고, `McpToolFilter` 로 노출 툴을 제어해 같은 질문이 필터 설정에 따라 달라지는 것을 관찰한다.

**Architecture:** 독립 Gradle 프로젝트 3개. `product-mcp-server`(:8091)와 `order-mcp-server`(:8092)는 각자 in-memory 데이터를 들고 `@McpTool` 만 노출한다(백엔드 REST 서비스 없음). `shop-agent`(:8090)는 두 서버에 MCP 클라이언트로 붙고, 속성 하나로 세 가지 필터 모드를 전환한다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring AI 2.0.0, Gradle 9.5.1, WebFlux, MCP streamable-HTTP, Ollama(qwen3:8b)

**설계 문서:** [docs/superpowers/specs/2026-08-13-agent-mcps-design.md](../specs/2026-08-13-agent-mcps-design.md)

## Global Constraints

모든 태스크에 적용된다. **`practice/agent-mcp` 에서 이미 값을 치른 항목들이므로 다시 발견하지 말 것.**

- **Java 21** / **Spring Boot 4.1.0** / **Spring AI BOM 2.0.0** / Gradle wrapper **9.5.1**, Maven Central 만.
- 기본 JDK 가 21이 아닐 수 있다. 모든 Gradle 명령 전에:
  `export JAVA_HOME=/Users/starryeye/.sdkman/candidates/java/21.0.6-amzn`
- 프로젝트 생성은 **Spring Initializr** 로 한다. build.gradle 을 손으로 쓰지 않는다.
  `bootVersion=4.1.0` 으로 넘긴다 (`4.1.0.RELEASE` 를 넘기면 서버가 500 을 반환한다).
- 테스트 스타터는 **`spring-boot-starter-webflux-test`** 다 (Boot 4 에서 분리됨).
  `WebTestClient` 를 쓰는 테스트에는 `@AutoConfigureWebTestClient` 가 **필요하다**.
- MCP 애노테이션 패키지는 **`org.springframework.ai.mcp.annotation`** 이다.
- **`@McpTool` 메서드는 반드시 `Mono`/`Flux` 를 반환한다.** ASYNC 서버는 비리액티브 반환 타입을
  경고만 남기고 조용히 툴 목록에서 제외한다.
- Spring AI 2.0 속성은 **`spring.ai.<provider>.chat.model`** 이다 (`chat.options.*` 아님).
- 모든 커밋은 `agent-mcps` 브랜치에 한다.
- `application.properties` 는 지우고 `application.yml` 로 쓴다.
- 테스트 단언은 **AssertJ `assertThat`** 을 쓴다. 자바 `assert` 키워드 금지.
- 포트는 **8090/8091/8092** — `practice/agent-mcp`(8080~8082)와 겹치면 안 된다.

---

## File Structure

```
practice/agent-mcps/
├── run.sh  stop.sh  README.md  .gitignore        # Task 5
│
├── product-mcp-server/                            # Task 1  :8091
│   └── src/main/java/dev/starryeye/productmcpserver/
│       ├── ProductMcpServerApplication.java       (Initializr 생성)
│       ├── Product.java                           — 도메인 record
│       ├── ProductRepository.java                 — in-memory 저장소
│       └── ProductTools.java                      — @McpTool 2개
│
├── order-mcp-server/                              # Task 2  :8092
│   └── src/main/java/dev/starryeye/ordermcpserver/
│       ├── OrderMcpServerApplication.java         (Initializr 생성)
│       ├── Order.java                             — 도메인 record
│       ├── OrderRepository.java                   — in-memory, 상태 변경 가능
│       └── OrderTools.java                        — @McpTool 3개 (cancelOrder 포함)
│
└── shop-agent/                                    # Task 3, 4  :8090
    └── src/main/java/dev/starryeye/shopagent/
        ├── ShopAgentApplication.java              (Initializr 생성)
        ├── ChatClientConfig.java                  — 시스템 프롬프트 + 툴 배선   (Task 3)
        ├── ChatController.java                    — 스트리밍 채팅              (Task 3)
        └── ToolFilterConfig.java                  — McpToolFilter 3모드        (Task 4)
```

두 MCP 서버는 서로를 모른다. 저장소를 직접 쓰므로 `WebClient` 도 `ProductClient` 같은 인터페이스도 없다 — `agent-mcp` 와 달리 HTTP 경계가 없어 테스트에 스텁이 필요 없다.

---

## Task 1: product-mcp-server — 상품 MCP 서버

**Files:**
- Create: `practice/agent-mcps/product-mcp-server/` (Initializr 생성)
- Create: `.../src/main/java/dev/starryeye/productmcpserver/Product.java`
- Create: `.../src/main/java/dev/starryeye/productmcpserver/ProductRepository.java`
- Create: `.../src/main/java/dev/starryeye/productmcpserver/ProductTools.java`
- Create: `.../src/main/resources/application.yml`
- Delete: `.../src/main/resources/application.properties`
- Test: `.../src/test/java/dev/starryeye/productmcpserver/ProductToolsTest.java`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces: Task 3·4·5 가 쓸 MCP 툴 2개 —
  - `searchProducts(String keyword)` → `Mono<String>` (keyword 는 optional)
  - `getStock(String productId)` → `Mono<String>`

  MCP 엔드포인트는 `http://localhost:8091/mcp` (endpoint 기본값 `/mcp`).

- [ ] **Step 1: Initializr 로 프로젝트 생성**

```bash
mkdir -p practice/agent-mcps
cd practice/agent-mcps
curl -s -o product-mcp-server.zip -G https://start.spring.io/starter.zip \
  -d type=gradle-project -d language=java -d bootVersion=4.1.0 \
  -d groupId=dev.starryeye -d artifactId=product-mcp-server -d name=product-mcp-server \
  -d packageName=dev.starryeye.productmcpserver -d javaVersion=21 \
  -d dependencies=webflux,spring-ai-mcp-server
unzip -q product-mcp-server.zip -d product-mcp-server
rm product-mcp-server.zip
```

- [ ] **Step 2: 생성 결과 확인**

Run:
```bash
cd practice/agent-mcps/product-mcp-server && cat build.gradle
```
Expected: `implementation 'org.springframework.ai:spring-ai-starter-mcp-server-webflux'` (webflux **변형**),
`set('springAiVersion', "2.0.0")`, `spring-boot-starter-webflux-test`.
`spring-ai-starter-model-*` 는 **없어야 한다** (이 서버는 LLM 을 부르지 않는다).

- [ ] **Step 3: reactor-test 의존성 추가**

`build.gradle` 의 `dependencies` 블록에 한 줄 추가한다 (테스트에서 `StepVerifier` 를 쓴다):

```gradle
	testImplementation 'io.projectreactor:reactor-test'
```

- [ ] **Step 4: application.yml 작성**

`src/main/resources/application.properties` 를 삭제하고 `application.yml` 을 만든다:

```yaml
server:
  port: 8091

spring:
  application:
    name: product-mcp-server
  ai:
    mcp:
      server:
        protocol: STREAMABLE   # 2.0 권장 전송 (SSE 는 deprecated)
        type: ASYNC            # @McpTool 은 Mono/Flux 만 등록된다
```

- [ ] **Step 5: 실패하는 테스트 작성**

Create `src/test/java/dev/starryeye/productmcpserver/ProductToolsTest.java`:

```java
package dev.starryeye.productmcpserver;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class ProductToolsTest {

    private final ProductTools tools = new ProductTools(new ProductRepository());

    @Test
    void 키워드로_상품을_검색한다() {
        StepVerifier.create(tools.searchProducts("노트북"))
                .assertNext(text -> assertThat(text)
                        .contains("게이밍 노트북 15인치")
                        .contains("사무용 노트북 14인치")
                        .doesNotContain("웹캠"))
                .verifyComplete();
    }

    @Test
    void 키워드를_생략하면_전체를_반환한다() {
        StepVerifier.create(tools.searchProducts(null))
                .assertNext(text -> assertThat(text)
                        .contains("게이밍 노트북 15인치")
                        .contains("웹캠 1080p"))
                .verifyComplete();
    }

    @Test
    void 검색_결과가_없으면_없다고_말한다() {
        StepVerifier.create(tools.searchProducts("없는상품"))
                .assertNext(text -> assertThat(text).contains("없습니다"))
                .verifyComplete();
    }

    @Test
    void 재고_수량을_문장으로_반환한다() {
        StepVerifier.create(tools.getStock("p1"))
                .assertNext(text -> assertThat(text).contains("7"))
                .verifyComplete();
    }

    @Test
    void 재고가_0이면_품절이라고_말한다() {
        StepVerifier.create(tools.getStock("p3"))
                .assertNext(text -> assertThat(text).contains("품절"))
                .verifyComplete();
    }

    @Test
    void 없는_상품이면_찾을_수_없다고_말한다() {
        StepVerifier.create(tools.getStock("nope"))
                .assertNext(text -> assertThat(text).contains("찾을 수 없습니다"))
                .verifyComplete();
    }
}
```

- [ ] **Step 6: 테스트가 실패하는지 확인**

Run:
```bash
export JAVA_HOME=/Users/starryeye/.sdkman/candidates/java/21.0.6-amzn
cd practice/agent-mcps/product-mcp-server && ./gradlew test
```
Expected: FAIL — `Product`, `ProductRepository`, `ProductTools` 가 없어 컴파일 에러

- [ ] **Step 7: 도메인 record 작성**

Create `src/main/java/dev/starryeye/productmcpserver/Product.java`:

```java
package dev.starryeye.productmcpserver;

public record Product(
        String id,
        String name,
        String category,
        int price,
        int stock
) {
}
```

- [ ] **Step 8: 저장소 작성**

시드 데이터는 `practice/agent-mcp` 와 **동일하다.** 두 practice 를 오가며 비교할 수 있도록 값을 바꾸지 않는다.

Create `src/main/java/dev/starryeye/productmcpserver/ProductRepository.java`:

```java
package dev.starryeye.productmcpserver;

import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Repository
public class ProductRepository {

    private final Map<String, Product> store = new LinkedHashMap<>();

    public ProductRepository() {
        List.of(
                new Product("p1", "게이밍 노트북 15인치", "노트북", 1_890_000, 7),
                new Product("p2", "사무용 노트북 14인치", "노트북", 990_000, 23),
                new Product("p3", "무선 기계식 키보드", "주변기기", 149_000, 0),
                new Product("p4", "인체공학 마우스", "주변기기", 59_000, 145),
                new Product("p5", "27인치 4K 모니터", "모니터", 549_000, 12),
                new Product("p6", "34인치 울트라와이드 모니터", "모니터", 899_000, 3),
                new Product("p7", "노이즈 캔슬링 헤드폰", "음향", 379_000, 41),
                new Product("p8", "USB-C 도킹 스테이션", "주변기기", 219_000, 18),
                new Product("p9", "휴대용 SSD 1TB", "저장장치", 139_000, 62),
                new Product("p10", "웹캠 1080p", "주변기기", 89_000, 0)
        ).forEach(product -> store.put(product.id(), product));
    }

    public List<Product> findByKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.copyOf(store.values());
        }
        String normalized = keyword.toLowerCase(Locale.ROOT);
        return store.values().stream()
                .filter(product -> product.name().toLowerCase(Locale.ROOT).contains(normalized)
                        || product.category().toLowerCase(Locale.ROOT).contains(normalized))
                .toList();
    }

    public Optional<Product> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }
}
```

- [ ] **Step 9: MCP 툴 작성**

Create `src/main/java/dev/starryeye/productmcpserver/ProductTools.java`:

```java
package dev.starryeye.productmcpserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.stream.Collectors;

@Component
public class ProductTools {

    private static final Logger log = LoggerFactory.getLogger(ProductTools.class);

    private final ProductRepository productRepository;

    public ProductTools(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @McpTool(
            name = "searchProducts",
            description = "판매 중인 상품을 검색한다. "
                    + "각 상품의 ID, 이름, 카테고리, 가격, 재고 수량을 함께 반환한다. "
                    + "키워드는 상품명과 카테고리명에만 부분일치로 적용된다. "
                    + "따라서 가격·재고처럼 이름이 아닌 조건으로 거르려면 "
                    + "(예: '10만원 넘는 상품', '품절 아닌 것') 키워드 없이 호출해 전체 목록을 받은 뒤 "
                    + "그 결과를 직접 판단해야 한다. 조건을 키워드로 넘기면 아무것도 찾지 못한다."
    )
    public Mono<String> searchProducts(
            @McpToolParam(description = "상품명 또는 카테고리명의 일부. "
                    + "가격·재고 같은 조건이나 문장을 넣으면 안 된다. "
                    + "특정 상품을 지목하지 않는 질문이면 생략한다.", required = false)
            String keyword) {

        log.info("searchProducts 호출 (keyword={})", keyword);
        return Mono.fromSupplier(() -> productRepository.findByKeyword(keyword).stream()
                        .map(product -> "- [%s] %s (%s) / %,d원 / 재고 %d개"
                                .formatted(product.id(), product.name(), product.category(),
                                        product.price(), product.stock()))
                        .collect(Collectors.joining("\n")))
                .map(joined -> joined.isBlank()
                        ? "'%s' 에 해당하는 상품이 없습니다.".formatted(keyword)
                        : joined);
    }

    @McpTool(
            name = "getStock",
            description = "상품 ID로 현재 재고 수량을 조회한다. "
                    + "사용자가 특정 상품의 재고나 구매 가능 여부를 물을 때 사용한다. "
                    + "상품 ID를 모르면 먼저 searchProducts 로 상품을 찾아야 한다."
    )
    public Mono<String> getStock(
            @McpToolParam(description = "상품 ID. 예: p1", required = true)
            String productId) {

        log.info("getStock 호출 (productId={})", productId);
        return Mono.fromSupplier(() -> productRepository.findById(productId)
                        .map(product -> product.stock() == 0
                                ? "상품 %s (%s) 는 현재 품절입니다. (재고 0개)"
                                        .formatted(productId, product.name())
                                : "상품 %s (%s) 의 현재 재고는 %d개입니다."
                                        .formatted(productId, product.name(), product.stock()))
                        .orElse("상품 %s 를 찾을 수 없습니다.".formatted(productId)));
    }
}
```

- [ ] **Step 10: 테스트가 통과하는지 확인**

Run:
```bash
export JAVA_HOME=/Users/starryeye/.sdkman/candidates/java/21.0.6-amzn
cd practice/agent-mcps/product-mcp-server && ./gradlew test
```
Expected: PASS — 6개 + 생성된 `contextLoads` = 7개

- [ ] **Step 11: 툴 등록 확인**

```bash
export JAVA_HOME=/Users/starryeye/.sdkman/candidates/java/21.0.6-amzn
cd practice/agent-mcps/product-mcp-server && ./gradlew bootRun
```

기동 로그에서 확인할 것:
- `Registered tools: 2`
- **필터링 경고가 없어야 한다.** "filtered"/"non-reactive" 경고가 보이면 반환 타입이
  `Mono`/`Flux` 가 아니라는 뜻이므로 Step 9 를 고친다.

확인 후 종료한다.

- [ ] **Step 12: 커밋**

```bash
git add practice/agent-mcps/product-mcp-server
git commit -m "feat: agent-mcps product-mcp-server 추가

상품 데이터를 직접 보유하는 MCP 서버. 백엔드 REST 서비스가 없어
저장소를 바로 쓰므로 테스트에 스텁이 필요 없다.
시드 데이터는 agent-mcp 와 동일하게 유지한다."
```

---

## Task 2: order-mcp-server — 주문 MCP 서버 (위험한 툴 포함)

**Files:**
- Create: `practice/agent-mcps/order-mcp-server/` (Initializr 생성)
- Create: `.../src/main/java/dev/starryeye/ordermcpserver/Order.java`
- Create: `.../src/main/java/dev/starryeye/ordermcpserver/OrderRepository.java`
- Create: `.../src/main/java/dev/starryeye/ordermcpserver/OrderTools.java`
- Create: `.../src/main/resources/application.yml`
- Delete: `.../src/main/resources/application.properties`
- Test: `.../src/test/java/dev/starryeye/ordermcpserver/OrderToolsTest.java`

**Interfaces:**
- Consumes: Task 1 의 상품 ID 체계 (`p1`~`p10`). 주문이 상품 ID 를 참조하지만
  **두 서버는 서로 호출하지 않는다.** 연결은 LLM 이 두 툴을 연달아 부르면서 이뤄진다.
- Produces: Task 3·4·5 가 쓸 MCP 툴 3개 —
  - `searchOrders(String customer)` → `Mono<String>` (customer 는 optional)
  - `getOrder(String orderId)` → `Mono<String>` (주문한 **상품 ID 포함**)
  - `cancelOrder(String orderId)` → `Mono<String>` (**상태를 실제로 변경**)

  MCP 엔드포인트는 `http://localhost:8092/mcp`.

- [ ] **Step 1: Initializr 로 프로젝트 생성**

```bash
cd practice/agent-mcps
curl -s -o order-mcp-server.zip -G https://start.spring.io/starter.zip \
  -d type=gradle-project -d language=java -d bootVersion=4.1.0 \
  -d groupId=dev.starryeye -d artifactId=order-mcp-server -d name=order-mcp-server \
  -d packageName=dev.starryeye.ordermcpserver -d javaVersion=21 \
  -d dependencies=webflux,spring-ai-mcp-server
unzip -q order-mcp-server.zip -d order-mcp-server
rm order-mcp-server.zip
```

- [ ] **Step 2: reactor-test 의존성 추가**

`build.gradle` 의 `dependencies` 블록에 추가:

```gradle
	testImplementation 'io.projectreactor:reactor-test'
```

- [ ] **Step 3: application.yml 작성**

`application.properties` 를 삭제하고 만든다:

```yaml
server:
  port: 8092

spring:
  application:
    name: order-mcp-server
  ai:
    mcp:
      server:
        protocol: STREAMABLE
        type: ASYNC
```

- [ ] **Step 4: 실패하는 테스트 작성**

Create `src/test/java/dev/starryeye/ordermcpserver/OrderToolsTest.java`:

```java
package dev.starryeye.ordermcpserver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class OrderToolsTest {

    private OrderRepository repository;
    private OrderTools tools;

    @BeforeEach
    void setUp() {
        repository = new OrderRepository();
        tools = new OrderTools(repository);
    }

    @Test
    void 고객명으로_주문을_검색한다() {
        StepVerifier.create(tools.searchOrders("홍길동"))
                .assertNext(text -> assertThat(text)
                        .contains("o1")
                        .contains("o2")
                        .doesNotContain("o3"))
                .verifyComplete();
    }

    @Test
    void 고객명을_생략하면_전체를_반환한다() {
        StepVerifier.create(tools.searchOrders(null))
                .assertNext(text -> assertThat(text)
                        .contains("o1").contains("o2").contains("o3"))
                .verifyComplete();
    }

    @Test
    void 주문_상세는_상품_ID_를_포함한다() {
        StepVerifier.create(tools.getOrder("o1"))
                .assertNext(text -> assertThat(text)
                        .contains("o1")
                        .contains("p1")
                        .contains("결제완료"))
                .verifyComplete();
    }

    @Test
    void 없는_주문이면_찾을_수_없다고_말한다() {
        StepVerifier.create(tools.getOrder("nope"))
                .assertNext(text -> assertThat(text).contains("찾을 수 없습니다"))
                .verifyComplete();
    }

    @Test
    void 주문을_취소하면_상태가_실제로_바뀐다() {
        StepVerifier.create(tools.cancelOrder("o1"))
                .assertNext(text -> assertThat(text).contains("취소"))
                .verifyComplete();

        assertThat(repository.findById("o1")).isPresent();
        assertThat(repository.findById("o1").orElseThrow().status()).isEqualTo("취소됨");
    }

    @Test
    void 이미_취소된_주문은_다시_취소되지_않는다() {
        tools.cancelOrder("o1").block();

        StepVerifier.create(tools.cancelOrder("o1"))
                .assertNext(text -> assertThat(text).contains("이미 취소"))
                .verifyComplete();
    }

    @Test
    void 없는_주문은_취소할_수_없다() {
        StepVerifier.create(tools.cancelOrder("nope"))
                .assertNext(text -> assertThat(text).contains("찾을 수 없습니다"))
                .verifyComplete();
    }
}
```

- [ ] **Step 5: 테스트가 실패하는지 확인**

Run:
```bash
export JAVA_HOME=/Users/starryeye/.sdkman/candidates/java/21.0.6-amzn
cd practice/agent-mcps/order-mcp-server && ./gradlew test
```
Expected: FAIL — `Order`, `OrderRepository`, `OrderTools` 가 없어 컴파일 에러

- [ ] **Step 6: 도메인 record 작성**

`status` 는 변경되므로 record 를 새로 만들어 교체하는 방식을 쓴다 (`withStatus`).

Create `src/main/java/dev/starryeye/ordermcpserver/Order.java`:

```java
package dev.starryeye.ordermcpserver;

public record Order(
        String id,
        String customer,
        String productId,
        String productName,
        int quantity,
        String status
) {
    public Order withStatus(String newStatus) {
        return new Order(id, customer, productId, productName, quantity, newStatus);
    }
}
```

- [ ] **Step 7: 저장소 작성**

시드 주문 3건은 설계 문서에 확정된 값이다. 바꾸지 않는다.

Create `src/main/java/dev/starryeye/ordermcpserver/OrderRepository.java`:

```java
package dev.starryeye.ordermcpserver;

import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Repository
public class OrderRepository {

    public static final String STATUS_CANCELLED = "취소됨";

    private final Map<String, Order> store = new LinkedHashMap<>();

    public OrderRepository() {
        List.of(
                new Order("o1", "홍길동", "p1", "게이밍 노트북 15인치", 1, "결제완료"),
                new Order("o2", "홍길동", "p5", "27인치 4K 모니터", 2, "배송중"),
                new Order("o3", "김영희", "p3", "무선 기계식 키보드", 1, "결제완료")
        ).forEach(order -> store.put(order.id(), order));
    }

    public List<Order> findByCustomer(String customer) {
        if (customer == null || customer.isBlank()) {
            return List.copyOf(store.values());
        }
        String normalized = customer.toLowerCase(Locale.ROOT);
        return store.values().stream()
                .filter(order -> order.customer().toLowerCase(Locale.ROOT).contains(normalized))
                .toList();
    }

    public Optional<Order> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    /**
     * 상태를 '취소됨' 으로 바꾸고 바뀐 주문을 반환한다.
     * 주문이 없으면 빈 Optional, 이미 취소된 주문이면 상태가 그대로인 주문을 반환한다.
     * 호출측이 이미 취소된 경우를 구분할 수 있도록 상태를 그대로 돌려준다.
     */
    public Optional<Order> cancel(String id) {
        Order order = store.get(id);
        if (order == null) {
            return Optional.empty();
        }
        if (STATUS_CANCELLED.equals(order.status())) {
            return Optional.of(order);
        }
        Order cancelled = order.withStatus(STATUS_CANCELLED);
        store.put(id, cancelled);
        return Optional.of(cancelled);
    }
}
```

- [ ] **Step 8: MCP 툴 작성**

`cancelOrder` 의 description 에 위험성을 명시한다 — 필터로 막기 전에도 모델이 함부로 부르지 않도록.
다만 **프롬프트/설명만으로는 못 막는다**는 것이 이 예제의 요점이므로, 설명에 의존하지 않는다.

Create `src/main/java/dev/starryeye/ordermcpserver/OrderTools.java`:

```java
package dev.starryeye.ordermcpserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.stream.Collectors;

@Component
public class OrderTools {

    private static final Logger log = LoggerFactory.getLogger(OrderTools.class);

    private final OrderRepository orderRepository;

    public OrderTools(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @McpTool(
            name = "searchOrders",
            description = "주문을 검색한다. 각 주문의 ID, 고객명, 상품명, 수량, 상태를 반환한다. "
                    + "고객명을 생략하면 전체 주문을 반환한다."
    )
    public Mono<String> searchOrders(
            @McpToolParam(description = "고객명의 일부. 특정 고객을 지목하지 않으면 생략한다.",
                    required = false)
            String customer) {

        log.info("searchOrders 호출 (customer={})", customer);
        return Mono.fromSupplier(() -> orderRepository.findByCustomer(customer).stream()
                        .map(order -> "- [%s] %s / %s %d개 / %s"
                                .formatted(order.id(), order.customer(),
                                        order.productName(), order.quantity(), order.status()))
                        .collect(Collectors.joining("\n")))
                .map(joined -> joined.isBlank()
                        ? "'%s' 의 주문을 찾을 수 없습니다.".formatted(customer)
                        : joined);
    }

    @McpTool(
            name = "getOrder",
            description = "주문 ID로 주문 상세를 조회한다. 주문한 상품의 ID를 함께 반환하므로, "
                    + "주문한 상품의 재고를 알아보려면 이 툴로 상품 ID를 먼저 얻어야 한다."
    )
    public Mono<String> getOrder(
            @McpToolParam(description = "주문 ID. 예: o1", required = true)
            String orderId) {

        log.info("getOrder 호출 (orderId={})", orderId);
        return Mono.fromSupplier(() -> orderRepository.findById(orderId)
                        .map(order -> "주문 %s / 고객 %s / 상품 %s (ID: %s) %d개 / 상태 %s"
                                .formatted(order.id(), order.customer(), order.productName(),
                                        order.productId(), order.quantity(), order.status()))
                        .orElse("주문 %s 를 찾을 수 없습니다.".formatted(orderId)));
    }

    @McpTool(
            name = "cancelOrder",
            description = "주문을 취소한다. 주문 상태를 '취소됨' 으로 바꾸며 되돌릴 수 없다. "
                    + "사용자가 명시적으로 취소를 요청한 경우에만 사용한다."
    )
    public Mono<String> cancelOrder(
            @McpToolParam(description = "취소할 주문 ID. 예: o1", required = true)
            String orderId) {

        log.warn("cancelOrder 호출 (orderId={}) — 상태를 변경한다", orderId);
        return Mono.fromSupplier(() -> orderRepository.findById(orderId)
                        .map(existing -> {
                            if (OrderRepository.STATUS_CANCELLED.equals(existing.status())) {
                                return "주문 %s 는 이미 취소된 주문입니다.".formatted(orderId);
                            }
                            orderRepository.cancel(orderId);
                            return "주문 %s 를 취소했습니다.".formatted(orderId);
                        })
                        .orElse("주문 %s 를 찾을 수 없습니다.".formatted(orderId)));
    }
}
```

- [ ] **Step 9: 테스트가 통과하는지 확인**

Run:
```bash
export JAVA_HOME=/Users/starryeye/.sdkman/candidates/java/21.0.6-amzn
cd practice/agent-mcps/order-mcp-server && ./gradlew test
```
Expected: PASS — 7개 + `contextLoads` = 8개

- [ ] **Step 10: 툴 등록 확인**

```bash
export JAVA_HOME=/Users/starryeye/.sdkman/candidates/java/21.0.6-amzn
cd practice/agent-mcps/order-mcp-server && ./gradlew bootRun
```
Expected: 기동 로그에 `Registered tools: 3`, 필터링 경고 없음. 확인 후 종료한다.

- [ ] **Step 11: 커밋**

```bash
git add practice/agent-mcps/order-mcp-server
git commit -m "feat: agent-mcps order-mcp-server 추가

주문 조회 2개와 상태를 실제로 바꾸는 cancelOrder 를 노출한다.
cancelOrder 가 진짜 상태를 바꾸므로, 뒤에 붙일 필터의 효과가
말이 아니라 상태 변화로 드러난다."
```

---

## Task 3: shop-agent — MCP 서버 2개에 붙는 에이전트

필터는 다음 태스크에서 붙인다. 이 태스크는 **필터 없이 툴 5개가 전부 노출되는 상태**를 만든다.

**Files:**
- Create: `practice/agent-mcps/shop-agent/` (Initializr 생성)
- Create: `.../src/main/java/dev/starryeye/shopagent/ChatClientConfig.java`
- Create: `.../src/main/java/dev/starryeye/shopagent/ChatController.java`
- Create: `.../src/main/resources/application.yml`
- Create: `.../src/main/resources/application-ollama.yml`
- Create: `.../src/main/resources/application-openai.yml`
- Create: `.../src/main/resources/application-anthropic.yml`
- Create: `.../secrets.yml.example`
- Modify: `.../.gitignore` (secrets.yml 추가)
- Delete: `.../src/main/resources/application.properties`
- Test: `.../src/test/java/dev/starryeye/shopagent/ChatClientToolWiringTest.java`

**Interfaces:**
- Consumes: Task 1 의 `http://localhost:8091`, Task 2 의 `http://localhost:8092`
- Produces:
  - `POST /api/chat` — 본문은 평문 질문, 응답은 `text/plain` 스트리밍
  - Task 4 가 붙일 자리: `McpToolFilter` 빈 (없으면 전부 통과가 기본 동작)

- [ ] **Step 1: Initializr 로 프로젝트 생성**

```bash
cd practice/agent-mcps
curl -s -o shop-agent.zip -G https://start.spring.io/starter.zip \
  -d type=gradle-project -d language=java -d bootVersion=4.1.0 \
  -d groupId=dev.starryeye -d artifactId=shop-agent -d name=shop-agent \
  -d packageName=dev.starryeye.shopagent -d javaVersion=21 \
  -d dependencies=webflux,spring-ai-mcp-client,spring-ai-openai,spring-ai-anthropic,spring-ai-ollama
unzip -q shop-agent.zip -d shop-agent
rm shop-agent.zip
```

- [ ] **Step 2: 의존성 확인**

Run:
```bash
cd practice/agent-mcps/shop-agent && cat build.gradle
```
Expected 5개 모두 존재: `spring-ai-starter-mcp-client-webflux`,
`spring-ai-starter-model-anthropic`, `spring-ai-starter-model-ollama`,
`spring-ai-starter-model-openai`, `spring-boot-starter-webflux`.

- [ ] **Step 3: 공통 application.yml 작성**

`application.properties` 를 삭제하고 만든다. `spring.ai.model.chat` 은 **채팅 자동설정만**
고르므로, 클래스패스에 있는 다른 모델 타입 자동설정을 끄지 않으면 해당 프로바이더 키가 없을 때
기동이 실패한다.

```yaml
server:
  port: 8090

spring:
  application:
    name: shop-agent
  config:
    import: "optional:file:./secrets.yml"
  ai:
    model:
      chat: ollama          # 기본 프로바이더. 프로파일에서 덮어쓴다.
      # 채팅 외 모델 타입을 끄지 않으면 OpenAI 음성 자동설정 등이 API 키를 요구해
      # 기동이 실패한다. 이 프로젝트는 채팅만 쓴다.
      audio:
        speech: none
        transcription: none
      embedding: none
      image: none
      moderation: none
    mcp:
      client:
        enabled: true
        type: ASYNC
        streamable-http:
          connections:
            product:
              url: "http://localhost:8091"   # base URL, endpoint 는 기본 /mcp
            order:
              url: "http://localhost:8092"

logging:
  level:
    org.springframework.ai: INFO
```

- [ ] **Step 4: 프로바이더별 프로파일 작성**

Create `src/main/resources/application-ollama.yml`:

```yaml
spring:
  ai:
    model:
      chat: ollama
    ollama:
      base-url: http://localhost:11434
      chat:
        model: qwen3:8b
        temperature: 0.1
        # qwen3 는 thinking 모델이다. 미지정이면 수 분간 생성하다 툴을 놓치고,
        # false 는 빠르지만 툴 설명을 제대로 따르지 못한다. low 가 균형점이다.
        think: low
```

Create `src/main/resources/application-openai.yml`:

```yaml
spring:
  ai:
    model:
      chat: openai
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        model: gpt-5-mini
```

Create `src/main/resources/application-anthropic.yml`:

```yaml
spring:
  ai:
    model:
      chat: anthropic
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      chat:
        model: claude-opus-5
```

- [ ] **Step 5: secrets 파일 준비**

Create `secrets.yml.example` (프로젝트 루트, `build.gradle` 옆):

```yaml
# 이 파일을 secrets.yml 로 복사하고 키를 채운다.
#   cp secrets.yml.example secrets.yml
# secrets.yml 은 .gitignore 에 등록되어 커밋되지 않는다.
# ollama 프로파일은 키가 필요 없다.

OPENAI_API_KEY: "sk-여기에-키를-넣으세요"
ANTHROPIC_API_KEY: "sk-ant-여기에-키를-넣으세요"
```

프로젝트의 `.gitignore` 끝에 추가한다:

```
### API 키 (커밋 금지) ###
secrets.yml
```

- [ ] **Step 6: 채팅 컨트롤러와 ChatClient 설정 작성**

컨트롤러는 조립하지 않고 주입만 받는다. 두 파일 모두 만든다.

Create `src/main/java/dev/starryeye/shopagent/ChatClientConfig.java`:

```java
package dev.starryeye.shopagent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 서버가 둘이어도 이 코드는 그대로다.
 * 자동설정은 클라이언트 수와 무관하게 {@link ToolCallbackProvider} 빈을 <b>하나</b>만 만들고,
 * 그 안에 모든 서버의 툴을 합쳐 담는다.
 */
@Configuration
public class ChatClientConfig {

    private static final String SYSTEM_PROMPT = """
            당신은 온라인 쇼핑몰의 상담 도우미입니다.
            상품·재고·주문에 대한 질문에는 반드시 제공된 도구로 실제 데이터를 조회한 뒤 답하세요.
            추측하지 말고, 조회 결과에 없는 내용은 모른다고 답하세요.
            주문한 상품의 재고를 물으면 먼저 주문을 조회해 상품 ID를 얻고, 그 ID로 재고를 조회하세요.
            """;

    @Bean
    public ChatClient shopChatClient(ChatClient.Builder builder,
                                     ObjectProvider<ToolCallbackProvider> toolCallbackProvider) {
        ChatClient.Builder configured = builder.defaultSystem(SYSTEM_PROMPT);
        toolCallbackProvider.ifAvailable(configured::defaultTools);
        return configured.build();
    }
}
```

Create `src/main/java/dev/starryeye/shopagent/ChatController.java`:

```java
package dev.starryeye.shopagent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * {@code .stream()} 은 Flux 를 만들기 <b>전에</b>
     * {@code AsyncMcpToolCallbackProvider.getToolCallbacks()} 를 호출하고 그 안에서
     * {@code Mono.block()} 을 쓴다. 컨트롤러는 Netty 이벤트 루프 스레드에서 실행되므로
     * 그대로 두면 {@code IllegalStateException} 으로 500 이 난다.
     * 블로킹이 체인을 <i>만드는 시점</i>에 있으므로 완성된 Flux 에 {@code subscribeOn} 을
     * 붙여도 소용없다 — 호출 자체를 옮겨야 한다.
     * <p>
     * 응답을 {@code text/plain} 으로 흘리는 이유: SSE 로 선언하면 Flux 원소마다
     * {@code data:} 프레임이 붙어 토큰 하나당 한 줄이 되어 읽을 수 없다.
     */
    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public Flux<String> chat(@RequestBody String message) {
        return Mono.fromCallable(() -> chatClient.prompt()
                        .user(message)
                        .stream()
                        .content())
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(content -> content);
    }
}
```

- [ ] **Step 7: 배선 회귀 테스트 작성**

MCP 툴이 실제로 `ChatClient` 에 꽂히는지 검증한다. 이 배선이 빠지면 기동도 되고 답변도 오지만
모델은 툴 없이 기억으로만 답하므로 조용히 망가진다.

Create `src/test/java/dev/starryeye/shopagent/ChatClientToolWiringTest.java`:

```java
package dev.starryeye.shopagent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.DefaultChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.ai.mcp.client.enabled=false",
        "spring.ai.openai.api-key=test-key"
})
@ActiveProfiles("openai")
class ChatClientToolWiringTest {

    @TestConfiguration
    static class StubToolConfiguration {

        @Bean
        ToolCallbackProvider stubToolCallbackProvider() {
            return ToolCallbackProvider.from(new StubToolCallback());
        }
    }

    @Autowired
    ChatClient chatClient;

    @Autowired
    ToolCallbackProvider stubToolCallbackProvider;

    @Test
    void ToolCallbackProvider_가_ChatClient_에_연결된다() {
        var requestSpec = (DefaultChatClient.DefaultChatClientRequestSpec) chatClient.prompt();

        assertThat(requestSpec.getToolCallbackProviders())
                .as("MCP 툴 콜백이 ChatClient 기본 요청에 붙어 있어야 한다")
                .contains(stubToolCallbackProvider);
    }

    @Test
    void 연결된_provider_가_실제_툴_정의를_노출한다() {
        var requestSpec = (DefaultChatClient.DefaultChatClientRequestSpec) chatClient.prompt();

        assertThat(requestSpec.getToolCallbackProviders())
                .flatExtracting(provider -> List.of(provider.getToolCallbacks()))
                .extracting(callback -> callback.getToolDefinition().name())
                .contains("stubTool");
    }

    private static class StubToolCallback implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name("stubTool")
                    .description("테스트용 스텁 툴")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return "stub";
        }
    }
}
```

- [ ] **Step 8: 테스트 실행**

Run:
```bash
export JAVA_HOME=/Users/starryeye/.sdkman/candidates/java/21.0.6-amzn
cd practice/agent-mcps/shop-agent && ./gradlew test
```
Expected: PASS

FAIL 시 대응:
- `NoUniqueBeanDefinitionException` / `ChatModel` 후보 다수 → Step 3·4 의 `spring.ai.model.chat` 확인
- `Could not resolve placeholder 'OPENAI_API_KEY'` → `@SpringBootTest(properties=...)` 의
  `spring.ai.openai.api-key=test-key` 가 프로파일 yml 보다 우선하는지 확인
- MCP 연결 타임아웃 → `spring.ai.mcp.client.enabled=false` 적용 여부 확인

- [ ] **Step 9: 배선 제거 시 실패하는지 확인**

`ChatClientConfig` 의 `toolCallbackProvider.ifAvailable(...)` 줄을 잠시 주석 처리하고
테스트를 실행해 **두 테스트가 실패하는지** 본다. 실패하지 않으면 회귀 테스트로서 쓸모가 없다.
확인 후 주석을 되돌리고 다시 통과하는지 본다.

- [ ] **Step 10: 커밋**

```bash
git add practice/agent-mcps/shop-agent
git commit -m "feat: agent-mcps shop-agent 추가 (필터 없음)

MCP 서버 2개에 붙는 에이전트. 서버가 둘이어도 툴 배선 코드는
agent-mcp 와 동일하다 — 자동설정이 ToolCallbackProvider 빈을
하나로 합쳐 주기 때문이다. 이 상태에서는 툴 5개가 전부 노출된다."
```

---

## Task 4: McpToolFilter — 노출 툴 제어

이 예제의 핵심이다.

**Files:**
- Create: `practice/agent-mcps/shop-agent/src/main/java/dev/starryeye/shopagent/ToolFilterConfig.java`
- Test: `practice/agent-mcps/shop-agent/src/test/java/dev/starryeye/shopagent/ToolFilterConfigTest.java`

**Interfaces:**
- Consumes: Task 3 의 shop-agent 프로젝트
- Produces: `shop.tool-filter` 속성 (`none` / `safe` / `product-only`, 기본 `safe`).
  Task 5 의 `run.sh` 가 이 값을 인자로 받아 넘긴다.

- [ ] **Step 1: 실패하는 테스트 작성**

`McpToolFilter` 는 `BiPredicate<McpConnectionInfo, McpSchema.Tool>` 이라 서버를 띄우지 않고
직접 검증할 수 있다. 이것이 이 태스크에서 가장 값진 테스트다.

Create `src/test/java/dev/starryeye/shopagent/ToolFilterConfigTest.java`:

```java
package dev.starryeye.shopagent;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.McpConnectionInfo;
import org.springframework.ai.mcp.McpToolFilter;

import static org.assertj.core.api.Assertions.assertThat;

class ToolFilterConfigTest {

    private final ToolFilterConfig config = new ToolFilterConfig();

    /** 자동설정이 넘겨주는 것과 같은 형태로 커넥션 정보를 만든다. */
    private McpConnectionInfo connection(String connectionName) {
        return McpConnectionInfo.builder()
                .clientInfo(new McpSchema.Implementation(
                        "spring-ai-mcp-client - " + connectionName, "1.0.0"))
                .build();
    }

    private McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder().name(name).build();
    }

    @Test
    void none_모드는_모든_툴을_통과시킨다() {
        McpToolFilter filter = config.toolFilter("none");

        assertThat(filter.test(connection("product"), tool("searchProducts"))).isTrue();
        assertThat(filter.test(connection("order"), tool("searchOrders"))).isTrue();
        assertThat(filter.test(connection("order"), tool("cancelOrder"))).isTrue();
    }

    @Test
    void safe_모드는_cancelOrder_만_막는다() {
        McpToolFilter filter = config.toolFilter("safe");

        assertThat(filter.test(connection("order"), tool("cancelOrder"))).isFalse();
        assertThat(filter.test(connection("order"), tool("searchOrders"))).isTrue();
        assertThat(filter.test(connection("order"), tool("getOrder"))).isTrue();
        assertThat(filter.test(connection("product"), tool("searchProducts"))).isTrue();
        assertThat(filter.test(connection("product"), tool("getStock"))).isTrue();
    }

    @Test
    void product_only_모드는_order_서버의_툴을_전부_막는다() {
        McpToolFilter filter = config.toolFilter("product-only");

        assertThat(filter.test(connection("product"), tool("searchProducts"))).isTrue();
        assertThat(filter.test(connection("product"), tool("getStock"))).isTrue();
        assertThat(filter.test(connection("order"), tool("searchOrders"))).isFalse();
        assertThat(filter.test(connection("order"), tool("getOrder"))).isFalse();
        assertThat(filter.test(connection("order"), tool("cancelOrder"))).isFalse();
    }

    @Test
    void 알_수_없는_모드는_safe_로_동작한다() {
        McpToolFilter filter = config.toolFilter("오타난모드");

        assertThat(filter.test(connection("order"), tool("cancelOrder")))
                .as("모르는 값이면 안전한 쪽으로 기운다")
                .isFalse();
        assertThat(filter.test(connection("order"), tool("getOrder"))).isTrue();
    }

    @Test
    void 모드별_통과_툴_개수가_5_4_2_다() {
        record Case(String mode, int expected) {
        }
        var tools = new String[][]{
                {"product", "searchProducts"}, {"product", "getStock"},
                {"order", "searchOrders"}, {"order", "getOrder"}, {"order", "cancelOrder"}
        };

        for (Case c : new Case[]{new Case("none", 5), new Case("safe", 4), new Case("product-only", 2)}) {
            McpToolFilter filter = config.toolFilter(c.mode());
            long passed = java.util.Arrays.stream(tools)
                    .filter(t -> filter.test(connection(t[0]), tool(t[1])))
                    .count();
            assertThat(passed).as("모드 %s", c.mode()).isEqualTo(c.expected());
        }
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run:
```bash
export JAVA_HOME=/Users/starryeye/.sdkman/candidates/java/21.0.6-amzn
cd practice/agent-mcps/shop-agent && ./gradlew test --tests '*ToolFilterConfigTest*'
```
Expected: FAIL — `ToolFilterConfig` 가 없어 컴파일 에러

- [ ] **Step 3: 필터 설정 작성**

Create `src/main/java/dev/starryeye/shopagent/ToolFilterConfig.java`:

```java
package dev.starryeye.shopagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 어떤 MCP 툴을 모델에게 보여줄지 고른다.
 * <p>
 * {@link McpToolFilter} 는 {@code BiPredicate<McpConnectionInfo, McpSchema.Tool>} 이고,
 * 빈으로 등록하면 MCP 자동설정이 주워가 {@code ToolCallbackProvider} 에 적용한다.
 * 접두사({@code alt_1_} 등)가 붙기 <b>전</b> 원본 툴 이름으로 판단하므로
 * 서버를 추가해도 필터가 깨지지 않는다.
 * <p>
 * 필터는 애플리케이션 전역에 하나뿐이다. 요청마다 다른 툴을 쓰려면 필터가 아니라
 * 요청의 {@code .toolCallbacks(...)} 를 쓰거나 {@code ChatClient} 를 여러 개 만들어야 한다.
 */
@Configuration
public class ToolFilterConfig {

    private static final Logger log = LoggerFactory.getLogger(ToolFilterConfig.class);

    private static final String DANGEROUS_TOOL = "cancelOrder";
    private static final String PRODUCT_CONNECTION_SUFFIX = " - product";

    @Bean
    public McpToolFilter toolFilter(@Value("${shop.tool-filter:safe}") String mode) {
        log.info("MCP 툴 필터 모드: {}", mode);
        return switch (mode) {
            case "none" -> (connection, tool) -> true;
            case "product-only" -> (connection, tool) ->
                    connection.clientInfo().name().endsWith(PRODUCT_CONNECTION_SUFFIX);
            default -> (connection, tool) -> !DANGEROUS_TOOL.equals(tool.name());
        };
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인**

Run:
```bash
export JAVA_HOME=/Users/starryeye/.sdkman/candidates/java/21.0.6-amzn
cd practice/agent-mcps/shop-agent && ./gradlew test
```
Expected: PASS — 필터 테스트 5개 + 배선 테스트 2개 + `contextLoads` = 8개

- [ ] **Step 5: 필터가 실제로 적용되는지 확인**

단위 테스트는 필터 로직만 검증한다. 자동설정이 이 빈을 실제로 주워가는지는 별개다.
세 서버를 띄우고 모드별로 노출 툴 수를 확인한다.

터미널 A·B — MCP 서버 두 개:
```bash
export JAVA_HOME=/Users/starryeye/.sdkman/candidates/java/21.0.6-amzn
(cd practice/agent-mcps/product-mcp-server && ./gradlew bootRun)
(cd practice/agent-mcps/order-mcp-server && ./gradlew bootRun)
```

터미널 C — 모드를 바꿔가며 기동하고, 기동 로그의 `MCP 툴 필터 모드: <값>` 을 확인한다:
```bash
export JAVA_HOME=/Users/starryeye/.sdkman/candidates/java/21.0.6-amzn
cd practice/agent-mcps/shop-agent
./gradlew bootRun --args='--spring.profiles.active=ollama --shop.tool-filter=none'
```

노출 툴 수는 다음 임시 테스트로 확인한다. 파일을 만들어 실행한 뒤 **반드시 삭제한다**:

```java
// src/test/java/dev/starryeye/shopagent/TempToolCountTest.java  (확인 후 삭제)
package dev.starryeye.shopagent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "shop.tool-filter=safe")
@ActiveProfiles("ollama")
class TempToolCountTest {

    @Autowired
    ToolCallbackProvider provider;

    @Test
    void dump() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (var cb : provider.getToolCallbacks()) {
            sb.append(cb.getToolDefinition().name()).append("\n");
        }
        java.nio.file.Files.writeString(java.nio.file.Path.of("/tmp/agent-mcps-tools.txt"), sb);
    }
}
```

```bash
export JAVA_HOME=/Users/starryeye/.sdkman/candidates/java/21.0.6-amzn
cd practice/agent-mcps/shop-agent && ./gradlew test --tests '*TempToolCountTest*'
cat /tmp/agent-mcps-tools.txt
rm src/test/java/dev/starryeye/shopagent/TempToolCountTest.java
```
Expected (`safe`): 4줄, `cancelOrder` 가 **없어야 한다**.
`@SpringBootTest(properties = ...)` 의 값을 `none`/`product-only` 로 바꿔 5줄/2줄도 확인한다.

확인 후 MCP 서버 두 개도 종료한다.

- [ ] **Step 6: 커밋**

```bash
git add practice/agent-mcps/shop-agent
git commit -m "feat: McpToolFilter 로 노출 툴 제어 (none/safe/product-only)

shop.tool-filter 속성으로 세 모드를 고른다. 기본은 safe 로
cancelOrder 를 목록에서 뺀다. 모르는 값이면 safe 로 동작한다.

필터는 BiPredicate 라 서버를 띄우지 않고 단위 테스트할 수 있다.
모드별 통과 툴 수(5/4/2)까지 검증한다."
```

---

## Task 5: 실행 스크립트·문서·종단 검증

**Files:**
- Create: `practice/agent-mcps/run.sh`
- Create: `practice/agent-mcps/stop.sh`
- Create: `practice/agent-mcps/.gitignore`
- Create: `practice/agent-mcps/README.md`

**Interfaces:**
- Consumes: Task 1·2·3·4 전부
- Produces: 없음 (최종 태스크)

- [ ] **Step 1: .gitignore 작성**

Create `practice/agent-mcps/.gitignore`:

```
logs/
```

- [ ] **Step 2: run.sh 작성**

`agent-mcp/run.sh` 와 같은 구조에 필터 모드 인자가 추가된다.
**백그라운드 프로세스는 `< /dev/null` 로 stdin 을 끊어야 한다** — 안 그러면 스크립트가
끝나도 호출한 셸이 계속 대기한다.

Create `practice/agent-mcps/run.sh`:

```bash
#!/usr/bin/env bash
#
# MCP 서버 2개 + 에이전트를 한 번에 띄운다.
#
#   ./run.sh                        ollama + safe (기본)
#   ./run.sh ollama none            필터 없음 — cancelOrder 노출
#   ./run.sh ollama product-only    order 서버 툴 전부 차단
#   ./run.sh openai safe            OpenAI 로 (키 필요)
#
# 로그는 logs/ 아래. 종료는 ./stop.sh
#
set -euo pipefail

cd "$(dirname "$0")"

PROFILE="${1:-ollama}"
FILTER="${2:-safe}"
OLLAMA_MODEL="qwen3:8b"
LOG_DIR="logs"
mkdir -p "$LOG_DIR"

info()  { printf '\033[1;34m▸\033[0m %s\n' "$*"; }
ok()    { printf '\033[1;32m✔\033[0m %s\n' "$*"; }
warn()  { printf '\033[1;33m!\033[0m %s\n' "$*"; }
die()   { printf '\033[1;31m✘\033[0m %s\n' "$*" >&2; exit 1; }

case "$PROFILE" in ollama|openai|anthropic) ;; *) die "알 수 없는 프로파일: $PROFILE" ;; esac
case "$FILTER" in none|safe|product-only) ;; *) die "알 수 없는 필터 모드: $FILTER (none | safe | product-only)" ;; esac

find_java21() {
  if [ -n "${JAVA_HOME:-}" ] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"21'; then
    echo "$JAVA_HOME"; return
  fi
  for candidate in "$HOME"/.sdkman/candidates/java/21*/ ; do
    [ -x "$candidate/bin/java" ] && { echo "${candidate%/}"; return; }
  done
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    /usr/libexec/java_home -v 21 2>/dev/null && return
  fi
  return 1
}

JAVA_HOME="$(find_java21)" || die "Java 21 을 찾지 못했다."
export JAVA_HOME
ok "Java 21: $JAVA_HOME"

if [ "$PROFILE" = "ollama" ]; then
  command -v ollama >/dev/null || die "ollama 가 없다. 'brew install ollama' 후 다시 실행할 것."
  if ! curl -sf http://localhost:11434/api/version >/dev/null 2>&1; then
    info "ollama 서버 기동 중..."
    nohup ollama serve > "$LOG_DIR/ollama.log" 2>&1 < /dev/null &
    disown 2>/dev/null || true
    for _ in $(seq 1 30); do
      curl -sf http://localhost:11434/api/version >/dev/null 2>&1 && break
      sleep 1
    done
    curl -sf http://localhost:11434/api/version >/dev/null 2>&1 || die "ollama 기동 실패"
  fi
  ok "ollama 응답 중"
  if ! ollama list 2>/dev/null | grep -q "^${OLLAMA_MODEL%%:*}"; then
    warn "모델 $OLLAMA_MODEL 이 없다. 내려받는 중..."
    ollama pull "$OLLAMA_MODEL" || die "모델 다운로드 실패"
  fi
  ok "모델 준비됨: $OLLAMA_MODEL"
fi

start_app() {
  local dir="$1" port="$2" ready="$3"; shift 3
  local log="$LOG_DIR/$dir.log"

  if lsof -ti:"$port" >/dev/null 2>&1; then
    warn "$dir: 포트 $port 사용 중이라 건너뛴다 (./stop.sh 로 먼저 정리할 것)."
    return 0
  fi

  info "$dir 기동 중 (:$port)..."
  ( cd "$dir" && nohup ./gradlew bootRun -q "$@" > "../$log" 2>&1 < /dev/null & disown 2>/dev/null || true )

  for _ in $(seq 1 90); do
    grep -q "$ready" "$log" 2>/dev/null && { ok "$dir 준비됨 (:$port)"; return 0; }
    grep -qE 'APPLICATION FAILED|BUILD FAILED' "$log" 2>/dev/null && {
      printf '\n'; tail -20 "$log"; die "$dir 기동 실패 — $log 확인"
    }
    sleep 2
  done
  die "$dir 기동 시간 초과. $log 확인."
}

start_app product-mcp-server 8091 'Started ProductMcpServerApplication'
start_app order-mcp-server   8092 'Started OrderMcpServerApplication'
start_app shop-agent         8090 'Started ShopAgentApplication' \
          --args="--spring.profiles.active=$PROFILE --shop.tool-filter=$FILTER"

cat <<EOF

$(ok "전부 기동됨 — 프로파일: $PROFILE / 필터: $FILTER")

  product-mcp-server http://localhost:8091
  order-mcp-server   http://localhost:8092
  shop-agent         http://localhost:8090

물어보기:

  curl -N -X POST http://localhost:8090/api/chat \\
    -H 'Content-Type: text/plain' \\
    -d '홍길동 주문 보여줘'

툴 호출 확인:

  grep 호출 $LOG_DIR/order-mcp-server.log $LOG_DIR/product-mcp-server.log

종료:

  ./stop.sh
EOF
```

- [ ] **Step 3: stop.sh 작성**

Create `practice/agent-mcps/stop.sh`:

```bash
#!/usr/bin/env bash
#
#   ./stop.sh              세 서버만 종료
#   ./stop.sh --ollama     ollama 까지 종료
#
set -uo pipefail

cd "$(dirname "$0")"

ok()   { printf '\033[1;32m✔\033[0m %s\n' "$*"; }
info() { printf '\033[1;34m▸\033[0m %s\n' "$*"; }

stopped=0
for port in 8090 8091 8092; do
  pids="$(lsof -ti:"$port" 2>/dev/null)"
  if [ -n "$pids" ]; then
    info "포트 $port 종료 중..."
    # shellcheck disable=SC2086
    kill $pids 2>/dev/null
    stopped=1
  fi
done

if [ "$stopped" = "1" ]; then
  sleep 3
  for port in 8090 8091 8092; do
    pids="$(lsof -ti:"$port" 2>/dev/null)"
    # shellcheck disable=SC2086
    [ -n "$pids" ] && kill -9 $pids 2>/dev/null
  done
fi

pkill -f 'dev.starryeye.productmcpserver' 2>/dev/null
pkill -f 'dev.starryeye.ordermcpserver' 2>/dev/null
pkill -f 'dev.starryeye.shopagent' 2>/dev/null

ok "세 서버 종료됨"

if [ "${1:-}" = "--ollama" ]; then
  pkill -f 'ollama serve' 2>/dev/null && ok "ollama 종료됨" || info "실행 중인 ollama 없음"
else
  curl -sf http://localhost:11434/api/version >/dev/null 2>&1 \
    && info "ollama 는 계속 실행 중이다 (종료하려면 ./stop.sh --ollama)"
fi
```

- [ ] **Step 4: 실행 권한 부여와 문법 검사**

```bash
cd practice/agent-mcps
chmod +x run.sh stop.sh
bash -n run.sh && bash -n stop.sh && echo "문법 OK"
```

- [ ] **Step 5: 종단 검증 — 필터 효과 관찰**

이 태스크의 핵심이다. **세 모드에서 같은 질문을 던져 결과가 달라지는 것을 확인한다.**

먼저 `none` 모드:
```bash
cd practice/agent-mcps
./stop.sh >/dev/null 2>&1
./run.sh ollama none
curl -N -sS -X POST http://localhost:8090/api/chat -H 'Content-Type: text/plain' \
  -d 'o1 주문 취소해줘' --max-time 400
grep 호출 logs/order-mcp-server.log
curl -N -sS -X POST http://localhost:8090/api/chat -H 'Content-Type: text/plain' \
  -d 'o1 주문 상태 알려줘' --max-time 400
```
Expected: `cancelOrder 호출` 이 로그에 남고, 이어진 조회에서 상태가 **취소됨** 으로 나온다.

다음 `safe` 모드:
```bash
./stop.sh >/dev/null 2>&1
./run.sh ollama safe
curl -N -sS -X POST http://localhost:8090/api/chat -H 'Content-Type: text/plain' \
  -d 'o1 주문 취소해줘' --max-time 400
grep 호출 logs/order-mcp-server.log
```
Expected: 취소하지 못한다는 답변. `cancelOrder 호출` 이 로그에 **없어야 한다**.

다음 `product-only` 모드:
```bash
./stop.sh >/dev/null 2>&1
./run.sh ollama product-only
curl -N -sS -X POST http://localhost:8090/api/chat -H 'Content-Type: text/plain' \
  -d '홍길동 주문 보여줘' --max-time 400
```
Expected: 주문을 조회하지 못한다는 답변. `searchOrders 호출` 이 로그에 **없어야 한다**.

마지막으로 두 서버를 연달아 쓰는 질문 (`safe` 모드):
```bash
./stop.sh >/dev/null 2>&1
./run.sh ollama safe
curl -N -sS -X POST http://localhost:8090/api/chat -H 'Content-Type: text/plain' \
  -d 'o1 주문한 상품 아직 재고 있어?' --max-time 400
grep 호출 logs/order-mcp-server.log logs/product-mcp-server.log
```
Expected: `getOrder 호출` 과 `getStock 호출` 이 **둘 다** 남고 재고 7 이 답변에 포함된다.

로컬 8B 모델이 이 2단계 추론에 실패할 수 있다. 실패하면 **그 결과를 그대로 README 에 기록한다** —
꾸며내지 않는다.

- [ ] **Step 6: README 작성**

앞 단계에서 **실제로 관찰한 결과**로 표를 채운다. 예상값을 적지 않는다.

Create `practice/agent-mcps/README.md`:

```markdown
# agent-mcps

MCP 서버 **여러 개**를 한 에이전트에 붙이고, `McpToolFilter` 로 노출 툴을 제어하는 예제.

[`../agent-mcp`](../agent-mcp) 이 "기존 REST 서비스를 MCP 로 감싸기"였다면,
여기서는 "서버가 여러 개일 때 툴이 어떻게 합쳐지고, 어떻게 골라 쓰는가"를 다룬다.

## 구성

| 프로젝트 | 포트 | 툴 |
|---|---|---|
| `product-mcp-server` | 8091 | `searchProducts`, `getStock` |
| `order-mcp-server` | 8092 | `searchOrders`, `getOrder`, **`cancelOrder`** |
| `shop-agent` | 8090 | MCP 클라이언트 2개 + LLM + 필터 |

두 MCP 서버는 백엔드 서비스 없이 각자 데이터를 들고 있다.
주문이 상품 ID 를 참조하지만 **서버끼리는 서로 호출하지 않는다** — 연결은 LLM 이
두 툴을 연달아 부르면서 이뤄진다.

## 실행

```bash
./run.sh                        # ollama + safe (기본)
./run.sh ollama none            # 필터 없음
./run.sh ollama product-only    # order 서버 툴 차단
./stop.sh
```

## 서버가 둘이어도 배선 코드는 그대로다

```java
toolCallbackProvider.ifAvailable(configured::defaultTools);
```

MCP 자동설정은 클라이언트 수와 무관하게 `ToolCallbackProvider` 빈을 **하나**만 만들고,
그 안에 모든 서버의 툴을 합쳐 담는다. 커넥션을 yml 에 추가하기만 하면 된다.

## 필터

`shop.tool-filter` 속성으로 세 모드를 고른다 (기본 `safe`).

| 모드 | 노출 툴 | 막는 것 |
|---|---|---|
| `none` | 5개 | 없음 |
| `safe` | 4개 | `cancelOrder` |
| `product-only` | 2개 | order 서버 전체 |

`McpToolFilter` 는 `BiPredicate<McpConnectionInfo, McpSchema.Tool>` 이라
**서버 단위·툴 단위 둘 다** 거를 수 있고, 접두사가 붙기 전 원본 이름으로 판단한다.

### 관찰 결과

<!-- Step 5 에서 실제로 실행한 결과로 이 표를 채운다. 예상값을 적지 않는다.
     각 칸에는 (1) 모델이 뭐라고 답했는지 한 줄, (2) 해당 툴 호출 로그가 남았는지를 적는다. -->

| 질문 | `none` | `safe` | `product-only` |
|---|---|---|---|
| "o1 주문 취소해줘" | | | |
| "홍길동 주문 보여줘" | | | |
| "o1 주문한 상품 아직 재고 있어?" | | | |

두 서버를 연달아 쓰는 마지막 질문에서 로컬 8B 모델이 실패했다면 그 사실도 그대로 적는다 —
모델 능력의 한계도 관찰 결과다.

## 학습 포인트

1. **필터는 프롬프트보다 강하다.** `none` 모드에서 "취소하지 마"라고 시스템 프롬프트에
   써두고 시험해보라. 모델이 지키는지 아닌지와 무관하게, `safe` 모드는 **툴 자체가 없어서**
   취소가 불가능하다. 부탁과 경계의 차이다.
2. **필터는 전역이다.** 요청마다 다른 툴을 쓰려면 필터가 아니라 요청의 `.toolCallbacks(...)`
   를 쓰거나 `ChatClient` 를 여러 개 만들어야 한다.
3. **툴 이름 충돌 시 `alt_1_` 접두사가 붙는다.** 서버 이름이 아니라 충돌 순번이다.
   두 서버의 툴 이름을 같게 바꿔보면 관찰할 수 있다.
   규칙을 바꾸려면 `McpToolNamePrefixGenerator` 빈을 등록한다.
4. **`getToolCallbacks()` 는 요청마다 호출된다.** 즉 필터도 매 요청 평가되고,
   MCP 서버에 툴 목록을 매번 물어본다.

## 해볼 것

- 두 서버의 툴 이름을 같게 바꿔 `alt_1_` 접두사를 관찰하기
- `McpToolNamePrefixGenerator` 로 접두사 규칙을 서버 이름 기반으로 바꾸기
- `none` 모드에서 프롬프트만으로 `cancelOrder` 를 막아보고, 필터와 비교하기
```

- [ ] **Step 7: 전체 테스트 재확인**

```bash
export JAVA_HOME=/Users/starryeye/.sdkman/candidates/java/21.0.6-amzn
(cd practice/agent-mcps/product-mcp-server && ./gradlew test) && \
(cd practice/agent-mcps/order-mcp-server && ./gradlew test) && \
(cd practice/agent-mcps/shop-agent && ./gradlew test)
```
Expected: 세 프로젝트 모두 PASS

- [ ] **Step 8: 커밋**

```bash
cd practice/agent-mcps && ./stop.sh
git add practice/agent-mcps
git commit -m "docs: agent-mcps 실행 스크립트와 README 추가

run.sh 는 프로파일과 필터 모드를 인자로 받는다.
README 의 관찰 결과는 실제 종단 실행에서 나온 값이다."
```

---

## 스펙 대비 커버리지

| 스펙 항목 | 담당 |
|---|---|
| product-mcp-server (:8091) 툴 2개 | Task 1 |
| order-mcp-server (:8092) 툴 3개, cancelOrder 상태 변경 | Task 2 |
| 시드 상품 10건 / 주문 3건 (o1→p1) | Task 1 Step 8, Task 2 Step 7 |
| shop-agent (:8090), MCP 커넥션 2개 | Task 3 Step 3 |
| 배선 코드가 agent-mcp 와 동일함 | Task 3 Step 6, README |
| `shop.tool-filter` 3모드 | Task 4 Step 3 |
| 필터 단위 테스트 (모드 × 툴) | Task 4 Step 1 |
| 노출 툴 수 5/4/2 검증 | Task 4 Step 1(단위), Step 5(실제) |
| 필터 효과 종단 관찰 | Task 5 Step 5 |
| 두 서버 연달아 쓰는 질문 | Task 5 Step 5 |
| run.sh / stop.sh (필터 인자) | Task 5 Step 2·3 |
| 학습 과제 (alt_ 접두사 등) | Task 5 Step 6 README |
| agent-mcp 에서 값 치른 항목들 | Global Constraints |
