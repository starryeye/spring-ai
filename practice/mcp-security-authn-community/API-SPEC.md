# mcp-security-authn-community API 명세

`auth-server`·`shop-mcp-server` 가 구현하는 endpoint 는 official 과 같다.
이 문서는 값 차이와 endpoint 별 제공자(module 자동 구성 / 직접 얹은 확장 / Spring 기본)만 잇는다.
필드 단위 전체 명세는 [official 의 API-SPEC.md](../mcp-security-authn-official/API-SPEC.md) 와 [MCP-API-SPEC.md](../MCP-API-SPEC.md) 에 있다.

## 목차

| 앵커 | 내용 |
|---|---|
| [`endpoints`](#endpoints) | endpoint 는 official 과 같다는 한 줄 + 값 차이 표 |
| [`providers`](#providers) | endpoint 별 제공자 표 |

---

<a id="endpoints"></a>

## Endpoint

`auth-server`(`:9000`)·`shop-mcp-server`(`:8101`)·`shop-agent`(`:8100`)가 노출하는 endpoint 는 [official](../mcp-security-authn-official/API-SPEC.md) 과 이름·경로·요청·응답 형식이 같다.
포트와 client 이름만 다르다.

| 항목 | official | community |
|---|---|---|
| Authorization Server issuer | `http://localhost:9010` | `http://localhost:9000` |
| MCP Server resource | `http://localhost:8111/mcp` | `http://localhost:8101/mcp` |
| Agent 주소 | `http://localhost:8110` | `http://localhost:8100` |
| confidential client(agent) `client_id` | `official-shop-agent` | `shop-agent` |
| public client `client_id` | `local-mcp-client`(공통) | `local-mcp-client`(공통) |
| session cookie 이름 | `OFFICIALAUTHSESSIONID`/`OFFICIALAGENTSESSIONID` 계열 | `AUTHSERVERSESSIONID`/`SHOPAGENTSESSIONID` |

각 endpoint 의 요청·응답 필드·오류·예시는 [official API-SPEC.md](../mcp-security-authn-official/API-SPEC.md) 의 [`auth-server`](../mcp-security-authn-official/API-SPEC.md#auth-server) · [`mcp-server`](../mcp-security-authn-official/API-SPEC.md#mcp-server) · [`agent`](../mcp-security-authn-official/API-SPEC.md#agent) · [`agent-login`](../mcp-security-authn-official/API-SPEC.md#agent-login) · [`api-chat`](../mcp-security-authn-official/API-SPEC.md#api-chat) · [`tool-search-products`](../mcp-security-authn-official/API-SPEC.md#tool-search-products) · [`tool-get-stock`](../mcp-security-authn-official/API-SPEC.md#tool-get-stock) 를 그대로 쓴다.

---

<a id="providers"></a>

## Endpoint 별 제공자

| endpoint | 제공 | 설정 위치 |
|---|---|---|
| `GET /.well-known/oauth-authorization-server` | 모듈 자동 구성(`McpAuthorizationServerAutoConfiguration`) | — |
| `GET /.well-known/openid-configuration`(OIDC discovery) | 직접 얹은 확장 — 자동 구성은 `.oidc(...)` 를 켜지 않아 기본으로는 `404` | `OidcDiscoveryConfig` |
| `GET /oauth2/authorize` | 모듈 자동 구성 + 직접 얹은 확장(`resource` 검증) | `McpAuthorizationServerAutoConfiguration` + `McpAuthorizationStandardConfig`(`ResourceIndicatorValidator`) |
| `POST /oauth2/authorize`(consent) | 모듈 자동 구성(`McpNoScopeClientConsentNotRequired`) + 직접 얹은 확장(consent 저장 안 함) | `McpAuthorizationStandardConfig`(`authorizationConsentService`, `PublicClientConsentService`) |
| authorization response(redirect, `iss`) | 직접 얹은 확장 | `McpAuthorizationStandardConfig`(`IssuerIdentifyingAuthorizationResponseHandler`) |
| `POST /oauth2/token`(`authorization_code`) | 모듈 자동 구성 + 직접 얹은 확장(`aud`) | `McpAuthorizationStandardConfig`(`ResourceAudienceTokenCustomizer`) |
| `POST /oauth2/token`(client 인증 실패) | 직접 얹은 확장 | `McpAuthorizationStandardConfig`(`ClientAuthenticationChallengeFailureHandler`) |
| `GET /oauth2/jwks` | 모듈 자동 구성 | `McpAuthorizationServerAutoConfiguration` |
| `POST /register`(DCR) | 모듈 자동 구성, 이 practice 는 끔 | `spring.ai.mcp.authorizationserver.dynamic-client-registration.enabled: false` |
| Client ID Metadata Document | 쓰지 않음 — module 에 `cimd(true)` 가 있지만 켜지 않는다 | — |
| `POST /mcp`(token 없음, `401`) | 모듈 자동 구성, entry point 는 직접 얹은 확장 | `SecurityConfig#resourceMetadataEntryPoint`(module 기본값에 인용부호를 더한 진입점으로 교체) |
| `GET /.well-known/oauth-protected-resource[/mcp]` | 모듈 자동 구성(값은 직접 얹은 확장) | `SecurityConfig`(`protectedResourceMetadataCustomizer`) |
| `POST /mcp`(Bearer, `aud` 검증) | 직접 얹은 확장 — 자동 구성에는 audience 검증을 켜는 길이 없다 | `SecurityConfig`(`McpServerOAuth2Configurer#validateAudienceClaim(true)`) |
| `GET /mcp`(Origin/Host 검증) | 모듈 자동 구성(설정으로 켬) | `SecurityConfig`(`allowedOrigins`/`allowedHosts`) |
| `POST /mcp`(`MCP-Protocol-Version` 검증) | 직접 얹은 확장 — 모듈에 대응 기능이 없다 | `McpProtocolVersionFilterConfig` |
| `GET /` · `GET /oauth2/authorization/authserver` · `GET /login/oauth2/code/authserver` | 직접 얹은 확장(로그인 배선) + 모듈 자동 구성(token 부착) | `SecurityConfig`(agent), `AuthorizationResponseIssuerFilter` |
| `POST /api/chat` | Spring 기본(`@RestController`) + 직접 얹은 확장(`.contextWrite(...)`) | `ChatController` |

차이가 나는 네 항목은 이렇다.

- **OIDC discovery** — 모듈 자동 구성은 `openid` scope 로 `oauth2Login` 을 하는 이 practice 에 필요한 `.oidc(...)` 를 켜지 않는다. `OidcDiscoveryConfig` 가 그 확장점으로 켠다.
- **DCR 끔** — 모듈은 DCR 을 기본으로 켜지만 `spring.ai.mcp.authorizationserver.dynamic-client-registration.enabled: false` 로 끈다. MCP 2026-07-28 이 DCR 을 deprecated 로 표시한다.
- **`401` entry point** — 모듈 기본 진입점은 `resource_metadata` 값에 인용부호를 붙이지 않는다. `SecurityConfig#resourceMetadataEntryPoint` 가 RFC 9110 §11.2 의 quoted-string 규칙대로 붙이는 진입점으로 바꿔 끼운다.
- **`none` advertise** — 모듈 기본 metadata 는 `none` 인증 방식을 광고하지 않는다. `McpAuthorizationStandardConfig` 가 `authorizationServerMetadataCustomizer`(AS metadata)와 `providerConfigurationCustomizer`(OIDC discovery)에 **같은 커스터마이저 람다** 안에서 더한다 — 두 커스터마이저는 필드 하나에 담겨 마지막 호출이 앞의 것을 덮어쓰기 때문이다.
