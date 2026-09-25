# mcp-security-authn-official

공식 라이브러리(Spring Security · Spring Authorization Server · Spring AI MCP)만으로 인증이 포함된 MCP 호출을 손으로 배선한다.

> 인증이 포함된 MCP 표준 자체를 배우려면 [MCP-AUTHORIZATION.md](../MCP-AUTHORIZATION.md) 를 먼저 읽는다. 이 README 는 그 표준을 이 practice 가 어떻게 구현했는지를 다룬다.

## 다루는 것

사용자가 browser 로 로그인하고, agent 가 그 사용자를 대신해 보호된 MCP Server 를 호출한다.
discovery·PKCE·`resource`·`aud`·`iss`·전송 보안처럼 [MCP-AUTHORIZATION.md](../MCP-AUTHORIZATION.md) 의 authorization 표준이 요구하는 배선을 filter chain·bean·확장점 클래스로 직접 채운다.
어떤 검증이 어디서 켜지는지가 이 practice 의 클래스 이름으로 드러나고, 흐름은 [SEQUENCES.md](SEQUENCES.md) 에 그렸다.

## 구성

| module | 역할 |
|---|---|
| `auth-server` | token 을 발급한다(`spring-boot-starter-oauth2-authorization-server`) |
| `shop-mcp-server` | token 을 검증한다. 없으면 `401`(`spring-boot-starter-oauth2-resource-server`) |
| `shop-agent` | token 을 얻어 MCP 요청에 붙인다. browser UI + Ollama LLM |

포트·issuer·client_id·로그인 계정은 [허브의 포트·계정 표](../MCP-AUTHORIZATION.md#s2-ports) 한 곳에 있다.
세 앱은 모두 Spring Boot 4.1.0 · servlet 스택이고, 패키지는 `dev.starryeye.official*` 다.

## module 별 역할

### Authorization Server

`auth-server` 는 사용자를 로그인시키고 MCP Server 용 access token 을 발급한다.
`AuthorizationServerConfig` 가 filter chain 두 개를 직접 정의해 PKCE 강제, `resource` 검증([4.5](../MCP-AUTHORIZATION.md#s4-5)), access token `aud` 발급([4.7](../MCP-AUTHORIZATION.md#s4-7)), authorization response 의 `iss`([4.6](../MCP-AUTHORIZATION.md#s4-6))를 건다.
두 client 는 `application.yml` 의 pre-registration 이고([4.4](../MCP-AUTHORIZATION.md#s4-4)), public client 는 두 장치로 매 요청 consent 를 거친다([5.6](../MCP-AUTHORIZATION.md#s5-6)).
자세히: [API-SPEC.md](API-SPEC.md#auth-server) · [등록](SEQUENCES.md#registration) · [내부 호출 순서](SEQUENCES.md#as-internals)

### MCP Server

`shop-mcp-server` 는 OAuth 2.1 resource server 다.
`SecurityConfig` 가 Protected Resource Metadata 와 `401` challenge([4.1](../MCP-AUTHORIZATION.md#s4-1), [4.2](../MCP-AUTHORIZATION.md#s4-2))를 켜고, `application.yml` 의 `issuer-uri`·`audiences` 로 token 서명·`iss`·`aud`·`exp` 를 검증한다([4.9](../MCP-AUTHORIZATION.md#s4-9)).
`McpTransportSecurityFilter` 는 인증 전에 `Origin`·`Host` 를, `McpProtocolVersionFilter` 는 인증 뒤에 `MCP-Protocol-Version` 을 검증한다([4.8](../MCP-AUTHORIZATION.md#s4-8), [5.7](../MCP-AUTHORIZATION.md#s5-7)).
자세히: [API-SPEC.md](API-SPEC.md#mcp-server) · [요청 검증](SEQUENCES.md#mcp-server-validation) · [구성](SEQUENCES.md#modules)

### Agent

`shop-agent` 는 confidential client 다.
`McpAuthorizationDiscovery` 가 discovery([4.2](../MCP-AUTHORIZATION.md#s4-2), [4.3](../MCP-AUTHORIZATION.md#s4-3))로 Authorization Server 를 찾고, `SecurityConfig`·`DiscoveredClientRegistrationRepository` 가 authorization code 로그인을 만든다.
`OAuth2TokenAttachingRequestCustomizer`·`SecurityMcpTransportContextProvider` 가 MCP 요청마다 로그인한 사용자의 token 을 붙인다([5.1](../MCP-AUTHORIZATION.md#s5-1)).
자세히: [API-SPEC.md](API-SPEC.md#agent) · [로그인](SEQUENCES.md#agent-login) · [discovery](SEQUENCES.md#discovery) · [MCP 호출](SEQUENCES.md#mcp-call)

## 직접 쓴 코드

MCP authorization 표준(PKCE·`resource`·`aud`·`iss`·전송 보안)이 요구하는 배선을 아래 클래스로 직접 짠다.
클래스 사이의 호출 순서는 [SEQUENCES.md](SEQUENCES.md) 에 있다.

`shop-agent` — discovery·authorization·token 부착:

| 클래스 | 역할 | 명세 |
|---|---|---|
| `SecurityConfig` | `oauth2Login`+`oauth2Client` filter chain, PKCE resolver·`iss` 검증 filter 배치, `csrf.spa()` | [4.5](../MCP-AUTHORIZATION.md#s4-5), [4.6](../MCP-AUTHORIZATION.md#s4-6) |
| `DiscoveredClientRegistrationRepository` | 설정의 자격증명과 discovery 결과로 `ClientRegistration` 을 만든다. `credentials-issuer` 를 discovery 에 넘긴다 | [4.4](../MCP-AUTHORIZATION.md#s4-4) |
| `McpAuthorizationDiscovery` | `401` → PRM → issuer binding 확인 → Authorization Server Metadata 순서로 discovery 하고, endpoint 스킴을 확인한다 | [4.1](../MCP-AUTHORIZATION.md#s4-1)–[4.3](../MCP-AUTHORIZATION.md#s4-3), [5.4](../MCP-AUTHORIZATION.md#s5-4) |
| `DiscoveredAuthorization` | discovery 결과(resource 식별자·issuer·metadata)를 담는 레코드 | [4.2](../MCP-AUTHORIZATION.md#s4-2), [4.3](../MCP-AUTHORIZATION.md#s4-3) |
| `McpAuthorizationProperties` | `mcp.authorization.resource-url`/`credentials-issuer` 설정 binding | [4.4](../MCP-AUTHORIZATION.md#s4-4) |
| `McpDiscoveryException` | discovery 가 명세대로 끝나지 않았을 때 던진다 | [4.1](../MCP-AUTHORIZATION.md#s4-1)–[4.3](../MCP-AUTHORIZATION.md#s4-3) |
| `ResourceIndicators` | RFC 8707 `resource` 를 authorization·token·refresh 요청에 싣는다 | [4.5](../MCP-AUTHORIZATION.md#s4-5), [4.7](../MCP-AUTHORIZATION.md#s4-7), [4.10](../MCP-AUTHORIZATION.md#s4-10) |
| `AuthorizationResponseIssuerFilter` | callback 의 `iss`(RFC 9207)를 code 교환 **전에** 검증한다 | [4.6](../MCP-AUTHORIZATION.md#s4-6) |
| `LoginFailureHandler` | 로그인 실패를 `401` 본문으로 그대로 알린다 | [4.6](../MCP-AUTHORIZATION.md#s4-6) |
| `McpSecurityConfig` | MCP client 보안 bean(discovery 저장소, authorized client 서비스·매니저, transport 커스터마이저)을 직접 등록 | [4.4](../MCP-AUTHORIZATION.md#s4-4), [4.7](../MCP-AUTHORIZATION.md#s4-7), [4.10](../MCP-AUTHORIZATION.md#s4-10) |
| `OAuth2TokenAttachingRequestCustomizer` | transport context 에서 인증을 꺼내 token 을 얻고 `Authorization` 헤더에 붙인다 | [5.1](../MCP-AUTHORIZATION.md#s5-1) |
| `SecurityMcpTransportContextProvider` | `SecurityContextHolder` 의 인증을 MCP SDK 의 `McpTransportContext` 로 옮긴다 | [4.8](../MCP-AUTHORIZATION.md#s4-8) |

`shop-mcp-server` — Protected Resource·전송 보안:

| 클래스 | 역할 | 명세 |
|---|---|---|
| `SecurityConfig` | Protected Resource Metadata, `401` challenge, token 서명·`iss`·`aud`·`exp` 검증 | [4.1](../MCP-AUTHORIZATION.md#s4-1), [4.2](../MCP-AUTHORIZATION.md#s4-2), [4.9](../MCP-AUTHORIZATION.md#s4-9) |
| `McpTransportConfig` | MCP endpoint 에 filter 두 개(`McpTransportSecurityFilter`·`McpProtocolVersionFilter`)를 등록한다. Streamable HTTP transport 는 Spring AI 자동 구성 bean 을 그대로 쓴다 | [4.8](../MCP-AUTHORIZATION.md#s4-8), [5.7](../MCP-AUTHORIZATION.md#s5-7) |
| `McpTransportSecurityFilter` | SDK `DefaultServerTransportSecurityValidator` 로 `Origin`·`Host` 를 Spring Security 앞에서 검사한다(`403`·`421`) | [4.8](../MCP-AUTHORIZATION.md#s4-8), [5.7](../MCP-AUTHORIZATION.md#s5-7) |
| `McpProtocolVersionFilter` | `MCP-Protocol-Version` 헤더 검증(SDK 가 하지 않는 부분을 보충) | [4.8](../MCP-AUTHORIZATION.md#s4-8) |

`auth-server` — MCP authorization 표준 준수(PKCE·`resource`·`aud`·`iss`·public client):

| 클래스 | 역할 | 명세 |
|---|---|---|
| `AuthorizationServerConfig` | filter chain 두 개를 직접 정의해 PKCE 강제와 아래 확장점을 건다 | [4.3](../MCP-AUTHORIZATION.md#s4-3), [4.5](../MCP-AUTHORIZATION.md#s4-5)–[4.7](../MCP-AUTHORIZATION.md#s4-7), [5.6](../MCP-AUTHORIZATION.md#s5-6) |
| `ResourceIndicatorValidator` | authorization request 의 `resource` 가 허용 목록의 값 하나가 아니면 `invalid_target` 을 던진다 | [4.5](../MCP-AUTHORIZATION.md#s4-5) |
| `PublicClientScopeValidator` | public client 가 `openid` 말고 consent 할 scope 를 요청하지 않으면 `invalid_scope` 를 던진다 | [4.5](../MCP-AUTHORIZATION.md#s4-5), [5.6](../MCP-AUTHORIZATION.md#s5-6) |
| `ResourceAudienceTokenCustomizer` | access token 의 `aud` 를 `resource` 로 발급한다. authorization request 와 다르거나 거기 없던 `resource` 는 `invalid_target` 이다 | [4.7](../MCP-AUTHORIZATION.md#s4-7) |
| `IssuerIdentifyingAuthorizationResponseHandler` | 성공·오류 authorization response 모두에 `iss` 를 싣는다 | [4.6](../MCP-AUTHORIZATION.md#s4-6) |
| `McpResourceProperties` | 이 Authorization Server 가 token 을 발급할 수 있는 resource 목록 | [4.5](../MCP-AUTHORIZATION.md#s4-5), [4.7](../MCP-AUTHORIZATION.md#s4-7) |
| `ClientAuthenticationChallengeFailureHandler` | `Authorization` 헤더로 시도한 `invalid_client` 실패에 `WWW-Authenticate` 를 붙인다 | [4.7](../MCP-AUTHORIZATION.md#s4-7) |
| `PublicClientConsentService` | public client(`none`)의 consent 를 저장하지 않고, `findById` 도 public client 면 `null` 을 돌려준다 | [5.6](../MCP-AUTHORIZATION.md#s5-6) |

## 실행과 확인

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
browser 에서 `http://localhost:8110/` 을 열고 **`user` / `password`** 로 로그인한다.

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
| `curl -X POST http://localhost:8111/mcp -H 'Origin: http://evil.example'`(token 없음) | `403` `Invalid Origin header` — `401` 보다 먼저 나온다 |
| session 없이 `http://localhost:8110/` 접근 | `auth-server`(`:9010`)의 로그인 화면으로 redirect |
| 로그인 후 `노트북 재고 있어?` / `무선 기계식 키보드 살 수 있어?` | 재고 숫자가 정확히 나오고(p1=7개, p2=23개), 품절 상품은 품절이라고 답한다 |
| `grep '호출' logs/shop-mcp-server.log` | `사용자=user` — MCP Server 에 도착한 신원은 agent 가 아니라 로그인한 사람이다 |

## 학습 포인트

### public client 의 consent 는 두 장치로 강제한다

Spring 은 요청 scope 가 `openid` 하나이거나, 저장된 consent 가 요청 scope 를 모두 덮으면 consent 를 건너뛴다.
`PublicClientScopeValidator` 는 consent 할 scope 가 없는 요청을 consent 판정 전에 `invalid_scope` 로 거부하고, `PublicClientConsentService` 는 public client 의 consent 를 저장하지 않는다.
규칙과 근거는 [5.6](../MCP-AUTHORIZATION.md#s5-6) 에 있다.

관측: 이전 consent 가 있어도 두 번째 요청이 `200` consent 화면이고(P8), `openid` 하나만 요청하면 `302` `error=invalid_scope` 다(P8-1).

### `Origin`·`Host` 는 인증보다 먼저 본다

SDK 검증기를 transport 안에서만 쓰면 Spring Security 가 먼저 돌아, token 없는 요청은 `Origin` 검사 전에 `401` 을 받는다.
`McpTransportSecurityFilter` 는 같은 검증기를 `SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1` 순서의 servlet filter 로 불러, token 과 무관하게 `403`·`421` 로 막는다([5.7](../MCP-AUTHORIZATION.md#s5-7)).
테스트: `McpAuthorizationStandardTest#token_이_없어도_허용되지_않은_Origin_은_인증보다_먼저_403이다`, `#token_이_없어도_허용되지_않은_Host_는_인증보다_먼저_421이다`.

### Authorization Server Metadata 는 신뢰한 issuer 에서만 가져온다

`McpAuthorizationDiscovery#discover(resourceUrl, trustedIssuer)` 는 PRM 의 `authorization_servers` 가 `credentials-issuer` 가 아니면 metadata 를 GET 하지 않고 멈춘다.
metadata 의 `authorization_endpoint`·`token_endpoint` 는 `https` 이거나 loopback 주소의 `http` 여야 한다([5.4](../MCP-AUTHORIZATION.md#s5-4)).
테스트: `McpAuthorizationDiscoveryTest#PRM_의_Authorization_Server_가_자격증명의_issuer_가_아니면_metadata_를_요청하지_않는다`, `#authorization_endpoint_가_loopback_이_아닌_http_URL_이면_진행하지_않는다`.

### 로컬 서버는 `127.0.0.1` 에만 bind 한다

세 앱의 `application.yml` 은 `server.address: 127.0.0.1` 로 이 기기 안의 연결만 받는다([5.7](../MCP-AUTHORIZATION.md#s5-7), Transports **SHOULD**).
컨테이너·reverse proxy 뒤에 배포할 때 쓸 값(`0.0.0.0` 이나 그 네트워크 인터페이스 주소)은 같은 yml 의 주석에 있다.

관측: `auth-server`·`shop-mcp-server`·`shop-agent` 가 `127.0.0.1:9010`·`127.0.0.1:8111`·`127.0.0.1:8110` 에서 LISTEN 한다([2026-09-25-listen-addresses.txt](../../docs/superpowers/captures/2026-09-25-listen-addresses.txt) S21).

### `/api/chat` 도 CSRF 를 검사한다

`SecurityConfig` 의 `csrf.spa()` 는 JS 가 읽을 수 있는 `XSRF-TOKEN` 쿠키를 응답에 싣고, `index.html` 은 그 값을 `X-XSRF-TOKEN` 헤더로 되돌려 보낸다.
다른 사이트의 페이지는 이 쿠키를 읽지 못해, 사용자 몰래 채팅(곧 MCP tool 호출)을 보낼 수 없다.
테스트: `ChatCsrfTest#CSRF_토큰_없이_채팅하면_403`, `#페이지가_준_XSRF_TOKEN_을_헤더로_보내면_채팅이_시작된다`.

### MCP session 을 사용자에 묶지 않는다

Spring AI 자동 구성의 MCP client 하나를 모든 사용자가 나눠 써서, 한 MCP session 에 여러 사용자의 token 이 실린다.
session 을 사용자에 묶으면 두 번째 사용자의 요청이 막히므로, 묶지 않고 매 요청의 token 검증으로 session ID 가 인증을 대신하지 않게 한다.
판정은 [준수표](../MCP-AUTHORIZATION.md#s6) 28번(**SHOULD**, 아니오)이고, 근거는 [5.8](../MCP-AUTHORIZATION.md#s5-8) 이다.

### `protectedResourceMetadata` 는 Spring Security 가 제공한다

RFC 9728 Protected Resource Metadata 는 Spring Security 7.1 의 `.protectedResourceMetadata(...)` 한 줄로 켜진다.
`SecurityConfig` 는 여기에 `authorizationServer(issuer)` 와 `tlsClientCertificateBoundAccessTokens(false)` 만 더한다.

### 오류 없이 token 만 빠지는 설정 두 개

`ShopAgentApplication` 의 `Hooks.enableAutomaticContextPropagation()` 은 reactor thread 로 `SecurityContext` 를 옮기고, `SecurityMcpTransportContextProvider` 가 거기서 사용자를 읽는다.
이 줄을 빼거나 `spring.ai.mcp.client.type` 을 `ASYNC` 로 바꾸면(`McpClientCustomizer<McpClient.SyncSpec>` 이 적용되지 않음) 오류 없이 token 만 빠진다.
남는 흔적은 DEBUG 로그 한 줄이고, MCP Server 는 `401` 을 준다.

### filter chain 을 직접 정의하면 OIDC discovery 도 직접 켠다

Boot 4.1 의 `OAuth2AuthorizationServerWebSecurityConfiguration` 은 `.oidc(withDefaults())` 를 켜지만, `@ConditionalOnDefaultWebSecurity` 라 `SecurityFilterChain` 을 직접 정의하면 물러난다.
`AuthorizationServerConfig` 는 filter chain 을 직접 만들므로 `.oidc(...)` 를 스스로 켜고, 그 OIDC metadata 에도 `iss`·signing alg·`none` 을 더한다.

### `issuer-uri` 는 없으면 기동이 실패하고, 틀리면 첫 token 검증에서 실패한다

`SecurityConfig` 는 `issuer-uri` 를 기본값 없는 `@Value` 로 받아, 값이 없으면 placeholder 를 풀지 못해 기동이 실패한다.
JWT decoder 는 Boot 자동 구성의 `SupplierJwtDecoder` 라 issuer metadata 를 첫 token 검증 때 가져온다.
그래서 닿지 않는 issuer 로도 기동은 되고, 첫 token 요청이 `JwtDecoderInitializationException` 으로 끝난다.

### MCP `initialize` 는 첫 채팅 때 한다

`spring.ai.mcp.client.initialized: false` 는 MCP handshake 를 기동 시점이 아니라 첫 채팅 요청으로 미룬다.
기동 시점에는 대신 호출할 사용자가 없어, MCP Server 가 `401` 을 주고 context 기동이 실패하기 때문이다([4.8](../MCP-AUTHORIZATION.md#s4-8)).

### `localhost` 의 두 OAuth2 앱은 session cookie 이름을 나눈다

cookie 는 host 만 보고 포트를 구분하지 않는다([RFC 6265 §8.5](https://www.rfc-editor.org/rfc/rfc6265#section-8.5)).
`auth-server`·`shop-agent` 가 같은 `JSESSIONID` 를 쓰면 한쪽 로그인이 다른 쪽 session 을 덮어쓰므로, 각각 `OFFICIALAUTHSESSIONID`·`OFFICIALAGENTSESSIONID` 로 둔다.

## 비목표

- scope 검사, tool 단위 authorization → 후속 `mcp-security-authz`
- 사용자 여러 명, 역할 분리
- token 저장소 영속화(in-memory 로 충분)
- UI 완성도 — `index.html` 은 OAuth redirect 를 browser 에 맡기기 위한 최소 장치다
- `client_credentials`, Dynamic Client Registration(DCR)
- Client ID Metadata Document(CIMD) — `https` 문서 URL 이 전제라 별도 practice 로 미룬다
- public client 쪽 프로그램(loopback callback 서버, token 보관) — Authorization Server 쪽만 다루고 client 는 캡처 스크립트가 대신한다

## 링크

- [MCP-AUTHORIZATION.md](../MCP-AUTHORIZATION.md) · [MCP-API-SPEC.md](../MCP-API-SPEC.md) · [MCP-SEQUENCES.md](../MCP-SEQUENCES.md)
- 캡처: [2026-09-12-official.txt](../../docs/superpowers/captures/2026-09-12-official.txt) · [2026-09-16-official-supplement.txt](../../docs/superpowers/captures/2026-09-16-official-supplement.txt) · [2026-09-25-official-public-client.txt](../../docs/superpowers/captures/2026-09-25-official-public-client.txt) · [2026-09-25-listen-addresses.txt](../../docs/superpowers/captures/2026-09-25-listen-addresses.txt)
- 이 practice 를 확장한 practice: [`mcp-security-authn-chat-memory`](../mcp-security-authn-chat-memory)
- 같은 흐름을 module 자동 구성으로 대신한 practice: [`mcp-security-authn-community`](../mcp-security-authn-community) — 대응 관계는 [대체 표](../mcp-security-authn-community/README.md#대체-표)
