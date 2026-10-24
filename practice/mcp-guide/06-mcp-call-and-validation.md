# 6. MCP 호출과 token 검증 — MCP Server는 요청마다 무엇을 확인하나

## 6.1 MCP 요청 검사의 필요성

client는 5장에서 이 MCP Server에서만 통하는 access token을 받았다.
이제 이 token을 붙여 MCP Server를 부른다.

OAuth에서 MCP Server는 resource server 역할을 한다.
주소를 아는 누구나 HTTP 요청을 보낼 수 있으므로, MCP Server는 요청이 올 때마다 누가 누구를 대신해 보냈는지 token으로 확인한다.
1장의 session ID가 있어도 마찬가지다(6.7).

token 검사는 보통의 API 서버가 Bearer token을 검사하는 것과 같다.
MCP는 여기에 전송 단계의 검사를 더한다.
MCP Server는 `Origin` header로 browser를 거친 요청을 거절한다.
official은 `Host`도 보고, 이 검사를 token보다 먼저 한다.

이 장에서는 token을 붙인 요청의 형식을 보고, MCP Server가 그 요청을 어떤 순서로 검사하는지 따라간다.
이어서 agent가 MCP 요청마다 그 요청을 일으킨 사용자의 token을 골라 붙이는 방법을 본다.
마지막으로 서버가 오래 쓰지 않은 session을 끝냈을 때 agent가 호출을 이어 가는 방법을 본다.

## 6.2 Bearer token으로 부르기

client는 access token을 `Authorization` header에 `Bearer` 방식으로 넣는다.
authorization은 HTTP 단계의 일이라서, JSON-RPC 본문은 token이 없을 때와 같다.
아래는 official에서 `tools/list`를 보낸 요청이다.

```http
POST /mcp HTTP/1.1
Host: localhost:8111
Authorization: Bearer eyJraWQiOiI3YWI2Zjg1...
Content-Type: application/json
Accept: application/json, text/event-stream
Mcp-Session-Id: 6226b6d0-cb22-46cb-8d5e-6f91545a879e
MCP-Protocol-Version: 2025-11-25

{"jsonrpc":"2.0","id":2,"method":"tools/list"}
```

`Mcp-Session-Id`와 `MCP-Protocol-Version`은 1장에서 본 header이고, 응답도 1장의 SSE 응답과 같다.
이 장에서는 MCP Server가 이 요청의 `Authorization`과 `Host`를 어떻게 검사하는지 본다.

`Bearer`(가진 사람)라는 이름대로, 이 token은 가진 사람이면 누구든 쓸 수 있다.
그래서 token을 보내는 방법을 좁게 정해 둔다.

- 모든 HTTP 요청에 붙인다. `initialize`, notification, SSE stream을 여는 `GET`, session을 끝내는 `DELETE`에도 붙인다.
- 같은 session 안의 요청이라도 매번 붙인다. MCP Server는 요청마다 token을 따로 검사한다(6.7).
- header로만 보낸다. 주소는 서버와 proxy의 로그에 남기 쉬워서, query string에 넣은 token도 함께 남는다.
- 이 MCP Server의 Authorization Server가 발급한 token만 보낸다. 다른 서비스용으로 받은 token은 보내지 않는다.

## 6.3 MCP Server가 요청을 검사하는 순서: 시퀀스 다이어그램

MCP Server는 요청을 처리하기 전에 네 단계로 검사한다.
한 단계라도 통과하지 못하면 그 자리에서 응답하고, 뒤의 단계로 가지 않는다.

```mermaid
sequenceDiagram
    autonumber
    participant C as MCP client
    participant M as MCP Server
    participant A as Authorization Server
    C->>M: POST /mcp + Authorization: Bearer
    Note over M: 1단계 Origin, Host
    alt Origin이 허용 목록 밖
        M-->>C: 403 Invalid Origin header
    else Host가 허용 목록 밖
        M-->>C: 421 Invalid Host header
    end
    Note over M: 2단계 token
    alt token이 없다
        M-->>C: 401 + resource_metadata
    end
    opt token이 있고, 처음 검증할 때 한 번
        M->>A: GET metadata
        A-->>M: issuer, jwks_uri
        M->>A: GET /oauth2/jwks
        A-->>M: public key (JWK Set)
    end
    alt signature, iss, aud, exp, typ 중 하나가 틀리다
        M-->>C: 401 error=invalid_token
    end
    Note over M: 3단계 MCP-Protocol-Version, 4단계 session
    alt 모르는 버전
        M-->>C: 400 Unsupported MCP-Protocol-Version
    else session ID가 없다
        M-->>C: 400 Session ID missing
    else 끝난 session
        M-->>C: 404 Session not found
    end
    M-->>C: 200 MCP 응답
```

[다이어그램 그림으로 보기](diagrams/06-mcp-call-and-validation-1.png)

| 단계 | 검사 | 하는 곳 | 실패 응답 | 막는 것 |
|---|---|---|---|---|
| 1단계 (2)(3) | `Origin`·`Host` | `McpTransportSecurityFilter` | `403`·`421` | browser를 거쳐 들어오는 요청(DNS rebinding) |
| 2단계 (4)\~(9) | token의 signature·`iss`·`aud`·`exp`·`typ` | Spring Security | `401` | token 없는 요청, 위조·만료된 token, 다른 서버용 token, access token이 아닌 JWT |
| 3단계 (10) | `MCP-Protocol-Version` | `McpProtocolVersionFilter` | `400` | 서버가 모르는 버전을 쓰는 요청 |
| 4단계 (11)(12) | session | Spring AI의 transport | `400`·`404` | 어느 연결인지 알 수 없는 요청 |

명세는 검사 순서를 정하지 않는다.
위 순서는 official의 순서다.
순서가 결과를 바꾸는 곳은 6.4와 6.6에서 본다.
아래 예시는 official practice를 실제로 띄워 받은 응답이다.

## 6.4 1단계: `Origin`·`Host` 검사

**DNS rebinding**

사용자 기기의 `localhost`에서 도는 MCP Server는 같은 기기의 프로그램만 부를 수 있다고 여기기 쉽다.
그런데 사용자의 browser도 같은 기기에서 돈다.
DNS rebinding은 web page가 browser를 거쳐 이런 서버를 부르게 만드는 공격이다.

1. 공격자는 자기 domain의 web page를 `http://evil.example:8111`에 올린다.
2. 사용자가 이 page를 열면, 공격자는 `evil.example`의 DNS record를 `127.0.0.1`로 바꾼다.
3. page의 script가 `http://evil.example:8111/mcp`로 요청을 보낸다. page와 출처(scheme·host·port)가 같아서 browser는 막지 않는다.
4. `evil.example`은 이제 `127.0.0.1`을 가리키므로, 요청은 사용자 기기의 MCP Server로 간다.

browser의 same-origin 정책은 주소의 이름만 보고, 그 이름이 가리키는 IP가 바뀌었는지는 보지 않는다.
그래서 MCP Server가 스스로 요청이 어디서 왔는지 확인한다.
이렇게 들어온 요청은 header 두 개가 정상 요청과 다르다.

| header | 공격 page에서 온 요청 | 정상 client의 요청 |
|---|---|---|
| `Origin` | `http://evil.example:8111` | 없다. agent와 `local-client`는 browser가 아니다 |
| `Host` | `evil.example:8111` | `localhost:8111` 또는 `127.0.0.1:8111` |

official은 두 header를 모두 본다.
허용한 `Origin`이 하나도 없어서, `Origin`이 있는 요청은 모두 `403`이다.
`Host`는 `localhost:8111`과 `127.0.0.1:8111`만 받고, 나머지는 `421`이다.
`Host`까지 보는 이유가 있다.
browser는 같은 출처로 보내는 `GET`에는 `Origin`을 붙이지 않지만, `Host`는 모든 요청에 있다.

`Origin`이 붙은 요청과 `Host`가 다른 요청은 token이 없어도 이렇게 거절된다.
본문은 둘 다 `text/plain`이다.

```text
HTTP/1.1 403  Invalid Origin header
HTTP/1.1 421  Invalid Host header
```

`421 Misdirected Request`는 "이 요청은 이 서버로 올 것이 아니었다"는 뜻이다.

**인증보다 먼저 검사하는 이유**

MCP Java SDK에는 `Origin`·`Host`를 검사하는 `DefaultServerTransportSecurityValidator`가 있다.
그런데 Spring AI의 transport가 기본으로 쓰는 검증기는 아무것도 막지 않고, Spring AI 자동 구성은 SDK의 검증기를 transport에 넣지 않는다.
검증기를 transport에 넣더라도 transport는 Spring Security 뒤에 있어서, token 없는 요청은 `Origin`을 보기 전에 `401`로 끝난다.
`Origin` 검사는 token과 상관없이 browser를 거친 요청을 `403`으로 거절하는 방어다.
그래서 official은 같은 검증기를 `McpTransportSecurityFilter`라는 servlet filter로 감싸 Spring Security 앞에 둔다(6.10).
transport에는 검증기를 넣지 않으므로, official에서 `Origin`·`Host`를 보는 곳은 이 filter 하나뿐이다.

**`127.0.0.1`에만 bind한다**

로컬에서 도는 서버는 모든 네트워크 interface(`0.0.0.0`)가 아니라 `127.0.0.1`에서만 연결을 받게 한다.
그러면 같은 네트워크의 다른 기기는 연결조차 할 수 없다.
official의 세 앱은 `application.yml`에 이렇게 적는다.

```yaml
server:
  address: 127.0.0.1   # 이 기기 안의 연결만 받는다
  port: 8111
```

실행 중에 `lsof -nP -iTCP -sTCP:LISTEN`으로 보면 `127.0.0.1:8111`에서 연결을 기다린다.
bind 주소는 다른 기기에서 오는 요청을 막고, `Origin`·`Host` 검사는 같은 기기의 browser를 거친 요청을 막는다.

**원격에 배포할 때**

official의 값은 MCP Server가 사용자 기기에서 돈다고 보고 정한 것이다.
MCP Server를 `https://mcp.example.com/mcp`로 배포하면, client의 요청은 TLS를 끝내는 reverse proxy를 거쳐 앱에 닿는다.
이때 바꿀 값은 다음과 같다.

| 설정 | official(로컬) | 원격 배포의 예 | 이유 |
|---|---|---|---|
| bind 주소 `server.address` | `127.0.0.1` | proxy가 닿는 interface 주소 | 외부에 무엇을 열지는 proxy와 방화벽이 정한다 |
| 허용 `Host` | `localhost:8111`, `127.0.0.1:8111` | `mcp.example.com` | 앱이 받는 `Host`이고, proxy가 원래 `Host`를 넘기면 client가 부르는 이름이다. client는 기본 포트(443)를 `Host`에 대개 적지 않는다 |
| 허용 `Origin` | 없음 | 없음 | agent와 명령줄 client는 browser가 아니라서 `Origin`을 보내지 않는다 |
| `audiences` | `http://localhost:8111/mcp` | `https://mcp.example.com/mcp` | PRM의 `resource`와 token의 `aud`는 client가 부르는 주소와 같아야 한다(3장) |
| `issuer-uri` | `http://localhost:9010` | `https://auth.example.com` | token의 `iss`와 글자 그대로 같아야 한다(6.5) |

허용 `Host` 목록은 설정 파일에 없다.
`McpTransportConfig`가 `server.port`로 `localhost:<포트>`와 `127.0.0.1:<포트>`를 만들어 SDK 검증기에 넘긴다(6.10).
원격에 배포하려면 이 목록을 배포한 이름으로 바꾼다.

```java
DefaultServerTransportSecurityValidator.builder()
        .allowedHosts(List.of("mcp.example.com"))   // client가 부르는 이름
        .build();                                   // 허용 Origin은 여전히 두지 않는다
```

SDK 검증기는 `Host`를 목록의 값과 글자 그대로 비교한다.
끝이 `:*`인 값(`mcp.example.com:*`)은 그 이름의 모든 포트를 받는다.
목록을 비워 두면 모두 거절하는 것이 아니라, `Host` 검사를 아예 하지 않는다.
filter는 `/mcp`에만 적용되므로, proxy가 내부 주소로 보내는 health check 같은 다른 경로는 이 목록과 상관없다.

**reverse proxy 뒤의 `Host`**

앱이 보는 `Host`는 proxy가 넘겨 준 값이다.
proxy가 원래 `Host`를 그대로 넘기면 앱은 `mcp.example.com`을 보고, 위 목록으로 검사한다.
proxy가 `Host`를 앱의 내부 주소(`10.0.0.5:8111` 같은 값)로 바꿔 보내면, 앱은 client가 쓴 이름을 볼 수 없다.
그때는 앱의 허용 `Host`에 proxy가 보내는 값(`10.0.0.5:8111`)을 넣고, `mcp.example.com`으로 온 요청만 앱에 넘기는 일은 proxy가 맡는다.
허용 `Host`를 그대로 두면 앱은 proxy가 넘긴 모든 요청에 `421`로 답한다.

Spring Boot의 `server.forward-headers-strategy`를 켜면, 앱은 `X-Forwarded-Host`·`X-Forwarded-Proto`나 `Forwarded`로 요청 주소를 다시 계산한다.
이 설정은 `request.getServerName()`·`getScheme()` 같은 값을 바꿀 뿐, `McpTransportSecurityFilter`가 읽는 `Host` header는 바꾸지 않는다.
official의 MCP Server에는 이 설정이 필요 없다.
PRM의 `resource`와 `401`의 `resource_metadata`를 요청 주소가 아니라 `audiences` 설정으로 만들기 때문이다(3장).
요청 주소를 쓰는 다른 기능 때문에 이 설정을 켠다면, forwarded header는 proxy만 넣게 한다.
client가 보낸 forwarded header를 proxy가 지우지 않고 넘기면, 누구나 앱이 계산하는 주소를 바꿀 수 있다.

**모두 허용하지 않는 이유**

내부망에서만 닿는 MCP Server라면, `Host`와 `Origin`을 모두 받을 때 DNS rebinding의 길이 다시 열린다.
회사 내부망에만 연 MCP Server(`http://10.1.2.3:8111`)를 생각해 보자.
사용자의 browser는 내부망 안에 있으므로, 공격 page는 앞의 방법으로 자기 domain을 그 IP에 붙여 요청을 보낼 수 있다.
이 요청의 `Host`는 공격자의 domain이고 `Origin`은 공격 page의 주소라서, 두 목록이 좁으면 token을 보기 전에 거절된다.
`Host`를 모두 받으면, 요청 주소로 링크나 metadata를 만드는 코드가 공격자의 이름을 그대로 알리게 될 수도 있다.
browser에서 MCP Server를 직접 부르는 web page가 있다면, 그 page의 origin(`https://app.example.com`) 하나만 허용 `Origin`에 더한다.
그런 page는 다른 origin의 응답을 읽어야 하므로, MCP Server는 그 origin에 대한 CORS 응답 header도 따로 보내야 한다.

## 6.5 2단계: token 검증

`Origin`·`Host`를 통과한 요청은 Spring Security로 간다.
`Authorization` header가 없으면 3장에서 본 `401`과 `resource_metadata`를 받는다.
token이 있으면 MCP Server는 다섯 가지를 확인한다.
official의 access token은 JWT라서, MCP Server는 Authorization Server에 묻지 않고 token만 보고 판단한다.

| 확인 | 기준 | 확인하지 않으면 |
|---|---|---|
| signature | Authorization Server의 public key로 확인한다. signature가 없는 token(`alg: none`)은 거절한다 | 누구나 payload를 고친 token을 만들 수 있다 |
| `iss` | `issuer-uri`와 글자 그대로 같다 | 믿지 않는 Authorization Server가 발급한 token이 통과한다 |
| `aud` | MCP Server 자신의 이름(`http://localhost:8111/mcp`)이 들어 있다 | 다른 서비스용 token이 통과한다 |
| `exp` | 현재 시각이 `exp` 전이다 | 새어 나간 token을 기한 없이 쓸 수 있다 |
| header의 `typ` | 없거나 `JWT`·`at+jwt`·`application/at+jwt`다 | 다른 용도로 만든 JWT가 access token으로 통과할 수 있다 |

**signature: `jwks_uri`의 public key**

token의 첫 부분(header)을 base64url로 풀면 signature를 확인하는 데 쓰는 정보가 들어 있다.

```json
{"kid":"269c4f65-3590-4e66-948b-4a7844f2efce","alg":"RS256"}
```

`alg`는 signature 방식(RSA와 SHA-256)이고, `kid`는 signature를 만든 key를 가리킨다.
MCP Server는 Authorization Server Metadata의 `jwks_uri`(3장)에서 public key 목록(JWK Set)을 받는다.

```bash
curl http://localhost:9010/oauth2/jwks
```

```json
{
  "keys": [
    {
      "kty": "RSA",
      "e": "AQAB",
      "kid": "269c4f65-3590-4e66-948b-4a7844f2efce",
      "n": "mAjyTA9iW62JDNff2clc..."
    }
  ]
}
```

MCP Server는 header의 `kid`와 같은 key를 골라 signature를 확인한다.
metadata와 public key는 처음 token을 검증할 때 받아 두고, 그 뒤로는 요청마다 Authorization Server에 묻지 않는다.
official의 Authorization Server는 뜰 때마다 key를 새로 만들어서, `kid`와 `n`은 실행마다 다르다.
그래서 위의 `kid`는 6.2 요청의 token과 다른 실행에서 받은 값이다.

**`iss`와 `aud`: Spring 설정 두 줄**

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:9010      # 믿는 issuer. public key도 이 issuer의 metadata에서 찾는다
          audiences: http://localhost:8111/mcp   # token의 aud에 있어야 하는 값
```

`issuer-uri`는 두 가지 일을 한다.
이 주소로 metadata를 찾아 `jwks_uri`를 알아내고, token의 `iss`가 이 값과 같은지 본다.
`audiences`는 token의 `aud`에 이 값이 있는지 본다.
상용 IdP에서 `audiences`에 넣을 값은 MCP Server 주소가 아닐 수 있다([5장](05-authorization-and-token.md#58-token의-내용-access-token과-id-token)).
Spring은 이 줄이 없으면 `aud`를 검사하지 않고, 같은 Authorization Server가 다른 서비스용으로 발급한 token도 받는다.
이 틈을 노리는 공격은 [8장](08-security.md#86-confused-deputy와-다른-mcp-server용-token)에서 본다.

ID token을 Bearer로 보내 보면 `aud` 검사가 하는 일이 보인다.
ID token은 access token과 같은 key로 signature를 만들었고 `iss`도 같다.
다른 것은 `aud`뿐이고, 값은 `official-shop-agent`다(5장).

```http
HTTP/1.1 401
WWW-Authenticate: Bearer error="invalid_token", error_description="An error occurred while attempting to decode the Jwt: The aud claim is not valid", error_uri="https://tools.ietf.org/html/rfc6750#section-3.1", resource_metadata="http://localhost:8111/.well-known/oauth-protected-resource/mcp"
```

| parameter | 뜻 |
|---|---|
| `error="invalid_token"` | token은 있었지만 쓸 수 없다. 만료, 위조, 대상 불일치가 모두 이 값이다 |
| `error_description` | 사람이 읽는 이유. 여기서는 `aud`가 맞지 않는다는 뜻이다 |
| `resource_metadata` | PRM 주소(3장). client는 discovery부터 다시 해 새 token을 받을 수 있다 |

token이 아예 없을 때의 `401`에는 `error`가 없다(3장).
그래서 `401`을 받은 client는 `error`가 있는지로 "token이 필요하다"와 "이 token은 쓸 수 없다"를 가린다.

**`exp`**

official의 access token 수명은 300초다(5장).
`exp`가 지난 token은 `401 invalid_token`이다.
Spring은 두 서버의 시계가 조금 다를 수 있다고 보고, `exp`가 지나고 60초까지는 받아 준다.
agent는 만료가 가까운 token을 refresh token으로 새로 받아 붙인다(6.8).

**`typ`**

`typ`은 token의 첫 부분(header)에 있는 JWT의 종류 표시다.
official의 Authorization Server는 `typ`을 넣지 않고, RFC 9068 형식으로 token을 만드는 Authorization Server는 `at+jwt`를 넣는다(5장).
RFC 9068은 `application/at+jwt`라는 긴 이름도 같은 뜻으로 인정한다.
MCP Server는 이 경우를 모두 받으려고 `typ`이 없거나 `JWT`·`at+jwt`·`application/at+jwt`인 token을 받는다.
그 밖의 값은 `401 invalid_token`이다.

**`403 insufficient_scope`와 step-up**

token은 유효하지만 이 요청에 필요한 scope가 모자라면, MCP Server는 `401`이 아니라 `403`과 `error="insufficient_scope"`, 필요한 `scope`로 답한다.
쓰기 tool에만 쓰기 scope를 요구하는 서버에 조회 scope만 받은 client가 쓰기 tool을 부르면 이렇게 된다.
사용자를 대신하는 client는 그 scope로 다시 authorization을 받아 원래 요청을 다시 보낸다.
이렇게 필요할 때 scope를 넓혀 가는 흐름이 step-up authorization이다.
official의 MCP Server는 tool마다 scope를 두지 않아서 이 `403`을 보내지 않고, 두 client에도 step-up 처리가 없다([준수표](reference-compliance.md)의 16번).
tool별 scope와 step-up은 [10장](10-scope-and-step-up.md)에서 `mcp-security-authz` practice로 직접 본다.

## 6.6 3·4단계: `MCP-Protocol-Version`과 session 검사

token까지 통과한 요청만 MCP의 규칙을 검사받는다.
규칙과 오류 본문은 1장에서 봤으므로, 여기서는 어디서 검사하는지만 본다.

`MCP-Protocol-Version`은 official의 `McpProtocolVersionFilter`가 본다.
모르는 버전이면 `400`이고, header가 없으면 통과시킨다.
session은 Spring AI의 transport가 본다.
`initialize`가 아닌 요청에 `Mcp-Session-Id`가 없으면 `400`, 끝났거나 모르는 session이면 `404`다.
`initialize`에 `Mcp-Session-Id`를 붙이면 이미 연 session을 다시 열려는 요청이라 `400`이다.
session이 끝나는 때와 session 수의 상한은 6.9에서 본다.

`McpProtocolVersionFilter`는 Spring Security 뒤에서 돈다.
그래서 token 없이 `MCP-Protocol-Version: 1999-01-01`을 보내면 `400`이 아니라 `401`을 받는다.
인증되지 않은 요청에는 MCP 규칙의 오류를 알려 주지 않는 셈이다.

## 6.7 session과 사용자

이 절의 session은 1장의 MCP session이고, browser와 agent 사이의 login session이 아니다.
session ID를 알아낸 사람이 그 값을 보내면, 서버는 원래 client와 구별하지 못한다.
이 공격이 session hijacking이다.
official의 MCP Server는 두 가지로 이를 막는다.

- session ID가 있어도 요청마다 token을 검사한다. session ID만 가진 사람은 `401`을 받는다.
- session ID는 SDK가 `UUID.randomUUID()`로 만드는 무작위 값이라 추측할 수 없다.

남는 경우가 하나 있다.
다른 사용자가 자기의 유효한 token과 남의 session ID를 함께 보내면, official은 그 요청을 받는다.
official의 MCP Server는 session을 연 사용자를 기억하지 않기 때문이다.

명세는 session ID를 사용자 정보에 묶어 두기를 권한다.
session 데이터를 `<user_id>:<session_id>` 같은 key로 두고, `user_id`는 token에서 꺼낸다.
그러면 session ID를 알아내도 다른 사용자의 token으로는 그 session을 쓰지 못한다.

이 권고에는 전제가 있다.
MCP 명세의 구조에서 Claude Code(CLI) 같은 host는 MCP Server마다 client를 하나 만든다.
client는 그 MCP Server와 1:1로 연결되고, session도 하나만 연다.
client가 받는 token은 사용자 한 명의 것이므로, client 하나와 그 session은 사용자 한 명의 것이다.
사용자 기기의 앱과 [7장](07-local-client.md)의 `local-client`가 이 구조다.
여러 사용자가 쓰는 서버에서 도는 agent도 token은 사용자별로 보관하므로, 이 구조를 따르려면 MCP client도 사용자별로 둔다.

official의 agent는 이 구조를 따르지 않는다.
Spring AI 자동 구성은 설정한 MCP 연결마다 앱 전체가 함께 쓰는 client 객체(`McpSyncClient`) 하나를 만든다.
이 구성은 앱 하나가 자기 신원 하나로 MCP Server를 부를 때 맞는다.
official은 흐름을 단순하게 보이려고 이 client 하나를 모든 사용자가 같이 쓰게 두었다.
그리고 요청마다 그 요청을 보낸 사용자의 token만 바꿔 붙인다(6.8).
그래서 session은 첫 채팅을 보낸 사용자의 token으로 열리고, 그 뒤의 요청에는 그때그때 채팅한 사용자의 token이 붙는다.
이 session을 처음 연 사용자에 묶으면, 두 번째 사용자부터는 요청이 막힌다.
앱을 끌 때 client가 session을 끝내려고 보내는 `DELETE`에는 붙일 사용자 token이 아예 없다([부록: 준수표](reference-compliance.md)).

사용자마다 client를 두는 agent는 [chat-memory practice](../mcp-security-authn-chat-memory/README.md)에 있다.
그 agent는 사용자마다 MCP client를 따로 열고, MCP Server는 각 session을 처음 연 사용자(token의 `sub`)에 묶는다.
다른 사용자의 token으로 그 session ID를 쓰면 `403`이다.
client를 닫을 때 보내는 `DELETE`에도 그 client 주인의 token이 붙는다.

MCP 2026-07-28에는 protocol 수준의 session이 없어서, official처럼 client 하나에 요청마다 그 사용자의 token을 붙여도 session이 사용자끼리 섞이지 않는다([9장](09-versions.md#97-initialize와-session의-제거-2026-07-28)).
호출 사이의 상태는 서버가 만든 handle로 가리키고, 서버는 handle과 token의 사용자를 함께 확인한다([11장](11-stateless-and-handle.md)).
다만 사용자마다 다른 tool 목록을 공유 client가 cache하면 목록이 사용자끼리 섞일 수 있다([12장](12-tool-visibility.md#127-web-agent-사용자별-tool-목록-cache)).

## 6.8 agent가 token을 붙이는 방법

agent는 여러 사용자가 함께 쓰는 서버다.
MCP 요청마다 그 요청을 일으킨 사용자의 token을 골라 붙여야 한다.
agent 자신의 token은 없다.
붙이는 token은 모두 authorization code로 받은 사용자의 것이고, `sub`는 login한 사용자다.

```mermaid
sequenceDiagram
    autonumber
    participant U as browser
    participant G as shop-agent
    participant P as SecurityMcpTransportContextProvider
    participant T as OAuth2TokenAttachingRequestCustomizer
    participant M as MCP Server
    U->>G: POST /api/chat (login한 사용자)
    Note over G: LLM이 tool을 고른다. reactor thread에서 MCP client가 tools/call을 만든다
    G->>P: get()
    P-->>G: McpTransportContext (Authentication)
    G->>T: customize(요청 builder, context)
    Note over T: 그 사용자의 access token을 찾는다. 만료가 가까우면 refresh
    T-->>G: Authorization: Bearer
    G->>M: POST /mcp + Authorization: Bearer
```

[다이어그램 그림으로 보기](diagrams/06-mcp-call-and-validation-2.png)

| 하는 곳 | 불리는 때 | 하는 일 |
|---|---|---|
| `SecurityMcpTransportContextProvider` | MCP client가 요청을 만들 때 (2)(3) | `SecurityContextHolder`의 `Authentication`을 `McpTransportContext`에 담는다 |
| `OAuth2TokenAttachingRequestCustomizer` | transport가 HTTP 요청을 보내기 직전 (4)(5) | context의 사용자로 access token을 찾아 `Authorization` header를 넣는다 |

두 클래스는 `McpSecurityConfig`가 `McpClientCustomizer` bean으로 Spring AI의 MCP client에 연결한다.

```java
public void customize(HttpRequest.Builder builder, String method, URI endpoint, String body,
                      McpTransportContext context) {
    Object candidate = context.get(SecurityMcpTransportContextProvider.AUTHENTICATION_KEY);
    if (!(candidate instanceof Authentication authentication)) {
        return;                                    // 사용자가 없으면 token 없이 보낸다
    }
    OAuth2AuthorizeRequest authorizeRequest = /* registration "authserver", principal = authentication */;
    // 그 사용자의 access token. 만료가 가까우면 refresh token으로 새로 받는다(5장)
    OAuth2AuthorizedClient authorizedClient = this.authorizedClientManager.authorize(authorizeRequest);
    /* authorizedClient가 없으면 token 없이 보낸다 */
    builder.header(HttpHeaders.AUTHORIZATION,
            "Bearer " + authorizedClient.getAccessToken().getTokenValue());
}
```

agent의 `authorizedClientManager`는 `AuthorizedClientServiceOAuth2AuthorizedClientManager`다.
이 manager는 servlet 요청 없이 `Authentication`만으로 token을 찾는다.
아래에서 볼 reactor thread에는 servlet 요청이 없어서 이 manager를 쓴다.

**reactor thread로 `SecurityContext` 옮기기**

`ChatController`는 LLM의 답을 `stream()`으로 조금씩 보낸다.
이때 LLM 호출과 tool 호출은 요청을 받은 thread가 아니라 reactor의 다른 thread에서 돈다.
`SecurityContextHolder`는 thread마다 값을 따로 두므로, 그 thread에서는 login한 사용자가 보이지 않는다.

```yaml
spring:
  reactor:
    context-propagation: auto   # reactor thread로 SecurityContext를 옮긴다(기본값 limited)
```

`auto`면 Spring Boot가 기동할 때 Reactor의 자동 context 전파를 켠다.
그러면 Reactor는 thread를 바꿀 때 Spring Security의 `SecurityContextHolderThreadLocalAccessor`로 `SecurityContext`를 옮겨 준다.
그래서 context provider가 reactor thread에서도 login한 사용자를 읽는다.

**오류 없이 token만 빠지는 설정 실수**

| 실수 | 결과 |
|---|---|
| `spring.reactor.context-propagation: auto`를 빼거나 `limited`로 둔다 | reactor thread에서 `SecurityContextHolder`가 비어, context provider가 빈 context를 만든다 |
| `spring.ai.mcp.client.type`을 `ASYNC`로 바꾼다 | context provider를 넣는 `McpClientCustomizer<McpClient.SyncSpec>`이 async client에는 적용되지 않는다 |

두 실수 모두 기동할 때 오류가 나지 않는다.
MCP 요청에서 token만 빠지고, MCP Server가 `401`로 답한 뒤에야 알 수 있다.
이 설정이 빠진 경우에는 `practice/mcp-security-authn-official/logs/shop-agent.log`에 DEBUG 줄 하나가 남는다.
`SecurityMcpTransportContextProvider`가 남기는 `인증 없음 — 빈 전송 컨텍스트를 만든다 (토큰이 붙지 않는다)`다.

**`local-client`는 한 번만 붙인다**

`local-client`는 실행하는 동안 token이 하나라서, transport의 기본 요청에 `Authorization` header를 한 번 넣는다([7장](07-local-client.md#78-6단계-mcp-호출--mcpcalls)).

## 6.9 session 만료와 상한

**만료와 상한의 필요성**

Streamable HTTP 서버는 `initialize`를 받을 때마다 session을 하나 만들어 메모리에 둔다.
client가 `DELETE`로 끝내 주면 서버는 그 자원을 바로 정리하지만, 그러지 않고 사라지는 client도 있다.
끊긴 연결, 꺼진 기기, 앱 종료 때 `DELETE`를 보내지 못한 agent(6.7)가 그런 예다.
이런 session을 지우지 않으면 서버를 다시 띄울 때까지 메모리에 쌓인다.
또 token을 가진 사용자는 `initialize`를 되풀이해 session을 얼마든지 만들 수 있다.

명세는 서버가 session을 언제든 끝낼 수 있게 한다.
끝낸 session의 ID로 요청이 오면 서버는 `404`로 답하고, `404`를 받은 client는 session ID 없이 `initialize`를 보내 새 session을 연다(1.9).
그래서 official의 MCP Server는 오래 쓰지 않은 session을 끝내고, 한꺼번에 들고 있는 session 수에도 상한을 둔다.
session 수의 상한은 명세가 정한 것이 아니라, 서버가 자기 메모리를 지키려고 둔 것이다.

| 설정 | 기본값 | 하는 일 |
|---|---|---|
| `mcp.limits.session-idle-timeout` | `30m` | 이 시간 동안 요청이 없던 session을 지운다. 지운 session의 ID로 온 요청은 `404`다 |
| `mcp.limits.max-sessions` | `1000` | 서버가 한꺼번에 들고 있는 session 수의 상한이다. 넘으면 새 `initialize`가 `503`이다 |

`404`의 본문은 1.9에서 본 `Session not found`다.
`503`의 본문은 `id` 없는 JSON-RPC 오류 `-32603`과 `Max number of sessions reached`다.

**만료 시각을 재는 방법**

transport는 session마다 그 session ID가 붙은 POST나 GET을 마지막으로 받은 시각을 적어 둔다.
그리고 `session-idle-timeout` 간격으로 돌면서, 마지막 요청 뒤 그 시간이 지난 session을 지운다.
검사가 주기적이라서, 쉬는 session은 정한 시간의 1\~2배 사이에 사라진다.
열어 둔 GET stream은 요청으로 세지 않는다.
그래서 agent가 GET stream을 열어 두어도, tool 호출 없이 30분\~1시간이 지나면 그 session은 끝난다.

**상한은 서버 전체의 수다**

`max-sessions`는 사용자마다 따로 세지 않고, 서버가 가진 session을 모두 센다.
그래서 사용자 한 명이 `initialize`를 되풀이해 상한을 채우면, 다른 사용자의 `initialize`도 `503`을 받는다.
그 session들이 만료로 지워져야 자리가 다시 빈다.
상한은 서버 메모리가 바닥나는 것을 막을 뿐, 다른 사용자가 새 session을 열지 못하는 피해는 막지 못한다.
공격자가 session을 계속 쓰거나 지워진 자리를 다시 채우면 `503`도 계속된다.
사용자마다 상한을 두려면 token의 `sub`로 session을 세는 filter를 따로 만든다.

**만료 뒤 agent의 첫 tool 호출**

official의 agent에서 tool 호출이 없는 사이 session이 만료되면, 만료 뒤 첫 tool 호출은 끝난 session으로 나가 `404`를 받는다.
MCP Java SDK client는 `404`를 받으면 그 session을 버리고, 뒤에서 `initialize`를 다시 보낸다.
하지만 실패한 tool 호출은 다시 보내지 않으므로, 그대로 두면 사용자는 오래 쉰 뒤 첫 질문에서 tool 오류를 받는다.
그래서 agent는 MCP tool을 `SessionRetryingToolCallback`으로 감싸, session이 없어 실패한 호출을 같은 입력으로 다시 보낸다.

여러 사용자가 같이 쓰는 client에는 문제가 하나 더 있다.
SDK가 뒤에서 보내는 `initialize`는 채팅 요청 밖에서 나가서, transport context에 사용자가 없다.
그래서 6.8의 customizer는 token을 붙이지 못하고, 이 `initialize`는 `401`을 받는다.
다시 보낸 호출이 이 `initialize`가 끝나기 전에 나가면, 그 `initialize`를 기다렸다가 같은 `401`로 끝난다.
`SessionRetryingToolCallback`은 이 실패도 알아보고 한 번 더 부르고, 그 호출이 채팅한 사용자의 token으로 새 session을 연다.

```mermaid
sequenceDiagram
    autonumber
    participant R as SessionRetryingToolCallback
    participant C as MCP client (SDK)
    participant M as MCP Server
    R->>C: getStock 호출
    C->>M: POST tools/call + token + 옛 session ID
    M-->>C: 404 Session not found
    C-->>R: 실패 (session 없음)
    C->>M: DELETE 옛 session (token 없음)
    M-->>C: 401
    C->>M: POST initialize (token 없음)
    M-->>C: 401
    R->>C: 같은 입력으로 다시 부른다
    C->>M: POST initialize + 사용자의 token
    M-->>C: 200 + 새 Mcp-Session-Id
    C->>M: POST tools/call + token + 새 session ID
    M-->>C: 200 tool 결과
    C-->>R: tool 결과
```

[다이어그램 그림으로 보기](diagrams/06-mcp-call-and-validation-3.png)

(5)\~(8)은 SDK가 뒤에서 보내는 요청이라, 다시 부른 호출(9)과 순서가 섞일 수 있다.
`notifications/initialized`는 줄여 그렸다.

만료 뒤 첫 tool 호출 때마다 MCP Server는 token 없는 `DELETE`와 `initialize`를 하나씩 받아 `401`로 답한다.
agent 로그에도 처음 실패한 호출 때문에 SDK와 Spring AI가 남기는 `ERROR` 줄과 stack trace가 있다.
그 뒤에 `MCP session이 없어 같은 tool 호출을 다시 보낸다 (tool=getStock)`가 찍히고 다시 부른 호출이 성공하면, 사용자에게는 정상 답이 간다.
token 없는 두 요청은 앱 종료 때의 `DELETE`(6.7)와 같은 이유로 생기고, [부록: 준수표](reference-compliance.md#남은-위반)에 남은 위반으로 적었다.

**다시 보내지 않는 실패와 한계**

다시 보내는 실패는 tool이 실행되지 않았다고 볼 수 있는 경우뿐이다.
timeout, 연결 오류, `isError` 결과는 MCP Server가 이미 tool을 실행했을 수 있어서 다시 보내지 않는다.
`403`과 token을 붙인 요청의 `401`은 다시 보내도 같은 답이 오므로 그대로 올린다.
다만 SDK는 늦게 온 `404`가 지금 session의 것인지 옛 session의 것인지 가리지 않고 지금 session을 닫는다.
그래서 만료 직후 여러 사용자의 호출 셋 이상이 겹치면, 이미 실행된 호출이 한 번 더 갈 수 있다.
official의 tool은 조회뿐이라 두 번 실행되어도 결과가 같다.

**chat-memory의 agent**

사용자마다 client를 두는 [chat-memory](../mcp-security-authn-chat-memory/README.md)의 agent는 client 주인의 token을 붙이므로, SDK가 뒤에서 보내는 `initialize`도 성공한다.
그래도 SDK는 실패한 호출을 다시 보내지 않으므로, 같은 이름의 감싸기를 쓴다.
chat-memory의 MCP Server는 session 묶음의 만료를 transport의 만료와 같은 값(기본 1시간)으로 맞춘다.

## 6.10 official 코드에서 보기

**`SecurityConfig`: resource server**

`application.yml`의 설정은 6.4(`server.address`)와 6.5(`issuer-uri`, `audiences`)에서 봤다.
`shop-mcp-server`의 `SecurityConfig`는 모든 요청에 token을 요구하고, token을 JWT로 검증한다.

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http /* , issuer, properties */) throws Exception {
    return http
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())   // session ID가 있어도 token을 본다
            .oauth2ResourceServer(resourceServer -> resourceServer
                    .jwt(Customizer.withDefaults())                             // signature, iss, aud, exp, typ
                    /* 401의 resource_metadata와 PRM (3장) */)
            .csrf(csrf -> csrf.disable())                                       // cookie가 아니라 token으로만 인증한다
            .build();
}
```

`jwt(Customizer.withDefaults())`는 Spring Boot가 `issuer-uri`와 `audiences`로 만든 `JwtDecoder`를 쓴다.
이 decoder는 첫 token 검증 때 metadata를 읽으므로, Authorization Server가 꺼져 있어도 MCP Server는 뜨고 token이 붙은 첫 요청에서 실패한다.

`typ`의 검증은 `JwtTypeValidator` bean이 정한다.
Spring Boot는 이 bean을 `issuer-uri`와 `audiences`의 검증기와 함께 decoder에 넣는다.

```java
@Bean
public JwtTypeValidator accessTokenTypeValidator() {
    JwtTypeValidator validator = new JwtTypeValidator("JWT", "at+jwt", "application/at+jwt");
    validator.setAllowEmpty(true);                                  // typ이 없는 token도 받는다
    return validator;
}
```

**`McpTransportConfig`: filter 두 개의 자리**

`McpTransportConfig`는 `/mcp`에만 적용되는 servlet filter 두 개를 등록한다.

```java
@Bean
public FilterRegistrationBean<McpTransportSecurityFilter> mcpTransportSecurityFilter(
        McpServerStreamableHttpProperties properties, @Value("${server.port}") int port) {
    DefaultServerTransportSecurityValidator validator = DefaultServerTransportSecurityValidator.builder()
            .allowedHosts(List.of("localhost:" + port, "127.0.0.1:" + port))   // 허용 Host. 허용 Origin은 두지 않는다
            .build();
    FilterRegistrationBean<McpTransportSecurityFilter> registration =
            new FilterRegistrationBean<>(new McpTransportSecurityFilter(validator));
    registration.addUrlPatterns(properties.getMcpEndpoint());                  // /mcp에만
    registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1);  // Spring Security 바로 앞
    return registration;
}
```

`SecurityFilterProperties.DEFAULT_FILTER_ORDER`는 Spring Security filter chain의 순서라서, 1을 빼면 그보다 먼저 돈다.
`mcpProtocolVersionFilter` bean은 순서를 정하지 않는다.
`FilterRegistrationBean`의 기본 순서는 가장 뒤라서, 이 filter는 Spring Security 다음에 돈다.

**`McpProtocolVersionFilter`: 3단계**

```java
public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
        throws IOException, ServletException {
    /* ... */
    String version = request.getHeader(HEADER_NAME);                // MCP-Protocol-Version
    if (version != null && !SUPPORTED_VERSIONS.contains(version)) {  // 없으면 통과, 모르는 값이면 거절
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        /* {"jsonrpc":"2.0","id":null,"error":{"code":-32600,"message":"Unsupported MCP-Protocol-Version: ..."}} */
        return;
    }
    chain.doFilter(servletRequest, servletResponse);
}
```

`SUPPORTED_VERSIONS`는 SDK가 아는 네 버전(`2024-11-05`, `2025-03-26`, `2025-06-18`, `2025-11-25`)이다.

**`McpTransportConfig`: session 상한·만료와 오류 본문**

Spring AI에는 session 상한과 만료를 정하는 설정이 없고, transport의 builder에만 있다.
그래서 `McpTransportConfig`는 자동 구성 대신 transport bean을 직접 만든다.
자동 구성의 transport bean은 같은 타입의 bean이 없을 때만 만들어진다.

```java
@Bean
public WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
        @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper, McpServerStreamableHttpProperties properties,
        McpLimitProperties limits) {
    return WebMvcStreamableServerTransportProvider.builder()
            .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
            .mcpEndpoint(properties.getMcpEndpoint())
            .keepAliveInterval(properties.getKeepAliveInterval())
            .disallowDelete(properties.isDisallowDelete())
            .maxSessions(limits.maxSessions())                   // mcp.limits.max-sessions
            .sessionIdleTimeout(limits.sessionIdleTimeout())     // mcp.limits.session-idle-timeout
            .build();
}

@Bean
public RouterFunction<ServerResponse> webMvcStreamableServerRouterFunction(
        WebMvcStreamableServerTransportProvider transport,
        @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper) {
    return transport.getRouterFunction().filter(new TransportErrorResponseFilter(jsonMapper));
}
```

앞의 네 builder 호출은 자동 구성과 같고, 6.4에서 본 대로 검증기는 넣지 않는다.
이 bean이 없으면 transport의 상한은 100,000이고 만료가 없어서, 한 번 찬 상한은 다시 비지 않는다.
두 값은 `McpLimitProperties`가 `mcp.limits` 아래에서 읽고, 0 이하면 앱이 뜨지 않는다.

router bean은 transport의 오류 응답에서 Java 예외 정보를 뺀다.
Spring AI의 transport는 처리하지 못한 요청에 SDK의 예외 객체 `McpError`를 본문으로 쓰고, Spring MVC는 그 객체를 stack trace까지 JSON으로 만든다.
`TransportErrorResponseFilter`는 상태 코드는 그대로 두고, 본문을 `code`와 `message`만 남긴 `id` 없는 JSON-RPC 오류로 바꾼다(1.9의 두 본문).
자동 구성은 이름이 `webMvcStreamableServerRouterFunction`인 bean이 없을 때만 router를 만든다.
그래서 이 bean의 이름을 바꾸면 filter 없는 자동 구성의 router도 함께 등록되고, 어느 router가 답할지는 bean 순서에 달린다.

**agent의 `ChatClientConfig`: 다시 보내는 tool**

```java
toolCallbackProvider.ifAvailable(provider -> configured.defaultTools(SessionRetryingToolCallback.wrap(provider)));
```

`wrap`은 provider의 tool마다 `SessionRetryingToolCallback`을 씌운다.
provider는 처음 불릴 때 `initialize`를 한다.
tool 목록을 bean을 만들 때가 아니라 부를 때마다 받는 것은, 이 `initialize`를 login한 사용자의 채팅 안으로 미루기 위해서다.

## 6.11 직접 해 보기

```bash
cd practice/mcp-security-authn-official
./run.sh
```

1단계와 검사 순서는 token 없이 확인할 수 있다.

```bash
INIT='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'
H=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')

# 1단계: token이 없어도 Origin이 있으면 403, Host가 다르면 421
curl -i -X POST http://localhost:8111/mcp "${H[@]}" -H 'Origin: http://evil.example' -d "$INIT"
curl -i -X POST http://localhost:8111/mcp "${H[@]}" -H 'Host: evil.example:8111' -d "$INIT"

# 2단계: JWT가 아닌 token은 401 invalid_token
curl -i -X POST http://localhost:8111/mcp "${H[@]}" -H 'Authorization: Bearer not-a-jwt' -d "$INIT"

# 순서: token이 없으면 모르는 버전을 보내도 400이 아니라 401
curl -i -X POST http://localhost:8111/mcp "${H[@]}" -H 'MCP-Protocol-Version: 1999-01-01' -d "$INIT"

# signature를 확인할 public key
curl http://localhost:9010/oauth2/jwks
```

token이 필요한 요청은 캡처 스크립트로 본다.
`docs/superpowers/captures/mcp-authorization-walkthrough.sh` 출력의 12\~15단계가 ID token의 `401`, `Origin`의 `403`, session ID가 없는 `400`, 모르는 버전의 `400`이다.
`Host`의 `421`과 끝난 session의 `404`는 `docs/superpowers/captures/mcp-authorization-supplement.sh`에 있다.
각 단계의 curl 명령은 스크립트 안에 있어서, token이 있다면 한 단계씩 직접 보내 볼 수 있다.

browser로 `http://localhost:8110`에 들어가 `user`/`password`로 login하고 채팅하면, agent가 token을 붙이는 것도 볼 수 있다.
`practice/mcp-security-authn-official/logs/shop-agent.log`에 `토큰을 헤더에 붙였다 (사용자=user)`가 남는다.
`practice/mcp-security-authn-official/logs/shop-mcp-server.log`의 tool 호출 줄에는 MCP Server가 token에서 읽은 사용자(`사용자=user`)가 찍힌다.

session 만료는 기다리는 시간을 줄여 보면 된다.
`shop-mcp-server/src/main/resources/application.yml`의 `mcp.limits.session-idle-timeout`을 `1m`으로 바꾸고 `./stop.sh`와 `./run.sh`로 다시 띄운다.
채팅으로 재고를 한 번 묻고, 2분 넘게 기다렸다가 다시 묻는다.
두 번째 답도 정상으로 오고, `shop-agent.log`에는 `MCP session이 없어 같은 tool 호출을 다시 보낸다`가 남는다.
확인한 뒤에는 설정을 `30m`으로 되돌린다.

## 6.12 정리

- client는 모든 HTTP 요청의 `Authorization` header에 `Bearer`로 access token을 넣는다. session 안의 요청도 매번 넣는다.
- official의 MCP Server는 `Origin`·`Host` → token → `MCP-Protocol-Version` → session 순서로 검사한다. `Origin`·`Host`는 token이 없어도 `403`·`421`이다.
- 원격에 배포하면 허용 `Host`를 앱이 실제로 받는 `Host`로 바꾸고(proxy가 그대로 넘기면 client가 부르는 이름), 허용 `Origin`은 browser에서 직접 부르는 page가 있을 때만 그 origin을 더한다.
- token은 `jwks_uri`의 public key로 signature를, `issuer-uri`로 `iss`를, `audiences`로 `aud`를 확인한다. 다른 `aud`의 token은 `401 invalid_token`이다.
- header의 `typ`은 없거나 `JWT`·`at+jwt`·`application/at+jwt`여야 한다. 그 밖의 값은 `401 invalid_token`이다.
- session ID는 인증을 대신하지 않는다. official은 요청마다 token을 보지만 session을 사용자에 묶지는 않고, chat-memory practice는 묶는다.
- agent는 요청마다 그 사용자의 token을 찾아 붙인다. reactor thread로 `SecurityContext`를 옮기는 설정이 빠지면 오류 없이 token만 빠진다.
- MCP Server는 오래 쓰지 않은 session을 끝내고 그 ID에 `404`로 답하며, 서버 전체의 session 수에 상한을 둔다. agent는 끝난 session 때문에 실패한 tool 호출을 다시 보내, 사용자의 token으로 새 session을 연다.

## 6.13 명세 근거

| 내용 | 명세 | 요구 수준 |
|---|---|---|
| client는 같은 session이라도 모든 HTTP 요청의 `Authorization` header에 access token을 넣고, query string에는 넣지 않는다. 그 MCP Server의 Authorization Server가 발급한 token만 보낸다 | [MCP 2025-11-25 Authorization — Token Requirements](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#token-requirements), [Token Handling](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#token-handling), [OAuth 2.1 §5.1.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-5.1.1) | MUST, MUST NOT |
| 서버는 모든 연결의 `Origin`을 검증하고, 있는데 유효하지 않으면 `403`으로 답한다. 로컬 서버는 `127.0.0.1`에만 bind하고, 모든 연결에 인증을 둔다 | [MCP 2025-11-25 Transports — Security Warning](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#security-warning), [MCP 2026-07-28 Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http) | MUST, SHOULD |
| 이 서버로 올 요청이 아니면 `421 Misdirected Request`로 답할 수 있다 | [RFC 9110 §15.5.20](https://www.rfc-editor.org/rfc/rfc9110#section-15.5.20) | — |
| resource server를 포함해 OAuth에 쓰는 주소는 `https`이고, loopback redirect URI만 `http`를 쓸 수 있다 | [OAuth 2.1 §1.5](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-1.5), [MCP 2025-11-25 Authorization — Communication Security](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#communication-security) | MUST, MAY |
| `Forwarded` header는 요청이 거쳐 온 누구나 바꿀 수 있으므로, 믿는 proxy가 넣은 값만 믿는다 | [RFC 7239 §8.1](https://www.rfc-editor.org/rfc/rfc7239#section-8.1) | — |
| MCP Server는 요청을 처리하기 전에 token을 검증하고, 자신을 audience로 발급된 token만 받는다. 유효하지 않거나 만료된 token에는 `401`로 답한다 | [MCP 2025-11-25 Authorization — Token Handling](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#token-handling), [Access Token Privilege Restriction](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#access-token-privilege-restriction), [OAuth 2.1 §5.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-5.2), [RFC 8707 §2](https://www.rfc-editor.org/rfc/rfc8707#section-2) | MUST, MUST NOT |
| RFC 9068이 정한 JWT access token 확인 가운데 이 장에서 본 것은 `typ`, `iss` 일치, `aud`에 자신이 있는지, signature(`alg: none` 거절), `exp`다. 실패하면 `invalid_token`이고 `401`로 답하며, Authorization Server는 metadata의 `jwks_uri`와 `issuer`로 key와 `iss` 값을 알린다 | [RFC 9068 §4](https://www.rfc-editor.org/rfc/rfc9068#section-4), [RFC 6750 §3.1](https://www.rfc-editor.org/rfc/rfc6750#section-3.1) | MUST, SHOULD |
| token의 scope가 모자라면 MCP Server는 `403`과 `WWW-Authenticate`의 `error="insufficient_scope"`·`scope`·`resource_metadata`로 답하고, `scope`에는 이 요청에 필요한 scope를 넣는다. 사용자를 대신하는 client는 그 scope로 step-up authorization을 하고(`client_credentials`처럼 자기 권한으로 동작하는 client는 바로 멈춰도 된다), 다시 시도하는 횟수를 제한한다 | [MCP 2025-11-25 Authorization — Scope Challenge Handling](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#scope-challenge-handling), [RFC 6750 §3.1](https://www.rfc-editor.org/rfc/rfc6750#section-3.1) | SHOULD, MAY |
| 지원하지 않는 `MCP-Protocol-Version`에는 `400`, session ID가 없으면 `400`, 끝난 session에는 `404`로 답한다 | [MCP 2025-11-25 Transports — Protocol Version Header](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#protocol-version-header), [Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management) | MUST, SHOULD |
| 서버는 session을 언제든 끝낼 수 있고, 끝낸 session의 ID가 붙은 요청에는 `404`로 답한다. `404`를 받은 client는 session ID 없이 새 `initialize`를 보내 session을 다시 시작한다 | [MCP 2025-11-25 Transports — Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management) | MAY, MUST |
| host는 MCP Server마다 client를 하나 만들고, client는 그 서버와 1:1로 연결되어 session을 하나 연다 | [MCP 2025-11-25 Architecture — Clients](https://modelcontextprotocol.io/specification/2025-11-25/architecture#clients) | — |
| authorization을 구현한 MCP Server는 모든 요청을 검증하고 session을 인증에 쓰지 않는다. session ID는 추측할 수 없는 값으로 만들고, 사용자 정보에 묶는다 | [MCP 2025-11-25 Security Best Practices — Session Hijacking](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices#session-hijacking) | MUST, MUST NOT, SHOULD |
| 2026-07-28은 protocol 수준의 session과 `Mcp-Session-Id`를 없앤다 | [MCP 2026-07-28 Changelog](https://modelcontextprotocol.io/specification/2026-07-28/changelog), [Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http) | — |

[← 5장](05-authorization-and-token.md) · [목차](README.md) · [7장 →](07-local-client.md)
