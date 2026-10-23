# mcp-cimd

이 practice는 [mcp-tool-visibility](../mcp-tool-visibility/README.md)를 바탕으로, Authorization Server가 미리 등록하지 않은 client를 CIMD 문서로 받게 한다.
client는 자기 정보를 담은 JSON 문서를 `https` 주소에 올리고, 그 주소를 `client_id`로 쓴다.
web agent는 ChatGPT형(`private_key_jwt`)과 Claude형(`none`) 두 client type 가운데 하나로 붙는다.
사용자 기기의 앱(`local-client`)은 없고, 서버에서 도는 두 제품의 방식만 web agent로 본다.
흐름과 규칙은 [안내서 13장](../mcp-guide/13-cimd.md)이 설명하고, 이 README에서는 visibility와 다른 점만 본다.

## mcp-tool-visibility와 다른 점

| 바뀐 곳 | visibility | 이 practice | 안내서 절 |
|---|---|---|---|
| Authorization Server의 client 등록 | `application.yml`에 미리 등록한 client 둘(`visibility-shop-agent`, `local-mcp-client`)이 있다 | 미리 등록한 client가 없다. `ClientIdMetadataDocumentRegisteredClientRepository`가 URL `client_id`의 문서를 가져와 `RegisteredClient`로 바꾸고 cache한다 | [13장 2단계](../mcp-guide/13-cimd.md#135-2단계-문서를-가져와-믿기까지) |
| 문서 주소 검사 | 문서가 없다 | `ClientIdUrlValidator`는 path가 있는 `https` 주소만 받고, DNS로 푼 주소가 loopback·사설 같은 내부 대역이면 거절한다. 예외는 `https://localhost:8172` 하나다 | [13장 2단계](../mcp-guide/13-cimd.md#135-2단계-문서를-가져와-믿기까지) |
| 문서 가져오기 | 문서가 없다 | `HttpsClientMetadataFetcher`는 redirect를 따라가지 않고 `200`과 JSON `Content-Type`만 받으며, 크기·시간·동시에 가져오는 수를 제한한다 | [13장 2단계](../mcp-guide/13-cimd.md#135-2단계-문서를-가져와-믿기까지) |
| 문서 내용 검사 | 문서가 없다 | `ClientMetadataValidator`는 문서의 `client_id`가 주소와 글자까지 같은지, `client_name`이 있는지, `redirect_uris`가 모두 `https`나 loopback `http`인지 본다. `client_secret`이 있으면 거절하고, 인증 방식은 `none`과 `private_key_jwt`만 받는다 | [13장 2단계](../mcp-guide/13-cimd.md#135-2단계-문서를-가져와-믿기까지) |
| consent할 scope가 없는 요청 | `PublicClientScopeValidator`가 public client(`none`)의 요청만 본다. `openid`만 있거나 scope가 없으면 `invalid_scope`다 | `ConsentableScopeValidator`가 모든 client에게 같은 검사를 한다. 누구나 문서를 올려 `private_key_jwt` client가 될 수 있으므로, `openid`만 요청해 consent 화면을 건너뛰는 길을 막는다 | [13장 3단계](../mcp-guide/13-cimd.md#136-3단계-consent-화면) |
| consent 화면 | Spring 기본 화면이다 | `ConsentController`가 client 이름, 문서 host, 허락한 뒤 돌아갈 redirect host를 보여 준다. 문서의 redirect 주소가 모두 loopback이면 경고 문장을 더한다 | [13장 3단계](../mcp-guide/13-cimd.md#136-3단계-consent-화면) |
| 저장한 consent | public client의 consent만 저장하지 않는다 | 문서의 `redirect_uris`로 만든 hash를 consent와 함께 저장한다. 문서의 redirect 주소가 바뀌면 저장한 consent를 쓰지 않고 다시 묻는다 | [13장 3단계](../mcp-guide/13-cimd.md#136-3단계-consent-화면) |
| authorization request의 오류 | 오류를 client의 redirect 주소로 보내고 `iss`를 붙인다 | login한 사용자가 consent 화면에서 거절한 `access_denied`만 redirect한다. 그 밖의 오류는 `400`과 `text/plain` 본문으로 끝내고, `redirect_uri`를 뺀 요청도 이 오류(`invalid_request`)다 | [13장 2단계](../mcp-guide/13-cimd.md#135-2단계-문서를-가져와-믿기까지) |
| token endpoint 인증 | agent는 `client_secret_basic`, `local-client`는 `none`이다 | client마다 문서의 `token_endpoint_auth_method` 하나만 받는다. `private_key_jwt`의 assertion은 `CimdJwtClientAssertionDecoderFactory`가 문서의 `jwks_uri`에서 가져온 key로 검증한다 | [13장 4단계](../mcp-guide/13-cimd.md#137-4단계-두-인증-방식) |
| public client의 refresh token | 주지 않는다 | 주고, refresh할 때마다 새것을 주며 옛것은 쓸 수 없게 한다(rotation). 버린 refresh token이 다시 오면 grant 전체를 끊고, 한 grant의 refresh는 한 번에 하나씩 처리한다 | [13장 5단계](../mcp-guide/13-cimd.md#138-5단계-public-client의-refresh와-rotation) |
| refresh의 client 인증 | 등록한 인증 방식을 쓴다 | `client_id`만 온 refresh는 grant를 받을 때 `none`이었던 grant만 받는다. 그사이 문서가 `none`으로 바뀌어도 `private_key_jwt`로 받은 grant는 assertion 없이 refresh할 수 없다 | [13장 5단계](../mcp-guide/13-cimd.md#138-5단계-public-client의-refresh와-rotation) |
| introspection | 인증된 client면 어느 token이든 내용을 알려 준다(Spring 기본) | token을 발급받은 client에게만 내용을 알려 주고, 그 밖에는 `{"active":false}`만 준다 | [13장 4단계](../mcp-guide/13-cimd.md#137-4단계-두-인증-방식) |
| metadata | 알리는 인증 방식은 Spring 기본 여섯에 `none`을 더한 일곱이고, signature 알고리즘은 12개다. DPoP와 mTLS 인증서 binding도 Spring 기본대로 알린다 | `client_id_metadata_document_supported: true`를 알리고, 인증 방식은 `["private_key_jwt", "none"]`, signature 알고리즘은 `["RS256"]`이다. DPoP와 mTLS 인증서 binding은 알리지 않는다 | [13장 1단계](../mcp-guide/13-cimd.md#134-1단계-metadata와-두-문서) |
| agent의 client 정보 | 설정에 `client-id`·`client-secret`과 `credentials-issuer`가 있다 | 문서 주소가 `client_id`이고 비밀이 없다. `mcp.authorization.client-type`(`chatgpt`·`claude`)이 문서 주소와 인증 방식을 정한다 | [13장 agent 코드](../mcp-guide/13-cimd.md#1311-agent-코드에서-보기) |
| agent의 discovery | PRM이 알려 준 Authorization Server를 `credentials-issuer`와 비교한다 | CIMD `client_id`는 어느 Authorization Server에도 묶이지 않으므로 비교하지 않는다. metadata에 CIMD 표시와 고른 인증 방식이 없으면 이유를 남기고 멈춘다 | [13장 1단계](../mcp-guide/13-cimd.md#134-1단계-metadata와-두-문서) |
| agent의 문서 host | 없다 | `ClientMetadataServer`가 JDK `HttpsServer`로 `127.0.0.1:8172`를 연다. 두 문서와 JWKS, 세 주소만 `GET`으로 준다 | [13장 1단계](../mcp-guide/13-cimd.md#134-1단계-metadata와-두-문서) |
| agent의 token·refresh request | Basic header에 `client_id`와 `client_secret`을 넣는다 | ChatGPT형은 `ClientSigningKey`로 signature를 만든 client assertion을 붙인다. Claude형은 `client_id`만 보낸다 | [13장 4단계](../mcp-guide/13-cimd.md#137-4단계-두-인증-방식) |
| `local-client` | 있다 | 없다. 서버에서 도는 두 제품만 보는 이유는 13장 첫머리에 있고, 사용자 기기의 앱(Claude Code(CLI))은 실제 문서로만 본다 | [13장](../mcp-guide/13-cimd.md) 첫머리 |
| `run.sh` | 인자가 없다 | `certs/`에 인증서와 key를 만든다. 첫 인자(`chatgpt`·`claude`)로 client type을 고른다 | [실행](#실행) |
| MCP Server | 로그와 token의 `client_id`가 `visibility-shop-agent`다 | 코드는 같고 이름·package·포트만 다르다. 로그와 token의 `client_id`가 문서 주소다 | [13장 MCP Server의 client_id](../mcp-guide/13-cimd.md#139-mcp-server에서-보이는-client_id) |

[코드 지도](#코드-지도)에 없는 클래스는 visibility와 같다.
그 클래스들은 package 이름(`dev.starryeye.cimd.*`)과 포트·cookie 같은 설정 값만 다르다.
scope, 역할 표, 역할별 tool 목록, step-up 흐름은 [visibility](../mcp-tool-visibility/README.md#숨기기와-step-up)와 같다.
다만 Claude형은 consent를 저장하지 않으므로, step-up의 consent 화면에서 이미 허락한 scope까지 모두 다시 고른다.

MCP 요청 형식은 visibility와 같은 2025-11-25이고, 버전도 Spring Boot 4.1.2, Spring AI 2.0.2, MCP Java SDK 2.1.0으로 같다.
CIMD 규칙은 2026-07-28 Client Registration을 따른다.

### 두 client type

agent는 ChatGPT형(`chatgpt`, 기본, `private_key_jwt`)과 Claude형(`claude`, `none`) 가운데 `mcp.authorization.client-type`으로 고른 하나로 붙는다.
`run.sh`의 첫 인자가 이 값을 정하고([실행](#실행)), agent는 두 문서를 늘 함께 올리므로 client type을 바꿔도 문서 서버는 같다.
Authorization Server에는 client type 설정이 없고, 어떤 인증 방식을 받을지는 그 client가 올린 문서의 `token_endpoint_auth_method`가 정한다.
두 client type의 문서 주소, 인증 방식, refresh, consent 저장의 차이는 [13장 필요성](../mcp-guide/13-cimd.md#131-cimd의-필요성)에 있다.
본뜬 두 제품의 실제 문서는 [13장 실제 제품의 문서](../mcp-guide/13-cimd.md#132-실제-제품의-문서)에서 나란히 본다.

### localhost 학습 환경의 타협

CIMD의 `client_id`는 path가 있는 `https` 주소여야 하므로, agent는 self-signed 인증서로 `https://localhost:8172`에 문서를 올린다.
Authorization Server는 `mcp.cimd.trust-bundle`이 가리키는 `certs/client-metadata-trust.p12`의 그 인증서 하나만 믿는다.
loopback 주소의 문서는 `mcp.cimd.loopback-exception`의 `https://localhost:8172` 하나만 가져오고, scheme·host·port가 모두 같아야 한다.
이렇게 타협하는 이유는 [13장 1단계](../mcp-guide/13-cimd.md#134-1단계-metadata와-두-문서)에, 내부 주소를 막는 이유(SSRF)와 예외의 범위는 [13장 2단계](../mcp-guide/13-cimd.md#135-2단계-문서를-가져와-믿기까지)에 있다.

실제 배포에서는 공인 인증서를 쓰는 공개 `https` 주소에 문서를 올린다.
그때는 `mcp.cimd.trust-bundle`을 지워 JVM 기본 truststore를 쓰고, `loopback-exception`은 비운다.
이 예외가 준수표의 어느 행에 남는지는 [부록: 명세 준수표](../mcp-guide/reference-compliance.md#mcp-cimd에서-달라지는-행)에 있다.

## 실행

준비물과 `run.sh`가 ollama를 준비하는 방법은 [official의 실행](../mcp-security-authn-official/README.md#실행)과 같다.

```bash
# 저장소 최상위 폴더에서
cd practice/mcp-cimd
./run.sh            # ChatGPT형(기본)
```

Claude형으로 보려면 세 서버를 내린 뒤 인자를 주고 다시 띄운다.

```bash
# practice/mcp-cimd에서
./stop.sh && ./run.sh claude
```

`run.sh`는 포트가 이미 쓰이고 있는 서버를 건너뛴다.
그래서 agent가 떠 있는 채로 `./run.sh claude`를 실행하면 client type이 바뀌지 않는다.
이때 `run.sh`는 끝에 client type을 바꾸지 않았다는 안내를 찍으므로, `./stop.sh` 뒤에 다시 실행한다.
제대로 띄웠다면 끝에 `준비되었습니다. agent의 client type은 claude입니다.`가 찍힌다.

| 주소 | module | 하는 일 |
|---|---|---|
| `http://localhost:9060` | `auth-server` | Authorization Server다. 모든 client를 CIMD 문서로 받는다 |
| `http://localhost:8171/mcp` | `shop-mcp-server` | MCP Server다. tool과 역할 표는 visibility와 같다 |
| `http://localhost:8170` | `shop-agent` | 채팅 화면이다. browser가 여는 곳이다 |
| `https://localhost:8172` | `shop-agent` | client 문서 두 개와 JWKS를 준다. Authorization Server만 가져간다 |

`run.sh`는 `auth-server`(9060) → `shop-mcp-server`(8171) → `shop-agent`(8170, 8172) 순서로 띄운다.
처음 실행하면 세 서버가 모두 뜨기까지 2\~3분 걸린다.
앱의 로그는 `practice/mcp-cimd/logs/<module>.log`에 남는다.
issuer는 `http://localhost:9060`이고, MCP Server의 resource는 `http://localhost:8171/mcp`다.

**`certs/`의 네 파일**

`run.sh`는 서버를 띄우기 전에 `certs/`에 네 파일을 `keytool`로 만든다.
이미 있는 파일은 그대로 두고, 비밀번호는 모두 `changeit`이다.

| 파일 | 쓰는 곳 | 내용 |
|---|---|---|
| `client-metadata-tls.p12` | agent의 8172 서버 | `CN=localhost`, SAN `localhost`·`127.0.0.1`의 self-signed 인증서와 그 private key |
| `client-metadata.crt` | `curl --cacert` | 위 인증서의 PEM이다 |
| `client-metadata-trust.p12` | Authorization Server | 위 인증서 하나만 든 truststore다 |
| `client-signing.p12` | agent의 `private_key_jwt` | RSA 2048 signing key다. public key만 JWKS로 올린다 |

browser는 8172를 열지 않는다.
문서와 JWKS를 가져가는 것은 Authorization Server이고, browser는 8170의 채팅 화면과 9060의 login·consent 화면만 연다.
그래서 OS나 browser가 이 인증서를 믿게 할 필요가 없다.

`certs/`에는 private key가 들어 있으므로 commit하지 않는다(`.gitignore`).
지우면 다음 `run.sh`가 새로 만든다.
JWKS의 `kid`는 public key의 RFC 7638 thumbprint라서, `client-signing.p12`가 같으면 실행마다 같다.

**login과 browser 주소**

login 계정은 둘이고, 비밀번호는 둘 다 `password`다.

| 계정 | MCP Server의 역할 | 보이는 tool |
|---|---|---|
| `user` | 점원(`STAFF`) | 7개 |
| `user2` | 손님(`CUSTOMER`) | `updateStock`을 뺀 6개 |

browser에서는 `http://localhost:8170`을 연다.
`http://127.0.0.1:8170`으로 열면 login이 실패한다.
redirect 주소가 문서에 `http://localhost:8170/login/oauth2/code/authserver`로 고정되어 있어, callback이 `localhost`로 돌아오기 때문이다.
그러면 browser는 `127.0.0.1`에서 받은 agent의 session cookie를 보내지 않고, agent는 login을 시작한 요청을 찾지 못한다.
visibility는 요청 host로 redirect 주소를 만들었지만, 이 practice는 문서에 올린 주소 하나만 쓴다.
다른 계정으로 login하는 방법(시크릿 창, 다시 띄우기)은 [visibility의 실행](../mcp-tool-visibility/README.md#실행)과 같다.

**메모리에 두는 상태**

`auth-server`는 login session, consent, 발급한 token, 가져온 client 문서를 메모리에 둔다.
MCP Server는 장바구니와 재고를, agent는 token·step-up 기록·tool 목록·대화 기억을 메모리에 둔다.
다시 띄우면 이 상태가 모두 처음으로 돌아가고, 파일로 남는 것은 `certs/`뿐이다.

Authorization Server는 문서 응답의 `Cache-Control: max-age=300`에 따라 문서를 5분 동안 cache한다.
그래서 문서를 바꾸고 agent만 다시 띄우면, Authorization Server는 길어야 5분 동안 옛 문서를 쓴다.
`./stop.sh`로 모두 내리면 cache도 사라진다.
JWKS는 cache하지 않고 assertion을 검증할 때마다 가져오므로, signing key를 바꾸면 바로 적용된다.

**멈추기**

```bash
# practice/mcp-cimd에서
./stop.sh
```

`stop.sh`는 8170·8172·8171·9060 포트에서 연결을 기다리는 process를 내린다.
`certs/`는 지우지 않는다.
`./stop.sh --ollama`는 ollama도 함께 내린다.

## 코드 지도

visibility와 같은 클래스는 [visibility README의 코드 지도](../mcp-tool-visibility/README.md#코드-지도)에 있다.
아래는 이 practice에만 있거나 visibility와 다른 클래스다.
`shop-mcp-server`는 이름·포트 같은 설정 값만 달라서 표에 없다.
클래스는 `<module>/src/main/java/dev/starryeye/cimd/<package>/` 아래에 있다.
package는 `auth-server`가 `authserver`, `shop-agent`가 `agent`다.
표에서는 클래스 이름 앞에 그 아래의 package(`cimd`, `security`, `web`, `config`, `discovery`)를 붙였다.

| module | 클래스 | 하는 일 | 안내서 |
|---|---|---|---|
| `auth-server` | `cimd.ClientIdMetadataDocumentProperties` | `mcp.cimd.*` 정책이다. 예외 주소, 문서 크기, 시간 제한, 동시 가져오기 상한, cache 기간, truststore 이름, CIMD client가 요청할 수 있는 scope를 둔다 | [13장 2단계](../mcp-guide/13-cimd.md#135-2단계-문서를-가져와-믿기까지) |
| | `cimd.ClientIdUrlValidator` | 문서 주소와 `jwks_uri`의 형식을 보고, DNS로 푼 주소가 loopback·사설·link-local·multicast이거나 CGNAT·NAT64·6to4 같은 공유·변환 대역이면 거절한다. DNS 조회는 2초까지 기다리고, `loopback-exception`과 scheme·host·port가 모두 같은 주소만 예외로 통과한다 | [13장 2단계](../mcp-guide/13-cimd.md#135-2단계-문서를-가져와-믿기까지), [13장 서버 코드](../mcp-guide/13-cimd.md#1310-authorization-server-코드에서-보기) |
| | `cimd.HostResolver` | host 이름을 IP 주소로 푼다. 테스트가 DNS 없이 주소를 정할 수 있게 interface로 둔다 | [13장 2단계](../mcp-guide/13-cimd.md#135-2단계-문서를-가져와-믿기까지) |
| | `cimd.HttpsClientMetadataFetcher`, `cimd.ClientMetadataHttp`, `cimd.FetchedDocument` | 가져오기 직전에 주소를 다시 검사하고, 문서와 JWKS를 같은 규칙(redirect 없음, `trust-bundle`의 truststore, `200`과 JSON만, 5120 byte, 연결 2초, 본문까지 응답 전체 3초)으로 가져와 `FetchedDocument`에 본문과 `Cache-Control` 값을 담는다. 같은 주소의 동시 요청은 한 번으로 합치고, 동시에 가져오는 수가 `max-concurrent-fetches`(16)를 넘으면 기다리지 않고 실패한다 | [13장 2단계](../mcp-guide/13-cimd.md#135-2단계-문서를-가져와-믿기까지), [13장 서버 코드](../mcp-guide/13-cimd.md#1310-authorization-server-코드에서-보기) |
| | `cimd.ClientMetadataValidator`, `cimd.ClientMetadata` | 문서의 `client_id` 일치, 필수 field, `redirect_uris`의 scheme(`https`나 loopback `http`), 비밀 금지, 인증 방식과 `jwks_uri`·`RS256`을 본다. 통과한 값은 `ClientMetadata`에 담는다 | [13장 2단계](../mcp-guide/13-cimd.md#135-2단계-문서를-가져와-믿기까지) |
| | `cimd.ClientIdMetadataDocumentRegisteredClientRepository` | `findByClientId`·`findById`가 문서 주소를 받아 cache를 보고, 없으면 주소 검사 → 가져오기 → 내용 검사 → `RegisteredClient` 변환 → cache 순서로 처리한다. cache는 `max-age`(상한 1시간, 없으면 5분)를 따르고, `no-store`·`no-cache`와 실패는 cache하지 않으며, 항목은 1000개까지다 | [13장 2단계](../mcp-guide/13-cimd.md#135-2단계-문서를-가져와-믿기까지), [13장 서버 코드](../mcp-guide/13-cimd.md#1310-authorization-server-코드에서-보기) |
| | `cimd.CimdJwtClientAssertionDecoderFactory` | `private_key_jwt`의 assertion을 검증할 때마다 문서와 같은 규칙(`ClientMetadataHttp`)으로 `jwks_uri`의 key를 가져오고, `aud`는 이 서버의 issuer 하나(RFC 7523bis), `jti`는 있어야 하고 `exp`는 5분 안이어야 하며 같은 client가 전에 쓴 `jti`는 거절한다. 쓴 `jti`는 client마다 1,000개, 모두 합쳐 10,000개까지 기억하고, client 상한에 닿으면 그 client의 새 assertion만 거절하며 전체 상한을 넘으면 만료가 가장 이른 기록부터 지운다 | [13장 4단계](../mcp-guide/13-cimd.md#137-4단계-두-인증-방식), [13장 서버 코드](../mcp-guide/13-cimd.md#1310-authorization-server-코드에서-보기) |
| | `security.PublicClientRefreshTokenGenerator` | client의 grant에 `refresh_token`이 있으면 public client에게도 refresh token을 만든다. Spring 기본 generator는 public client에게 만들지 않는다 | [13장 5단계](../mcp-guide/13-cimd.md#138-5단계-public-client의-refresh와-rotation) |
| | `security.PublicClientRefreshTokenAuthenticationConverter` | token endpoint로 온 `grant_type=refresh_token` 요청에 다른 client 인증이 없고 `client_id`가 하나면, public client 인증으로 넘긴다. introspection·revocation 같은 다른 endpoint의 요청과, Spring이 PKCE와 함께 검사하는 authorization code 요청은 맡지 않는다 | [13장 5단계](../mcp-guide/13-cimd.md#138-5단계-public-client의-refresh와-rotation) |
| | `security.PublicClientRefreshTokenAuthenticationProvider` | 문서가 `none`을 선언하고 grant에도 `none`이 기록된 refresh 요청만 인증하고, 이 client의 grant인데 어느 하나라도 아니면 `invalid_client`다. 모르는 token이나 남의 token은 Spring이 `invalid_grant`로 거절한다 | [13장 5단계](../mcp-guide/13-cimd.md#138-5단계-public-client의-refresh와-rotation), [13장 서버 코드](../mcp-guide/13-cimd.md#1310-authorization-server-코드에서-보기) |
| | `security.RefreshTokenReuseDetectingAuthorizationService` | 버린 refresh token의 SHA-256 hash를 만료까지 기억해, 그 token이 refresh 요청으로 다시 오면 grant 전체를 지운다(introspection 같은 조회에서는 지우지 않는다). 메모리 authorization 저장소를 감싸고, refresh token으로 grant를 찾을 때 그 grant의 lock을 10초까지 기다려 잡는다 | [13장 5단계](../mcp-guide/13-cimd.md#138-5단계-public-client의-refresh와-rotation), [13장 서버 코드](../mcp-guide/13-cimd.md#1310-authorization-server-코드에서-보기) |
| | `security.RefreshLockReleasingFilter` | token endpoint 요청이 끝나면 그 요청이 잡은 refresh lock을 푼다. Spring Security filter chain 바깥에 두므로, 검증이나 client 인증에 실패해 grant를 저장하지 않은 요청도 lock을 남기지 않는다 | [13장 5단계](../mcp-guide/13-cimd.md#138-5단계-public-client의-refresh와-rotation) |
| | `security.AuthenticationMethodRecordingAuthorizationService` | grant를 처음 저장할 때 문서의 인증 방식을 grant에 남기고, 그 뒤의 저장에서도 처음 기록을 지킨다 | [13장 4단계](../mcp-guide/13-cimd.md#137-4단계-두-인증-방식) |
| | `security.ConsentableScopeValidator` | `openid` 말고 scope가 하나도 없는 authorization request를 client와 상관없이 `invalid_scope`로 거절한다. 그래서 모든 요청이 consent 화면을 거치거나, 전에 그 화면에서 허락한 scope 안에 든다 | [13장 3단계](../mcp-guide/13-cimd.md#136-3단계-consent-화면) |
| | `security.RedirectUriRequiredValidator` | `redirect_uri`가 없는 authorization request를 `invalid_request`로 거절한다. consent 화면이 돌아갈 주소를 늘 보여 줄 수 있게 한다 | [13장 2단계](../mcp-guide/13-cimd.md#135-2단계-문서를-가져와-믿기까지) |
| | `security.IssuerIdentifyingAuthorizationResponseHandler` | authorization response에 `iss`를 넣는다. 오류는 login한 사용자의 `access_denied`만 redirect하고, 나머지는 `400`과 `text/plain` 본문으로 끝낸다 | [13장 2단계](../mcp-guide/13-cimd.md#135-2단계-문서를-가져와-믿기까지) |
| | `security.PublicClientConsentService` | public client의 consent는 저장하지 않는다. confidential client의 consent에는 문서 `redirect_uris`의 hash를 함께 저장하고, 조회할 때 지금 문서와 다르면 없는 것으로 본다 | [13장 3단계](../mcp-guide/13-cimd.md#136-3단계-consent-화면) |
| | `security.OwnTokenIntrospectionAuthenticationProvider` | introspection endpoint에서 token을 발급받은 client에게만 내용을 알려 준다. 다른 client가 물으면 `{"active":false}`다 | [13장 4단계](../mcp-guide/13-cimd.md#137-4단계-두-인증-방식) |
| | `web.ConsentController` | `GET /oauth2/consent` 화면이다. client 이름, 문서 host, 돌아갈 redirect host, loopback 경고, 새로 허락할 scope 체크박스와 이미 허락한 scope를 보여 준다 | [13장 3단계](../mcp-guide/13-cimd.md#136-3단계-consent-화면) |
| | `config.AuthorizationServerConfig` | 저장소, decoder factory, refresh converter·provider, validator, introspection provider, consent 화면, token generator, lock을 푸는 filter를 연결한다(순서는 표 아래). metadata에 CIMD 표시, 두 인증 방식, `RS256`을 알리고 DPoP·mTLS binding 알림은 지운다 | [13장 1단계](../mcp-guide/13-cimd.md#134-1단계-metadata와-두-문서), [13장 서버 코드](../mcp-guide/13-cimd.md#1310-authorization-server-코드에서-보기) |
| `shop-agent` | `cimd.ClientType` | `CHATGPT`(`/oauth/client.json`, `private_key_jwt`)와 `CLAUDE`(`/oauth/public-client.json`, `none`)다. 문서 주소가 곧 `client_id`다 | [13장 agent 코드](../mcp-guide/13-cimd.md#1311-agent-코드에서-보기) |
| | `cimd.ClientMetadataProperties` | `mcp.client-metadata.*` 설정이다. 문서 주소의 앞부분, 포트, 인증서 bundle, signing key 파일, redirect 주소를 둔다 | [13장 agent 코드](../mcp-guide/13-cimd.md#1311-agent-코드에서-보기) |
| | `cimd.ClientSigningKey` | `certs/client-signing.p12`의 RSA key를 읽는다. `kid`는 RFC 7638 thumbprint다 | [13장 4단계](../mcp-guide/13-cimd.md#137-4단계-두-인증-방식), [13장 agent 코드](../mcp-guide/13-cimd.md#1311-agent-코드에서-보기) |
| | `cimd.ClientMetadataDocuments` | 두 문서와 JWKS의 JSON을 만든다. JWKS에는 public key만 넣는다 | [13장 1단계](../mcp-guide/13-cimd.md#134-1단계-metadata와-두-문서), [13장 agent 코드](../mcp-guide/13-cimd.md#1311-agent-코드에서-보기) |
| | `cimd.ClientMetadataServer` | JDK `HttpsServer`로 `127.0.0.1:8172`에서 세 주소만 `GET`으로 주고, 다른 주소는 `404`, 다른 method는 `405`다. 요청마다 virtual thread로 따로 처리해, handshake만 하고 멈춘 연결 하나가 서버를 막지 못하게 한다 | [13장 1단계](../mcp-guide/13-cimd.md#134-1단계-metadata와-두-문서), [13장 agent 코드](../mcp-guide/13-cimd.md#1311-agent-코드에서-보기) |
| | `cimd.ClientMetadataConfig` | `ClientSigningKey`, `ClientMetadataDocuments`, `ClientMetadataServer` bean을 만든다 | [13장 agent 코드](../mcp-guide/13-cimd.md#1311-agent-코드에서-보기) |
| | `config.McpAuthorizationProperties` | `mcp.authorization.*` 설정이다. `resource-url`과 `client-type`(기본 `chatgpt`)을 두고, `credentials-issuer`는 없다 | [13장 agent 코드](../mcp-guide/13-cimd.md#1311-agent-코드에서-보기) |
| | `discovery.DiscoveredClientRegistrationRepository` | `clientId`는 고른 client type의 문서 주소이고, 인증 방식도 client type을 따르며, client secret이 없다. redirect 주소는 문서와 같은 `http://localhost:8170/login/oauth2/code/authserver`다 | [13장 agent 코드](../mcp-guide/13-cimd.md#1311-agent-코드에서-보기) |
| | `discovery.McpAuthorizationDiscovery` | PRM의 `authorization_servers` 첫 값을 미리 정한 issuer와 비교하지 않고 쓴다. metadata에 `client_id_metadata_document_supported: true`와 고른 인증 방식이 없으면 멈춘다 | [13장 1단계](../mcp-guide/13-cimd.md#134-1단계-metadata와-두-문서), [13장 agent 코드](../mcp-guide/13-cimd.md#1311-agent-코드에서-보기) |
| | `config.McpSecurityConfig` | authorization code와 refresh의 token response client 둘에 `NimbusJwtClientAuthenticationParametersConverter`를 더해, ChatGPT형이면 `ClientSigningKey`로 assertion을 만들고(step-up의 token request도 같다) Claude형이면 `client_id`만 보낸다. assertion의 `aud`는 Spring 기본값인 token endpoint 대신 issuer 하나이고 `typ`은 `client-authentication+jwt`이며, token을 꺼내는 manager는 `SingleFlightAuthorizedClientManager`로 감싼다 | [13장 4단계](../mcp-guide/13-cimd.md#137-4단계-두-인증-방식), [13장 agent 코드](../mcp-guide/13-cimd.md#1311-agent-코드에서-보기) |
| | `security.SingleFlightAuthorizedClientManager` | 같은 사용자의 token 요청을 한 번에 하나씩 처리해, 요청 둘이 동시에 만료된 token을 보아도 refresh는 한 번만 나간다. 쓰는 요청이 없어진 사용자의 lock은 지운다 | [13장 5단계](../mcp-guide/13-cimd.md#138-5단계-public-client의-refresh와-rotation), [13장 agent 코드](../mcp-guide/13-cimd.md#1311-agent-코드에서-보기) |

`config.AuthorizationServerConfig`가 잇는 순서는 다음과 같다.

- authorization request 검증: Spring 기본 검증 → `RedirectUriRequiredValidator` → `ResourceIndicatorValidator` → `ConsentableScopeValidator`
- authorization 저장소: 메모리 저장소를 `AuthenticationMethodRecordingAuthorizationService`가, 그것을 `RefreshTokenReuseDetectingAuthorizationService`가 감싼다.
- token generator: `JwtGenerator`(`ResourceAudienceTokenCustomizer`) → `OAuth2AccessTokenGenerator` → `PublicClientRefreshTokenGenerator`
- client 인증: `JwtClientAssertionAuthenticationProvider`에 `CimdJwtClientAssertionDecoderFactory`를 넣고, refresh converter·provider를 더하며, Spring의 public client provider는 refresh 요청을 건너뛰게 감싼다([13장 서버 코드](../mcp-guide/13-cimd.md#1310-authorization-server-코드에서-보기)).

## 직접 확인할 것

`run.sh`로 띄운 뒤 `practice/mcp-cimd`에서 실행한다.
캡처 스크립트의 줄은 저장소 최상위 폴더에서 실행한다.
browser의 줄은 표의 순서대로 한다.

| 해 볼 것 | 기대 결과 |
|---|---|
| `curl -i --cacert certs/client-metadata.crt https://localhost:8172/oauth/client.json` | `Content-type: application/json`, `Cache-control: max-age=300`과 함께 문서 JSON이 온다. `"client_id":"https://localhost:8172/oauth/client.json"`, `"token_endpoint_auth_method":"private_key_jwt"`, `"jwks_uri":"https://localhost:8172/oauth/jwks.json"`이 있다 |
| 같은 명령으로 `/oauth/public-client.json`과 `/oauth/jwks.json` | Claude형 문서는 `"token_endpoint_auth_method":"none"`이고 `jwks_uri`가 없다. JWKS의 key에는 `kty`·`e`·`use`·`kid`·`alg`·`n`만 있고, private key 값 `d`가 없다 |
| `curl -s http://localhost:9060/.well-known/oauth-authorization-server` | `client_id_metadata_document_supported`가 `true`이고, `token_endpoint_auth_methods_supported`는 `private_key_jwt`와 `none`, `token_endpoint_auth_signing_alg_values_supported`는 `RS256`이다. `dpop_signing_alg_values_supported`는 없다 |
| browser로 `http://localhost:8170`을 열고 `user`/`password`로 login | consent 화면에 `권한 요청: Shop Agent (ChatGPT형)`, `client 문서: localhost:8172`, `허락하면 돌아갈 주소: localhost:8170`과 loopback 경고가 나온다. 선택 항목은 `products:read` 하나다 |
| `grep 'client 문서를' logs/auth-server.log` | 먼저 `client 문서를 가져왔다 (client_id=https://localhost:8172/oauth/client.json, 인증 방식=private_key_jwt, cache=300초)`가 찍힌다. 그 뒤로 `client 문서를 cache에서 꺼낸다 (client_id=https://localhost:8172/oauth/client.json)`가 여러 번 찍힌다 |
| `products:read`를 체크해 제출하고 `노트북 재고 있어?` 보내기 | 재고를 알려 주는 답이 온다. MCP Server 로그에는 `tools/list — 사용자=user, 역할=STAFF, 보인 tool=7/7`이 찍힌다 |
| `p1 재고를 10개로 바꿔 줘` 보내기 | 채팅 아래에 `updateStock을(를) 하려면 products:write 권한이 더 필요합니다. 허용하면 권한을 받은 뒤 질문을 다시 보냅니다.` 카드와 "권한 허용" 버튼이 뜬다 |
| `grep 'scope 부족' logs/shop-mcp-server.log` | `scope 부족 — 사용자=user, client_id=https://localhost:8172/oauth/client.json, tool=updateStock, 필요한 scope=products:write, 가진 scope=[openid, products:read]`다. `client_id`가 문서 주소다 |
| "권한 허용" 누르기 | consent 화면에서 새로 고를 항목은 `products:write` 하나다. `products:read`는 "이미 허락한 권한" 아래에 나온다 |
| `products:write`를 체크해 제출 | 질문이 다시 가고, `상품 p1(게이밍 노트북 15인치)의 재고를 10개로 변경했습니다.`처럼 답한다. MCP Server 로그에는 `updateStock 호출 (productId=p1, quantity=10, 사용자=user)`가 찍힌다 |
| `./stop.sh && ./run.sh claude` 뒤 같은 순서로 login과 재고 변경. step-up에서는 `products:read`와 `products:write`를 모두 체크해 제출 | consent 화면의 제목이 `권한 요청: Shop Agent (Claude형)`이고, 두 host와 경고는 같다. step-up의 consent 화면에는 두 scope가 모두 빈 체크박스로 나오고, 하나라도 빼면 새 token에 scope가 모자라 step-up 카드가 다시 뜬다 |
| `grep 'client 문서를 가져왔다' logs/auth-server.log`와 `grep 'scope 부족' logs/shop-mcp-server.log` | `client 문서를 가져왔다 (client_id=https://localhost:8172/oauth/public-client.json, 인증 방식=none, cache=300초)`가 찍힌다. MCP Server 로그의 `client_id=`도 `https://localhost:8172/oauth/public-client.json`이다 |
| Claude형의 재고 변경이 끝나고 6분 뒤 `노트북 재고 있어?` 보내기 | login 화면 없이 답이 온다. access token(5분)이 끝나, agent가 `client_id`만 보내는 refresh로 새 token을 받았기 때문이다 |
| `./stop.sh && ./run.sh` 직후 `docs/superpowers/captures/mcp-cimd-walkthrough.sh` 실행 | ChatGPT형 token request는 `→ HTTP 200`이고, access token payload의 `client_id`는 `https://localhost:8172/oauth/client.json`이다. Claude형 token request도 `→ HTTP 200`이고 응답에 `refresh_token`이 있다 |
| 같은 출력의 실패 요청 | `jwks_uri`에 없는 key로 만든 assertion, assertion 없는 ChatGPT형 요청, `aud`가 token endpoint인 assertion은 모두 `→ HTTP 401`, `{"error":"invalid_client"}`다. 문서에 없는 redirect 주소는 `Location` 없이 `HTTP/1.1 400`이다 |
| 같은 출력의 refresh 요청 | 두 client type 모두 refresh하면 새 `refresh_token`이 온다. Claude형의 버린 refresh token을 다시 쓰면 `→ HTTP 400`, `{"error":"invalid_grant"}`이고, 그 뒤에는 지금의 refresh token도 `invalid_grant`다. `logs/auth-server.log`에는 `버린 refresh token이 다시 왔다 — grant를 끊는다`가 남는다 |

loopback 경고의 전체 문장은 `이 client는 이 기기의 주소(localhost)로만 돌아갑니다. 같은 기기의 다른 프로그램도 이 client의 이름을 댈 수 있으니, 직접 시작한 요청인지 확인하세요.`다.
두 문서의 redirect 주소가 `http://localhost:8170` 하나뿐이라 두 client type 모두 이 경고가 나온다.

refresh가 일어났는지는 `logs/auth-server.log`에서도 볼 수 있다.
6분 사이에 문서 cache(5분)도 끝나므로, refresh 요청을 받은 Authorization Server는 문서를 다시 가져온다.
그래서 질문을 보낸 시각에 `client 문서를 가져왔다 (client_id=https://localhost:8172/oauth/public-client.json, 인증 방식=none, cache=300초)`가 한 줄 더 찍힌다.

LLM의 답은 `qwen3:8b`의 출력이라 문장이 매번 조금씩 다르다.
기기에 따라 답 하나에 30\~100초가 걸린다.

`mcp-cimd-walkthrough.sh`는 agent 대신 두 client type의 authorization request와 token request를 curl로 보낸다.
ChatGPT형의 assertion은 `openssl`로 `certs/client-signing.p12`의 key를 써서 만든다.
처음 보는 client의 consent 화면이 나와야 하므로, `./stop.sh`와 `./run.sh`로 다시 띄운 직후에 돌린다.
두 문서를 모두 쓰므로 agent의 client type과는 상관없다.

```bash
# 저장소 최상위 폴더에서. 출력의 JWT는 앞 20자, refresh token과 code는 앞 12자만 남는다
docs/superpowers/captures/mcp-cimd-walkthrough.sh > /tmp/cimd-walkthrough.txt
```

같은 방법으로 받은 기록이 [cimd 캡처](../../docs/superpowers/captures/2026-10-09-cimd-walkthrough.txt)에 있다.
ChatGPT와 Claude Code(CLI)의 실제 문서를 curl로 받은 기록은 [실제 제품 문서 캡처](../../docs/superpowers/captures/2026-10-08-cimd-real-documents.txt)에 있다.

## 더 읽을 것

- [안내서 13장 CIMD](../mcp-guide/13-cimd.md): 이 practice로 문서를 가져와 믿기까지의 검사, consent 화면, 두 인증 방식, public client의 refresh rotation을 설명한다.
- [안내서 4장 CIMD](../mcp-guide/04-client-registration.md#45-cimd): client 등록 방식 네 가지 가운데 CIMD의 자리와, Authorization Server가 문서를 믿기 전에 보는 기본 검사를 설명한다.
- [안내서 12장 tool 목록과 권한](../mcp-guide/12-tool-visibility.md): 이 practice가 그대로 쓰는 역할별 tool 목록, step-up, 사용자별 목록 cache를 설명한다.
- [부록: 명세 준수표](../mcp-guide/reference-compliance.md#mcp-cimd에서-달라지는-행): CIMD와 loopback 예외처럼, 이 practice에서 새로 생기거나 visibility와 판정이 달라지는 행을 모았다.
- [mcp-tool-visibility](../mcp-tool-visibility/README.md): 이 practice의 바탕이 된 practice다.
- [mcp-2026-07-28](../mcp-2026-07-28/README.md): 이 practice의 MCP 요청 형식을 2026-07-28로, MCP Java SDK를 2.2로 올린 다음 practice다. 설명은 [안내서 14장](../mcp-guide/14-modern-protocol.md)에 있다.
