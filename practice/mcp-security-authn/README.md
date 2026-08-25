# mcp-security-authn

`agent-mcps` 최종 리뷰가 지적한 구멍 — 클라이언트 측 `McpToolFilter` 는 노출할 툴을
고르는 허용목록일 뿐, `POST :8092/mcp` 는 토큰 없이 누구나 두드릴 수 있게 여전히
열려 있었다 — 를 메우는 practice. `org.springaicommunity` 의 MCP 보안 모듈
(`mcp-server-security`, `mcp-client-security`, `mcp-authorization-server`) 을
최소로 연동해, MCP 서버 자체를 사람이 로그인한 토큰이 없으면 거부하게 만든다.

## 구성

| 프로젝트 | 포트 | 역할 | 사용하는 보안 모듈 |
|---|---|---|---|
| `auth-server` | 9000 | OAuth2 인가 서버. `authorization_code` 로 토큰 발급 | `mcp-authorization-server` (`-spring-boot` 변형) |
| `shop-mcp-server` | 8101 | 상품 조회 MCP 서버. 토큰 없으면 `/mcp` 가 401 | `mcp-server-security` (`-spring-boot` 변형) |
| `shop-agent` | 8100 | 브라우저 로그인 UI + Ollama LLM + MCP 클라이언트. 로그인한 사용자의 토큰으로 MCP 를 호출 | `mcp-client-security` (`-spring-boot` 변형) |

세 앱 모두 Spring Boot 4.1.0, MCP 보안 모듈은 0.1.14.

## 이름

원래 이름은 `agent-auth` 였다. `agent-auth` 는 `client_credentials` 로 에이전트
자신을 인증시키는 시나리오에 어울리는 이름인데, 이 practice는 그 반대를 골랐다.
`authorization_code` 토큰의 `sub` 는 로그인한 **사용자**이고 `client_id` 는
**에이전트**다. 즉 주제는 "에이전트를 인증하는 것"이 아니라 "사람이 로그인하고
에이전트가 그 사람을 대신해 MCP 를 호출하는 것"이다. 그래서 이름을
`mcp-security-authn` 으로 바꿨다 — `mcp-security` 는 학습 대상 모듈군 이름
그대로이고, `-authn` 은 인가(스코프·툴 단위 권한)를 다룰 후속 practice
(`mcp-security-authz`)와 구분하기 위한 접미사다.

## 실행

```bash
cd practice/mcp-security-authn
./run.sh
```

`auth-server → shop-mcp-server → shop-agent` 순서로 뜬다 (순서가 강제된다 —
아래 학습 포인트 참고). 브라우저에서 `http://localhost:8100/` 을 열고
`user` / `password` 로 로그인한 뒤 질문한다.

종료: `./stop.sh`

**첫 질문은 느리다.** `spring.ai.mcp.client.initialized: false` 때문에 MCP
핸드셰이크와 툴 목록 조회가 앱 기동 시점이 아니라 로그인한 사용자의 **첫 채팅
요청** 중에 일어난다. 로컬 8B 모델(`qwen3:8b`, `think: low`) 툴 호출 응답은
보통 30~100 초 걸리는데, 첫 요청은 핸드셰이크까지 더해져 그보다 더 걸릴 수
있다 (아래 시나리오 3 실측 참고). 조급하게 실패로 판단하지 말 것.

## 검증 결과

2026-08-26, 실제로 세 앱을 함께 띄우고 관측한 값이다. 예측값이 아니다.

### 시나리오 1 — 토큰 없이 MCP 직접 호출

```
$ curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8101/mcp \
    -H 'Content-Type: application/json' \
    -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
401
```

전체 헤더:

```
HTTP/1.1 401
WWW-Authenticate: Bearer resource_metadata=http://localhost:8101/.well-known/oauth-protected-resource/mcp
```

**`agent-mcps` 의 같은 요청과 나란히 비교.** `agent-mcps/product-mcp-server`
(:8091, 보안 모듈 없음)에 똑같은 방식으로 요청을 두 단계로 실측했다.

| | `mcp-security-authn` (:8101) | `agent-mcps` (:8091) |
|---|---|---|
| `tools/list` 만 보냄 (세션 없음) | `401`, `WWW-Authenticate: Bearer ...` | `400`, `{"message":"Session ID missing"}` — 인증이 아니라 **프로토콜** 단계에서 걸림 |
| `initialize` 로 세션을 먼저 만든 뒤 `tools/list` | (토큰 없인 애초에 `initialize` 조차 401) | `200 OK`, `searchProducts`/`getStock` 스키마가 **그대로 반환됨** |

즉 `agent-mcps` 쪽은 토큰이 전혀 없어도 프로토콜 절차(`initialize` → 세션ID
발급)만 지키면 툴 목록을 통째로 받아간다 — 보안 계층이 아예 없다는 뜻이다.
`mcp-security-authn` 쪽은 `initialize` 시도 자체가 인증 단계에서 막힌다.

### 시나리오 2 — 로그인 없이 브라우저로 접근

`http://localhost:8100/` 에 세션 없이 접근하면 302 로 `http://localhost:9000`
(auth-server)의 "Please sign in" 폼으로 이동한다. 브라우저 자동화로 실제
확인했다 (스크린샷상 `Please sign in` / `Username` / `Password` / `Sign in`
버튼, URL `localhost:9000`).

curl 로 리다이렉트 체인을 따라가도 동일하다:

```
localhost:8100/          → 302 → localhost:8100/oauth2/authorization/authserver
localhost:8100/oauth2/authorization/authserver → 302 → localhost:9000/oauth2/authorize?...
localhost:9000/oauth2/authorize?... (Accept: text/html) → 302 → localhost:9000/login
```

### 시나리오 3 — 로그인 후 질문

**최초 시도에서 실제로 겪은 문제:** 브라우저 자동화로 로그인 폼까지는 정상
도달했으나, 자격증명 제출 후 매번 `Invalid credentials` 오류 페이지로 튕겼다.
원인은 curl 재현으로 추적했다 — 세 앱이 모두 `localhost` 라는 **같은
호스트명**을 쓰고 포트만 다른데, 기본 `JSESSIONID` 쿠키는 **포트를 구분하지
않고 호스트명에만 스코프된다**(RFC 6265 특성). 그래서 브라우저가
`localhost:8100`(에이전트)의 세션 쿠키로 원래 authorization request 를 저장해
두어도, 로그인을 위해 `localhost:9000`(인가 서버)을 거치는 동안 같은 이름의
쿠키가 인가 서버의 세션ID 로 덮어써지고, 콜백으로 `localhost:8100` 에
돌아왔을 때 에이전트가 자신이 저장했던 세션을 더 이상 찾지 못해
`/login?error` 로 떨어졌다. 자동화 도구만의 문제가 아니라 **일반 사용자가
그냥 브라우저로 따라 해도 매번 재현되는 실제 결함**이었다 —
`authorization_code` 는 브라우저 전제 설계이므로 이 흐름 자체가 이 practice
의 핵심 경로다. curl 로 우회하는 것으로는 답이 될 수 없어서, `auth-server`
와 `shop-agent` 에 각각 고유한 세션 쿠키 이름을 지정해 근본 수정했다
(`server.servlet.session.cookie.name` — `AUTHSERVERSESSIONID` /
`SHOPAGENTSESSIONID`). `shop-mcp-server` 는 브라우저가 직접 접근하지 않는
stateless JWT 리소스 서버(에이전트 백엔드가 서버 대 서버로 호출)라 변경이
필요 없었다.

**수정 후, 실제 브라우저(Claude_Browser 자동화, curl 아님)로 처음부터 끝까지
재검증했다.** 로그인 폼 제출 → `shop-agent` 홈(`/?continue`)으로 정상
복귀 → 채팅창 표시까지 한 번에 통과했다.

질문 1: `노트북 재고 있어?` (브라우저 화면에 렌더된 답변을 그대로 옮김)

```
노트북 관련 상품 재고 정보입니다:

1. [p1] 게이밍 노트북 15인치 - 1,890,000원 / 재고 7개
2. [p2] 사무용 노트북 14인치 - 990,000원 / 재고 23개

필요하신 상품이 있으시면 상품 ID(p1/p2)를 알려주시면 더 자세히 안내드릴 수 있습니다.
```

**p1=7개, p2=23개 — 실제 재고 숫자가 정확히 나왔다.**

질문 2 (품절 케이스): `무선 기계식 키보드 살 수 있어?`

```
현재 '무선 기계식 키보드'는 품절된 상태입니다. 재고가 있을 경우 안내드리겠습니다. 다른 상품을 찾아보시겠어요?
```

p3(무선 기계식 키보드, 재고 0)를 정확히 품절로 답했다. 두 질문 모두 브라우저
탭에서 직접 입력·전송하고 화면에 렌더된 답을 확인했다 — API 를 curl 로 찌른
것이 아니다. 체감 지연: 질문 1은 전송 후 화면이 "생각 중..." 상태에서
약 30초 뒤 답으로 바뀌었고(핸드셰이크가 겹친 첫 요청), 질문 2는 약 20초
뒤 바뀌었다 — 두 값 모두 브리핑이 안내한 30~100초 범위 안이다.

### 시나리오 4 — 서버 로그에서 호출자 확인

```
$ grep '호출' logs/shop-mcp-server.log
searchProducts 호출 (keyword=노트북, 사용자=user)
searchProducts 호출 (keyword=무선 기계식 키보드, 사용자=user)
```

세션 쿠키 이름을 바꾼 뒤 재검증한 결과이며 (경로가 바뀌었으니 재확인이
필요했다), 결과는 동일하다. **`사용자=user` 가 이 practice 의 결론이다.** MCP 서버에 도착한 신원은
에이전트(`client_id=shop-agent`)가 아니라 로그인한 사람(`user`)이다.
`shop-agent.log` 에도 토큰이 실제로 요청에 붙는 과정이 DEBUG 로 그대로 남는다:

```
DEBUG ...AuthorizationCodeSyncHttpRequestCustomizer : Requesting access token for client [authserver]
DEBUG ...AuthorizationCodeSyncHttpRequestCustomizer : Token scopes match requested scopes [openid, profile]
DEBUG ...AuthorizationCodeSyncHttpRequestCustomizer : Adding token to header
```

## 학습 포인트

- `authorization_code` 토큰은 `sub`=사용자 / `client_id`=에이전트다. MCP 서버
  로그의 `사용자=user` 가 그 증거다.
- 스트리밍에는 `.contextWrite(writeToReactorContext())` 가 필수다. 없으면
  토큰이 조용히 빠지고 DEBUG 로그 한 줄만 남는다.
- `spring.security.oauth2.resourceserver.jwt.issuer-uri` 가 없으면 MCP 서버
  보안 자동설정이 아예 안 뜬다 — 즉 **무방비로 열린다**. Task 2 Step 7 에서
  직접 확인한 내용이고, 위 시나리오 1 비교표가 그 결과다.
- `spring.ai.mcp.client.type` 이 `SYNC` 가 아니면 클라이언트 보안이 통째로
  사라진다.
- OAuth2 클라이언트 등록이 2개 이상(또는 0개)이면 transport 커스터마이저가
  조용히 no-op 이 된다. `ShopAgentApplicationTests` 에
  `OAuth2ClientProperties.getRegistration()` 크기가 정확히 1인지 보는
  단언을 추가해 이 실패 모드를 잡는다.
- SYNC 서버는 `@McpTool` 반환이 평문 `String` 이다 — 앞 두 practice
  (`agent-mcp`, `agent-mcps`, 둘 다 ASYNC/Mono) 와 정반대다. **직접 확인**:
  `ProductTools.searchProducts` 의 반환 타입을 `Mono<String>` 으로 바꾸고
  `MCP_툴이_실제로_등록된다()` 테스트를 돌리면 —

  ```
  ShopMcpServerApplicationTests > MCP_툴이_실제로_등록된다() FAILED
      java.lang.AssertionError:
      Expecting actual:
        ["getStock"]
      to contain exactly in any order:
        ["searchProducts", "getStock"]
      but could not find the following elements:
        ["searchProducts"]
  ```

  즉 오류 없이, 딱 그 툴 하나만 등록에서 조용히 빠진다. 원래 `String` 으로
  되돌리면 다시 통과한다. (실험 후 파일은 커밋된 상태로 정확히 복구했다.)
- 세 모듈 모두 `@ConditionalOnDefaultWebSecurity` 계열이라, 직접
  `SecurityFilterChain` 을 만들면 해당 자동설정이 통째로 물러난다
  (에이전트 쪽 로그인 페이지 커스터마이징은 예외적으로 별도 처리됨).
- 감사(`aud`) 검증(`validateAudienceClaim`)은 기본 **꺼져** 있다. 이 최소
  연동에서는 issuer 검증만 한다.
- **`spring.ai.mcp.client.initialized: false` 가 필수다.** MCP 서버를
  사용자별 토큰으로 보호한다는 것은, 앱 기동 시점의 핸드셰이크에는 대신할
  로그인한 사용자가 없다는 뜻이다. 기본값(`true`)으로 두면
  `McpSyncClient.initialize()` 가 기동 시점에 즉시 호출되고, 그 시점엔 인증
  정보가 없으니 MCP 서버가 무조건 401을 주고, 그 결과 스프링 컨텍스트
  초기화 자체가 실패해 앱이 아예 뜨지 않는다. `false` 로 미루면 핸드셰이크와
  **툴 목록 조회**가 실제 첫 요청(로그인한 사용자의 첫 채팅) 시점까지
  지연된다 — 그래서 첫 질문이 눈에 띄게 느리다.
- **`issuer-uri` 를 지워도 서버가 열리는 게 아니라 보호 수단이 조용히
  바뀐다.** `issuer-uri` 를 빼면 MCP 서버 보안 자동설정이 안 뜨는 대신,
  Boot 의 기본 HTTP Basic(무작위 생성 비밀번호)이 그 자리를 대신 채워서
  여전히 401 을 반환한다. 그래서 상태 코드만 보는 테스트는 이 경우에도
  그대로 통과해 버린다 — **실제로 검증됨**, 계획 문서의 명시적 예측을
  뒤집은 결과다. 두 경우를 가르는 유일한 신호는 `WWW-Authenticate` 의
  스킴이다: 이 모듈이 살아있으면 `Bearer`, Boot 기본 보안이면 `Basic`.
  그래서 401 테스트(`토큰_없이_MCP_엔드포인트를_호출하면_401이다`)는 상태
  코드뿐 아니라 스킴이 `Bearer` 로 시작하는지까지 단언한다.
- **`localhost` 에서 여러 OAuth2 앱을 같이 띄우려면 세션 쿠키 이름을 앱마다
  다르게 줘야 한다.** 쿠키는 포트를 구분하지 않고 호스트명에만 스코프된다
  (RFC 6265). `auth-server`(:9000)와 `shop-agent`(:8100)가 기본 쿠키 이름
  `JSESSIONID` 를 그대로 쓰면, 로그인을 위해 두 호스트를 오가는 동안 브라우저가
  같은 이름의 쿠키를 서로 덮어써서 에이전트가 저장해 둔 authorization request
  를 잃어버리고 로그인이 매번 실패한다 — 이 문서 종단 검증 중 실제로 겪었다.
  `server.servlet.session.cookie.name` 으로 앱마다 고유한 이름
  (`AUTHSERVERSESSIONID`, `SHOPAGENTSESSIONID`)을 지정해 해결했다. 로컬에서
  포트만 다른 여러 웹앱을 동시에 개발할 때 흔히 걸리는 함정이다.

## 트러블슈팅

| 증상 | 확인 명령 | 의미 |
|---|---|---|
| 시나리오 3에서 401 또는 툴이 호출되지 않음 | `grep -i 'not requesting token' logs/shop-agent.log` | 보이면 `ChatController` 의 `.contextWrite(...)` 가 빠졌거나, `writeToReactorContext()` 가 요청 스레드 밖에서 호출된 것 |
| 위 증상 계속 | `grep -i 'client registrations but expected exactly 1' logs/shop-agent.log` | 보이면 `spring.security.oauth2.client.registration.*` 이 2개 이상(또는 0개) |
| 위 증상 계속 | `grep -i 'McpOAuth2ClientAutoConfiguration\|transport customizer' logs/shop-agent.log` | 아무것도 안 보이면 `spring.ai.mcp.client.type` 이 `SYNC` 가 아닐 가능성이 높음 |
| 최후의 수단 | 컨트롤러를 `.stream().content()` → `.call().content()`(반환 타입 `String`)로 임시 변경 후 재확인 | 요청 스레드에서 실행되어 thread-local 이 살아있다. 이때 통하면 원인은 확실히 리액터 컨텍스트 전달. **진단용, 최종 코드는 스트리밍으로 되돌린다** |
| 브라우저에서 로그인이 항상 `Invalid credentials` 로 실패 | `auth-server`/`shop-agent` 의 `application.yml` 에서 `server.servlet.session.cookie.name` 이 앱마다 다른지 확인 | 같으면(또는 기본값 `JSESSIONID` 그대로면) 호스트 전용(host-only) 쿠키가 포트를 구분하지 않고 서로 덮어써서 에이전트가 저장해 둔 authorization request 를 잃어버린다. 이 practice 에서는 실제로 겪었고, 각 앱에 고유 쿠키 이름을 줘서 고쳤다 (`AUTHSERVERSESSIONID` / `SHOPAGENTSESSIONID`) |

## 비목표

스코프 검사·툴 단위 인가(어떤 사용자가 어떤 툴/리소스까지 쓸 수 있는지)는
다루지 않는다. 이 practice 는 "인증"(누가 왔는지 확인)까지만 다루고, "인가"
(그 사람이 이 툴을 써도 되는지)는 후속 practice `mcp-security-authz` 의
몫이다.
