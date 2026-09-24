# 공개 클라이언트(비밀 없는 클라이언트) 지원 — 설계

## 목표

인증 practice 세 개의 인가 서버가 **비밀을 보관할 수 없는 클라이언트**를 받아들이게 한다. 사용자 기기에서 도는 MCP 클라이언트가 이 부류다.

지금 각 인가 서버는 기밀 클라이언트(에이전트) 하나만 안다. 여기에 공개 클라이언트 하나를 **사전 등록**으로 더해, 두 부류가 같은 인가 서버에서 어떻게 다르게 동작하는지 보이게 한다.

CIMD 는 [별도 practice 로 보류](2026-09-24-cimd-public-client-design.md)했다. 이번 작업은 CIMD 와 무관하다 — 클라이언트를 **자동 등록**하는 방법(CIMD·DCR)과, 등록된 클라이언트를 **어떻게 인증**하는가(기밀·공개)는 서로 다른 축이다.

## 기준 조항

| 출처 | 요구 |
|---|---|
| RFC 6749 §2.1 | 클라이언트 유형 — `confidential`(자격증명을 안전하게 보관 가능)과 `public`(불가능) |
| RFC 6749 §3.2.1 / OAuth 2.1 §3.2.2 | 공개 클라이언트는 토큰 엔드포인트에서 클라이언트 인증을 하지 않는다. `client_id` 만 보낸다 |
| RFC 8414 §2 | `token_endpoint_auth_methods_supported` — `none` 은 공개 클라이언트를 뜻한다 |
| RFC 7636 · MCP 2025-11-25 | PKCE 는 MUST. 공개 클라이언트에는 비밀이 없으므로 코드 가로채기를 막는 유일한 장치다 |
| RFC 8252 §7.3 | 네이티브 앱은 루프백 IP 리다이렉트(`127.0.0.1`)를 쓴다. 포트는 요청 시점에 정해질 수 있다 |
| RFC 6749 §3.1.2.3 | 인가 서버는 등록된 리다이렉트 URI 와 대조한다 |
| RFC 8707 | `resource` 는 클라이언트 유형과 무관하게 동일하게 적용된다 |

## 설계

### 클라이언트 등록 (세 practice 공통, `application.yml`)

기존 에이전트 클라이언트는 그대로 두고 아래를 더한다.

- `client-id: local-mcp-client` (비밀 없음)
- `client-authentication-methods: [none]`
- `authorization-grant-types: [authorization_code, refresh_token]`
- `redirect-uris: [http://127.0.0.1:8123/callback]` — 학습용 루프백. 특정 제품 콜백에 매지 않는다
- `scopes: [openid, profile]`
- `require-proof-key: true`
- `require-authorization-consent: true` — 아래 참고

Boot 가 `client-authentication-methods` 값을 그대로 `ClientAuthenticationMethod` 로 만들므로 `none` 은 설정만으로 된다(`OAuth2AuthorizationServerPropertiesMapper` 확인). Spring 의 `PublicClientAuthenticationProvider` 가 이 클라이언트를 처리하고, `CodeVerifierAuthenticator` 가 PKCE 를 검증한다.

### 메타데이터 광고

Spring 은 `none` 을 **절대** 광고하지 않는다(`OAuth2AuthorizationServerMetadataEndpointFilter.clientAuthenticationMethods()` 가 여섯 가지를 고정으로 넣는다). 이미 쓰고 있는 메타데이터 커스터마이저에서 `token_endpoint_auth_methods_supported` 에 `none` 을 더한다 — AS 메타데이터와 OIDC 디스커버리 문서 양쪽.

### 동의 화면

공개 클라이언트에만 켠다(`require-authorization-consent: true`). 클라이언트 단위 설정이라 기존 에이전트 흐름은 바뀌지 않는다.

이유: 공개 클라이언트는 비밀이 없어 "자기 자신임"을 증명하지 못한다. 사용자가 어떤 클라이언트에 무엇을 허용하는지 확인하는 단계가 보안상 의미를 갖는 지점이고, 학습 자료로서도 기밀 클라이언트와의 차이가 드러난다. Spring 기본 동의 화면을 쓴다.

### 무엇이 달라지지 않는가

`resource` → `aud`, PKCE 강제, `iss` 응답, MCP 서버의 audience·Origin·프로토콜 버전 검증은 클라이언트 유형과 무관하게 동일하다. 문서에서 이 점을 분명히 한다.

## 검증

| 대상 | 방법 |
|---|---|
| 메타데이터 | AS 문서·OIDC 문서에 `none` 이 있는지 |
| 공개 클라이언트 흐름 | 인가 요청(PKCE) → 동의 → 코드 → **비밀 없이** 토큰 교환 성공, `aud` 가 resource |
| PKCE 강제 | `code_challenge` 없는 인가 요청 거부 |
| 코드 가로채기 방어 | 틀린 `code_verifier` 로 토큰 요청 시 `invalid_grant` |
| 리다이렉트 검증 | 등록되지 않은 `redirect_uri` 거부 |
| 동의 | 공개 클라이언트는 동의 화면을 거치고, 기존 에이전트는 거치지 않는다 |
| 회귀 | 기존 기밀 클라이언트 흐름(로그인·토큰·MCP 호출)과 기존 테스트 전부 통과 |
| 종단 | 세 practice 에서 공개 클라이언트 흐름을 curl 로 캡처해 기록으로 남긴다 |

## 다루지 않는 것

| 항목 | 이유 |
|---|---|
| CIMD·DCR | 자동 등록은 별도 주제. CIMD 는 보류, DCR 은 2026-07-28 deprecated |
| 실제 제품 콜백 주소 등록 | 학습이 목적이다. 나중에 로컬로 붙일 때 값 하나를 추가하면 된다 |
| scope 설계·step-up | 다음 practice(`mcp-security-authz`) |
| HTTPS | 학습용 localhost 유지. 준수표에 위반으로 기록돼 있다 |

## 문서 작업

- `practice/MCP-AUTHORIZATION.md` — 클라이언트 등록·인증 절에 기밀/공개 구분을 더한다. 공개 클라이언트 흐름 시퀀스 다이어그램 하나, 토큰 요청 엔드포인트 명세에 "공개 클라이언트는 `client_id` 만 보낸다" 행, 준수표에 공개 클라이언트·동의 항목, 관측은 새 캡처에서 인용한다.
- 세 practice README — 등록된 클라이언트가 둘이 된 사실과 각각의 차이.

## 작업 순서

1. official — 설정·메타데이터·테스트
2. chat-memory 이식
3. community — 같은 설정 + 모듈 커스터마이저에 `none` 추가
4. 세 practice 종단 캡처
5. 문서 갱신
