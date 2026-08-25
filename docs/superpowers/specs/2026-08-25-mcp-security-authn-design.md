# mcp-security-authn — MCP 보안 3개 모듈 최소 연동 설계

작성일: 2026-08-25

## 배경

`practice/agent-mcps` 의 최종 코드 리뷰가 이렇게 지적했다:

> `McpToolFilter` 는 클라이언트측 허용목록이지 서버측 인가가 아니다.
> `cancelOrder` 는 여전히 `POST localhost:8092/mcp` 로 누구나 호출할 수 있다.

`practice/mcp-security-authn` 는 그 구멍을 메운다. 필터로 가린 것과 **실제로 잠근 것**의 차이를 보여준다.

## 이름에 대해

처음에 `agent-auth` 로 잡았다가 바꿨다. 그 이름은 **에이전트를 인증한다**로 읽히는데,
우리가 고른 `authorization_code` 로 발급되는 토큰은 이렇게 생겼다:

```
sub       = 사용자        ← 권한의 출처
client_id = 에이전트      ← 발급 대상
```

주제는 "에이전트 인증"이 아니라 **사용자가 로그인하고, 에이전트가 그 사용자를 대신해
보호된 MCP 서버를 호출하는 것**이다. `agent-auth` 는 `client_credentials` 를 골랐을 때
어울릴 이름이고, 그건 안 고른 쪽이다.

`mcp-security` 는 학습 대상 모듈군의 이름 그대로다. `-authn` 은 이 practice 가
**인증까지만** 한다는 뜻이고, 인가를 다루는 후속 practice 는 `mcp-security-authz` 가 된다.

> `authorization_code` 라는 grant 이름 때문에 "인가를 하는 것 아닌가" 싶을 수 있는데,
> **grant 종류의 이름일 뿐이다.** 여기서 하는 일은 토큰 검증(인증)까지이고,
> 스코프 검사나 툴 단위 권한 판단은 하지 않는다.

앞선 두 practice 가 `agent-` 로 시작하는 것과 모양이 달라지는데, 의도한 것이다.
`agent-mcp` / `agent-mcps` 는 **MCP 구성**이 축이었고 이건 **MCP 보안**이 축이라, 축이 다르면
이름 모양도 다른 편이 폴더 목록만 보고 구분하기 좋다.

## 학습 대상

`org.springaicommunity` 의 MCP 보안 모듈 **0.1.14** 세 개다. 이 모듈들을 쓰는 법 자체가 목표다.

| 모듈 | 배치 | 역할 |
|---|---|---|
| `mcp-authorization-server-spring-boot` | auth-server | 토큰 발급. MCP용 DCR·CIMD·localhost 리다이렉트 검증이 얹힌 Spring Authorization Server |
| `mcp-server-security-spring-boot` | shop-mcp-server | `Authorization: Bearer` 검증, audience 바인딩, 세션 바인딩 |
| `mcp-client-security-spring-boot` | shop-agent | 토큰 획득·부착, 401 시 재인가 |

`-spring-boot` 접미 변형을 쓴다. 자동설정이 붙고, 접미 없는 코어 모듈(`mcp-server-security` 등)을
전이 의존으로 그대로 끌어온다. 코어만 직접 쓰면 필터체인을 손으로 조립해야 해서 "최소 연동"과 어긋난다.

**버전 제약**: `0.1.x` 는 Spring AI `2.0.x` 전용이다. (`1.1.x` 는 `0.0.6`)

### POM 실측으로 확인한 것

- `mcp-security-common` 은 `mcp-client-security` 와 `mcp-authorization-server` 만 의존한다.
  `mcp-server-security` 는 쓰지 않는다. 어느 쪽이든 전이 의존이라 직접 선언할 일은 없다.
- `mcp-server-security` 와 `mcp-authorization-server` 는 `jakarta.servlet-api` 를 요구한다.
  두 설정 클래스가 `AbstractHttpConfigurer<..., HttpSecurity>` 를 상속한다 —
  **servlet 전용이고 `ServerHttpSecurity`(리액티브) 대응물이 없다.**
  그래서 이 practice 는 servlet 스택으로 간다.

## 결정 사항

| 항목 | 선택 | 이유 |
|---|---|---|
| Grant type | **`authorization_code`** | 토큰에 사용자 신원(`sub`)이 담긴다. `client_credentials` 는 에이전트 신원뿐이라 "사용자 대신 호출"이 드러나지 않는다 |
| 스택 | **servlet (webmvc)** | 위 제약. 선택이 아니라 강제다 |
| 인가 범위 | **인증만** — 토큰 없으면 401 | "최소 연동"에 맞춘다. 스코프·툴 단위 인가는 다음 practice |
| LLM | **포함** (ollama, qwen3:8b) | 앞선 두 practice 와 같은 모양을 유지 |
| 질문 방식 | **브라우저 페이지** | `authorization_code` 는 브라우저 전제 설계다. curl 로 로그인 왕복을 흉내 내는 스크립트가 이 프로젝트에서 가장 깨지기 쉬운 부분이 될 것이므로 없앤다 |

## 레이아웃

```
practice/mcp-security-authn/
├── auth-server/       :9000
├── shop-mcp-server/   :8101
└── shop-agent/        :8100
```

포트는 `agent-mcp`(8080~8082) · `agent-mcps`(8090~8092) 와 겹치지 않는다. 세 practice 를 동시에 띄울 수 있다.
`9000` 은 OAuth 관례이고 `issuer-uri` 예시와도 맞는다.

### 기존 practice 는 건드리지 않는다

`practice/agent-mcp` 와 `practice/agent-mcps` 의 파일은 **한 줄도 수정하지 않는다.**
읽고 참고하는 것은 되지만 쓰기는 안 된다. 두 practice 는 종단 검증까지 끝나 있고,
여기서 손대면 그 검증 결과가 무효가 된다.

루트의 `settings.gradle` · `.gitignore` 같은 공용 파일에 이 practice 를 등록해야 한다면
**추가만** 하고 기존 항목은 그대로 둔다. 구현 계획의 Global Constraints 에 다시 명시한다.

## servlet 이 뒤집는 것 — 앞 practice 와 반대다

| | agent-mcp / agent-mcps | **mcp-security-authn** |
|---|---|---|
| 스택 | WebFlux | **servlet (webmvc)** |
| MCP 서버 타입 | `type: ASYNC` | **`type: SYNC`** |
| `@McpTool` 반환 | `Mono<String>` | **`String`** |
| 스타터 | `spring-ai-starter-mcp-server-webflux` | `spring-ai-starter-mcp-server-webmvc` |
| MCP 클라이언트 | `spring-ai-starter-mcp-client-webflux` | `spring-ai-starter-mcp-client` |

**반환 타입 규칙이 정반대로 뒤집힌다.** 앞 두 practice 의 계획서에 "반드시 `Mono`" 라고 못 박았던 제약이
여기서는 틀린 지시가 된다. SYNC 서버는 리액티브 반환 타입을 걸러내고 비리액티브만 등록한다.
구현 계획의 Global Constraints 에 명시한다.

## 컴포넌트

### 1. auth-server (:9000)

의존성: `mcp-authorization-server-spring-boot`, `oauth2-authorization-server`, `webmvc`

- 사용자 하나를 in-memory 로 둔다 (`user` / `password`). 학습용이므로 그 이상 필요 없다.
- MCP 클라이언트는 **동적 클라이언트 등록(DCR)** 으로 스스로 등록한다 — 수동 클라이언트 설정을 줄인다.
- 동의 화면은 뜨지 않을 것으로 본다. 모듈에 `McpNoScopeClientConsentNotRequired` 가 있고,
  이 practice 는 스코프를 요청하지 않는다. 실측으로 확인한다.

### 2. shop-mcp-server (:8101)

의존성: `spring-ai-starter-mcp-server-webmvc`, `mcp-server-security-spring-boot`, `webmvc`

```yaml
spring:
  ai:
    mcp:
      server:
        protocol: STREAMABLE
        type: SYNC
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:9000
```

툴 두 개. 반환은 **평문 `String`** 이다.

| 툴 | 설명 |
|---|---|
| `searchProducts(keyword?)` | 키워드 부분일치. 생략 시 전체 |
| `getStock(productId)` | 재고 수량. 0이면 품절 문구 |

시드 상품 10건은 `agent-mcp` · `agent-mcps` 와 **동일하다** (p1 게이밍 노트북 재고 7, p3 무선 기계식 키보드 재고 0 등).
세 practice 를 오가며 비교하기 위해서다.

**위험한 툴(`cancelOrder`)은 넣지 않는다.** 인가를 하지 않기로 했으므로 있어도 쓸 데가 없다.

### 3. shop-agent (:8100)

의존성: `spring-ai-starter-mcp-client`, `mcp-client-security-spring-boot`,
`spring-boot-starter-security-oauth2-client`, `spring-ai-starter-model-ollama`, `webmvc`

- `/api/chat` — 질문을 받아 `text/plain` 으로 스트리밍 (앞 practice 와 동일)
- `static/index.html` — 입력창 + 버튼 + 출력 영역. `fetch` + `ReadableStream` 으로 점진 출력.

> **UI 연습이 아니다.** 프레임워크·빌드도구·CSS 프레임워크를 쓰지 않는다.
> 파일 한 장, 50줄 내외. 로그인 흐름을 브라우저에 맡기기 위한 최소 장치일 뿐이다.

`ChatClientConfig` 의 툴 배선은 앞 practice 와 같다 — `toolCallbackProvider.ifAvailable(configured::defaultTools)`.
MCP 자동설정이 클라이언트 수와 무관하게 `ToolCallbackProvider` 빈을 하나만 만들기 때문이다.

## 흐름

```
사용자 ──브라우저──▶ shop-agent(:8100)/index.html
                        │ 미인증이면
                        ▼
                   auth-server(:9000) 로그인 → code
                        │
                        ▼ code ↔ token 교환, 세션에 보관
사용자 ──질문──▶ shop-agent ──▶ ollama (툴 호출 결정)
                        │
                        └─Bearer 토큰─▶ shop-mcp-server(:8101)
                                          │ JWT 검증 (issuer + audience)
                                          └─▶ @McpTool
```

## 검증 시나리오

이 practice 의 산출물은 아래 관측 결과다.

1. **토큰 없이 MCP 서버 직접 호출** → `curl -X POST localhost:8101/mcp` → **401**
   - `agent-mcps` 에서 열려 있던 바로 그 구멍이 닫힌 것을 보여준다. 두 practice 가 이어지는 지점이다.
2. **로그인 전 브라우저 접속** → auth-server 로그인 화면으로 이동
3. **로그인 후 질문** → 툴 호출 성공, 실제 재고 숫자가 답변에 포함
4. **서버 로그** → 툴 호출 기록과 인증 주체 확인

검증은 브라우저를 직접 조작해 수행하고, **관측한 그대로** README 에 기록한다. 예측값을 적지 않는다.

## 에러 처리

- 토큰 없음/만료 → MCP 서버가 401. 클라이언트 모듈이 재인가를 시도한다
- 존재하지 않는 상품 ID → 툴이 예외 대신 "찾을 수 없습니다" 문장을 반환 (앞 practice 와 동일 원칙)
- 각 툴은 호출 시 `log.info` 를 남긴다 (검증에 필요)

## 테스트

- **MCP 서버 보안 회귀 테스트** — `MockMvc` 로 토큰 없이 `/mcp` 호출 시 401. 이 practice 에서 가장 중요한 테스트다.
  모듈이 실제로 붙었는지를 이것만이 보증한다
- **툴 등록 테스트** — 툴 두 개가 실제로 등록되는지. `agent-mcps` 에서 반환 타입 하나만 바꿔도
  테스트가 전부 통과하던 문제가 있었으므로 처음부터 넣는다
- 툴 메서드 단위 테스트 — SYNC 라 스텁이 필요 없다
- 에이전트 컨텍스트 로드 + MCP 클라이언트 배선 테스트

LLM 실호출은 테스트하지 않는다.

## 앞선 practice 에서 값을 치른 것들

`agent-mcp` · `agent-mcps` 에서 확인된 사실이다. 다시 발견하지 않는다.

| 항목 | 내용 |
|---|---|
| 툴 배선 | `defaultTools(provider)` 로 직접 꽂아야 한다. 자동으로 안 붙는다 |
| 모델 타입 | `spring.ai.model.{audio.speech, audio.transcription, embedding, image, moderation}: none` — 안 끄면 다른 스타터가 API 키를 요구해 기동 실패 |
| 응답 | `text/plain` (SSE 는 토큰마다 `data:` 가 붙어 읽을 수 없다) |
| ollama | `think: low` (미지정은 수 분, `false` 는 툴 설명을 못 따른다) |
| 애노테이션 | `org.springframework.ai.mcp.annotation.{McpTool, McpToolParam}` |
| 속성 | `spring.ai.<provider>.chat.model` (`chat.options.*` 아님) |
| 툴 캐싱 | `getToolCallbacks()` 결과는 캐시된다. `McpToolsChangedEvent` 때만 갱신 |
| 빌드 | Initializr 로 생성, `bootVersion=4.1.0` |
| 스크립트 | 백그라운드 프로세스는 `< /dev/null` + `disown` 로 분리. 안 하면 스크립트가 호출자를 붙잡는다 |

단 **`Mono` 반환 제약은 여기서 뒤집힌다** (위 servlet 표 참조).

## 미확인 위험 — 1번 태스크로 먼저 실측한다

`authorization_code` 로 받은 토큰은 **사용자 세션**에 묶인다. 그런데 MCP 툴 호출은 LLM 이 결정해서
일어나므로, 그 시점에 사용자의 `Authentication` 이 살아 있어야 토큰을 붙일 수 있다.

`AuthenticationMcpTransportContextProvider` 에 `writeToReactorContext()`,
`fromToolCallReactiveContextHolder()` 가 있는 것으로 보아 이 문제를 인지하고 만든 흔적은 있으나,
**실제로 통하는지 확인하지 못했다.**

이번 조사에서 가정으로 세 번 틀렸다 — `ToolCallbackProvider` 자동 배선, `getToolCallbacks()` 캐싱,
이 보안 모듈들의 존재 여부. 그러므로 **구현 1번 태스크를 "인증 컨텍스트가 툴 호출까지 전달되는가"
실측으로 잡고, 결과에 따라 나머지 설계를 확정한다.** 통하지 않으면 설계를 다시 본다.

## 비목표

- 스코프 검사, 툴 단위 인가 (다음 practice)
- 사용자 여러 명, 역할 분리
- 토큰 저장소 영속화 (in-memory 로 충분)
- UI 완성도
- `client_credentials`, `hybrid` grant

## 참고

- [agent-mcps 설계](2026-08-13-agent-mcps-design.md)
- [spring-ai-community/mcp-security](https://github.com/spring-ai-community/mcp-security)
