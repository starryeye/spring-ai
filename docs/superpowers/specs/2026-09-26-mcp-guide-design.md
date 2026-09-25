# MCP 안내서 재구성 — 설계

## 목표

official practice와 practice 폴더 바로 아래 문서를 **읽으면 MCP와 MCP OAuth를 다룰 줄 알게 되는 교재**로 다시 만든다.
독자는 OAuth 기본은 알지만 MCP와 discovery는 모르는 사람이다.
지금 문서는 명세 준수를 증명하는 감사 보고서 모양이라 이 독자에게 맞지 않는다(2026-09-26 검토).

## 결정 (사용자 확인)

| # | 항목 | 결정 |
|---|---|---|
| A | 용어 | 기술 용어는 흔한 말도 영어로 쓴다: token, browser, login, consent, client, credentials, public key, signature, parameter, header, field, redirect, endpoint, session 등. 요청·응답·주소·설정 같은 일상 단어는 한국어 |
| B | 내용 범위 | MCP 기초 장을 넣는다. 로컬 client 시나리오(Claude Desktop·Cursor 같은 public client)를 **코드로** official에 추가한다 |
| C | 최상위 구조 | 설계자가 정한다(아래 2절) |
| D | 진행 방식 | 3장(discovery) 시험판을 먼저 쓰고 확인받았다. 나머지를 같은 방식으로 쓴다 |
| E | 다이어그램 | mermaid 코드 블록 + 바로 아래 PNG 그림 링크 `[다이어그램 그림으로 보기](diagrams/<문서>-<n>.png)`. GitHub·IntelliJ는 mermaid를, Claude Code desktop은 링크를 연다(desktop은 mermaid·로컬 그림을 그리지 않는다 — [anthropics/claude-code#52517](https://github.com/anthropics/claude-code/issues/52517)) |

## 1. 문체 규칙 (스킬 `writing-practice-docs` 전면 개정)

시험판(3장)에서 확인받은 모양을 규칙으로 옮긴다.

**장의 틀**: 필요성 → 시퀀스 다이어그램 → 단계별 실제 요청·응답 → client·server가 확인하는 것(어기면 생기는 일) → official 코드 → 직접 해 보기 → 정리 → 명세 근거.

**본문 규칙**
- "왜 이 단계가 있나"를 먼저 쓰고, 규칙은 그 뒤에 쓴다.
- 요청과 응답은 official을 실제로 띄워 받은 값을 보여 준다. 긴 JSON은 핵심 field만 남기고 `"...": "그 밖의 field는 생략"`으로 줄인다.
- 본문에 **MUST** 같은 요구 수준 단어, 캡처 번호(`C3`), 테스트 메서드 이름, 절 번호 링크(`[4.5](#s4-5)`)를 넣지 않는다. 명세 근거는 장 끝 표 하나에 모은다. 표 열은 "내용 · 명세 링크 · 요구 수준"이다.
- 제목은 한국어 제목 관례를 따른다: "왜 필요한가" 대신 "Discovery의 필요성".
- 영어 단어 뒤 조사는 붙여 쓴다: "token을", "`resource`를".
- 기계적 번역 어투를 쓰지 않는다: "싣다", "(설정을) 걸다", "배선", "물러나다", "드러나다" 등.
- 앵커 태그(`<a id>`), HTML 태그(`<sub>` 등)를 쓰지 않는다. 링크는 제목 자동 앵커로 한다.
- 문단 길이 제한(2~3문장)은 없앤다. 한 문장에 개념 하나를 원칙으로 하고, 문장당 150자 제한은 유지한다.

**다이어그램**
- ` ```mermaid ` 블록 바로 아래에 `[다이어그램 그림으로 보기](diagrams/<문서 이름>-<n>.png)` 링크를 둔다.
- 다이어그램 안에는 식별자·경로·field 이름 위주로 짧게 쓴다.
- 스크립트 `render_diagrams.py`가 문서의 mermaid 블록마다 PNG를 만들고, 원본 해시를 `diagrams/.sources.json`에 기록한다.

**검사 스크립트 개정**
- 용어 목록을 결정 A에 맞게 확장한다(토큰·브라우저·로그인·동의·자격증명·공개 키·서명·파라미터·헤더·엔드포인트·세션·클라이언트·리다이렉트 → 영어).
- 영어·코드 뒤 띄어 쓴 조사를 찾는다.
- 번역 어투 목록을 추가한다.
- 본문의 요구 수준 단어·캡처 번호·테스트 이름을 금지한다. "명세 근거" 절과 부록 문서는 예외다.
- `<a id`와 HTML 태그를 금지한다.
- mermaid 블록마다 바로 아래 그림 링크가 있는지, PNG가 있는지, 원본 해시가 최신인지 확인한다.
- 없앨 규칙: 문단 3문장 제한, `관측:` 줄 규칙, `원문 필드: N개 → 표 M행` 규칙.

## 2. 문서 구조

| 지금 | 바꾼 뒤 |
|---|---|
| `practice/MCP-AUTHORIZATION.md`(허브), `practice/MCP-SEQUENCES.md` | `practice/mcp-guide/` 안의 장별 문서로 흡수한다. 다이어그램은 개념을 설명하는 장에 둔다 |
| `practice/MCP-API-SPEC.md` | `practice/mcp-guide/reference-api.md` — endpoint별 field 사전. 표기 설명은 몇 줄로 줄이고, "이 practice" 열은 official 기준으로 한다 |
| 허브의 준수표·구현 위치 지도 | `practice/mcp-guide/reference-compliance.md` — 세 practice 비교는 여기에만 둔다 |
| 시험판 `practice/MCP-GUIDE.md` | `practice/mcp-guide/03-discovery.md`로 옮긴다 |

`practice/mcp-guide/`
- `README.md` — 읽는 순서, 준비물(practice 실행 방법), 장 목록
- `01-mcp-basics.md` — MCP란 무엇인가, JSON-RPC, `initialize` → `notifications/initialized` → `tools/list` → `tools/call`, session(`Mcp-Session-Id`), `MCP-Protocol-Version`, stdio와 Streamable HTTP. OAuth는 HTTP transport에만 해당하고 stdio server는 환경 변수 등으로 credentials를 받는다
- `02-why-oauth.md` — 원격 MCP Server에 OAuth가 붙는 이유, 역할(MCP client = OAuth client, MCP Server = resource server, Authorization Server), 일반 OAuth와 다른 점 다섯 가지, 전체 흐름 한 장
- `03-discovery.md` — 시험판
- `04-client-registration.md` — pre-registration, CIMD, DCR(deprecated), confidential client와 public client, credentials의 issuer binding
- `05-authorization-and-token.md` — authorization request(PKCE, `resource`), consent, callback과 `iss`, token request, token 안의 `aud`, refresh
- `06-mcp-call-and-validation.md` — Bearer token으로 MCP 호출, MCP Server의 token 검증(signature, `iss`, `aud`, `exp`), `Origin`·`Host`, `MCP-Protocol-Version`, session과 사용자
- `07-local-client.md` — 로컬 MCP client(public client)의 전체 흐름. 새 `local-client` 모듈로 따라 한다(3절)
- `08-security.md` — token passthrough, confused deputy, mix-up, SSRF, redirect URI, public client 사칭, 전송 보안
- `09-versions.md` — 2025-03-26 → 2025-06-18 → 2025-11-25 → 2026-07-28 변화(역할 분리, CIMD, `iss`, session 제거 등)
- `reference-api.md`, `reference-compliance.md`
- `diagrams/`

practice 문서
- `mcp-security-authn-official/README.md` — practice 소개, 실행, 코드 지도(모듈·클래스 → 안내서 장 링크), 직접 확인할 것. `API-SPEC.md`·`SEQUENCES.md`는 없앤다(안내서 장이 official 코드로 설명한다).
- `mcp-security-authn-chat-memory/README.md`, `mcp-security-authn-community/README.md` — "official과 다른 점"만 쓴다. community는 대체 표를 유지한다. 두 practice의 `API-SPEC.md`·`SEQUENCES.md`는 없앤다. 필요한 다이어그램은 README에 넣는다.
- 저장소 `README.md`의 링크를 새 구조로 바꾼다.

## 3. official에 로컬 client 추가 (`local-client` 모듈)

Claude Desktop·Cursor처럼 사용자 기기에서 도는 MCP client의 흐름을 코드로 보여 준다.

- `practice/mcp-security-authn-official/local-client` — 새 Gradle 프로젝트. Spring Boot 없이 Java 21 + MCP Java SDK로 만든 command-line 앱
- 흐름
  1. 인자로 MCP Server 주소를 받는다.
  2. discovery(3장 규칙)를 한다.
  3. `127.0.0.1`의 임시 포트에 callback용 HTTP server(JDK `HttpServer`)를 연다.
  4. PKCE `S256`과 `resource`, `state`를 넣은 authorization URL을 browser로 연다(`Desktop.browse`, 안 되면 주소를 출력).
  5. callback에서 `state`와 `iss`를 확인하고, `client_id=local-mcp-client`만으로(`client_secret` 없이) token을 받는다.
  6. MCP SDK로 `initialize`, `tools/list`, `tools/call`을 한다.
- token은 메모리에만 둔다. refresh token은 Authorization Server가 public client에 발급하지 않는다.
- 등록: 이미 있는 `local-mcp-client`를 쓴다. redirect URI는 `http://127.0.0.1:8123/callback`으로 등록돼 있고, 루프백은 포트가 달라도 허용된다.
- 설계 원칙: 한 클래스가 한 단계를 맡는다(`Discovery`, `Pkce`, `LoopbackCallbackServer`, `AuthorizationRequest`, `AuthorizationResponse`, `TokenClient`, `McpCalls`, `Main`). 각 클래스는 7장에서 그대로 인용할 수 있을 만큼 짧게 쓴다.
- 테스트: 단계별 단위 테스트(PKCE 값, callback의 `state`·`iss` 검증, token 요청 모양, discovery 확인 규칙). 전체 흐름은 자동 테스트로 만들지 않는다. auth-server·shop-mcp-server를 띄워 한 번 돌리고 그 출력을 캡처로 남긴다. 캡처할 때는 `--no-browser`로 돌리고 browser 대신 curl이 login·consent를 한다(local-client 코드는 사람이 browser로 쓸 때와 같다).
- `run.sh`·`stop.sh`는 바꾸지 않는다. local-client 실행 방법은 7장과 official README에 쓴다.

## 4. official 코드 주석 정리

"다 보면 다룰 줄 안다"에는 코드도 들어간다. official 세 모듈(+`local-client`)의 주석과 javadoc을 결정 A와 1절 문체로 고친다(기술 용어 영어, 번역 어투 제거). 동작은 바꾸지 않는다. chat-memory·community 코드 주석은 범위 밖이다.

## 5. 기존 자료 처리

- 캡처(`docs/superpowers/captures/`)는 그대로 쓴다. 안내서 본문은 캡처 값을 보여 주되 캡처 번호는 적지 않는다. 부록(`reference-*.md`)은 캡처 번호를 써도 된다.
- 새 캡처: `local-client` 실행 출력 한 개.
- 옛 문서의 외부 링크(명세·RFC)는 새 문서의 명세 근거 표나 부록으로 옮긴다. 옮기지 않는 것은 이유와 함께 목록으로 남긴다(`check_docs.py --links-from`).

## 진행 순서

1. 스킬·검사 스크립트·다이어그램 스크립트 개정
2. `local-client` 모듈(TDD)과 실행 캡처
3. 안내서 1~2장, 3장(시험판 옮기기), 4~6장
4. 7장(local-client), 8~9장
5. 부록 두 개(API, 준수표)
6. practice README 세 개, 저장소 README, 옛 문서 삭제
7. official 코드 주석 정리

## 검증

- 새 검사 스크립트로 안내서·부록·README 위반 0, 다이어그램 PNG 최신.
- 모든 모듈 `./gradlew test` 통과(`local-client` 포함).
- `local-client`를 실제로 한 번 돌려 `tools/call` 결과까지 받는다.
- 옛 문서 외부 링크가 빠짐없이 옮겨졌거나 이유와 함께 목록에 있다.

## 다루지 않는 것

- CIMD 구현(보류 — 4장에서 개념만), scope·step-up(`mcp-security-authz`로 보류 — 5·6장에서 개념만), 상태 있는 tool 핸들(보류).
- chat-memory·community 코드 변경.
- 캡처(C·S·P) 다시 뜨기.
