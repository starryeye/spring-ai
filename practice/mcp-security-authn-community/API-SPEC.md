# mcp-security-authn-community API 명세

`auth-server`·`shop-mcp-server`·`shop-agent` 가 구현하는 endpoint 는 official 과 같다.
이 문서는 endpoint 별 제공자(module 자동 구성 / 직접 얹은 확장 / Spring 기본)와, module 때문에 official 과 다른 오류 경로만 잇는다.
필드 단위 전체 명세는 [official 의 API-SPEC.md](../mcp-security-authn-official/API-SPEC.md) 와 [MCP-API-SPEC.md](../MCP-API-SPEC.md) 에 있다.

## 목차

| 앵커 | 내용 |
|---|---|
| [`endpoints`](#endpoints) | endpoint 는 official 과 같다는 한 줄과 링크 |
| [`providers`](#providers) | endpoint 별 제공자 표 |
| [`errors`](#errors) | module 이 끼어드는 자리의 오류 |

---

<a id="endpoints"></a>

## Endpoint

`auth-server`·`shop-mcp-server`·`shop-agent` 가 노출하는 endpoint 는 [official](../mcp-security-authn-official/API-SPEC.md) 과 이름·경로·요청·응답 형식이 같다.
포트·issuer·client_id 는 [허브의 포트·계정 표](../MCP-AUTHORIZATION.md#s2-ports) 에 있고, session cookie 이름은 `AUTHSERVERSESSIONID`·`SHOPAGENTSESSIONID` 다.

각 endpoint 의 요청·응답 필드·오류·예시는 official 의 [`auth-server`](../mcp-security-authn-official/API-SPEC.md#auth-server) · [`mcp-server`](../mcp-security-authn-official/API-SPEC.md#mcp-server) · [`agent`](../mcp-security-authn-official/API-SPEC.md#agent) · [`agent-login`](../mcp-security-authn-official/API-SPEC.md#agent-login) · [`api-chat`](../mcp-security-authn-official/API-SPEC.md#api-chat) · [`tool-search-products`](../mcp-security-authn-official/API-SPEC.md#tool-search-products) · [`tool-get-stock`](../mcp-security-authn-official/API-SPEC.md#tool-get-stock) 를 그대로 쓴다.

---

<a id="providers"></a>

## Endpoint 별 제공자

| endpoint | 제공 | 설정 위치 |
|---|---|---|
| `GET /.well-known/oauth-authorization-server` | module 자동 구성(`McpAuthorizationServerAutoConfiguration`) | — |
| `GET /.well-known/openid-configuration`(OIDC discovery) | 직접 얹은 확장 — 자동 구성은 `.oidc(...)` 를 켜지 않아 기본으로는 `404` | `OidcDiscoveryConfig` |
| `GET /oauth2/authorize` | module 자동 구성 + 직접 얹은 확장(`resource`·public client scope 검증) | `McpAuthorizationStandardConfig`(`ResourceIndicatorValidator` → `PublicClientScopeValidator`) |
| `POST /oauth2/authorize`(consent) | module 자동 구성(`McpNoScopeClientConsentNotRequired`) + 직접 얹은 확장(public client consent 저장 안 함) | `McpAuthorizationStandardConfig`(`authorizationConsentService`, `PublicClientConsentService`) |
| authorization response(redirect, `iss`) | 직접 얹은 확장 | `McpAuthorizationStandardConfig`(`IssuerIdentifyingAuthorizationResponseHandler`) |
| `POST /oauth2/token`(`authorization_code`) | module 자동 구성 + 직접 얹은 확장(`resource` 개수 확인, access token `aud`, ID token `aud` 복원) | `McpAuthorizationStandardConfig`(`SingleResourceTokenRequestConverter`, `ResourceAudienceTokenCustomizer`) |
| `POST /oauth2/token`(client 인증 실패) | 직접 얹은 확장 | `McpAuthorizationStandardConfig`(`ClientAuthenticationChallengeFailureHandler`) |
| `GET /oauth2/jwks` | module 자동 구성 | `McpAuthorizationServerAutoConfiguration` |
| `POST /register`(DCR) | module 자동 구성, 이 practice 는 끔 | `spring.ai.mcp.authorizationserver.dynamic-client-registration.enabled: false` |
| Client ID Metadata Document | 쓰지 않음 — module 에 `cimd(true)` 가 있지만 켜지 않는다 | — |
| `POST /mcp`(token 없음, `401`) | module 자동 구성, entry point 는 직접 얹은 확장 | `SecurityConfig#resourceMetadataEntryPoint`(module 기본값에 인용부호를 더한 entry point 로 교체) |
| `GET /.well-known/oauth-protected-resource[/mcp]` | module 자동 구성(값은 직접 얹은 확장) | `SecurityConfig`(`protectedResourceMetadataCustomizer`) |
| `POST /mcp`(Bearer, `aud` 검증) | module 자동 구성(설정으로 켬) — 자동 구성 filter chain 에는 audience 검증을 켜는 길이 없어 `SecurityConfig` 가 직접 켠다 | `SecurityConfig`(`McpServerOAuth2Configurer#validateAudienceClaim(true)`) |
| `/mcp`(Origin/Host 검증) | module 자동 구성(설정으로 켬) | `SecurityConfig`(`allowedOrigins`/`allowedHosts`) |
| `POST /mcp`(`MCP-Protocol-Version` 검증) | 직접 얹은 확장 — module 에 대응 기능이 없다 | `McpProtocolVersionFilterConfig` |
| `GET /` · `GET /oauth2/authorization/authserver` · `GET /login/oauth2/code/authserver` | 직접 얹은 확장(로그인 배선) + module 자동 구성(token 부착) | `SecurityConfig`(agent), `AuthorizationResponseIssuerFilter` |
| `POST /api/chat` | Spring 기본(`@RestController`) + 직접 얹은 확장(`.contextWrite(...)`) | `ChatController` |

module 기본값과 다른 네 항목은 이렇다.

- **OIDC discovery** — module 자동 구성은 `openid` scope 로 `oauth2Login` 을 하는 이 practice 에 필요한 `.oidc(...)` 를 켜지 않는다. `OidcDiscoveryConfig` 가 그 확장점으로 켠다.
- **DCR 끔** — module 은 DCR 을 기본으로 켜지만 `spring.ai.mcp.authorizationserver.dynamic-client-registration.enabled: false` 로 끈다. MCP 2026-07-28 이 DCR 을 deprecated 로 표시한다.
- **`401` entry point** — module 기본 entry point 는 `resource_metadata` 값에 인용부호를 붙이지 않는다. `SecurityConfig#resourceMetadataEntryPoint` 가 RFC 9110 §11.2 의 quoted-string 규칙대로 붙이는 entry point 로 바꿔 끼운다.
- **`none` 광고** — module 기본 metadata 는 `none` 인증 방식을 광고하지 않는다. `McpAuthorizationStandardConfig` 가 `authorizationServerMetadataCustomizer`(AS metadata)와 `providerConfigurationCustomizer`(OIDC discovery)에 **같은 customizer 람다** 안에서 더한다. 두 customizer 는 필드 하나에 담겨 마지막 호출이 앞의 것을 덮어쓰기 때문이다.

---

<a id="errors"></a>

## module 이 끼어드는 자리의 오류

오류 응답의 모양은 official 의 [`auth-server` 오류](../mcp-security-authn-official/API-SPEC.md#auth-server) · [`mcp-server` 오류](../mcp-security-authn-official/API-SPEC.md#mcp-server) 와 같다. 여기에는 module 의 동작 때문에 막는 자리나 순서가 다른 오류만 적는다.

근거: [RFC 8707 §2](https://www.rfc-editor.org/rfc/rfc8707#section-2) · 허브 [4.7](../MCP-AUTHORIZATION.md#s4-7) · [4.9](../MCP-AUTHORIZATION.md#s4-9) · [5.6](../MCP-AUTHORIZATION.md#s5-6) · 시퀀스 [`diff-authorization-server`](SEQUENCES.md#diff-authorization-server) · [`diff-mcp-server`](SEQUENCES.md#diff-mcp-server)

| 상황 | 응답 | 이 practice |
|---|---|---|
| `POST /oauth2/token` 의 `resource` 가 여러 개 | `400` `invalid_target` | `SingleResourceTokenRequestConverter` 가 module customizer 의 `(String)` 캐스트 전에 막는다. 테스트 `토큰_요청의_resource_가_여러_개면_invalid_target_이다`, `openid_없는_토큰_요청의_resource_가_여러_개여도_invalid_target_이다` |
| public client 가 scope 를 생략 | redirect 로 `error=invalid_scope` | `PublicClientScopeValidator` — module `McpNoScopeClientConsentNotRequired` 는 이 요청의 consent 를 건너뛴다. 테스트 `공개_클라이언트가_scope_없이_요청하면_invalid_scope_다` |
| `Host` 를 바꾸고 그 Host 용 token 을 실은 `/mcp` 요청 | `421`, audience 계산 전 | `OriginValidationFilter` 가 인증 filter 앞에서 막는다. 테스트 `Host_를_바꾸고_그_Host_용_token_을_실어도_audience_계산_전에_421이다` |
| `Origin` 이 허용 목록 밖 | `403` `Invalid Origin header`, token 이 없어도 먼저 | `OriginValidationFilter` — 허용 Origin 은 `http://localhost:8101`. 테스트 `token_이_없어도_허용되지_않은_Origin_은_인증보다_먼저_403이다` |
