# CIMD practice (`mcp-cimd`) — 설계

## 목표

미리 등록하지 않은 MCP client가 **CIMD(Client ID Metadata Document)** 로 Authorization Server에 붙는 practice와 학습 문서를 만든다.
client는 자기 정보를 담은 JSON 문서를 `https` 주소에 올리고, 그 주소를 `client_id`로 쓴다.
Authorization Server는 처음 보는 `client_id`를 만나면 그 문서를 가져와 검증하고, 그 내용대로 client를 대한다.
독자는 안내서 1\~12장을 읽은 사람이다. 다 보고 나면 ChatGPT와 Claude가 붙을 Authorization Server를 CIMD로 만들 줄 알게 된다.

최종 목표(2026-09-28 사용자 결정)로 가는 로드맵의 네 번째 단계다.
로드맵: scope와 step-up(완료, PR #6) → stateless와 handle(완료, PR #11) → tool 목록과 권한(완료, PR #14) → **CIMD** → SDK 2.2(MCP 2026-07-28)로 올리기.

**출발점(2026-10-07 사용자):** 사용자는 ChatGPT와 Claude 같은 AI 플랫폼에 붙이는 것만 생각한다.
두 플랫폼은 모두 서버에서 도는 agent이고, 둘 다 CIMD로 등록한다. 다만 token endpoint에서 자기를 증명하는 방식이 다르다.
ChatGPT는 `private_key_jwt`, Claude 앱은 public client(`none`)다.
그래서 이 practice는 사용자 기기의 앱(`local-client`) 대신, web agent 하나가 두 제품의 모양을 모두 흉내 내게 한다.

이 문서는 보류해 둔 [2026-09-24 CIMD 설계](2026-09-24-cimd-public-client-design.md)를 대신한다.

## 결정

| # | 항목 | 결정 | 누가 |
|---|---|---|---|
| A | 문서를 둘 `https` 주소 | agent가 self-signed 인증서로 `https://localhost:8172`에 두 문서와 JWKS를 올린다. Authorization Server는 `run.sh`가 만든 truststore로만 그 인증서를 믿고, 그 주소 하나만 loopback 예외로 가져온다. 예외는 Authorization Server process의 설정일 뿐 OS·JVM 설정을 바꾸지 않는다 | 사용자 |
| B | 시작점 | `mcp-tool-visibility`를 복사한다. 버전은 그대로다(Spring Boot 4.1.1, Spring AI 2.0.1, MCP Java SDK 2.0.1) | 사용자 |
| C | `local-client` | 이 practice에서 뺀다. 앞 practice의 `local-client`는 그대로 둔다(Claude 앱도 public client이고, Claude Code(CLI)가 이 모양이며, 빼는 비용이 크다) | 사용자 |
| D | client 모양 | agent가 ChatGPT형(`private_key_jwt`)과 Claude형(`none`) 문서를 둘 다 올리고, 설정 `mcp.authorization.client-type`(`chatgpt` 기본, `claude`)으로 어느 문서를 자기 `client_id`로 쓸지 고른다. Authorization Server에는 client별 설정이 없다 | 사용자 |
| E | public client의 refresh token | CIMD public client에게도 refresh token을 주고, refresh할 때마다 새것을 주며 옛것은 쓸 수 없게 한다(rotation). Claude 문서가 요구하고, OAuth 2.1이 public client에게 허용하는 조건이다 | 사용자 |
| F | Authorization Server의 CIMD | Spring Security 7.1.1에 CIMD가 없으므로 직접 구현한다. community module의 CIMD 클래스는 쓰지 않는다 | 사용자 |
| G | 인증 방식 고정 | client마다 문서의 `token_endpoint_auth_method` 하나만 받는다. `private_key_jwt`를 선언한 client가 `none`으로 오면 거절한다(인증 방식 낮추기 차단). metadata에는 `["private_key_jwt", "none"]`을 알린다 | Claude |
| H | 미리 등록한 client | 모두 뺀다. 모든 client를 CIMD로 받는다 | Claude |
| I | consent 화면 | 직접 만든다. client 이름, 문서 host, redirect host를 보여 주고, 문서의 redirect가 모두 loopback이면 경고를 띄운다. public client의 consent는 지금처럼 저장하지 않는다 | Claude |
| J | agent의 discovery | metadata에 `client_id_metadata_document_supported: true`와 고른 인증 방식이 있어야 진행한다. `credentials-issuer` 비교는 뺀다(CIMD `client_id`는 Authorization Server에 묶이지 않고, 새어 나갈 미리 나눈 비밀이 없다) | Claude |
| K | key와 인증서 | `run.sh`가 처음 실행할 때 git-ignored `certs/`에 `keytool`로 만든다. agent의 서명 key는 파일로 두어 `kid`가 실행마다 바뀌지 않게 한다 | Claude |
| L | 표준 기준 | 요청 형식은 2025-11-25 그대로다. 등록은 MCP 2026-07-28 Client Registration과 CIMD draft-00을 따른다 | Claude |

## 1. 개념 (학습 문서가 설명할 것)

**CIMD의 필요성**
- 미리 등록은 서로 아는 사이에서만 된다. ChatGPT와 Claude는 사용자가 넣는 아무 MCP Server에나 붙으므로, 서버마다 미리 등록할 수 없다.
- DCR은 2026-07-28에서 deprecated다. 등록된 client가 서버에 끝없이 쌓이고, 누가 등록했는지 알 수 없다(4장).
- CIMD는 등록 요청이 없다. 문서 주소가 곧 `client_id`이고, 그 문서의 내용은 그 domain의 주인만 바꿀 수 있다.

**CIMD가 정하는 것과 열어 두는 것**
- CIMD가 정하는 것은 "이 client가 누구인지 알리는 방법"이다(문서 주소, `client_id`·`client_name`·`redirect_uris`).
- "token endpoint에서 자기를 어떻게 증명하는지"는 문서의 `token_endpoint_auth_method`로 client가 고른다.
- CIMD는 공유 비밀 방식만 금지한다. 문서는 누구나 읽을 수 있어서 비밀을 미리 나눌 수 없기 때문이다. `none`과 `private_key_jwt`는 둘 다 허용한다.
- ChatGPT는 `private_key_jwt`(더 강한 증명)를, Claude는 `none`(더 넓은 호환성)을 골랐다. 실제 두 문서를 나란히 보여 준다(9절).

**서버가 두 방식을 다 받아도 되는 이유와 지킬 점**
- `token_endpoint_auth_methods_supported`는 원래 목록이다(RFC 8414). 여러 방식을 받는다고 알리는 자리다.
- 다만 client마다 자기 문서에 적은 방식만 받아야 한다. 같은 `client_id`의 `none` 요청을 받아 주면, 공격자가 ChatGPT의 `client_id`로 key 없이 token을 받아 간다.

**public client의 refresh token**
- 4장의 official은 public client에게 refresh token을 주지 않는다. 새어 나가면 누구든 쓸 수 있기 때문이다.
- OAuth 2.1은 rotation이나 sender-constrained token을 조건으로 public client에게도 refresh token을 허용한다.
- rotation은 refresh할 때마다 새 refresh token을 주고 옛것을 버린다. 훔친 refresh token을 쓰면 정상 client의 다음 refresh가 실패해서 도난이 드러난다.
- Claude는 public client로 붙으면서 refresh token rotation을 요구한다. 주지 않으면 access token이 끝날 때마다 사용자가 다시 login해야 한다.

**localhost 학습 환경의 타협**
- 명세는 `client_id`가 `https`이고 path가 있어야 한다고 하며 localhost 예외가 없다. 그래서 agent가 self-signed `https`로 문서를 올린다.
- 명세는 Authorization Server가 loopback·사설 주소를 가져오지 말라고 권한다(SSRF). 이 practice는 `https://localhost:8172` 하나만 예외로 두고, 준수표에 그 차이를 적는다.

## 2. 구성

| 주소 | process | 하는 일 |
|---|---|---|
| `http://localhost:9060` | `auth-server` | Authorization Server. 모든 client를 CIMD로 받는다 |
| `http://localhost:8171/mcp` | `shop-mcp-server` | MCP Server. 12장 그대로 |
| `http://localhost:8170` | `shop-agent` | 채팅 화면(browser가 여는 곳), redirect URI `http://localhost:8170/login/oauth2/code/authserver` |
| `https://localhost:8172` | `shop-agent` | agent process 안의 별도 HTTPS 서버. client 문서와 JWKS만 준다 |

package는 `dev.starryeye.cimd.*`, MCP Server 이름은 `cimd-shop-mcp-server`, 계정은 `user`(점원)·`user2`(손님) 그대로다.

**agent가 올리는 문서**

ChatGPT형 `https://localhost:8172/oauth/client.json`:

```json
{
  "client_id": "https://localhost:8172/oauth/client.json",
  "client_name": "Shop Agent (ChatGPT형)",
  "client_uri": "https://localhost:8172/",
  "redirect_uris": ["http://localhost:8170/login/oauth2/code/authserver"],
  "grant_types": ["authorization_code", "refresh_token"],
  "response_types": ["code"],
  "token_endpoint_auth_method": "private_key_jwt",
  "token_endpoint_auth_signing_alg": "RS256",
  "jwks_uri": "https://localhost:8172/oauth/jwks.json"
}
```

Claude형 `https://localhost:8172/oauth/public-client.json`: `client_id`가 이 주소이고, `client_name`이 `Shop Agent (Claude형)`이며, `token_endpoint_auth_method`가 `none`이고, `token_endpoint_auth_signing_alg`·`jwks_uri`가 없다. 나머지 field는 같다.

`https://localhost:8172/oauth/jwks.json`은 agent 서명 key의 공개 부분만 담는다(`kty`, `n`, `e`, `kid`, `use: sig`, `alg: RS256`).
세 응답 모두 `Content-Type: application/json`, `Cache-Control: max-age=300`이다.

## 3. 흐름

### 3.1 ChatGPT형 login

1. browser가 `http://localhost:8170`에 오면 agent가 login을 시작한다.
2. agent는 discovery(`401` → PRM → Authorization Server metadata)를 한다. metadata에 `client_id_metadata_document_supported: true`와 `private_key_jwt`가 없으면 이유를 로그와 화면에 남기고 멈춘다.
3. authorization request에 `client_id=https://localhost:8172/oauth/client.json`, PKCE, `resource`를 넣는다.
4. Authorization Server는 처음 보는 `client_id`라 cache를 보고, 없으면 문서를 가져와 검증한다(5절). `RegisteredClient`로 바꿔 cache한다.
5. 요청의 `redirect_uri`가 문서의 `redirect_uris`에 있어야 한다. 없으면 redirect 없이 오류 화면으로 끝낸다.
6. login 뒤 직접 만든 consent 화면을 보여 준다.
7. code가 agent의 callback으로 돌아온다. agent는 `state`와 `iss`를 확인한다(12장 그대로).
8. agent는 token request에 `client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer`와 서명한 `client_assertion`을 넣는다.
9. Authorization Server는 문서의 `jwks_uri`에서 key를 가져와 서명과 claim을 검증하고 token을 준다. access token의 `client_id` claim은 문서 주소다.
10. 이후 MCP 호출, step-up, 역할별 tool 목록은 12장 그대로다. step-up의 다시 authorization과 refresh request에도 같은 assertion을 붙인다.

### 3.2 Claude형이 다른 점

- 8단계에서 `client_id`와 `code_verifier`만 보낸다.
- token 응답에 refresh token이 온다. refresh하면 새 refresh token이 오고, 옛 refresh token을 다시 쓰면 `400 invalid_grant`다.
- consent를 저장하지 않으므로 login과 step-up 때마다 모든 scope를 다시 묻는다(10장의 `local-client`와 같다).

## 4. module별 변경 (`mcp-tool-visibility` 대비)

### auth-server

**지우는 것:** `application.yml`의 미리 등록한 client 둘(`visibility-shop-agent`, `local-mcp-client`).

**새 클래스**

| 클래스 | 하는 일 |
|---|---|
| `ClientIdMetadataDocumentProperties` | `mcp.cimd.*` 정책. `loopback-exception`(`https://localhost:8172`), `max-document-bytes`(5120), `connect-timeout`(2초), `read-timeout`(3초), `default-cache-ttl`(5분, `Cache-Control`이 없을 때), `max-cache-ttl`(1시간), truststore SSL bundle 이름 |
| `ClientIdUrlValidator` | 문서 주소와 `jwks_uri`의 규칙. `https`, path 있음, `.`·`..` 없음, fragment·사용자 정보 없음, query 없음. host를 DNS로 풀어 loopback·사설·link-local·any-local 주소면 거절하되, scheme·host·port가 `loopback-exception`과 모두 같으면 통과 |
| `ClientMetadataHttp` | interface. `FetchedDocument get(URI)` 하나만 가진다. client 문서와 `jwks_uri`를 가져오는 곳은 모두 이것을 쓴다. 테스트는 메모리의 문서를 돌려주는 가짜 `ClientMetadataHttp`를 쓴다 |
| `HttpsClientMetadataFetcher` | `ClientMetadataHttp`의 구현. JDK `HttpClient`(redirect `NEVER`, SSL bundle의 `SSLContext`)로 `GET`한다. `Accept: application/json`. `200`이 아니면, `Content-Type`이 `application/json`이나 `+json`이 아니면, 본문이 `max-document-bytes`를 넘으면 거절한다. `Cache-Control`의 `max-age`·`no-store`를 읽어 함께 돌려준다 |
| `ClientMetadataValidator` | JSON object 하나인지, `client_id`가 문서 주소와 글자까지 같은지, `client_name`·`redirect_uris`(비지 않은 절대 URI 목록)가 있는지 본다. `client_secret`·`client_secret_expires_at`이 있으면 거절한다. `token_endpoint_auth_method`는 반드시 있어야 하고 `none`이나 `private_key_jwt`여야 한다. `private_key_jwt`면 `jwks_uri`가 있어야 하고 `ClientIdUrlValidator`를 통과해야 하며, `token_endpoint_auth_signing_alg`는 없거나 `RS256`이어야 한다. `grant_types`는 없거나 `authorization_code`를 포함해야 한다. 모르는 field는 무시한다 |
| `ClientIdMetadataDocumentRegisteredClientRepository` | `RegisteredClientRepository`. `findByClientId`·`findById`가 같은 주소를 받는다. cache에 살아 있는 항목이 있으면 쓰고, 없으면 검사 → 가져오기 → 검증 → 변환 → cache한다. cache 기간은 `min(max-age, max-cache-ttl)`, `no-store`면 cache하지 않는다. 실패는 cache하지 않고 `null`을 돌려준다. `save`는 지원하지 않는다 |
| `CimdJwtClientAssertionDecoderFactory` | `JwtDecoderFactory<RegisteredClient>`. `NimbusJwtDecoder.withJwkSource(...)`에 `ClientMetadataHttp`로 문서의 `jwks_uri`를 읽는 `JWKSource`를 넣고, 검증 규칙은 Spring의 `JwtClientAssertionDecoderFactory.DEFAULT_JWT_VALIDATOR_FACTORY`를 그대로 쓴다. decoder는 cache하지 않고 검증할 때마다 만든다(인증 전에 호출되므로 cache하면 client_id마다 쌓인다). key 목록도 검증할 때마다 가져온다 |
| `PublicClientRefreshTokenGenerator` | `OAuth2TokenGenerator<OAuth2RefreshToken>`. client의 grant에 `refresh_token`이 있으면 public client에게도 refresh token을 만든다(Spring의 `OAuth2RefreshTokenGenerator`는 public client면 만들지 않는다) |
| `PublicClientRefreshTokenAuthenticationConverter`, `PublicClientRefreshTokenAuthenticationProvider` | token endpoint의 `grant_type=refresh_token` 요청에 client 인증이 없고 `client_id`만 있으면, 그 client가 `none`일 때만 public client로 인증한다(Spring의 `PublicClientAuthenticationConverter`는 `code_verifier`가 있는 요청만 받는다) |
| `ConsentController` | `GET /oauth2/consent`. client 이름, 문서 host, redirect host(`state`로 찾은 authorization request의 `redirect_uri`), 문서의 redirect가 모두 loopback일 때의 경고, scope 체크박스, 이미 허락한 scope를 보여 준다. form은 Spring 기본 consent 화면과 같은 field(`client_id`, `state`, `scope`)로 `POST /oauth2/authorize`에 보낸다. HTML은 `HtmlUtils.htmlEscape`로 값을 감싸 직접 만든다 |

**`RegisteredClient`로 바꾸는 규칙**
- `id`와 `clientId`는 문서 주소, `clientName`은 `client_name`, `redirectUris`는 `redirect_uris`.
- grant는 `authorization_code`와, 문서에 있으면 `refresh_token`.
- 인증 방식은 문서의 `token_endpoint_auth_method` 하나(결정 G).
- scope는 서버 정책 `openid products:read products:write orders:write`.
- `ClientSettings`: `requireProofKey(true)`, `requireAuthorizationConsent(true)`, `private_key_jwt`면 `jwkSetUrl(jwks_uri)`와 `tokenEndpointAuthenticationSigningAlgorithm(RS256)`.
- `TokenSettings`: access token 5분(지금과 같다), refresh token은 Spring 기본 수명(60분), `reuseRefreshTokens(false)`.

**`AuthorizationServerConfig`에서 바뀌는 것**
- `RegisteredClientRepository` bean은 CIMD 저장소다.
- `OAuth2TokenGenerator` bean은 `DelegatingOAuth2TokenGenerator(JwtGenerator(+ 기존 token customizer), PublicClientRefreshTokenGenerator)`다.
- client 인증에 public refresh converter·provider를 더하고, `JwtClientAssertionAuthenticationProvider`에 `CimdJwtClientAssertionDecoderFactory`를 넣는다.
- authorization endpoint의 consent 화면은 `/oauth2/consent`다.
- metadata(OAuth와 OpenID Connect 두 문서)에 `client_id_metadata_document_supported: true`를 넣고, `token_endpoint_auth_methods_supported`를 `["private_key_jwt", "none"]`으로 둔다. `token_endpoint_auth_signing_alg_values_supported`는 `["RS256"]`이다.
- token generator를 직접 만들어 Spring 기본 JWT customizer의 DPoP binding(`cnf.jkt`)과 mTLS 인증서 binding(`cnf.x5t#S256`)이 빠지므로, metadata에서 `dpop_signing_alg_values_supported`와 `tls_client_certificate_bound_access_tokens`를 지운다(DPoP 같은 sender-constrained token은 아래 "다루지 않는 것"에서 범위 밖으로 두었다).
- `revocation_endpoint_auth_methods_supported`와 `introspection_endpoint_auth_methods_supported`는 `["private_key_jwt"]`이고, 짝이 되는 signing alg는 `["RS256"]`이다.
- `PublicClientConsentService`는 그대로 두고, Claude형에도 적용된다. 이름이나 주석의 `local-mcp-client` 전제만 지운다.
- `PublicClientScopeValidator`는 `ConsentableScopeValidator`로 이름을 바꾸고 모든 client에 적용한다. 누구나 문서를 올려 `private_key_jwt` client가 될 수 있으므로, `openid`만 요청해 consent 화면을 건너뛰는 길을 막는다(5절).

### shop-agent

**새 클래스와 설정**

| 클래스·설정 | 하는 일 |
|---|---|
| `ClientMetadataServer` | JDK `HttpsServer`. `127.0.0.1:8172`에서 `/oauth/client.json`, `/oauth/public-client.json`, `/oauth/jwks.json` 세 주소만 `GET`으로 답한다. 인증서는 SSL bundle `client-metadata`(`certs/client-metadata-tls.p12`) |
| `ClientMetadataDocuments` | 두 client 문서와 JWKS의 JSON을 만든다 |
| `ClientMetadataConfig` | `ClientSigningKey`, `ClientMetadataDocuments`, `ClientMetadataServer` bean |
| `ClientSigningKey` | `certs/client-signing.p12`의 RSA key pair를 읽어 `RSAKey`(`kid`는 RFC 7638 thumbprint)로 둔다 |
| `mcp.authorization.client-type` | `chatgpt`(기본)나 `claude`. `client_id`와 인증 방식을 정한다 |

Boot 4의 Tomcat 내부 API에 기대지 않고 문서 host가 채팅 앱과 다른 web site라는 점을 코드에서도 보이려고, `8172`는 Tomcat connector가 아니라 JDK `HttpsServer`로 연다.

**바뀌는 클래스**
- `DiscoveredClientRegistrationRepository`: `clientId`는 문서 주소, `clientAuthenticationMethod`는 `PRIVATE_KEY_JWT`나 `NONE`, `clientSecret`은 없다.
- `McpAuthorizationDiscovery`: `credentials-issuer` 비교를 지우고, CIMD 지원과 인증 방식을 확인한다(결정 J). `resource`·`issuer` 일치, `S256`, endpoint 주소 형식 확인은 그대로다.
- `McpSecurityConfig`: authorization code·refresh token response client에 `NimbusJwtClientAuthenticationParametersConverter`를 더한다. JWK resolver는 registration의 인증 방식이 `PRIVATE_KEY_JWT`일 때만 `ClientSigningKey`를 돌려준다. assertion은 `iss`·`sub`가 `client_id`, `aud`가 token endpoint다(Spring client 기본값).
- 설정에서 `client-secret`, `credentials-issuer`를 지운다.

### shop-mcp-server

코드를 바꾸지 않는다. 이름·포트·package만 바꾼다. 로그의 `client_id=`에 문서 주소가 찍힌다.

### local-client

module을 지운다.

### 실행 스크립트

- `run.sh`: `certs/`가 없으면 `keytool`로 세 파일을 만든다.
  - `client-metadata-tls.p12`: `CN=localhost`, SAN `DNS:localhost,IP:127.0.0.1`의 self-signed 인증서
  - `client-metadata-trust.p12`: 위 인증서만 담은 truststore(Authorization Server용)
  - `client-signing.p12`: RSA 2048 서명 key
- 그다음 `auth-server` → `shop-mcp-server` → `shop-agent` 순서로 띄운다. agent는 `8170`과 `8172`가 모두 열릴 때까지 기다린다.
- `stop.sh`: 포트로 process를 찾아 내린다. `certs/`는 지우지 않는다.
- `.gitignore`: `certs/`.

## 5. 보안 규칙 정리

| 규칙 | 자리 | 어기면 생기는 일 |
|---|---|---|
| 문서 주소는 `https`, path 있음, `.`·`..`·fragment·사용자 정보 없음 | `ClientIdUrlValidator` | 같은 문서를 여러 주소로 가리키거나, 평문으로 문서를 바꿔치기한다 |
| loopback·사설 주소는 가져오지 않는다(예외 하나) | `ClientIdUrlValidator` | 공격자가 `client_id`에 내부망 주소를 적어 Authorization Server가 그곳에 요청하게 한다(SSRF) |
| redirect를 따라가지 않고, 크기와 시간을 제한한다 | `HttpsClientMetadataFetcher` | redirect로 내부망에 닿거나, 큰 문서·느린 응답으로 서버 자원을 묶는다 |
| 문서의 `client_id`는 주소와 같아야 한다 | `ClientMetadataValidator` | 남의 문서를 복사해 자기 주소에 올린 client가 그 client 행세를 한다 |
| `redirect_uri`는 문서의 목록에 있어야 한다 | Spring의 authorization request 검증 | code가 문서에 없는 공격자 주소로 간다 |
| 인증 방식은 문서의 것 하나만 | 저장소의 변환 규칙 | key 없이 `none`으로 `private_key_jwt` client 행세를 한다 |
| 오류와 잘못된 문서는 cache하지 않는다 | 저장소 | 한 번의 실패가 cache 기간 내내 이어지거나, 잘못된 문서가 남는다 |
| consent 화면에 문서 host와 redirect host를 보이고, loopback뿐이면 경고한다 | `ConsentController` | 진짜 client 이름을 단 공격자의 요청을 사용자가 구별하지 못한다 |
| authorization request에는 `openid` 말고 scope가 하나 이상 있어야 한다 | `ConsentableScopeValidator` | `openid`만 요청해 consent 화면 없이 code·id_token·refresh token을 받는다 |
| public client의 refresh token은 rotation한다 | `TokenSettings`, refresh provider | 훔친 refresh token을 오래 쓴다 |

## 6. 학습 문서

- **새 13장** `practice/mcp-guide/13-cimd.md` "13. CIMD — 처음 보는 client를 문서로 알아본다". 장의 틀은 필요성 → 시퀀스 다이어그램 → 단계별 실제 요청·응답 → 확인하는 것 → 서버·client 코드 → 직접 해 보기 → 정리 → 명세 근거다. 다룰 것은 1절의 개념, 실제 ChatGPT·Claude Code 문서 비교, 문서를 가져와 믿기까지의 검사, consent 화면, 두 인증 방식과 인증 방식 낮추기 공격, public client의 refresh rotation(4장과의 차이), MCP Server에서 보이는 `client_id`다. 첫머리에 "이 장부터는 사용자 기기의 앱 대신 서버에서 도는 두 제품의 모양을 web agent로 본다"고 밝힌다.
- **함께 고칠 곳**
  - 4장의 "official은 CIMD를 구현하지 않는다" 뒤에 13장 링크
  - 목차(README)의 읽는 순서·준비물·장 목록, 12장 끝의 이웃 장 링크
  - 준수표에 "mcp-cimd에서 달라지는 행" 절(15 CIMD, 23 `none` 광고, 26 public refresh rotation, 10 issuer binding, 27 SSRF와 loopback 예외, 그리고 CIMD 새 행)
  - API 레퍼런스의 CIMD·token endpoint(`client_assertion`)·metadata 항목
  - practice README, 저장소 README, 문서 작성 스킬의 예시 값(13장은 `mcp-cimd` 값과 캡처를 쓴다)

## 7. 테스트

| 대상 | 확인할 것 |
|---|---|
| `ClientIdUrlValidator` | `http`, path 없음, `..`, fragment, 사용자 정보, query, loopback·사설·link-local IP를 거절한다. 예외 주소는 scheme·host·port가 모두 같을 때만 통과한다(`https://localhost:8173`, `http://localhost:8172`는 거절) |
| `ClientMetadataValidator` | `client_id` 불일치, `client_name`·`redirect_uris` 누락, `client_secret` 포함, 인증 방식 누락·`client_secret_basic`, `jwks_uri` 없는 `private_key_jwt`, `http` `jwks_uri`, `RS256`이 아닌 서명 알고리즘을 거절한다. 모르는 field(`token_endpoint_auth_methods_supported` 등)는 무시한다 |
| `HttpsClientMetadataFetcher` | 테스트용 HTTPS 서버로 5KB 초과, `302`, 시간 초과, `404`, JSON이 아닌 `Content-Type`을 거절하고, `max-age`·`no-store`를 읽는다 |
| 저장소 | cache 적중과 만료, `no-store`, 오류 미cache, `findById`와 `findByClientId`가 같은 client, 인증 방식 고정 |
| authorization endpoint | 문서에 없는 `redirect_uri`는 redirect 없이 오류, PKCE 없으면 거절, consent 화면의 두 host와 loopback 경고 |
| token endpoint | ChatGPT형: 올바른 assertion이면 token(`client_id` claim이 문서 주소), 다른 key의 assertion과 assertion 없는 요청은 `401 invalid_client`. Claude형: refresh token 발급, refresh하면 새 refresh token, 옛것은 `invalid_grant`, 다른 client의 refresh token은 거절 |
| metadata | 두 discovery 문서에 CIMD 표시, `["private_key_jwt", "none"]`, `RS256` |
| agent | 문서는 `8172`에서만, 문서의 `client_id`가 자기 주소, JWKS에 공개 key만, CIMD나 고른 방식을 알리지 않는 서버면 멈춤, ChatGPT형 token request에 assertion이 붙고 Claude형에는 없음 |
| 기존 기능 | 12장의 step-up, 역할별 tool 목록, 장바구니, 대화 기억 테스트가 그대로 통과한다 |

테스트용 인증서와 key는 `src/test/resources`에 둔다(테스트 전용이라 비밀이 아니다).

## 8. 캡처와 확인

- `docs/superpowers/captures/mcp-cimd-walkthrough.sh`가 curl로 단계를 `D<n>`으로 기록한다. curl은 `--cacert certs/client-metadata-tls.p12`에서 뽑은 인증서로 `8172`를 읽는다.
  - metadata의 CIMD 표시와 두 문서·JWKS
  - ChatGPT형 authorization request와 consent 화면, `private_key_jwt` token request(스크립트가 `openssl`로 assertion을 서명한다), MCP 호출과 token의 `client_id` claim
  - 실패: 다른 key의 assertion, assertion 없는 `none` 요청, 문서에 없는 redirect 주소
  - Claude형 authorization과 token request, refresh rotation, 옛 refresh token 재사용
- Authorization Server 로그에 문서를 가져온 것(주소, `max-age`)과 cache에서 꺼낸 것이 찍힌다.
- browser로 두 `client-type`에서 login, consent, 채팅, step-up을 한 번씩 확인한다.

## 9. 구현 전에 확인한 것 (2026-10-07)

**명세**
- MCP 2026-07-28 Client Registration: client는 `https` 주소에 문서를 올린다(MUST). `client_id`는 `https`이고 path가 있다(MUST). 문서에는 `client_id`·`client_name`·`redirect_uris`가 있다(MUST). client는 `private_key_jwt`를 쓸 수 있다(MAY, CIMD §6.2). Authorization Server는 문서를 가져오고(SHOULD), `client_id` 일치·`redirect_uri`·JSON 구조를 검증하며(MUST), cache header를 따른다(SHOULD). metadata는 `client_id_metadata_document_supported: true`. CIMD `client_id`는 Authorization Server에 묶이지 않는다.
- CIMD draft-00: §3 `https`, path, `.`·`..`·fragment·사용자 정보 금지, localhost 예외 없음. §4.4 오류·잘못된 문서는 cache하지 않는다(MUST NOT). §6.5 loopback·사설 주소를 가져오지 말 것(SHOULD). §6.2 예시는 `jwks_uri`. 서버는 client host에 대한 자기 신뢰 정책을 둘 수 있다(MAY).

**제품 문서와 실제 문서**
- ChatGPT(`https://chatgpt.com/oauth/client.json`, 직접 받음): `token_endpoint_auth_method: private_key_jwt`, `token_endpoint_auth_signing_alg: RS256`, `jwks_uri: https://chatgpt.com/oauth/jwks.json`, redirect `https://chatgpt.com/connector_platform_oauth_redirect`. 확장 field `token_endpoint_auth_methods_supported: ["none", "private_key_jwt"]`도 있고, OpenAI 문서는 서버가 받는 방식과의 교집합에서 고른다고 한다.
- Claude 앱: metadata에 `client_id_metadata_document_supported: true`와 `none`이 둘 다 있어야 CIMD를 쓰고, 없으면 DCR로 간다. public client로 붙고 refresh token rotation을 요구한다. redirect `https://claude.ai/api/mcp/auth_callback`.
- Claude Code(`https://claude.ai/oauth/claude-code-client-metadata`, 직접 받음): `none`, redirect `http://localhost/callback`·`http://127.0.0.1/callback`.

**Spring Security 7.1.1 (bytecode로 확인)**
- Authorization Server에 CIMD가 없다(issue spring-projects/spring-security#18375 열림).
- `JwtClientAssertionAuthenticationProvider`가 `private_key_jwt`를 지원하고 `setJwtDecoderFactory`로 decoder factory를 바꿀 수 있다.
- `JwtClientAssertionDecoderFactory`는 `final`이고 JVM 기본 truststore를 쓰는 내부 `RestTemplate`으로 JWKS를 받는다. 검증 규칙 `DEFAULT_JWT_VALIDATOR_FACTORY`는 `public static`이다. `aud`는 token endpoint를 받는다.
- `OAuth2RefreshTokenGenerator`는 public client의 authorization code 요청이면 refresh token을 만들지 않는다. `PublicClientAuthenticationConverter`는 `code_verifier`가 있는 요청만 public client로 인증한다. `OAuth2RefreshTokenAuthenticationProvider`는 public client에 DPoP proof가 있을 때만 그 key를 대조한다.

**community module 0.1.14**
- CIMD 클래스(`ClientIdMetadataDocumentRegisteredClientRepository` 등)가 있지만, 문서를 public client로만 바꾸고 `jwks_uri`·`private_key_jwt`를 다루지 않는다. `http` 주소도 받는다.

**구현하며 확인할 것**
- Spring 기본 consent 화면이 `openid`를 다시 붙이는 자리(화면의 hidden field인지 provider인지). 직접 만든 화면도 같은 결과가 나와야 한다.
- refresh rotation에서 옛 refresh token이 `invalid_grant`가 되는지(`reuseRefreshTokens(false)`의 실제 동작).
- agent가 step-up으로 다시 authorization할 때도 assertion이 붙는지.

## 다루지 않는 것

- 사용자 기기의 앱(`local-client`)의 CIMD. Claude Code(CLI)의 모양이며, 13장에 실제 문서만 보여 준다.
- DCR(2026-07-28 deprecated)과 사용자가 client 정보를 입력하는 방식.
- 문서 안의 `jwks`(inline). ChatGPT처럼 `jwks_uri`만 받는다.
- DPoP 같은 sender-constrained refresh token. rotation만 한다.
- client host 신뢰 정책(허용 domain 목록). 예외 주소 하나만 둔다.
- `logo_uri` 표시와 이미지 가져오기.
- DNS rebinding으로 검사와 연결 사이에 주소가 바뀌는 경우. 검사한 IP로 연결을 고정하지 않으며, 13장과 준수표에 남는 위험으로 적는다.
- 공개 HTTPS 배포. 학습용 localhost를 유지한다(준수표 12번).
- 앞 practice의 변경.
