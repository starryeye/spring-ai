# mcp-security-authn-official

`org.springaicommunity` 없이, 공식 라이브러리(Spring Security · Spring Authorization Server · Spring AI MCP)만으로 인증이 포함된 MCP 호출을 손으로 배선한다.

> 인증이 포함된 MCP 표준 자체를 배우려면 [MCP-AUTHORIZATION.md](../MCP-AUTHORIZATION.md) 를 먼저 읽는다. 이 README 는 그 표준을 이 practice 가 어떻게 구현했는지를 다룬다.

## 다루는 것

사용자가 브라우저로 로그인하고 agent 가 그 사용자를 대신해 보호된 MCP Server 를 호출하는 흐름을, [`mcp-security-authn-community`](../mcp-security-authn-community)와 같은 모양으로 만든다.
discovery·PKCE·`resource`·`aud`·`iss` 같은 [MCP-AUTHORIZATION.md](../MCP-AUTHORIZATION.md) 의 authorization 표준이 요구하는 배선을, 커뮤니티 모듈 3종이 자동으로 해 주던 자리에 31개 Java 파일(`src/main/java` 기준 1,902줄)로 직접 채운다.
재료만 다를 뿐 결과와 결론은 community 와 같다.

## 구성과 포트

| 모듈 | 포트 | 역할 |
|---|---|---|
| `auth-server` | `9010` | token 을 발급한다(`spring-boot-starter-oauth2-authorization-server`) |
| `shop-mcp-server` | `8111` | token 을 검증한다. 없으면 `401`(`spring-boot-starter-oauth2-resource-server`) |
| `shop-agent` | `8110` | token 을 얻어 붙인다. 브라우저 UI + Ollama LLM |

community 와 포트가 달라(`9000`/`8101`/`8100` → `9010`/`8111`/`8110`) 두 practice 를 동시에 띄워도 충돌하지 않는다.
세 앱 모두 Spring Boot 4.1.0 · servlet 스택이고, 패키지는 `dev.starryeye.official*` 다.

## 모듈별 역할

### Authorization Server

`auth-server` 는 사용자를 로그인시키고 MCP Server 용 access token 을 발급한다.
`AuthorizationServerConfig` 가 filter chain 두 개를 직접 정의해 PKCE 강제, `resource` 검증([4.5](../MCP-AUTHORIZATION.md#s4-5)), access token `aud` 발급([4.7](../MCP-AUTHORIZATION.md#s4-7)), authorization response 의 `iss`([4.6](../MCP-AUTHORIZATION.md#s4-6)), public client 재동의([5.6](../MCP-AUTHORIZATION.md#s5-6))를 건다.
자세히: [API-SPEC.md](API-SPEC.md#auth-server) · [SEQUENCES.md](SEQUENCES.md#as-internals)

### MCP Server

`shop-mcp-server` 는 OAuth 2.1 resource server 다.
`SecurityConfig` 가 Protected Resource Metadata 와 `401` challenge([4.1](../MCP-AUTHORIZATION.md#s4-1), [4.2](../MCP-AUTHORIZATION.md#s4-2))를 켜고, `application.yml` 의 `issuer-uri`·`audiences` 로 token 서명·`iss`·`aud`·`exp` 를 검증한다([4.9](../MCP-AUTHORIZATION.md#s4-9)).
`McpTransportConfig`·`McpProtocolVersionFilter` 가 `Origin`/`Host`·`MCP-Protocol-Version` 을 검증한다([4.8](../MCP-AUTHORIZATION.md#s4-8)).
자세히: [API-SPEC.md](API-SPEC.md#mcp-server) · [SEQUENCES.md](SEQUENCES.md#modules)

### Agent

`shop-agent` 는 confidential client 다.
discovery([4.2](../MCP-AUTHORIZATION.md#s4-2), [4.3](../MCP-AUTHORIZATION.md#s4-3))로 Authorization Server 를 찾고, `SecurityConfig`·`DiscoveredClientRegistrationRepository`·`McpAuthorizationDiscovery` 가 authorization code 로그인을 만들며, `OAuth2TokenAttachingRequestCustomizer`·`SecurityMcpTransportContextProvider` 가 MCP 호출에 사용자 token 을 붙인다([5.1](../MCP-AUTHORIZATION.md#s5-1)).
자세히: [API-SPEC.md](API-SPEC.md#agent) · [SEQUENCES.md](SEQUENCES.md#agent-login) · [SEQUENCES.md](SEQUENCES.md#mcp-call)

## 직접 쓴 코드

community 에서 라이브러리 3개(전이 의존 포함 수십 개 자동설정 bean)가 하던 일과, MCP authorization 표준(PKCE·`resource`·`aud`·`iss`·전송 보안)이 요구하는 배선을 아래 클래스로 직접 짠다.

`shop-agent` — discovery·authorization·token 부착:

| 클래스 | 역할 | 명세 |
|---|---|---|
| `SecurityConfig` | `oauth2Login`+`oauth2Client` filter chain, PKCE resolver·`iss` 검증 필터 배치 | [4.5](../MCP-AUTHORIZATION.md#s4-5), [4.6](../MCP-AUTHORIZATION.md#s4-6) |
| `DiscoveredClientRegistrationRepository` | 설정 대신 discovery 결과로 `ClientRegistration` 을 만들고 issuer 를 확인 | [4.4](../MCP-AUTHORIZATION.md#s4-4) |
| `McpAuthorizationDiscovery` | `401` → PRM → Authorization Server Metadata 순서로 discovery | [4.1](../MCP-AUTHORIZATION.md#s4-1)–[4.3](../MCP-AUTHORIZATION.md#s4-3) |
| `DiscoveredAuthorization` | discovery 결과(resource 식별자·issuer·metadata)를 담는 레코드 | [4.2](../MCP-AUTHORIZATION.md#s4-2), [4.3](../MCP-AUTHORIZATION.md#s4-3) |
| `McpAuthorizationProperties` | `mcp.authorization.resource-url`/`credentials-issuer` 설정 binding | [4.4](../MCP-AUTHORIZATION.md#s4-4) |
| `McpDiscoveryException` | discovery 가 명세대로 끝나지 않았을 때 던진다 | [4.1](../MCP-AUTHORIZATION.md#s4-1)–[4.3](../MCP-AUTHORIZATION.md#s4-3) |
| `ResourceIndicators` | RFC 8707 `resource` 를 authorization·token·refresh 요청에 싣는다 | [4.5](../MCP-AUTHORIZATION.md#s4-5), [4.7](../MCP-AUTHORIZATION.md#s4-7), [4.10](../MCP-AUTHORIZATION.md#s4-10) |
| `AuthorizationResponseIssuerFilter` | callback 의 `iss`(RFC 9207)를 코드 교환 **전에** 검증한다 | [4.6](../MCP-AUTHORIZATION.md#s4-6) |
| `LoginFailureHandler` | 로그인 실패를 `401` 본문으로 그대로 알린다 | [4.6](../MCP-AUTHORIZATION.md#s4-6) |
| `McpSecurityConfig` | MCP client 보안 bean(discovery 저장소, authorized client 서비스·매니저, transport 커스터마이저)을 직접 등록 | [4.4](../MCP-AUTHORIZATION.md#s4-4), [4.7](../MCP-AUTHORIZATION.md#s4-7), [4.10](../MCP-AUTHORIZATION.md#s4-10) |
| `OAuth2TokenAttachingRequestCustomizer` | 컨텍스트에서 인증을 꺼내 token 을 얻고 `Authorization` 헤더에 붙인다 | [5.1](../MCP-AUTHORIZATION.md#s5-1) |
| `SecurityMcpTransportContextProvider` | `SecurityContextHolder` 의 인증을 MCP SDK 의 `McpTransportContext` 로 옮긴다 | [4.8](../MCP-AUTHORIZATION.md#s4-8) |

`shop-mcp-server` — Protected Resource·전송 보안:

| 클래스 | 역할 | 명세 |
|---|---|---|
| `SecurityConfig` | Protected Resource Metadata, `401` challenge, token 서명·`iss`·`aud`·`exp` 검증 | [4.1](../MCP-AUTHORIZATION.md#s4-1), [4.2](../MCP-AUTHORIZATION.md#s4-2), [4.9](../MCP-AUTHORIZATION.md#s4-9) |
| `McpTransportConfig` | 전송 bean 을 직접 만들어 `Origin`/`Host` 검증기를 단다 | [4.8](../MCP-AUTHORIZATION.md#s4-8), [5.7](../MCP-AUTHORIZATION.md#s5-7) |
| `McpProtocolVersionFilter` | `MCP-Protocol-Version` 헤더 검증(SDK 가 하지 않는 부분을 보충) | [4.8](../MCP-AUTHORIZATION.md#s4-8) |

`auth-server` — MCP authorization 표준 준수(PKCE·`resource`·`aud`·`iss`·public client):

| 클래스 | 역할 | 명세 |
|---|---|---|
| `AuthorizationServerConfig` | filter chain 두 개를 직접 정의해 PKCE 강제와 아래 확장점을 건다 | [4.3](../MCP-AUTHORIZATION.md#s4-3), [4.5](../MCP-AUTHORIZATION.md#s4-5)–[4.7](../MCP-AUTHORIZATION.md#s4-7), [5.6](../MCP-AUTHORIZATION.md#s5-6) |
| `ResourceIndicatorValidator` | authorization request 의 `resource` 를 허용 목록과 대조해 `invalid_target` 을 던진다 | [4.5](../MCP-AUTHORIZATION.md#s4-5) |
| `ResourceAudienceTokenCustomizer` | access token 의 `aud` 를 요청한 `resource` 로 발급한다 | [4.7](../MCP-AUTHORIZATION.md#s4-7) |
| `IssuerIdentifyingAuthorizationResponseHandler` | 성공·오류 authorization response 모두에 `iss` 를 싣는다 | [4.6](../MCP-AUTHORIZATION.md#s4-6) |
| `McpResourceProperties` | 이 Authorization Server 가 token 을 발급할 수 있는 resource 목록 | [4.5](../MCP-AUTHORIZATION.md#s4-5), [4.7](../MCP-AUTHORIZATION.md#s4-7) |
| `ClientAuthenticationChallengeFailureHandler` | `Authorization` 헤더로 시도한 `invalid_client` 실패에 `WWW-Authenticate` 를 붙인다 | [4.7](../MCP-AUTHORIZATION.md#s4-7) |
| `PublicClientConsentService` | public client(`none`)의 consent 를 기록하지 않아 매 요청이 consent 화면을 거치게 한다 | [5.6](../MCP-AUTHORIZATION.md#s5-6) |

## 실행과 확인

준비물은 community 와 같다.

```bash
brew install ollama
ollama serve &
ollama pull qwen3:8b
```

```bash
cd practice/mcp-security-authn-official
./run.sh
```

`run.sh` 는 `auth-server(:9010) → shop-mcp-server(:8111) → shop-agent(:8110)` 순서로 띄운다.
세 앱은 순서와 관계없이 뜨고, 첫 로그인·첫 채팅 때 Authorization Server 와 MCP Server 가 떠 있으면 된다(MCP Server 의 JWT decoder 와 agent 의 discovery·`initialize` 가 모두 첫 사용 때 동작한다).
브라우저에서 `http://localhost:8110/` 을 열고 **`user` / `password`** 로 로그인한다.

confidential client(agent) 흐름과 public client(`local-mcp-client`) 흐름을 curl 캡처 스크립트로도 밟을 수 있다.

```bash
../../docs/superpowers/captures/mcp-authorization-walkthrough.sh > /tmp/official-walkthrough.txt
../../docs/superpowers/captures/mcp-authorization-supplement.sh > /tmp/official-supplement.txt
../../docs/superpowers/captures/mcp-authorization-public-client.sh > /tmp/official-public-client.txt
```

```bash
./stop.sh
```

| 확인 방법 | 기대 결과 |
|---|---|
| `curl -X POST http://localhost:8111/mcp`(token 없음) | `401` + `WWW-Authenticate: Bearer resource_metadata="http://localhost:8111/.well-known/oauth-protected-resource/mcp"` |
| session 없이 `http://localhost:8110/` 접근 | `auth-server`(`:9010`)의 로그인 화면으로 redirect |
| 로그인 후 `노트북 재고 있어?` / `무선 기계식 키보드 살 수 있어?` | 재고 숫자가 정확히 나오고(p1=7개, p2=23개), 품절 상품은 품절이라고 답한다 |
| `grep '호출' logs/shop-mcp-server.log` | `사용자=user` — MCP Server 에 도착한 신원은 agent 가 아니라 로그인한 사람이다 |
| community 세 앱과 동시 기동(6개 포트) | 서로 깨지지 않는다 — session cookie 이름이 4개 모두 다르고, 같은 질문에 같은 답이 나온다 |

## community 와의 차이

community 의 모듈 자동 구성과 이 practice 의 직접 쓴 클래스를 맞대 본 비교는 [community README 의 대체 표](../mcp-security-authn-community/README.md#대체-표) 한 곳에 있다.

## 학습 포인트

### `protectedResourceMetadata` 는 이미 공식이다

RFC 9728 Protected Resource Metadata 는 community 에서 라이브러리의 대표 기능처럼 보였지만, Spring Security 7.1 은 `.protectedResourceMetadata(Customizer.withDefaults())` 한 줄로 이미 제공한다.
커뮤니티 모듈이 하던 일 중 하나가 이미 상류로 흡수된 사례다.

### 공식으로 가면 코드는 늘고 조건부 자동설정은 준다

official 은 31개 파일(1,902줄)로 community 의 30개 파일(1,727줄)보다 많은 코드를 직접 쓴다.
그 대신 조건부 자동설정이 없어 무엇이 왜 켜지는지 전부 소스에 드러나고, 조용히 죽는 스위치도 community 의 4개([조건표](../mcp-security-authn-community/README.md#모듈이-조용히-물러나는-조건))에서 2개로 준다.
그 2개는 `ShopAgentApplication` 의 `Hooks.enableAutomaticContextPropagation()` 을 빼는 것과 `spring.ai.mcp.client.type` 을 `ASYNC` 로 바꾸는 것이고, 둘 다 오류 없이 token 만 안 붙는다(DEBUG 한 줄).

### 공식 스트리밍 전파 경로는 `internal` 패키지 없이 동작한다

`Hooks.enableAutomaticContextPropagation()` 과 `AuthorizedClientServiceOAuth2AuthorizedClientManager` 조합은 Spring AI 의 `internal` 패키지를 참조하지 않고도 리액터 경계를 넘어 `SecurityContext` 를 전파한다.
`ChatController` 에는 `.contextWrite(...)` 류의 코드가 없다.

### filter chain 을 직접 정의하면 OIDC discovery 도 직접 켠다

Boot 4.1 의 `OAuth2AuthorizationServerWebSecurityConfiguration` 은 `.oidc(withDefaults())` 를 켜지만, `@ConditionalOnDefaultWebSecurity` 라 `SecurityFilterChain` 을 직접 정의하면 물러난다.
`AuthorizationServerConfig` 는 filter chain 을 직접 만들므로 `.oidc(...)` 를 스스로 켜고, 그 OIDC metadata 에도 `iss`·signing alg·`none` 을 더한다.

### `issuer-uri` 는 없으면 기동이 실패하고, 틀리면 첫 token 검증에서 실패한다

`SecurityConfig` 는 `issuer-uri` 를 기본값 없는 `@Value` 로 받아, 값이 없으면 placeholder 를 풀지 못해 기동이 실패한다.
JWT decoder 는 Boot 자동 구성의 `SupplierJwtDecoder` 라 issuer metadata 를 첫 token 검증 때 가져온다.
그래서 닿지 않는 issuer 로도 기동은 되고, 첫 token 요청이 `JwtDecoderInitializationException` 으로 끝난다.

그 요청의 오류 페이지 재디스패치(`/error`)가 익명이라 `401` 을 받으며, token 검증 실패와 달리 `error="invalid_token"` 이 없다.
관측: 그 `WWW-Authenticate` 의 `resource_metadata` 는 `/.well-known/oauth-protected-resource/error` 를 가리킨다.

### community 에서 배운 것 중 라이브러리와 무관한 것은 그대로다

`spring.ai.mcp.client.initialized: false` 의 필요성과 `localhost` 멀티 앱의 session cookie 이름 분리는 `org.springaicommunity` 라이브러리의 특성이 아니라 문제 자체의 구조에서 나온다.
라이브러리 없이 쓴 이 practice 에도 그대로 필요하다.

## 비목표

- scope 검사, tool 단위 authorization → 후속 `mcp-security-authz`
- 사용자 여러 명, 역할 분리
- token 저장소 영속화(in-memory 로 충분)
- UI 완성도 — `index.html` 은 OAuth redirect 를 브라우저에 맡기기 위한 최소 장치다
- `client_credentials`, Dynamic Client Registration(DCR)
- Client ID Metadata Document(CIMD) — `https` 문서 URL 이 전제라 별도 practice 로 미룬다
- public client 쪽 프로그램(loopback callback 서버, token 보관) — Authorization Server 쪽만 다루고 client 는 캡처 스크립트가 대신한다

## 링크

- [MCP-AUTHORIZATION.md](../MCP-AUTHORIZATION.md) · [MCP-API-SPEC.md](../MCP-API-SPEC.md) · [MCP-SEQUENCES.md](../MCP-SEQUENCES.md)
- 캡처: [2026-09-12-official.txt](../../docs/superpowers/captures/2026-09-12-official.txt) · [2026-09-16-official-supplement.txt](../../docs/superpowers/captures/2026-09-16-official-supplement.txt) · [2026-09-25-official-public-client.txt](../../docs/superpowers/captures/2026-09-25-official-public-client.txt)
- 비교 대상: [`mcp-security-authn-community`](../mcp-security-authn-community)
