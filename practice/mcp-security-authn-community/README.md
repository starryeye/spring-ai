# mcp-security-authn-community

`org.springaicommunity` 의 MCP 보안 모듈 3종(`0.1.14`) 위에, [`mcp-security-authn-official`](../mcp-security-authn-official)과 같은 MCP authorization 표준을 구현한다.

> authorization 이 포함된 MCP 표준 자체를 배우려면 [MCP-AUTHORIZATION.md](../MCP-AUTHORIZATION.md) 를 먼저 읽는다. 이 README 는 그 표준을 이 practice 가 어떻게 구현했는지를 다룬다.

## 다루는 것

사용자가 browser 로 로그인하고 agent 가 그 사용자를 대신해 보호된 MCP Server 를 호출하는 흐름을, official 과 같은 결과로 만든다.
모듈 3종은 아직 discovery·PKCE·`resource`·`iss` 를 지원하지 않으므로, 그 자리를 직접 쓴 클래스로 채운다.
재료(모듈의 자동 구성과 확장점)만 official 과 다를 뿐 결과와 학습 결론은 같다.

## 구성과 포트

| 모듈 | 포트 | 역할 | 보안 모듈 |
|---|---|---|---|
| `auth-server` | `9000` | token 을 발급한다 | `mcp-authorization-server-spring-boot` |
| `shop-mcp-server` | `8101` | token 을 검증한다. 없으면 `401` | `mcp-server-security-spring-boot` |
| `shop-agent` | `8100` | token 을 얻어서 붙인다. browser UI + Ollama LLM | `mcp-client-security-spring-boot` |

official 과 포트가 달라(`9010`/`8111`/`8110` → `9000`/`8101`/`8100`) 두 practice 를 동시에 띄워도 충돌하지 않는다.
세 앱 모두 Spring Boot 4.1.0 · servlet 스택이고, 앱 하나가 발급·검증·사용 중 하나씩만 담당한다.

## 모듈별 역할

각 모듈은 `-spring-boot` 접미 변형을 쓴다. 접미 없는 core 모듈(`mcp-server-security` 등)을 전이 의존으로 끌어오면서 자동 구성을 얹어 준다.
아래 "자동으로 생기는 것" 표의 근거는 각 모듈의 자동 구성 소스(`*-0.1.14-sources.jar`)다.

### `mcp-authorization-server-spring-boot` — token 발급

`auth-server` 는 MCP 용으로 손본 Spring Authorization Server 다.
`McpAuthorizationServerAutoConfiguration` 이 filter chain·dynamic client registration(DCR)을 자동으로 켜고, `McpAuthorizationStandardConfig`·`OidcDiscoveryConfig` 가 그 확장점(`Customizer<McpAuthorizationServerConfigurer>`)으로 표준을 얹는다.
자세히: [API-SPEC.md](API-SPEC.md#providers) · [SEQUENCES.md](SEQUENCES.md#diff-authorization-server)

| bean / 기능 | 하는 일 |
|---|---|
| `authorizationServerSecurityFilterChain` | `@Order(HIGHEST_PRECEDENCE)`. `/oauth2/**` authorization endpoint 전체 |
| `defaultSecurityFilterChain` | 폼 로그인 화면. `/login` 을 직접 만들 필요가 없다 |
| `dcrRegisteredClientRepository` | `spring.security.oauth2.authorizationserver.client.*` 를 `RegisteredClient` 로 매핑. DCR 도 기본 활성(이 practice 는 끔) |
| `ResourceIdentifierAudienceTokenCustomizer`(module 기본 token customizer) | access token `aud` 를 `resource` 로 채운다. `openid` scope 가 있으면 건너뛴다 |
| `McpNoScopeClientConsentNotRequired` | scope 가 없거나 `openid` 하나뿐이면 consent 를 생략하게 하는 필수 검증기 |

### `mcp-server-security-spring-boot` — token 검증

`shop-mcp-server` 는 이 모듈로 OAuth 2.1 resource server 가 된다.
자동 구성(`McpServerSecurityAutoConfiguration`)은 `issuer-uri` 설정 하나로 filter chain 을 통째로 켜지만, audience 검증을 켜는 길이 없어 이 practice 는 `SecurityConfig` 에서 같은 확장점(`McpServerOAuth2Configurer`)을 직접 적용한다.
자세히: [API-SPEC.md](API-SPEC.md#providers) · [SEQUENCES.md](SEQUENCES.md#diff-mcp-server)

| bean / 기능 | 하는 일 |
|---|---|
| `mcpServerSecurityFilterChain`(자동 구성, 이 practice 는 대체) | `anyRequest().authenticated()`, `NimbusJwtDecoder.withIssuerLocation(...)` |
| `BearerResourceMetadataTokenAuthenticationEntryPoint` | `401` 의 `WWW-Authenticate: Bearer resource_metadata=...` (module 기본값은 인용부호 없음) |
| Protected Resource metadata endpoint | `/.well-known/oauth-protected-resource/mcp`(RFC 9728) |
| `OriginValidationFilter` | `allowedOrigins`/`allowedHosts` 설정 시 `addFilterAfter(CorsFilter)` 로 자동 등록 |
| `AudienceValidationJwtDecoder` | `validateAudienceClaim(true)` 일 때 decoder 를 감싸 `aud` 를 검증 |

### `mcp-client-security-spring-boot` — token 획득·부착

`shop-agent` 가 MCP 를 호출할 때 `Authorization` 헤더를 붙여 준다.
`McpOAuth2ClientAutoConfiguration`·`HttpClientStreamableHttpTransportAutoConfiguration` 이 thread-local 의 인증을 읽어 token 을 구해 붙이는 부분을 전부 자동으로 한다.
이 practice 는 그 자리는 그대로 두고, discovery 와 authorization request 배선만 직접 쓴다.

| bean / 기능 | 하는 일 |
|---|---|
| `McpClientRegistrationRepository`(자동 구성, 이 practice 는 대체) | `spring.security.oauth2.client.registration.*` 로부터 생성 |
| `AuthenticationMcpTransportContextProvider` | `McpClientCustomizer<SyncSpec>` 로 모든 MCP client 에 꽂혀, thread-local 인증을 `McpTransportContext` 에 담는다 |
| `OAuth2AuthorizationCodeSyncHttpRequestCustomizer` | `preRegisteredClientCustomizer` 로 전송 계층에 꽂혀 token 을 헤더에 붙인다 |

## 대체 표

official 이 손으로 쓴 클래스마다, community 는 모듈이 자동으로 해주거나(모듈 자동 구성) 모듈의 확장점에 직접 얹거나(직접 얹은 확장) 같은 클래스를 그대로 쓴다.
행은 [official 의 직접 쓴 코드 표](../mcp-security-authn-official/README.md#직접-쓴-코드)의 클래스를 모두 담는다.

### `auth-server`

| official 의 클래스 또는 설정 | community 의 설정·확장점 | 제공 |
|---|---|---|
| `AuthorizationServerConfig`(filter chain 두 개) | `McpAuthorizationServerAutoConfiguration` | 모듈 자동 구성 |
| `ResourceIndicatorValidator` | `McpAuthorizationStandardConfig#authorizationCodeRequestValidator(...).andThen(...)` 로 등록 | 같은 클래스 |
| `ResourceAudienceTokenCustomizer` | `OAuth2TokenCustomizer<JwtEncodingContext>` 빈, module 기본 customizer(`ResourceIdentifierAudienceTokenCustomizer`) 뒤에 실행 | 같은 클래스 |
| `IssuerIdentifyingAuthorizationResponseHandler` | `McpAuthorizationStandardConfig` 가 `authorizationResponseHandler`·`errorResponseHandler` 로 등록 | 같은 클래스 |
| `ClientAuthenticationChallengeFailureHandler` | `McpAuthorizationStandardConfig` 가 `clientAuthentication.errorResponseHandler` 로 등록 | 같은 클래스 |
| `McpResourceProperties` | `@ConfigurationProperties("mcp.authorization")`, `resources` 키 그대로 | 같은 클래스 |
| `PublicClientConsentService` | `authorizationConsentService` 빈으로 등록 | 같은 클래스 |
| `UserConfig` | 그대로 재사용, 폼 로그인 filter chain 은 모듈이 제공 | 같은 클래스 |
| public client 의 `none` 인증 방식 광고 | `McpAuthorizationStandardConfig` 의 `authorizationServerMetadataCustomizer`(같은 커스터마이저 람다) | 직접 얹은 확장 |
| OIDC discovery(`.oidc(...)`) 켜기 | `OidcDiscoveryConfig`(`Customizer<McpAuthorizationServerConfigurer>` 빈) | 직접 얹은 확장 |
| Dynamic Client Registration(DCR) 끔 | `spring.ai.mcp.authorizationserver.dynamic-client-registration.enabled: false` | 모듈 자동 구성(설정으로 끔) |

### `shop-mcp-server`

| official 의 클래스 또는 설정 | community 의 설정·확장점 | 제공 |
|---|---|---|
| `SecurityConfig`(filter chain 직접 정의, PRM·`401`·`aud`·`iss`·`exp` 검증) | `SecurityConfig` 가 `McpServerOAuth2Configurer` 를 적용, `validateAudienceClaim(true)` | 직접 얹은 확장 |
| `McpTransportConfig`(Origin/Host 검증기 직접 등록) | `SecurityConfig` 의 `mcp.allowedOrigins(...)`/`allowedHosts(...)` 호출로 module 의 `OriginValidationFilter` 가 자동 등록 | 모듈 자동 구성 |
| `issuer-uri` 동작([official 학습 포인트](../mcp-security-authn-official/README.md#issuer-uri-는-없으면-기동이-실패하고-틀리면-첫-token-검증에서-실패한다)) | 없으면 Boot 가 `JwtDecoder` bean 을 만들지 않아 `SecurityConfig` 가 기동에 실패한다(`issuer` 를 받는 `@Value` 에도 기본값이 없다). 닿지 않으면 기동은 되고 첫 token 검증에서 실패한다 | 같은 동작 |
| `McpProtocolVersionFilter` | 같은 이름 · 같은 로직, `McpProtocolVersionFilterConfig` 가 `FilterRegistrationBean` 으로 등록 | 같은 클래스 |
| `ProductTools` | 같은 코드 | 같은 클래스 |

### `shop-agent`

| official 의 클래스 또는 설정 | community 의 설정·확장점 | 제공 |
|---|---|---|
| `SecurityConfig`(`oauth2Login`+`oauth2Client` filter chain, PKCE resolver·`iss` 검증 필터 배치) | 같은 배선(module 에는 `@ConditionalOnDefaultWebSecurity` 가 없어 직접 정의해도 자동 구성과 부딪히지 않는다) | 같은 클래스 |
| `DiscoveredClientRegistrationRepository` | 같은 이름, `OAuth2ClientProperties` 에서 자격증명만 가져오고 나머지는 discovery 결과로 채운다 | 같은 클래스 |
| `McpAuthorizationDiscovery` | 401 challenge·PRM 조회는 module 의 `McpMetadataDiscoveryService` 에 맡기고, Authorization Server metadata 발견·PKCE 지원 확인은 직접 한다 | 직접 얹은 확장 |
| `DiscoveredAuthorization` | 같은 record | 같은 클래스 |
| `McpAuthorizationProperties` | 같은 설정 binding(`mcp.authorization.resource-url`/`credentials-issuer`) | 같은 클래스 |
| `McpDiscoveryException` | 같은 예외 | 같은 클래스 |
| `ResourceIndicators` | 같은 코드 | 같은 클래스 |
| `AuthorizationResponseIssuerFilter` | 같은 코드 | 같은 클래스 |
| `LoginFailureHandler` | 같은 코드 | 같은 클래스 |
| `McpSecurityConfig`(discovery 저장소·authorized client 서비스·매니저·transport 커스터마이저를 직접 등록) | discovery 저장소·token 요청 client·authorized client 매니저만 직접 등록. transport 커스터마이저는 모듈이 대신한다 | 직접 얹은 확장 |
| `OAuth2TokenAttachingRequestCustomizer` | `HttpClientStreamableHttpTransportAutoConfiguration` 의 `preRegisteredClientCustomizer`(`OAuth2AuthorizationCodeSyncHttpRequestCustomizer`) | 모듈 자동 구성 |
| `SecurityMcpTransportContextProvider` | `McpOAuth2ClientAutoConfiguration` 의 `McpClientCustomizer<SyncSpec>`(`AuthenticationMcpTransportContextProvider`) | 모듈 자동 구성 |
| `ChatController`(`.contextWrite(...)` 호출) | 같은 호출(`AuthenticationMcpTransportContextProvider.writeToReactorContext()`) | 같은 클래스 |
| `ChatClientConfig` | 같은 코드 | 같은 클래스 |

confidential client(agent)와 public client(`local-mcp-client`)의 등록·인증 방식·consent 비교는 [MCP-AUTHORIZATION.md 4.4](../MCP-AUTHORIZATION.md#s4-4) 에 있다.

## 실행과 확인

```bash
brew install ollama
ollama serve &
ollama pull qwen3:8b
```

```bash
cd practice/mcp-security-authn-community
./run.sh
```

`run.sh` 는 `auth-server(:9000) → shop-mcp-server(:8101) → shop-agent(:8100)` 순서로 띄운다.
이 순서가 필수가 아닌 점은 official 과 같다([official 실행과 확인](../mcp-security-authn-official/README.md#실행과-확인)).
browser 에서 `http://localhost:8100/` 을 열고 **`user` / `password`** 로 로그인한다.

public client(`local-mcp-client`) 흐름은 curl 캡처 스크립트로 밟는다. 비밀 없는 client 로 붙는 프로그램은 없으므로 스크립트가 그 역할을 하고, Authorization Server 와 MCP Server 만 떠 있으면 된다.

```bash
AS=http://localhost:9000 MCP_BASE=http://localhost:8101 \
  CONFIDENTIAL_CLIENT_ID=shop-agent CONFIDENTIAL_CLIENT_SECRET=shop-agent-secret \
  CONFIDENTIAL_REDIRECT_URI=http://localhost:8100/login/oauth2/code/authserver \
  ../../docs/superpowers/captures/mcp-authorization-public-client.sh
```

```bash
./stop.sh
```

| 확인 방법 | 기대 결과 |
|---|---|
| `curl -X POST http://localhost:8101/mcp`(token 없음) | `401` + `WWW-Authenticate: Bearer resource_metadata="http://localhost:8101/.well-known/oauth-protected-resource/mcp"` |
| session 없이 `http://localhost:8100/` 접근 | `auth-server`(`:9000`)의 로그인 화면으로 redirect |
| 로그인 후 `노트북 재고 있어?` / `무선 기계식 키보드 살 수 있어?` | 재고 숫자가 정확히 나오고(p1=7개, p2=23개), 품절 상품은 품절이라고 답한다 |
| `grep '호출' logs/shop-mcp-server.log` | `사용자=user` — MCP Server 에 도착한 신원은 agent 가 아니라 로그인한 사람이다 |
| `./gradlew test`(각 모듈) | `auth-server` 33개 · `shop-mcp-server` 22개 · `shop-agent` 21개 통과 |

## 학습 포인트

### 필터로 가리는 것과 서버에서 잠그는 것은 다르다

client 측 tool 필터는 agent 에게 무엇을 보여줄지 고르는 장치일 뿐, 서버를 잠그지 않는다.
MCP 프로토콜 절차(`initialize` → session ID)만 지키면 보안 계층이 없는 서버는 누구든 tool 목록을 받아간다.
이 practice 의 `shop-mcp-server` 는 `initialize` 시도 자체가 인증 단계에서 막힌다.

### Boot 의 JWT decoder 를 넘기면 issuer metadata 를 첫 token 검증 때 가져온다

`McpServerOAuth2Configurer` 는 decoder 를 받지 않으면 `NimbusJwtDecoder.withIssuerLocation(issuer).build()` 로 만들고, 이 `.build()` 는 bean 을 만드는 시점에 issuer metadata 를 조회한다.
`SecurityConfig` 는 `mcp.jwtDecoder(jwtDecoder)` 로 Boot 자동 구성의 `SupplierJwtDecoder` 를 넘기므로 그 경로를 타지 않는다.
그래서 `shop-mcp-server` 는 `auth-server` 없이도 뜨고, 그 테스트도 `auth-server` 없이 통과한다(`McpAuthorizationStandardTest` 는 `JwkSetUriJwtDecoderBuilderCustomizer` 로 가짜 metadata·JWKS 를 쓴다).

### token 으로 보호된 MCP Server 는 부팅 시점에 handshake 를 못 한다

`McpClientAutoConfiguration` 은 빈 생성 시점에 `McpSyncClient.initialize()` 를 즉시 부르지만, 그 시점에는 요청 스레드도 로그인한 사용자도 없다.
`spring.ai.mcp.client.initialized: false` 로 handshake 를 첫 채팅 요청으로 미루면, 그 대가로 첫 질문이 느려진다.

### 스트리밍에는 `.contextWrite(...)` 가 필수다

`AuthenticationMcpTransportContextProvider` 는 thread-local 에서 인증을 읽는데 `ChatClient.stream()` 의 리액터 체인은 요청 스레드 밖에서 돈다.
`ChatController` 가 `.contextWrite(AuthenticationMcpTransportContextProvider.writeToReactorContext())` 를 호출하지 않으면 token 이 붙지 않고, 흔적은 DEBUG 로그 한 줄뿐이다.

### `localhost` 에 OAuth2 앱을 여러 개 띄우면 session cookie 이름을 나눠야 한다

cookie 는 포트를 구분하지 않고 호스트명에만 scope 되므로(RFC 6265), 세 앱이 모두 `localhost` 를 쓰면 기본 `JSESSIONID` 가 서로 덮어쓴다.
`auth-server`·`shop-agent` 에 각각 `AUTHSERVERSESSIONID`·`SHOPAGENTSESSIONID` 를 지정해 분리한다. `shop-mcp-server` 는 browser 가 직접 접근하지 않아 변경이 필요 없다.

### SYNC 서버는 `@McpTool` 반환이 평문 `String` 이다

MCP Server 는 `spring.ai.mcp.server.type: SYNC` 이고, `@McpTool` 이 `Mono<String>` 을 반환하면 오류 없이 등록에서 빠진다.
tool 이 실제로 등록되는지 확인하는 테스트가 이 사고를 잡는다.

### 모듈이 조용히 물러나는 조건

전부 오류 없이 기능만 사라진다.

| 조건 | 안 지키면 |
|---|---|
| `spring.ai.mcp.client.type: SYNC` | client 보안 자동 구성이 통째로 사라진다(token 이 안 붙음) |
| OAuth2 client 등록이 정확히 1개 | 0개·2개 이상이면 커스터마이저가 WARN 한 줄 남기고 no-op |
| `shop-mcp-server`·`auth-server` 에 `SecurityFilterChain` 직접 정의 | `@ConditionalOnDefaultWebSecurity` 가 꺼져 모듈 설정이 물러난다(`shop-agent` 는 이 조건이 없어 예외) |
| `ChatController` 의 `.contextWrite(...)` | token 이 안 붙는다(DEBUG 한 줄만) |

## 비목표

- scope 검사, tool 단위 authorization → 후속 `mcp-security-authz`
- 사용자 여러 명, 역할 분리
- token 저장소 영속화(in-memory 로 충분)
- UI 완성도 — `index.html` 은 OAuth redirect 를 browser 에 맡기기 위한 최소 장치다
- `client_credentials`, hybrid grant
- Dynamic Client Registration(DCR) — module 은 지원하지만 이 practice 는 pre-registration(agent 와 `local-mcp-client`)만 쓴다
- Client ID Metadata Document(CIMD) — module 에 기능(`cimd(true)`)이 있지만 `https` 문서 URL 이 전제라 별도 practice 로 미룬다
- public client 쪽 프로그램(loopback callback 서버, token 보관) — Authorization Server 쪽만 다루고 client 는 캡처 스크립트가 대신한다

## 링크

- [MCP-AUTHORIZATION.md](../MCP-AUTHORIZATION.md) · [MCP-API-SPEC.md](../MCP-API-SPEC.md) · [MCP-SEQUENCES.md](../MCP-SEQUENCES.md)
- 캡처: [2026-09-12-community.txt](../../docs/superpowers/captures/2026-09-12-community.txt) · [2026-09-25-community-public-client.txt](../../docs/superpowers/captures/2026-09-25-community-public-client.txt)
- 비교 대상: [`mcp-security-authn-official`](../mcp-security-authn-official)
- [spring-ai-community/mcp-security](https://github.com/spring-ai-community/mcp-security)
