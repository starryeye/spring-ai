# mcp-security-authn-official

이 practice는 OAuth로 보호한 MCP 호출을 Spring Security·Spring Authorization Server·Spring AI·MCP Java SDK만으로 만든다.
spring-ai-community의 MCP 보안 module은 쓰지 않는다.
사용자가 browser로 login하면, agent가 그 사용자를 대신해 MCP Server의 tool을 부른다.
명령줄 앱 `local-client`는 사용자 기기의 MCP client를 흉내 내어 같은 MCP Server를 부른다.
[MCP 안내서](../mcp-guide/README.md)의 예시는 모두 이 practice에서 나온다.
흐름과 규칙은 안내서에서 읽고, 이 README에서는 실행 방법과 코드의 위치를 본다.

## 구성

| module | 포트 | 역할 |
|---|---|---|
| `auth-server` | 9010 | Authorization Server다. 사용자의 login을 받고, MCP Server에서 쓸 access token을 발급한다 |
| `shop-mcp-server` | 8111 | MCP Server(`/mcp`)다. 요청마다 token을 검증하고, `getStock`·`searchProducts` tool을 제공한다 |
| `shop-agent` | 8110 | browser 채팅 화면이 있는 agent다. confidential client `official-shop-agent`로 받은 token을 MCP 요청에 붙이고, LLM은 Ollama의 `qwen3:8b`를 쓴다 |
| `local-client` | 없음 | 명령줄 MCP client다. public client `local-mcp-client`로 discovery부터 MCP 호출까지 혼자 밟고, callback은 `127.0.0.1`의 빈 포트로 받는다 |

세 서버는 Spring Boot 4.1.0의 servlet 앱이고, `127.0.0.1`에서만 연결을 받는다.
`local-client`는 Spring Boot 없이 Java 21과 MCP Java SDK 2.0.0만 쓴다.
module 사이에서 token이 오가는 전체 흐름은 [2장 전체 흐름](../mcp-guide/02-why-oauth.md#25-전체-흐름-시퀀스-다이어그램)에 있다.

## 실행

준비물은 Java 21과 ollama다.
ollama가 없으면 `brew install ollama`로 설치한다.

```bash
# 저장소 최상위 폴더에서
cd practice/mcp-security-authn-official
./run.sh
```

`run.sh`는 먼저 Java 21을 찾는다.
`JAVA_HOME`이 Java 21이 아니면 sdkman의 설치 폴더(`$HOME/.sdkman/candidates/java/21.*`)에서 찾는다.
그다음 ollama를 켜고, `qwen3:8b` 모델이 없으면 내려받는다.
모델을 처음 받을 때는 시간이 걸린다.
마지막으로 `auth-server` → `shop-mcp-server` → `shop-agent` 순서로 띄우고, 앱마다 응답할 때까지 기다린다.
포트가 이미 쓰이고 있으면 그 앱은 새로 띄우지 않고 건너뛴다.
앱의 로그는 `practice/mcp-security-authn-official/logs/<module>.log`에 남는다.

browser에서 `http://localhost:8110`을 열고 `user`/`password`로 login한 뒤 상품 재고를 묻는다.

**`local-client` 실행**

`local-client`에게는 `auth-server`와 `shop-mcp-server`만 있으면 된다.
`run.sh`로 세 서버를 띄웠다면 그대로 실행한다.
`./gradlew run`은 `JAVA_HOME`이 Java 21을 가리켜야 돈다.

```bash
# 저장소 최상위 폴더에서
cd practice/mcp-security-authn-official/local-client
./gradlew run
```

browser가 열리면 `user`/`password`로 login하고, consent 화면에서 `profile`을 골라 제출한다.
terminal에 discovery부터 MCP 호출까지 다섯 단계가 찍히고, 앱은 끝난다.
인자와 출력의 뜻, 두 서버만 띄우는 방법은 [7장 실행해 보기](../mcp-guide/07-local-client.md#79-실행해-보기)에 있다.

**멈추기**

```bash
# practice/mcp-security-authn-official에서
./stop.sh
```

`stop.sh`는 9010·8111·8110 포트에서 연결을 기다리는 process를 내린다.
`./stop.sh --ollama`는 ollama도 함께 내린다.

## 코드 지도

module마다 주요 클래스와 그 클래스를 설명하는 안내서 절을 모았다.
클래스는 `<module>/src/main/java/dev/starryeye/<package>/`에 있다.

**`auth-server`** — package `officialauthserver`

| 클래스 | 하는 일 | 안내서 |
|---|---|---|
| `application.yml` | client 두 개를 미리 등록하고 PKCE를 반드시 쓰게 한다. token을 발급할 resource 목록(`mcp.authorization.resources`)도 둔다 | [4장 pre-registration](../mcp-guide/04-client-registration.md#43-pre-registration-official의-두-client) |
| `AuthorizationServerConfig` | filter chain 두 개를 직접 정의한다. metadata에 `none`과 `iss` 지원을 알리고, authorization endpoint에 검증기와 응답 handler를 연결한다 | [4장 official 코드](../mcp-guide/04-client-registration.md#48-official-코드에서-보기), [5장 official 코드](../mcp-guide/05-authorization-and-token.md#511-official-코드에서-보기) |
| `McpResourceProperties`, `ResourceIndicatorValidator` | 모르는 `resource`의 authorization request를 `invalid_target`으로 거절한다 | [5장 authorization request](../mcp-guide/05-authorization-and-token.md#53-authorization-request를-보낸다) |
| `PublicClientScopeValidator`, `PublicClientConsentService` | public client가 consent를 건너뛰지 못하게 한다 | [5장 login과 consent](../mcp-guide/05-authorization-and-token.md#55-login과-consent) |
| `IssuerIdentifyingAuthorizationResponseHandler` | 성공과 오류 redirect에 모두 `iss`를 붙인다 | [5장 callback 확인](../mcp-guide/05-authorization-and-token.md#56-callback에서-state와-iss를-확인한다) |
| `ResourceAudienceTokenCustomizer` | access token의 `aud`를 `resource`로 정한다. authorization request와 다른 `resource`의 token request는 `invalid_target`이다 | [5장 token request](../mcp-guide/05-authorization-and-token.md#57-token-request) |
| `ClientAuthenticationChallengeFailureHandler` | `Authorization` header로 시도한 client 인증이 실패하면 `401`에 `WWW-Authenticate`를 붙인다 | [부록 API의 token endpoint](../mcp-guide/reference-api.md#post-oauth2token--authorization_code) |
| `UserConfig` | login 계정 `user`/`password`를 둔다 | [2장 역할](../mcp-guide/02-why-oauth.md#22-역할) |

**`shop-mcp-server`** — package `officialmcpserver`

| 클래스 | 하는 일 | 안내서 |
|---|---|---|
| `SecurityConfig` | 모든 요청에 token을 요구하고 JWT로 검증한다. PRM과 `401`의 `resource_metadata`를 켠다 | [3장 official 코드](../mcp-guide/03-discovery.md#37-official-코드에서-보기), [6장 official 코드](../mcp-guide/06-mcp-call-and-validation.md#69-official-코드에서-보기) |
| `application.yml` | `issuer-uri`와 `audiences`로 token의 `iss`와 `aud`를 확인한다. `server.address: 127.0.0.1`로 이 기기의 연결만 받는다 | [6장 token 검증](../mcp-guide/06-mcp-call-and-validation.md#65-2단계-token-검증) |
| `McpTransportConfig`, `McpTransportSecurityFilter` | `Origin`과 `Host`를 Spring Security보다 먼저 검사해 `403`·`421`로 거절한다 | [6장 Origin·Host 검사](../mcp-guide/06-mcp-call-and-validation.md#64-1단계-originhost-검사) |
| `McpProtocolVersionFilter` | token 검증 뒤에 `MCP-Protocol-Version`을 보고, 모르는 버전을 `400`으로 거절한다 | [6장 버전과 session 검사](../mcp-guide/06-mcp-call-and-validation.md#66-34단계-mcp-protocol-version과-session-검사) |
| `ProductTools`, `ProductRepository` | `@McpTool`로 `getStock`·`searchProducts` tool을 만든다. 상품은 메모리에 있다 | [1장 official 코드](../mcp-guide/01-mcp-basics.md#112-official-코드에서-보기) |

**`shop-agent`** — package `officialagent`

| 클래스 | 하는 일 | 안내서 |
|---|---|---|
| `McpAuthorizationDiscovery`, `DiscoveredAuthorization`, `McpDiscoveryException` | `401` → PRM → issuer 비교 → metadata 순서로 discovery를 한다 | [3장 official 코드](../mcp-guide/03-discovery.md#37-official-코드에서-보기) |
| `DiscoveredClientRegistrationRepository`, `McpAuthorizationProperties` | 설정의 credentials와 discovery 결과로 login에 쓸 client 등록 정보를 만든다. `credentials-issuer`와 다른 issuer에는 credentials를 쓰지 않는다 | [4장 credentials와 issuer](../mcp-guide/04-client-registration.md#47-credentials를-issuer에-묶기), [4장 official 코드](../mcp-guide/04-client-registration.md#48-official-코드에서-보기) |
| `SecurityConfig` | `oauth2Login`을 켜고, authorization request에 PKCE와 `resource`를 더한다. `/api/chat`에도 CSRF 검사를 켠다 | [5장 official 코드](../mcp-guide/05-authorization-and-token.md#511-official-코드에서-보기) |
| `ResourceIndicators` | authorization request, token request, refresh request에 `resource`를 넣는다 | [5장 authorization request](../mcp-guide/05-authorization-and-token.md#53-authorization-request를-보낸다), [5장 만료와 refresh](../mcp-guide/05-authorization-and-token.md#59-만료와-refresh) |
| `McpSecurityConfig` | token request를 보내는 bean과 refresh를 하는 `authorizedClientManager`를 등록한다. `SecurityMcpTransportContextProvider`와 `OAuth2TokenAttachingRequestCustomizer`를 MCP client에 연결한다 | [5장 official 코드](../mcp-guide/05-authorization-and-token.md#511-official-코드에서-보기), [6장 token 붙이기](../mcp-guide/06-mcp-call-and-validation.md#68-agent가-token을-붙이는-방법) |
| `AuthorizationResponseIssuerFilter`, `LoginFailureHandler` | callback의 `iss`를 code 교환 전에 확인하고, 어긋나면 `401`로 끝낸다 | [5장 callback 확인](../mcp-guide/05-authorization-and-token.md#56-callback에서-state와-iss를-확인한다) |
| `SecurityMcpTransportContextProvider`, `OAuth2TokenAttachingRequestCustomizer` | MCP 요청마다 그 요청을 일으킨 사용자의 access token을 `Authorization` header에 넣는다 | [6장 token 붙이기](../mcp-guide/06-mcp-call-and-validation.md#68-agent가-token을-붙이는-방법) |
| `ShopAgentApplication` | `Hooks.enableAutomaticContextPropagation()`으로 reactor thread에 `SecurityContext`를 옮긴다 | [6장 token 붙이기](../mcp-guide/06-mcp-call-and-validation.md#68-agent가-token을-붙이는-방법) |
| `ChatClientConfig`, `ChatController` | MCP tool을 `ChatClient`의 기본 tool로 넣고, `/api/chat`의 답을 stream으로 보낸다 | [1장 official 코드](../mcp-guide/01-mcp-basics.md#112-official-코드에서-보기) |
| `application.yml` | discovery의 출발점 `mcp.authorization.resource-url`과 `credentials-issuer`를 둔다. `initialized: false`로 `initialize`를 첫 채팅까지 미룬다 | [3장 official 코드](../mcp-guide/03-discovery.md#37-official-코드에서-보기), [1장 official 코드](../mcp-guide/01-mcp-basics.md#112-official-코드에서-보기) |

**`local-client`** — package `localclient`

| 클래스 | 하는 일 | 안내서 |
|---|---|---|
| `Main` | 아래 클래스를 단계 순서로 부른다. scope는 `openid profile`로 정해 둔다 | [2장 official 코드](../mcp-guide/02-why-oauth.md#28-official-코드에서-보기), [7장 authorization request](../mcp-guide/07-local-client.md#75-3단계-authorization-request-주소를-만든다--authorizationrequest) |
| `Discovery` | `401` → PRM → metadata 순서로 Authorization Server를 찾고, 등록된 issuer인지 확인한다 | [7장 discovery](../mcp-guide/07-local-client.md#73-1단계-discovery--discovery) |
| `LoopbackCallbackServer` | `127.0.0.1`의 빈 포트에 callback server를 연다 | [7장 callback server](../mcp-guide/07-local-client.md#74-2단계-callback-server를-연다--loopbackcallbackserver) |
| `Pkce`, `AuthorizationRequest`, `Browser` | `code_verifier`와 `code_challenge`를 만들고, authorization request 주소를 기본 browser로 연다 | [5장 PKCE](../mcp-guide/05-authorization-and-token.md#54-pkce), [7장 authorization request](../mcp-guide/07-local-client.md#75-3단계-authorization-request-주소를-만든다--authorizationrequest) |
| `AuthorizationResponse` | callback을 `state` → `iss` → `error` → `code` 순서로 확인한다 | [7장 callback 확인](../mcp-guide/07-local-client.md#76-4단계-callback을-확인한다--authorizationresponse) |
| `TokenClient`, `TokenResponse` | `client_secret` 없이 `client_id`와 `code_verifier`로 token을 받는다 | [7장 token request](../mcp-guide/07-local-client.md#77-5단계-token-request--tokenclient) |
| `McpCalls` | transport의 기본 요청에 `Authorization` header를 넣고 `initialize`, `tools/list`, `tools/call`을 보낸다 | [7장 MCP 호출](../mcp-guide/07-local-client.md#78-6단계-mcp-호출--mcpcalls) |

## 안내서에서 다루지 않는 설정

- `/api/chat`도 CSRF를 검사해서, 다른 사이트의 page가 사용자 몰래 채팅(곧 tool 호출)을 보내지 못한다. `index.html`은 `csrf.spa()`가 준 `XSRF-TOKEN` cookie 값을 `X-XSRF-TOKEN` header로 보내고, 이 header가 없는 요청은 `403`이다.
- `AuthorizationServerConfig`는 filter chain을 직접 정의해서 Spring Boot의 기본 Authorization Server 설정이 빠진다. 그래서 agent의 login에 필요한 OpenID Connect(ID token, `/.well-known/openid-configuration`)를 `.oidc(...)`로 직접 켠다.
- `shop-mcp-server`의 `SecurityConfig`는 `issuer-uri`를 기본값 없는 `@Value`로 받아서, 이 설정이 없으면 앱이 뜨지 않는다. 값이 틀리면 앱은 뜨고, token이 붙은 첫 요청에서 실패한다([6장 official 코드](../mcp-guide/06-mcp-call-and-validation.md#69-official-코드에서-보기)).
- `auth-server`와 `shop-agent`는 session cookie 이름을 `OFFICIALAUTHSESSIONID`와 `OFFICIALAGENTSESSIONID`로 나눈다. cookie는 host만 보고 포트를 가리지 않아서([RFC 6265 §8.5](https://www.rfc-editor.org/rfc/rfc6265#section-8.5)), 두 앱이 같은 `JSESSIONID`를 쓰면 한쪽 login이 다른 쪽 session을 덮어쓴다.

## 직접 확인할 것

`run.sh`로 띄운 뒤 `practice/mcp-security-authn-official`에서 실행한다.
`local-client`의 두 줄은 `practice/mcp-security-authn-official/local-client`에서 실행한다.

| 해 볼 것 | 기대 결과 |
|---|---|
| `curl -i -X POST http://localhost:8111/mcp` | `401`과 `WWW-Authenticate: Bearer resource_metadata="http://localhost:8111/.well-known/oauth-protected-resource/mcp"` |
| `curl -i -X POST http://localhost:8111/mcp -H 'Origin: http://evil.example'` | token이 없어도 `401`보다 먼저 `403` `Invalid Origin header` |
| `curl -i -X POST http://localhost:8111/mcp -H 'Host: evil.example:8111'` | token이 없어도 `421` `Invalid Host header` |
| login하지 않은 browser로 `http://localhost:8110` 열기 | `auth-server`의 login 화면(`http://localhost:9010/login`)으로 간다 |
| login한 뒤 `노트북 재고 있어?`와 `무선 기계식 키보드 살 수 있어?` 묻기 | p1 7개, p2 23개처럼 재고 숫자가 맞고, 재고가 0인 무선 기계식 키보드(p3)는 품절이라고 답한다 |
| `grep '호출' logs/shop-mcp-server.log` | `사용자=user`가 찍힌다. MCP Server가 보는 사용자는 agent가 아니라 login한 사람이다 |
| `grep '토큰을 헤더에' logs/shop-agent.log` | `토큰을 헤더에 붙였다 (사용자=user)`가 찍힌다. agent가 MCP 요청마다 그 사용자의 token을 붙인다 |
| `local-client`에서 `./gradlew run` 뒤 login과 consent | terminal에 `[1]`부터 `[5]`까지 찍히고, 마지막 줄의 `getStock(p1)` 결과는 재고 7개다 |
| `local-client`에서 `./gradlew run --args="--issuer http://localhost:9999"` | discovery에서 `실패: 이 client는 http://localhost:9999에 등록돼 있는데, PRM의 authorization_servers는 [http://localhost:9010]뿐이다`로 멈춘다 |

token이 필요한 요청은 캡처 스크립트로 한 단계씩 기록해 본다.
스크립트는 login과 token 발급을 curl로 대신 하고, 출력에는 token 원문이 남는다.

```bash
# 저장소 최상위 폴더에서
docs/superpowers/captures/mcp-authorization-walkthrough.sh > /tmp/official-walkthrough.txt
docs/superpowers/captures/mcp-authorization-supplement.sh > /tmp/official-supplement.txt
docs/superpowers/captures/mcp-authorization-public-client.sh > /tmp/official-public-client.txt
docs/superpowers/captures/local-client-run.sh > /tmp/official-local-client.txt
```

마지막 스크립트는 `local-client`를 `./gradlew run`으로 돌리므로, 여기서도 `JAVA_HOME`이 Java 21을 가리켜야 한다.
출력의 단계 번호가 어느 장의 어느 요청인지는 장마다 "직접 해 보기" 절에 있다.

## 더 읽을 것

- [MCP 안내서](../mcp-guide/README.md): 이 practice로 MCP와 MCP authorization을 설명한다.
- [부록: 명세 준수표](../mcp-guide/reference-compliance.md): 이 practice가 명세 항목을 어디까지 지키는지와 남은 위반을 모았다.
- [mcp-security-authn-chat-memory](../mcp-security-authn-chat-memory/README.md): 이 practice에 사용자별 대화 기억을 더하고, MCP session을 사용자에 묶는다.
- [mcp-security-authn-community](../mcp-security-authn-community/README.md): 같은 흐름을 spring-ai-community의 MCP 보안 module 자동 설정으로 만든다.
