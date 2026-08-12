# agent-mcp

Spring AI 2.0의 **agent + MCP** 최소 예제.

평범한 Spring Boot 서비스를 MCP 서버로 감싸고, 에이전트가 MCP를 통해 그 기능을 사용한다.

## 구성

| 프로젝트 | 포트 | 역할 | Spring AI 의존성 |
|---|---|---|---|
| `product-service` | 8081 | 상품/재고 REST 서비스 | 없음 |
| `product-mcp-server` | 8082 | 서비스를 `@McpTool` 로 노출 | MCP 서버만 (모델 없음) |
| `product-agent` | 8080 | MCP 클라이언트 + LLM | MCP 클라이언트 + 모델 |

전 구간 WebFlux 리액티브. MCP 전송은 streamable-HTTP.

## 실행

API 키는 사용할 프로바이더 것만 있으면 된다.

```bash
export OPENAI_API_KEY='...'        # 또는
export ANTHROPIC_API_KEY='...'
```

터미널 3개에서 순서대로:

```bash
cd product-service     && ./gradlew bootRun
cd product-mcp-server  && ./gradlew bootRun
cd product-agent       && ./gradlew bootRun --args='--spring.profiles.active=openai'
```

질문:

```bash
curl -N -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: text/plain' \
  -d '노트북 재고 있어?'
```

## 프로바이더 전환

두 모델 스타터가 모두 클래스패스에 있고, `spring.ai.model.chat` 속성이 활성 자동설정을 고른다.
프로파일만 바꾸면 된다:

```bash
./gradlew bootRun --args='--spring.profiles.active=anthropic'
```

활성 `ChatModel` 빈이 항상 하나뿐이므로 자동설정된 `ChatClient.Builder` 를 그대로 쓴다.
`@Qualifier` 나 별도 설정 클래스가 필요 없다.

## 학습 포인트

1. **`@McpTool` 의 `description` 이 LLM의 툴 선택 근거다.**
   `ProductTools` 의 description을 부실하게 바꿔보면 LLM이 툴을 고르지 않는 것을 관찰할 수 있다.
2. **ASYNC MCP 서버는 리액티브 반환 타입만 툴로 등록한다.**
   `Mono`/`Flux` 가 아닌 반환 타입은 경고만 남기고 조용히 제외되므로,
   툴이 안 보이면 기동 로그의 필터링 경고부터 본다.
3. **MCP 툴은 자동으로 모델에 전달되지 않는다.**
   MCP 클라이언트 자동설정이 만들어주는 것은 `ToolCallbackProvider` 빈까지다.
   `ChatController` 가 `builder.defaultTools(provider)` 로 직접 꽂아야 LLM이 툴 정의를 받는다.
   이 한 줄을 지우면 기동도 되고 답변도 오지만, 모델은 툴 없이 기억으로만 답한다.
4. **같은 MCP 툴을 서로 다른 모델이 어떻게 고르는지 비교할 수 있다.**
   툴은 `ToolCallbackProvider` 하나에서 나오므로 두 프로바이더에 동일하게 적용된다.
5. **툴 실패는 예외가 아니라 대화의 일부다.**
   `product-service` 를 내린 채 질문해보면, 툴이 설명 문장을 반환하고
   LLM이 그것을 사용자에게 전달하는 흐름을 볼 수 있다.

## 트러블슈팅

| 증상 | 확인할 곳 |
|---|---|
| 툴이 등록되지 않음 | mcp-server 기동 로그의 필터링 경고 — 반환 타입이 `Mono`/`Flux` 인가 |
| agent가 툴을 못 찾음 | mcp-server가 먼저 떠 있는가, `streamable-http.connections.product.url` 이 맞는가 |
| 기동 시 `ChatModel` 빈 모호성 | `spring.ai.model.chat` 이 활성 프로파일에서 하나로 지정되었는가 |
| 답변에 실제 숫자가 없음 | 툴이 호출되지 않은 것 — mcp-server 로그에 `ProductTools` 의 `searchProducts 호출` / `getStock 호출` 이 찍히는가. 안 찍히면 agent 쪽 `defaultTools(provider)` 배선부터 본다 |
| 툴이 항상 "조회할 수 없습니다" 를 반환 | mcp-server 로그의 `ProductTools ... 실패` ERROR 스택트레이스 — 대개 product-service 가 안 떠 있거나 `product-service.base-url` 이 틀렸다 |

## 검증 상태

세 프로젝트 모두 단위 테스트(`./gradlew test`)는 통과했다.
그러나 세 프로세스를 동시에 띄워 실제 모델이 MCP 툴(`searchProducts`, `getStock`)을
호출하는 종단 시나리오는 아직 수행하지 않았다 — 이 환경에 `OPENAI_API_KEY`,
`ANTHROPIC_API_KEY` 가 모두 설정되어 있지 않기 때문이다.
API 키가 준비되면 위 브리프의 Step 1~5를 그대로 따라 검증한다.
