# mcp-2026-07-28

이 practice는 [mcp-cimd](../mcp-cimd/README.md)를 바탕으로, agent와 MCP Server가 주고받는 MCP 요청 형식을 2025-11-25에서 2026-07-28로 올린다.
2026-07-28에는 `initialize`와 session이 없고, client는 요청마다 `_meta`에 버전·capability·client 정보를 넣고 header 세 개(`MCP-Protocol-Version`, `Mcp-Method`, `Mcp-Name`)를 붙인다.
MCP Server는 MCP Java SDK 2.2.0-M1의 2026-07-28 서버 위에 cimd까지의 authorization 장치를 그대로 얹는다.
SDK에는 아직 2026-07-28 client가 없어서, agent는 Spring AI `ChatClient`를 그대로 쓰고 MCP를 부르는 부분만 직접 만든 client로 바꾼다.
Authorization Server, ChatGPT형·Claude형 두 client type, discovery, login과 consent는 cimd와 같다.
흐름과 규칙은 [안내서 14장](../mcp-guide/14-modern-protocol.md)이 설명하고, 이 README에서는 cimd와 다른 점만 본다.

## mcp-cimd와 다른 점

| 바뀐 곳 | cimd | 이 practice | 안내서 절 |
|---|---|---|---|
| MCP 요청 형식 | 2025-11-25다. `initialize`로 버전과 capability를 한 번 협상한다 | 2026-07-28이다. 요청마다 `_meta`와 header 세 개를 보내고, 서버가 받는 버전은 `server/discover`로 묻는다 | [14장 1단계](../mcp-guide/14-modern-protocol.md#143-1단계-token-없는-첫-요청), [14장 2단계](../mcp-guide/14-modern-protocol.md#144-2단계-serverdiscover로-서버가-받는-버전을-묻는다) |
| MCP Server transport | Spring AI MCP server starter와 MCP Java SDK 2.1.0이다 | Spring AI 없이 SDK 2.2.0-M1의 `McpServer`와 `HttpServletMcpTransport`를 `/mcp` servlet으로 등록한다. header와 본문의 대조, `_meta`와 버전 검사, 본문 상한(`413`)을 SDK transport가 맡는다 | [14장 서버 코드](../mcp-guide/14-modern-protocol.md#1410-서버-코드에서-보기) |
| tool 정의 | `@McpTool` 메서드에 `@RequiredScope`를 붙인다 | `ShopTools`가 이름·설명·입력 schema·scope·handler를 `ShopTool` record로 적는다. 목록, 숨김, 실행은 `ShopToolRepository`가 맡는다 | [14장 서버 코드](../mcp-guide/14-modern-protocol.md#1410-서버-코드에서-보기) |
| scope filter | 본문을 읽어 tool 이름을 찾고, 기본 scope가 모자라면 그것만 먼저 알린다 | 본문을 읽지 않고 `Mcp-Method`·`Mcp-Name` header만 본다. 기본 scope와 tool scope가 모두 모자라면 한 challenge에 함께 적는다 | [14장 5단계](../mcp-guide/14-modern-protocol.md#147-5단계-scope가-모자라면-한-challenge에-모은다), [14장 확인](../mcp-guide/14-modern-protocol.md#148-mcp-server가-확인하는-것) |
| 실행하는 tool 이름 | filter와 transport가 같은 규칙으로 본문을 읽게 맞춘다 | `ShopToolRepository`는 SDK가 본문에서 그대로 꺼낸 이름이 `Mcp-Name`과 같을 때만 실행한다. 문자열이 아닌 이름으로 header 대조를 건너뛰는 호출을 막는다 | [14장 확인](../mcp-guide/14-modern-protocol.md#148-mcp-server가-확인하는-것) |
| 오류의 HTTP 상태 | 없는 tool과 숨긴 tool은 HTTP `200` 안의 JSON-RPC 오류다 | SDK가 code로 상태를 정한다. `-32602`·`-32020`은 `400`, `-32601`은 `404`다 | [14장 확인](../mcp-guide/14-modern-protocol.md#148-mcp-server가-확인하는-것) |
| tool 목록 cache | agent가 TTL 5분을 스스로 정하고, access token마다 둔다 | MCP Server가 `tools/list`에 `ttlMs: 60000`과 `cacheScope: private`을 넣고, agent는 그 값대로 둔다 | [14장 3단계](../mcp-guide/14-modern-protocol.md#145-3단계-toolslist와-서버가-정하는-cache-기간) |
| agent의 MCP client | Spring AI MCP client starter의 공유 client에 token customizer와 step-up error handler를 끼운다 | `ModernMcpClient`가 요청 하나에 `POST` 하나를 보낸다. `UserToolCatalog`가 사용자마다 tool callback을 만들고, callback은 부를 때마다 그 사용자의 token을 꺼낸다 | [14장 agent 코드](../mcp-guide/14-modern-protocol.md#1411-agent-코드에서-보기) |
| discovery probe | token 없는 `initialize`다 | token 없는 2026-07-28 형식의 `server/discover`다. 그 뒤 순서는 같다 | [14장 1단계](../mcp-guide/14-modern-protocol.md#143-1단계-token-없는-첫-요청) |
| scope 부족 로그 | `필요한 scope=products:write` | `모자란 scope=products:write`다. 모자란 scope가 둘이면 공백으로 이어 적는다 | [14장 5단계](../mcp-guide/14-modern-protocol.md#147-5단계-scope가-모자라면-한-challenge에-모은다) |
| 옛 형식 client | 받는다 | 받지 않는다. 2025-11-25의 `initialize`는 `400`이다 | [14장 옛 client](../mcp-guide/14-modern-protocol.md#149-옛-client는-붙지-못한다) |

login 계정, scope, tool 7개, 역할 표, step-up 흐름, 사용자별 tool 목록, handle은 cimd와 같다.

cimd에 있던 다음 클래스는 이 practice에 없다.

| module | 클래스 | 없는 이유 |
|---|---|---|
| `shop-mcp-server` | `McpProtocolVersionFilter` | 2025-11-25까지의 버전만 받아서, 남겨 두면 2026-07-28 요청을 모두 막는다. 버전 검사는 SDK가 한다 |
| | `CachedBodyHttpServletRequest` | scope filter가 본문을 읽지 않는다 |
| | `TransportErrorResponseFilter` | SDK가 오류 본문에 `code`와 `message`만 넣는다 |
| | `ToolVisibilityTransport` | 목록 거르기와 숨긴 tool 처리는 `ShopToolRepository`가 한다 |
| | `RequiredScope` | scope는 `ShopTool`의 field다 |
| `shop-agent` | `OAuth2TokenAttachingRequestCustomizer`, `SecurityMcpTransportContextProvider` | `ModernMcpClient`가 token을 받아 header에 넣는다 |
| | `StepUpAuthorizationErrorHandler`, `StepUpToolExecutionExceptionProcessor` | `ModernMcpClient`가 `403`을 `StepUpRequiredException`으로 바꾸고, `ModernToolCallback`이 그대로 던진다 |
| | `UnknownToolAwareToolCallback` | 모르는 tool 오류를 받으면 목록을 버리는 일은 `ModernToolCallback`이 한다 |

버전은 Spring Boot 4.1.2이다.
MCP Server는 Spring AI 없이 `io.modelcontextprotocol.sdk:mcp:2.2.0-M1`을 쓴다.
agent는 Spring AI 2.0.2(`ChatClient`와 ollama)와, schema 타입용 `mcp-core`·`mcp-json-jackson3` 2.2.0-M1을 쓴다.

## 실행

준비물, `run.sh`가 ollama와 `certs/`를 준비하는 방법, client type을 고르는 인자는 [cimd의 실행](../mcp-cimd/README.md#실행)과 같다.

```bash
# 저장소 최상위 폴더에서
cd practice/mcp-2026-07-28
./run.sh            # ChatGPT형(기본)
```

Claude형으로 보려면 세 서버를 내린 뒤 인자를 주고 다시 띄운다.

```bash
# practice/mcp-2026-07-28에서
./stop.sh && ./run.sh claude
```

인자 `claude`는 agent가 어떤 client로 붙을지를 고르고, 쓰는 LLM은 바꾸지 않는다.
LLM은 `LLM=anthropic ./run.sh claude`처럼 따로 고른다([official의 Claude로 실행](../mcp-security-authn-official/README.md#claude로-실행)).

| 주소 | module | 하는 일 |
|---|---|---|
| `http://localhost:9070` | `auth-server` | Authorization Server다. 모든 client를 CIMD 문서로 받는다 |
| `http://localhost:8181/mcp` | `shop-mcp-server` | 2026-07-28 MCP Server다. tool과 역할 표는 cimd와 같다 |
| `http://localhost:8180` | `shop-agent` | 채팅 화면이다. browser가 여는 곳이다 |
| `https://localhost:8182` | `shop-agent` | client 문서 두 개와 JWKS를 준다. Authorization Server만 가져간다 |

`run.sh`는 `auth-server`(9070) → `shop-mcp-server`(8181) → `shop-agent`(8180, 8182) 순서로 띄운다.
제대로 띄웠다면 끝에 `준비되었습니다. agent의 client type은 chatgpt입니다.`가 찍힌다.
앱의 로그는 `practice/mcp-2026-07-28/logs/<module>.log`에 남는다.
issuer는 `http://localhost:9070`이고, MCP Server의 resource는 `http://localhost:8181/mcp`다.

문서 주소는 ChatGPT형이 `https://localhost:8182/oauth/client.json`, Claude형이 `https://localhost:8182/oauth/public-client.json`이다.
redirect 주소는 `http://localhost:8180/login/oauth2/code/authserver` 하나이고, Authorization Server의 loopback 예외(`mcp.cimd.loopback-exception`)는 `https://localhost:8182`다.
login 계정은 `user`(점원)와 `user2`(손님)이고, 비밀번호는 둘 다 `password`다.
browser에서는 `127.0.0.1`이 아니라 `http://localhost:8180`을 연다.

`./stop.sh`는 8180·8182·8181·9070 포트에서 연결을 기다리는 process를 내린다.

## 코드 지도

아래는 이 practice에만 있거나 cimd와 다른 클래스다.
클래스는 `<module>/src/main/java/dev/starryeye/modern/<package>/` 아래에 있다.
package는 `shop-mcp-server`가 `mcpserver`, `shop-agent`가 `agent`다.
표에서는 클래스 이름 앞에 그 아래의 package(`config`, `tool`, `filter`, `security`, `mcp`, `discovery`)를 붙였다.

| module | 클래스 | 하는 일 | 안내서 |
|---|---|---|---|
| `shop-mcp-server` | `config.ModernMcpConfig` | SDK의 `McpServer`(`serverInfo` `modern-shop-mcp-server` `0.0.1`, tool 기능)와 `HttpServletMcpTransport`를 만들어 `/mcp`에 async servlet으로 등록한다. `contextExtractor`는 `McpCaller::context`, `maxRequestSize`는 본문 상한이다 | [14장 서버 코드](../mcp-guide/14-modern-protocol.md#1410-서버-코드에서-보기) |
| | `config.McpTransportConfig` | `McpTransportSecurityFilter` → Spring Security → `ToolScopeFilter` 순서로 filter를 `/mcp`에 등록하고, `BasketStore` bean을 만든다. Spring AI transport bean은 없다 | [14장 서버 코드](../mcp-guide/14-modern-protocol.md#1410-서버-코드에서-보기) |
| | `config.McpLimitProperties` | 본문 상한 `mcp.limits.max-request-body`(1MB)를 transport의 `maxRequestSize`로 넘긴다. tool 호출 상한 `tool-calls-per-minute`(60)는 cimd와 같다 | [14장 확인](../mcp-guide/14-modern-protocol.md#148-mcp-server가-확인하는-것) |
| | `tool.ShopTool`, `tool.ShopTools` | tool 하나의 SDK `Tool`, 필요한 scope, handler를 한 record에 둔다. `ShopTools`는 tool 7개를 cimd와 같은 이름·설명·입력 schema·scope로 적는다 | [14장 서버 코드](../mcp-guide/14-modern-protocol.md#1410-서버-코드에서-보기) |
| | `tool.ShopToolRepository` | SDK의 `McpSyncToolRepository`다. `list`는 역할로 거른 목록에 `ttlMs` 60000과 `cacheScope` `private`을 단다. `find`는 숨긴 tool에 `null`을 주고, `call`은 `ctx.primitiveName()`이 `Mcp-Name`·요청 이름과 같을 때만 handler를 부른다 | [14장 3단계](../mcp-guide/14-modern-protocol.md#145-3단계-toolslist와-서버가-정하는-cache-기간), [14장 확인](../mcp-guide/14-modern-protocol.md#148-mcp-server가-확인하는-것) |
| | `tool.ProductTools`, `tool.BasketTools` | tool의 본문이다. annotation 없이 `ShopTools`의 handler가 부르는 메서드이고, SDK `CallToolResult`를 돌려준다 | [14장 서버 코드](../mcp-guide/14-modern-protocol.md#1410-서버-코드에서-보기) |
| | `tool.ToolScopeRegistry` | tool 이름마다 필요한 scope의 표다. `@McpTool` scan 대신 `ShopTool` 목록에서 만든다 | [14장 서버 코드](../mcp-guide/14-modern-protocol.md#1410-서버-코드에서-보기) |
| | `tool.ToolVisibility` | 역할로 tool을 숨길지 판단하는 `hidden`만 남는다. 목록을 거르는 일은 `ShopToolRepository`가 한다 | [14장 서버 코드](../mcp-guide/14-modern-protocol.md#1410-서버-코드에서-보기) |
| | `filter.ToolScopeFilter` | `Mcp-Method`·`Mcp-Name` header로 필요한 scope를 정하고, 모자란 scope를 모두 한 challenge에 적어 `403`으로 답한다. 풀 수 없는 Base64 `Mcp-Name`은 `400`과 `-32020`으로 끝내고, 통과한 `tools/call`은 사용자마다 센다 | [14장 5단계](../mcp-guide/14-modern-protocol.md#147-5단계-scope가-모자라면-한-challenge에-모은다), [14장 확인](../mcp-guide/14-modern-protocol.md#148-mcp-server가-확인하는-것) |
| | `filter.McpTransportSecurityFilter` | SDK의 `ServerHttpHeaderValidator`로 `Origin`·`Host`를 인증보다 먼저 검사한다. 규칙은 cimd와 같다 | [6장 1단계](../mcp-guide/06-mcp-call-and-validation.md#64-1단계-originhost-검사) |
| | `security.McpCaller` | `context(HttpServletRequest)`가 검증한 token의 `sub`·`client_id`와 푼 `Mcp-Name`을 transport context에 넣는다. `decodeMcpName`은 `=?base64?…?=`를 SDK와 같은 규칙으로 푼다 | [14장 확인](../mcp-guide/14-modern-protocol.md#148-mcp-server가-확인하는-것) |
| `shop-agent` | `mcp.ModernMcpClient` | 요청 하나에 `POST` 하나를 보낸다. `_meta`와 header 세 개, `Authorization`을 붙이고, redirect를 따라가지 않으며, JSON과 SSE 응답을 읽고 요청 하나를 20초 안에 끝낸다. `discover`는 `supportedVersions`에 `2026-07-28`이 없으면 멈춘다 | [14장 agent 코드](../mcp-guide/14-modern-protocol.md#1411-agent-코드에서-보기) |
| | `mcp.ModernMcpException`, `mcp.UnknownToolException`, `mcp.ToolRateLimitedException`, `mcp.McpProtocolMismatchException`, `mcp.McpTransportException` | MCP Server의 오류를 HTTP 상태와 JSON-RPC `code`로 나눈 예외다. `403 insufficient_scope`는 이 계열이 아니라 `StepUpRequiredException`이다 | [14장 agent 코드](../mcp-guide/14-modern-protocol.md#1411-agent-코드에서-보기) |
| | `mcp.ModernToolCallback` | SDK `Tool`을 Spring AI `ToolCallback`으로 보이게 한다. step-up 예외는 그대로 던지고, 그 밖의 MCP 오류는 모델에게 갈 tool 오류로 바꾼다 | [14장 agent 코드](../mcp-guide/14-modern-protocol.md#1411-agent-코드에서-보기) |
| | `mcp.UserToolCatalog` | 처음 한 번 `server/discover`를 보내고, `tools/list` 결과를 `ttlMs`·`cacheScope`대로 access token마다 둔다. callback은 사용자를 묶어 두고 부를 때마다 그 사용자의 token을 꺼낸다 | [14장 3단계](../mcp-guide/14-modern-protocol.md#145-3단계-toolslist와-서버가-정하는-cache-기간), [14장 agent 코드](../mcp-guide/14-modern-protocol.md#1411-agent-코드에서-보기) |
| | `config.ToolCatalogConfig` | `ModernMcpClient`(redirect 없음, HTTP/1.1, 연결 2초)와 `UserToolCatalog` bean을 만든다. 다른 클래스는 이 두 bean에만 기댄다 | [14장 agent 코드](../mcp-guide/14-modern-protocol.md#1411-agent-코드에서-보기) |
| | `config.McpSecurityConfig` | MCP client customizer bean이 없다. token request의 assertion 설정과 `SingleFlightAuthorizedClientManager`는 cimd와 같다 | [13장 agent 코드](../mcp-guide/13-cimd.md#1311-agent-코드에서-보기) |
| | `discovery.McpAuthorizationDiscovery` | token 없는 첫 요청을 2026-07-28 형식의 `server/discover`로 보낸다. 나머지 순서와 검사는 cimd와 같다 | [14장 1단계](../mcp-guide/14-modern-protocol.md#143-1단계-token-없는-첫-요청) |

표에 없는 클래스는 [cimd README의 코드 지도](../mcp-cimd/README.md#코드-지도)에 있고, package 이름(`dev.starryeye.modern.*`)과 포트·cookie 같은 설정 값만 다르다.
`auth-server`는 이름·포트·package·cookie 이름(`MODERNAUTHSESSIONID`)만 달라서 표에 없다.

## 직접 확인할 것

`run.sh`로 띄운 뒤 `practice/mcp-2026-07-28`에서 실행한다.
캡처 스크립트의 줄은 저장소 최상위 폴더에서 실행한다.
browser의 줄은 표의 순서대로 한다.

| 해 볼 것 | 기대 결과 |
|---|---|
| [14장 직접 해 보기](../mcp-guide/14-modern-protocol.md#1413-직접-해-보기)의 token 없는 `server/discover` curl | `HTTP/1.1 401`과 `WWW-Authenticate: Bearer resource_metadata="http://localhost:8181/.well-known/oauth-protected-resource/mcp", scope="products:read"`다 |
| `curl -s http://localhost:8181/.well-known/oauth-protected-resource/mcp` | `"resource":"http://localhost:8181/mcp"`, `"authorization_servers":["http://localhost:9070"]`, `"scopes_supported":["products:read"]`이다 |
| `./stop.sh && ./run.sh` 직후 `docs/superpowers/captures/mcp-2026-07-28-walkthrough.sh` 실행 | token 있는 `server/discover`는 `→ HTTP 200`이고 `"supportedVersions": ["2026-07-28"]`, `serverInfo`의 `modern-shop-mcp-server`가 있다. `tools/list`는 tool 7개와 `"ttlMs": 60000`, `"cacheScope": "private"`이다 |
| 같은 출력의 `tools/call` | `searchProducts`는 `→ HTTP 200`과 노트북 두 개다. 조회 token의 `updateStock`은 `→ HTTP 403`, `Bearer error="insufficient_scope", scope="products:write", resource_metadata=...`다 |
| 같은 출력의 실패 요청 | `Mcp-Name: searchProducts`에 본문 `updateStock`이면 `→ HTTP 400`, `-32020`, `Mcp-Name header does not match request name/uri`다. 본문 `name`이 숫자 `1`이면 `→ HTTP 400`, `-32602`, `Unknown tool: 1`이다 |
| 같은 출력의 `initialize` | header 없는 2025-11-25 `initialize`는 `→ HTTP 400`, `-32020`, `Missing required header: Mcp-Method`다. 2026-07-28 header와 `_meta`를 붙인 `initialize`는 `→ HTTP 404`, `-32601`, `Method not found: initialize`다 |
| 같은 출력의 마지막 `updateStock` | Claude형 쓰기 token으로 `→ HTTP 200`이고, `MCP Server 로그에서 updateStock 호출 줄 수: 1`이다. header와 본문이 달랐던 요청은 `updateStock`을 실행하지 않았다 |
| browser로 `http://localhost:8180`을 열고 `user`/`password`로 login, `products:read`를 체크해 제출 | consent 화면은 cimd와 같다. 제목은 `권한 요청: Shop Agent (ChatGPT형)`이고, `client 문서: localhost:8182`, `허락하면 돌아갈 주소: localhost:8180`과 loopback 경고가 나온다 |
| `노트북 재고 있어?` 보내기 | 재고를 알려 주는 답이 온다 |
| `p1 재고를 10개로 바꿔 줘` 보내기 | 채팅 아래에 `updateStock을(를) 하려면 products:write 권한이 더 필요합니다. 허용하면 권한을 받은 뒤 질문을 다시 보냅니다.` 카드와 "권한 허용" 버튼이 뜬다 |
| `grep 'scope 부족' logs/shop-mcp-server.log` | `scope 부족 — 사용자=user, client_id=https://localhost:8182/oauth/client.json, tool=updateStock, 모자란 scope=products:write, 가진 scope=[openid, products:read]`다 |
| "권한 허용"을 누르고 `products:write`를 체크해 제출 | consent 화면의 새 항목은 `products:write` 하나이고, `products:read`는 "이미 허락한 권한" 아래에 나온다. 질문이 다시 가고 재고가 10개로 바뀐다 |
| `grep 'updateStock 호출' logs/shop-mcp-server.log` | `updateStock 호출 (productId=p1, quantity=10, 사용자=user)`다 |
| `./stop.sh && ./run.sh claude` 뒤 같은 순서로 login과 재고 변경 | step-up의 consent 화면에 `products:read`와 `products:write`가 모두 새 항목으로 나온다. Claude형은 consent를 저장하지 않기 때문이다. 둘 다 체크해 제출하면 재고가 10개로 바뀐다 |

LLM의 답은 `qwen3:8b`의 출력이라 문장이 매번 조금씩 다르다.

`mcp-2026-07-28-walkthrough.sh`는 agent 대신 두 client type의 authorization request와 token request, MCP 요청을 curl로 보낸다.
조회 token은 ChatGPT형으로, `products:read`와 `products:write`를 함께 요청한 쓰기 token은 Claude형으로 받는다.
처음 보는 client의 consent 화면이 나와야 하므로, `./stop.sh`와 `./run.sh`로 다시 띄운 직후에 돌린다.

```bash
# 저장소 최상위 폴더에서. 출력의 JWT는 앞 20자, refresh token과 code는 앞 12자만 남는다
docs/superpowers/captures/mcp-2026-07-28-walkthrough.sh > /tmp/modern-walkthrough.txt
```

같은 방법으로 받은 기록이 [2026-07-28 캡처](../../docs/superpowers/captures/2026-10-18-modern-walkthrough.txt)에 있다.

## 더 읽을 것

- [안내서 14장 MCP 2026-07-28](../mcp-guide/14-modern-protocol.md): 이 practice로 2026-07-28 요청의 header와 `_meta`, header로 하는 scope 검사, 서버가 정하는 목록 cache, 옛 client가 붙지 못하는 이유를 설명한다.
- [안내서 9장 버전](../mcp-guide/09-versions.md#97-initialize와-session의-제거-2026-07-28): 2026-07-28이 `initialize`와 session을 없앤 이유를 설명한다.
- [안내서 13장 CIMD](../mcp-guide/13-cimd.md): 이 practice가 그대로 쓰는 CIMD Authorization Server와 두 client type을 설명한다.
- [부록: 명세 준수표](../mcp-guide/reference-compliance.md#mcp-2026-07-28에서-달라지는-행): 요청 형식을 2026-07-28로 올리면서 cimd와 판정이 달라지는 행을 모았다.
- [mcp-cimd](../mcp-cimd/README.md): 이 practice의 바탕이 된 practice다.
