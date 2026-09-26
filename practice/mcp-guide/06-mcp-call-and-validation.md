# 6. MCP 호출과 token 검증 — MCP Server는 요청마다 무엇을 확인하나

## 6.1 MCP 요청 검사의 필요성

client는 5장에서 이 MCP Server에서만 통하는 access token을 받았다.
이제 이 token을 붙여 MCP Server를 부른다.

OAuth에서 MCP Server는 resource server 역할을 한다.
주소를 아는 누구나 HTTP 요청을 보낼 수 있으므로, MCP Server는 요청이 올 때마다 누가 누구를 대신해 보냈는지 token으로 확인한다.
1장의 session ID가 있어도 마찬가지다.
session ID는 어느 연결인지 가리킬 뿐, 보낸 사람을 증명하지 않는다.

token 검사는 보통의 API 서버가 Bearer token을 검사하는 것과 같다.
MCP는 여기에 전송 단계의 검사를 더한다.
MCP Server는 `Origin` header로 browser를 거친 요청을 거절한다.
official은 `Host`도 보고, 이 검사를 token보다 먼저 한다.

이 장에서는 token을 붙인 요청의 형식을 보고, MCP Server가 그 요청을 어떤 순서로 검사하는지 따라간다.
마지막으로 agent가 MCP 요청마다 그 요청을 일으킨 사용자의 token을 골라 붙이는 방법을 본다.

## 6.2 Bearer token으로 부르기

client는 access token을 `Authorization` header에 `Bearer` 방식으로 넣는다.
authorization은 HTTP 단계의 일이라서, JSON-RPC 본문은 token이 없을 때와 같다.
아래는 official에서 `tools/list`를 보낸 요청이다.

```http
POST /mcp HTTP/1.1
Host: localhost:8111
Authorization: Bearer eyJraWQiOiJlZDY1ZWFl...
Content-Type: application/json
Accept: application/json, text/event-stream
Mcp-Session-Id: 7c324e98-8854-4b88-9243-7a97e94fd80a
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
    alt signature, iss, aud, exp 중 하나가 틀리다
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
| 2단계 (4)~(9) | token의 signature·`iss`·`aud`·`exp` | Spring Security | `401` | token 없는 요청, 위조·만료된 token, 다른 서버용 token |
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

```http
HTTP/1.1 403
Content-Type: text/plain;charset=UTF-8

Invalid Origin header
```

```http
HTTP/1.1 421
Content-Type: text/plain;charset=UTF-8

Invalid Host header
```

`421 Misdirected Request`는 "이 요청은 이 서버로 올 것이 아니었다"는 뜻이다.

**인증보다 먼저 검사하는 이유**

MCP Java SDK에는 `Origin`·`Host`를 검사하는 `DefaultServerTransportSecurityValidator`가 있다.
SDK transport는 이 검증기를 받아 쓸 수 있지만, Spring AI 자동 구성은 transport에 검증기를 넣지 않는다.
넣더라도 transport는 Spring Security를 지난 요청만 받아서, token 없는 요청은 `Origin`을 보기 전에 `401`로 끝난다.
`Origin` 검사는 token과 상관없이 browser를 거친 요청을 `403`으로 거절하는 방어다.
그래서 official은 같은 검증기를 `McpTransportSecurityFilter`라는 servlet filter로 감싸 Spring Security 앞에 둔다(6.9).

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

## 6.5 2단계: token 검증

`Origin`·`Host`를 통과한 요청은 Spring Security로 간다.
`Authorization` header가 없으면 3장에서 본 `401`과 `resource_metadata`를 받는다.
token이 있으면 MCP Server는 네 가지를 확인한다.
official의 access token은 JWT라서, MCP Server는 Authorization Server에 묻지 않고 token만 보고 판단한다.

| 확인 | 기준 | 확인하지 않으면 |
|---|---|---|
| signature | Authorization Server의 public key로 확인한다. signature가 없는 token(`alg: none`)은 거절한다 | 누구나 payload를 고친 token을 만들 수 있다 |
| `iss` | `issuer-uri`와 글자 그대로 같다 | 믿지 않는 Authorization Server가 발급한 token이 통과한다 |
| `aud` | MCP Server 자신의 이름(`http://localhost:8111/mcp`)이 들어 있다 | 다른 서비스용 token이 통과한다 |
| `exp` | 현재 시각이 `exp` 전이다 | 새어 나간 token을 기한 없이 쓸 수 있다 |

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
Spring은 이 줄이 없으면 `aud`를 검사하지 않고, 같은 Authorization Server가 다른 서비스용으로 발급한 token도 받는다.
그러면 사용자가 다른 MCP Server에 준 token을 그 서버가 이 MCP Server에 들고 와도 통과한다.

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
token은 쓸 수 있지만 scope가 모자란 경우는 `401`이 아니라 `403`으로 온다(아래의 step-up).

**`exp`**

official의 access token 수명은 300초다(5장).
`exp`가 지난 token은 `401 invalid_token`이다.
Spring은 두 서버의 시계가 조금 다를 수 있다고 보고, `exp`가 지나고 60초까지는 받아 준다.
agent는 만료가 가까운 token을 refresh token으로 새로 받아 붙인다(6.8).

**`403 insufficient_scope`와 step-up**

MCP Server는 tool마다 다른 권한을 요구할 수 있다.
읽기 tool은 `files:read`로 충분하고, 쓰기 tool에는 `files:write`가 더 필요한 식이다.
client는 처음에 기본 기능에 필요한 scope만 받으므로(5장), 쓰기 tool을 부르면 token은 유효해도 scope가 모자랄 수 있다.
이때 MCP Server는 `403`으로 답하고, 이 요청에 필요한 scope를 `WWW-Authenticate` header에 적는다.
아래는 MCP 명세의 예시를 줄인 것이다.

```http
HTTP/1.1 403 Forbidden
WWW-Authenticate: Bearer error="insufficient_scope", scope="files:read files:write", resource_metadata="https://mcp.example.com/.well-known/oauth-protected-resource"
```

`scope`에는 새로 필요한 scope와 함께, 이미 받은 scope 가운데 계속 필요한 것도 적는다.
client가 새 token을 받으면서 원래 있던 권한을 잃지 않게 하기 위해서다.
사용자를 대신하는 client는 이 `scope`로 authorization request를 다시 보내 새 token을 받고, 원래 요청을 다시 보낸다.
이렇게 필요할 때 scope를 넓혀 가는 흐름이 step-up authorization이다.
같은 요청이 계속 실패하지 않도록, client는 다시 시도하는 횟수를 제한한다.

official의 MCP Server는 인증된 요청에 모든 tool을 허용하고, tool마다 scope를 요구하지 않는다.
그래서 `403 insufficient_scope`를 보내지 않고, official의 두 client에도 step-up 처리가 없다.
official의 scope 설계에 대한 판정은 [준수표](reference-compliance.md)의 16번에 있다.

## 6.6 3·4단계: `MCP-Protocol-Version`과 session 검사

token까지 통과한 요청만 MCP의 규칙을 검사받는다.
규칙과 오류 본문은 1장에서 봤으므로, 여기서는 어디서 검사하는지만 본다.

`MCP-Protocol-Version`은 official의 `McpProtocolVersionFilter`가 본다.
모르는 버전이면 `400`이고, header가 없으면 통과시킨다.
session은 Spring AI의 transport가 본다.
`initialize`가 아닌 요청에 `Mcp-Session-Id`가 없으면 `400`, 끝났거나 모르는 session이면 `404`다.

`McpProtocolVersionFilter`는 Spring Security 뒤에서 돈다.
그래서 token 없이 `MCP-Protocol-Version: 1999-01-01`을 보내면 `400`이 아니라 `401`을 받는다.
인증되지 않은 요청에는 MCP 규칙의 오류를 알려 주지 않는 셈이다.

## 6.7 session과 사용자

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

official이 session을 사용자에 묶지 않는 데는 이유가 있다.
agent는 Spring AI 자동 구성이 만든 MCP client 하나를 모든 사용자가 같이 쓴다.
그래서 session은 첫 채팅을 보낸 사용자의 token으로 열리고, 그 뒤의 요청에는 그때그때 채팅한 사용자의 token이 붙는다(6.8).
session을 처음 연 사용자에 묶으면, 두 번째 사용자부터는 요청이 막힌다.

session을 사용자에 묶는 방법은 [chat-memory practice](../mcp-security-authn-chat-memory/README.md)에 있다.
그 agent는 사용자마다 MCP client를 따로 열고, MCP Server는 각 session을 처음 연 사용자(token의 `sub`)에 묶는다.
다른 사용자의 token으로 그 session ID를 쓰면 `403`이다.

MCP 2026-07-28에서는 protocol 수준의 session과 `Mcp-Session-Id`가 없어졌다.
호출 사이에 상태가 필요한 서버는 스스로 만든 handle을 tool 인자로 주고받고, 그 handle이 요청한 사용자의 것인지 token으로 확인한다.
자세한 것은 9장에서 본다.

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

`McpSecurityConfig`는 `McpClientCustomizer` bean 두 개로 두 클래스를 Spring AI의 MCP client에 연결한다.
context provider는 `McpClient.SyncSpec`에, token customizer는 `HttpClientStreamableHttpTransport.Builder`에 넣는다.
token customizer는 요청을 보낼 때마다 불린다.

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

```java
public static void main(String[] args) {
    Hooks.enableAutomaticContextPropagation();   // reactor thread로 SecurityContext를 옮긴다
    SpringApplication.run(ShopAgentApplication.class, args);
}
```

이 hook을 켜면 Reactor는 thread를 바꿀 때 Spring Security의 `SecurityContextHolderThreadLocalAccessor`로 `SecurityContext`를 옮겨 준다.
그래서 context provider가 reactor thread에서도 login한 사용자를 읽는다.

**오류 없이 token만 빠지는 설정 실수**

| 실수 | 결과 |
|---|---|
| `Hooks.enableAutomaticContextPropagation()`을 뺀다 | reactor thread에서 `SecurityContextHolder`가 비어, context provider가 빈 context를 만든다 |
| `spring.ai.mcp.client.type`을 `ASYNC`로 바꾼다 | context provider를 넣는 `McpClientCustomizer<McpClient.SyncSpec>`이 async client에는 적용되지 않는다 |

두 실수 모두 기동할 때 오류가 나지 않는다.
MCP 요청에서 token만 빠지고, MCP Server가 `401`로 답한 뒤에야 알 수 있다.
`Hooks` 줄이 빠진 경우에는 `practice/mcp-security-authn-official/logs/shop-agent.log`에 DEBUG 줄 하나가 남는다.
`SecurityMcpTransportContextProvider`가 남기는 `인증 없음 — 빈 전송 컨텍스트를 만든다 (토큰이 붙지 않는다)`다.

**`local-client`는 한 번만 붙인다**

`local-client`는 사용자 한 명이 자기 기기에서 쓰고, 한 번 실행하는 동안 token도 하나다.
그래서 transport를 만들 때 기본 요청에 `Authorization` header를 한 번 넣는다(`McpCalls`).
SDK는 이 기본 요청을 복사해 `initialize`부터 session을 끝내는 `DELETE`까지 모든 요청을 만든다.
자세한 과정은 7장에서 본다.

## 6.9 official 코드에서 보기

**`SecurityConfig`: resource server**

`application.yml`의 설정은 6.4(`server.address`)와 6.5(`issuer-uri`, `audiences`)에서 봤다.
`shop-mcp-server`의 `SecurityConfig`는 모든 요청에 token을 요구하고, token을 JWT로 검증한다.

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http,
        @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) throws Exception {
    return http
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())   // session ID가 있어도 token을 본다
            .oauth2ResourceServer(resourceServer -> resourceServer
                    .jwt(Customizer.withDefaults())                             // signature, iss, aud, exp
                    .authenticationEntryPoint(resourceMetadataEntryPoint())     // 401에 resource_metadata (3장)
                    .protectedResourceMetadata(/* ... PRM (3장) */))
            .csrf(csrf -> csrf.disable())                                       // cookie가 아니라 token으로만 인증한다
            .build();
}
```

`jwt(Customizer.withDefaults())`는 Spring Boot가 `issuer-uri`와 `audiences`로 만든 `JwtDecoder`를 쓴다.
이 decoder는 처음 token을 검증할 때 metadata를 읽는다.
그래서 Authorization Server가 꺼져 있어도 MCP Server는 뜨고, token이 붙은 첫 요청에서 실패한다.

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

`SecurityFilterProperties.DEFAULT_FILTER_ORDER`는 Spring Security filter chain의 순서다.
1을 빼면 그보다 먼저 돈다.
`mcpProtocolVersionFilter` bean은 순서를 정하지 않는다.
`FilterRegistrationBean`의 기본 순서는 가장 뒤라서, 이 filter는 Spring Security 다음에 돈다.
`McpTransportSecurityFilter`는 검증기가 거절하면 `403`·`421`로 바로 응답하고, 그 요청을 Spring Security로 넘기지 않는다.

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

## 6.10 직접 해 보기

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
`docs/superpowers/captures/mcp-authorization-walkthrough.sh` 출력의 12~15단계가 ID token의 `401`, `Origin`의 `403`, session ID가 없는 `400`, 모르는 버전의 `400`이다.
`Host`의 `421`과 끝난 session의 `404`는 `docs/superpowers/captures/mcp-authorization-supplement.sh`에 있다.
각 단계의 curl 명령은 스크립트 안에 있어서, token이 있다면 한 단계씩 직접 보내 볼 수 있다.

browser로 `http://localhost:8110`에 들어가 `user`/`password`로 login하고 채팅하면, agent가 token을 붙이는 것도 볼 수 있다.
`practice/mcp-security-authn-official/logs/shop-agent.log`에 `토큰을 헤더에 붙였다 (사용자=user)`가 남는다.
`practice/mcp-security-authn-official/logs/shop-mcp-server.log`의 tool 호출 줄에는 MCP Server가 token에서 읽은 사용자(`사용자=user`)가 찍힌다.

## 6.11 정리

- client는 모든 HTTP 요청의 `Authorization` header에 `Bearer`로 access token을 넣는다. session 안의 요청도 매번 넣는다.
- official의 MCP Server는 `Origin`·`Host` → token → `MCP-Protocol-Version` → session 순서로 검사한다. `Origin`·`Host`는 token이 없어도 `403`·`421`이다.
- token은 `jwks_uri`의 public key로 signature를, `issuer-uri`로 `iss`를, `audiences`로 `aud`를 확인한다. 다른 `aud`의 token은 `401 invalid_token`이다.
- session ID는 인증을 대신하지 않는다. official은 요청마다 token을 보지만 session을 사용자에 묶지는 않고, chat-memory practice는 묶는다.
- agent는 요청마다 그 사용자의 token을 찾아 붙인다. reactor thread로 `SecurityContext`를 옮기는 설정이 빠지면 오류 없이 token만 빠진다.

## 6.12 명세 근거

| 내용 | 명세 | 요구 수준 |
|---|---|---|
| client는 같은 session이라도 모든 HTTP 요청의 `Authorization` header에 access token을 넣고, query string에는 넣지 않는다. 그 MCP Server의 Authorization Server가 발급한 token만 보낸다 | [MCP 2025-11-25 Authorization — Token Requirements](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#token-requirements), [Token Handling](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#token-handling), [OAuth 2.1 §5.1.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-5.1.1) | MUST, MUST NOT |
| server는 모든 연결의 `Origin`을 검증하고, 있는데 유효하지 않으면 `403`으로 답한다. 로컬 서버는 `127.0.0.1`에만 bind하고, 모든 연결에 인증을 둔다 | [MCP 2025-11-25 Transports — Security Warning](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#security-warning), [MCP 2026-07-28 Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http) | MUST, SHOULD |
| 이 서버로 올 요청이 아니면 `421 Misdirected Request`로 답할 수 있다 | [RFC 9110 §15.5.20](https://www.rfc-editor.org/rfc/rfc9110#section-15.5.20) | — |
| MCP Server는 요청을 처리하기 전에 token을 검증하고, 자신을 audience로 발급된 token만 받는다. 유효하지 않거나 만료된 token에는 `401`로 답한다 | [MCP 2025-11-25 Authorization — Token Handling](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#token-handling), [Access Token Privilege Restriction](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#access-token-privilege-restriction), [OAuth 2.1 §5.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-5.2), [RFC 8707 §2](https://www.rfc-editor.org/rfc/rfc8707#section-2) | MUST, MUST NOT |
| RFC 9068이 정한 JWT access token 확인 가운데 이 장에서 본 것은 `iss` 일치, `aud`에 자신이 있는지, signature(`alg: none` 거절), `exp`다. 실패하면 `invalid_token`이고 `401`로 답하며, Authorization Server는 metadata의 `jwks_uri`와 `issuer`로 key와 `iss` 값을 알린다 | [RFC 9068 §4](https://www.rfc-editor.org/rfc/rfc9068#section-4), [RFC 6750 §3.1](https://www.rfc-editor.org/rfc/rfc6750#section-3.1) | MUST, SHOULD |
| token의 scope가 모자라면 MCP Server는 `403`과 `WWW-Authenticate`의 `error="insufficient_scope"`·`scope`·`resource_metadata`로 답하고, `scope`에는 이 요청에 필요한 scope를 넣는다. 사용자를 대신하는 client는 그 scope로 step-up authorization을 하고(`client_credentials`처럼 자기 권한으로 동작하는 client는 바로 멈춰도 된다), 다시 시도하는 횟수를 제한한다 | [MCP 2025-11-25 Authorization — Scope Challenge Handling](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#scope-challenge-handling), [RFC 6750 §3.1](https://www.rfc-editor.org/rfc/rfc6750#section-3.1) | SHOULD, MAY |
| 지원하지 않는 `MCP-Protocol-Version`에는 `400`, session ID가 없으면 `400`, 끝난 session에는 `404`로 답한다 | [MCP 2025-11-25 Transports — Protocol Version Header](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#protocol-version-header), [Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management) | MUST, SHOULD |
| authorization을 구현한 MCP Server는 모든 요청을 검증하고 session을 인증에 쓰지 않는다. session ID는 추측할 수 없는 값으로 만들고, 사용자 정보에 묶는다 | [MCP 2025-11-25 Security Best Practices — Session Hijacking](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices#session-hijacking) | MUST, MUST NOT, SHOULD |
| 2026-07-28은 protocol 수준의 session과 `Mcp-Session-Id`를 없앤다 | [MCP 2026-07-28 Changelog](https://modelcontextprotocol.io/specification/2026-07-28/changelog), [Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http) | — |

[← 5장](05-authorization-and-token.md) · [목차](README.md) · [7장 →](07-local-client.md)
