# MCP 인증 practice 전체 리뷰 반영 — 설계

## 목표

main(8a3e11c) 전체 리뷰에서 나온 결함·문서 오류·준수표 누락을 한 번에 고친다.
세 practice 의 코드·테스트·스크립트·캡처와 문서 12개, 문서 스킬이 대상이다.
완료 뒤 준수표의 모든 판정이 코드 근거와 맞아야 한다.

## 결정 (사용자 확인)

| # | 항목 | 결정 |
|---|---|---|
| A | MCP session 을 사용자에 묶기(MCP Security Best Practices, SHOULD) | official·community: 구현하지 않고 준수표에 "아니오"와 이유(agent 가 MCP client 하나를 모든 사용자와 공유). **chat-memory: 구현** — agent 를 사용자별 MCP client 로, MCP Server 가 session 을 token 의 사용자에 묶음 |
| B | 로컬 서버를 127.0.0.1 에만 bind(Transports, SHOULD) | 9개 앱에 `server.address: 127.0.0.1`. 주석에 뜻과 실제 배포에서 쓸 값을 적는다 |
| C | `/api/chat` CSRF | 세 practice 모두 예외 없이 CSRF 를 적용한다. Spring Security 7.1 `csrf.spa()` 가 `index.html` 흐름(첫 GET 에서 `XSRF-TOKEN` 쿠키 발급 → `X-XSRF-TOKEN` 헤더로 전송)을 통과시키면 그것으로 통일하고, 아니면 chat-memory 의 기존 방식(`SpaCsrfTokenRequestHandler` + `CsrfCookieFilter`)으로 통일한다 |
| D | community 의 기대 `aud` 를 요청 URL 로 계산 | 모듈 기능을 유지한다. Host 검증(`OriginValidationFilter`)이 인증보다 먼저 막는다는 의존 관계를 테스트로 고정하고 문서에 적는다 |

## 1. 보안 결함·스크립트·준수표 정정·주석

### 1.1 public client consent 우회 (세 practice)

- 원인: Spring `OAuth2AuthorizationCodeRequestAuthenticationProvider#isAuthorizationConsentRequired` 는 scope 가 `openid` 하나면 consent 를 건너뛴다. community 는 모듈 `McpNoScopeClientConsentNotRequired` 가 scope 가 없을 때도 건너뛴다. `PublicClientConsentService` 는 저장된 consent 만 막는다.
- 수정: authorization code request provider 의 consent 판정(`setAuthorizationConsentRequired`)을 바꾼다. 등록된 client 의 인증 방식이 `none` 이면 scope 와 관계없이 `true`, 아니면 기존 판정. official·chat-memory 는 `AuthorizationServerConfig` 의 provider 설정에서, community 는 모듈의 post-processor 뒤에 적용되는 경로에서 건다.
- `PublicClientConsentService` 는 유지한다(저장된 consent 무시 — 이전 consent 로 건너뛰는 경로를 막음). 두 장치의 역할을 javadoc 과 yml 주석에 나눠 적는다.
- 테스트: public client 의 `scope=openid` 단독 요청과 scope 없는 요청이 200 consent 화면을 받는다. confidential client 흐름은 그대로다.

### 1.2 `stop.sh`·`run.sh` (세 practice)

`lsof -ti tcp:PORT` 에 `-sTCP:LISTEN` 을 붙인다. 수신 프로세스만 고르고, 연결만 한 브라우저 등을 종료하지 않는다.

### 1.3 준수표 정정

- 25번: 1.1 수정 뒤 판정 근거를 "consent 판정 + 저장된 consent 무시" 두 장치로 적는다.
- 11번: 3.4 수정(official·chat-memory 의 Origin·Host 검증을 인증 앞으로) 뒤 근거를 바로잡는다.
- 9번·10번: "요청별 issuer 기록" 단서를 9번으로 옮긴다(2026-07-28 Authorization Response Validation).

### 1.4 틀린 주석·javadoc

- community·official `ShopMcpServerApplicationTests` 의 `issuer-uri`·Basic 401 설명.
- `application.yml`·`OAuth2TokenAttachingRequestCustomizer` 의 없는 "README §7.1/7절" 참조.
- `ChatController` 의 "Step 10" 계획 문구.
- community `McpAuthorizationStandardConfig` javadoc 의 ID token 설명(모듈은 access token 만 건너뛰고 ID token 에는 `aud=resource` 를 씌운다).
- `OAuth2TokenAttachingRequestCustomizer` 의 "직접 쓰는 두 클래스 중 하나".
- chat-memory 의 "부모 practice" 가 두 뜻으로 쓰이는 곳.
- 각 auth-server yml 과 `PublicClientConsentService` 의 "매번 consent" 설명(1.1 에 맞게).

## 2. 문서와 스킬

### 2.1 사실 오류

- community README·SEQUENCES: 모듈 customizer 가 ID token `aud` 를 `resource` 로 덮어쓰고 `ResourceAudienceTokenCustomizer` 가 client_id 로 되돌린다. 다이어그램에 ID token 분기를 더한다.
- community 대체 표: official `ChatController` 는 `Hooks.enableAutomaticContextPropagation`, community 는 `.contextWrite(...)`. 제공 열은 "직접 얹은 확장".
- 허브: "supplement 에 원본 token 이 남아 있다" 절을 지운다(원본 token 은 `2026-09-12-*.txt` 에만 있다 — 캡처 관리 이야기는 범위 밖).
- 허브 :464 S13 은 "모르는 session", 끝난 session 은 S16.
- `PublicClientConsentService#findById` 는 public client 일 때만 `null`.
- chat-memory SEQUENCES "경로 문자" → 코드의 규칙("영숫자·한글·`_`·`-` 외 문자").
- community README :10 "모듈이 discovery 미지원" → 지원하지 않는 부분만(AS metadata discovery·PKCE 확인·`resource`·`iss`).
- community SEQUENCES :207 `iss` 비교 → metadata `issuer` 일치.
- official README :158-159 캡처 근거 없는 관측은 근거를 달거나 지운다.

### 2.2 이동 링크와 구조

- `MCP-API-SPEC.md`: 엔드포인트 절마다 근거 줄 옆에 허브 절·시퀀스 절 링크.
- `MCP-SEQUENCES.md`: 절 끝에 practice 시퀀스 링크 한 줄("구현: official … · community …"). 단계 설명의 MUST 재인용은 허브 앵커 링크로 바꾼다.
- practice SEQUENCES(official 기준): `registration`(yml pre-registration, confidential·public client)과 `mcp-server-validation`(`SecurityConfig` → `JwtDecoder` → `McpProtocolVersionFilter` → transport 검증기) 두 절을 더한다. chat-memory·community 는 링크 한 줄, 다른 점만 그린다.
- official README: community 기준 서술("community 에서 배운 것", "보였지만" 류)을 걷어내고 official 단독으로 다시 쓴다. 비교는 community 대체 표 한 곳에만.
- 포트·client_id 표는 허브 §2 한 곳에 두고 나머지는 링크로. SDK 버전 설명 반복(허브 4곳)은 §1 한 곳으로.
- chat-memory README: "직접 쓴 코드" 표(`ConversationId`·`ConversationController`·`ChatMemoryConfig`·사용자별 MCP client 구성요소), 중복 절(tool 결과와 memory) 하나 삭제, 범위 밖 서술(테스트 배선·git 상태·설계 문서 링크) 삭제.
- 용어: `재동의`·`인가된`·`커뮤니티`·`엔드포인트`·`브라우저` 등 checker 가 놓친 한국어 용어, `모듈`/`module` 혼용 정리.

### 2.3 스킬 보강 (`.claude/skills/writing-practice-docs/`)

- SKILL.md 규칙 추가: 범위(MCP 와 그 Authorization Server — 테스트 배선·운영 절차·저장소 관리는 쓰지 않음), 이동 링크(API 절 → 허브·시퀀스, 표준 시퀀스 → practice 시퀀스), practice SEQUENCES 필수 절(구성·등록·런타임), 공유 표는 허브 한 곳, 기준 practice 는 단독으로 서술, mermaid 렌더 검사(`mmdc`).
- `check_docs.py`: 표 칸도 문장 수 검사, 문장당 글자 수 상한, 합성어 용어(앞에 한글이 붙은 경우), 과거형 서사 표현 확대, `관측:` 줄의 캡처 ID 형식 확인. 테스트를 먼저 쓴다.
- `terms.txt`: `재동의`, `브라우저`, `엔드포인트`, `커뮤니티`, `인가된` 등 추가.
- 스킬 변경은 writing-skills 절차를 가볍게 따른다: 바뀐 규칙마다 걸리는 예·통과하는 예를 검사 스크립트 테스트로 고정한다(규칙 문구만 바꾸는 경우는 표본 GREEN 1회로 확인).

## 3. 준수표 추가 항목과 작은 보안 개선

### 3.1 준수표에 추가할 행

| 항목 | 출처·수준 | official | chat-memory | community |
|---|---|---|---|---|
| MCP session 을 사용자에 묶기 | Security Best Practices SHOULD | 아니오(설계 이유) | 예(3.6) | 아니오(설계 이유, 모듈 `sessionBinding()` 은 끔) |
| session ID 가 인증을 대신하지 않음·예측 불가 | Security Best Practices MUST | 예 | 예 | 예 |
| 로컬 실행 시 127.0.0.1 bind | Transports SHOULD | 예(B) | 예 | 예 |
| 지원하지 않는 `MCP-Protocol-Version` 에 400 | Transports MUST | 예 | 예 | 예 |
| redirect URI 정확 일치 | MCP Open Redirection MUST | 예 | 예 | 예 |
| authorization URL 스킴 검증 | Security Best Practices MUST | 간접(신뢰한 issuer 의 metadata 만 씀) — 3.3 에서 직접 검사를 넣으면 예 | 같음 | 같음 |

판정은 구현 뒤 코드로 확인해 적는다. 16번(scope)에는 "PRM 에 `scopes_supported` 가 없으면 scope 생략(SHOULD) — agent 는 OIDC 로그인 때문에 `openid profile` 을 보낸다"를 한 줄 더한다.

### 3.2 SSRF 순서 (세 practice)

issuer binding 검사를 Authorization Server Metadata GET 전으로 옮긴다. discovery 가 `credentials-issuer` 를 받아 PRM 의 `authorization_servers` 가 다르면 GET 없이 멈춘다. 준수표 27번 근거에 반영한다.

### 3.3 authorization URL 스킴 검증 (세 practice)

AS metadata 의 `authorization_endpoint`·`token_endpoint` 가 `http`/`https` 가 아니면 discovery 가 실패한다(Security Best Practices MUST). 테스트로 고정한다.

### 3.4 official·chat-memory 의 Origin·Host 검증 위치

SDK `DefaultServerTransportSecurityValidator` 를 쓰는 필터를 Spring Security 앞(인증 전)에 둔다. token 이 없어도 잘못된 Origin 은 403, Host 는 421. 테스트: token 없이 잘못된 Origin → 403.

### 3.5 token request 에서 `resource` 추가 거부 (세 practice)

authorization request 에 `resource` 가 없었는데 token request 가 `resource` 를 보내면 `invalid_target`. `ResourceAudienceTokenCustomizer`(community 는 같은 클래스)에서 처리하고 테스트로 고정한다. community 에서 `resource` 를 두 번 보내 500 이 나는 경로(모듈 캐스트)는 practice 의 검증이 먼저 `invalid_target` 을 돌려주도록 막는다.

### 3.6 chat-memory MCP session 을 사용자에 묶기

- **MCP Server**: 인증 뒤에 도는 session binding 필터. 요청의 `Mcp-Session-Id` 가 다른 사용자(token 의 `sub`)에 묶여 있으면 403. `initialize` 응답의 `Mcp-Session-Id` 를 현재 사용자에 묶는다. `DELETE` 로 끝난 session 은 묶음을 지운다. 참고 구현: 모듈 `McpSessionFilter`(community 는 쓰지 않음).
- **agent**: 사용자별 `McpSyncClient`. Spring AI MCP client 자동 구성의 공유 client 를 쓰지 않고, 로그인한 사용자(principal 이름)마다 transport 와 client 를 만들어 첫 채팅 때 `initialize` 한다. `ChatController` 는 요청마다 그 사용자의 client 에서 tool callback 을 얻어 넘긴다. 로그아웃과 HTTP session 종료 때 그 사용자의 client 를 닫는다(`DELETE` 로 session 종료). token 부착(`OAuth2TokenAttachingRequestCustomizer`)과 SecurityContext 전달은 그대로 쓴다.
- **테스트**: 서버 — alice 의 session 에 bob 의 token → 403, 같은 사용자는 통과, DELETE 뒤 묶음 해제. agent — alice·bob 이 서로 다른 client(다른 session)를 받고, 로그아웃이 client 를 닫는다.
- **문서**: chat-memory README·SEQUENCES·API-SPEC 에 반영(대화 격리에 이어 MCP session 도 사용자별). 준수표 새 행 chat-memory "예".

### 3.7 D: community audience 의존 관계

테스트: Host 를 조작하고 다른 resource 용 token 을 실은 요청이 421 로 막힌다(audience 계산 전). 문서: 모듈은 기대 `aud` 를 요청 URL 로 계산하므로 Host 검증이 먼저 와야 하고, official 은 고정 값(`jwt.audiences`)으로 검증한다 — community 대체 표와 허브 4.9.

### 3.8 B: bind 주소 주석

9개 `application.yml` 에 `server.address: 127.0.0.1` 과 주석: 이 값이 무엇인지(서버가 받을 네트워크 주소, 127.0.0.1 이면 이 기기 안에서만 접속), 근거(Transports SHOULD), 실제 배포에서 쓸 값(컨테이너·reverse proxy 뒤에서는 `0.0.0.0` 또는 그 네트워크 인터페이스 주소, 외부 노출은 proxy·방화벽이 맡음).

### 3.9 테스트 공백

- refresh 요청이 실제 bean(`refreshTokenTokenResponseClient`)을 거치는지(`TokenRefreshTest`).
- `OAuth2TokenAttachingRequestCustomizerTest` 가 registrationId·principal 전달을 확인.
- `McpAuthorizationStandardTest` 의 `iss` 불일치가 `invalid_token` 까지 확인, 만료·다른 키 서명 token 케이스.
- chat-memory: `/api/chat` CSRF 거부, `X-XSRF-TOKEN` 헤더 경로, `ChatController` 의 `CONVERSATION_ID` 배선(`ChatModel` mock), 목록 테스트에 `hasItem`.
- community `McpAuthorizationDiscoveryTest`: official 에 있는 resource 불일치·루트형 fallback·401 아닌 응답 케이스.
- `resource` 를 여러 개 보낸 authorization·token 요청.

## 캡처

- public client 스크립트에 단계 하나(`P8-1`): `scope=openid` 단독 요청도 200 consent. 세 practice P 캡처를 다시 뜬다(단계 번호는 그대로).
- 수신 주소: 세 practice 를 띄워 `lsof -nP -iTCP -sTCP:LISTEN` 결과를 새 캡처 파일로 남긴다(S21 대체). 문서에서 S21 을 인용한 곳은 새 파일로 바꾼다.
- chat-memory session 묶기와 Origin 검증 위치는 테스트를 근거로 문서에 적는다(캡처 추가 없음).
- 다른 C/S 캡처는 다시 뜨지 않는다.

## 진행 순서

1. 스킬·검사 스크립트 보강(문서 task 들이 새 규칙을 쓴다).
2. 세 practice 공통 보안 수정: consent 판정, token 시점 `resource`, SSRF 순서, URL 스킴 검증, `stop.sh`/`run.sh`, bind 주소, 틀린 주석.
3. official·chat-memory Origin·Host 검증 위치, CSRF 통일(C).
4. chat-memory session 묶기(서버·agent).
5. community audience 의존 테스트(D)와 community 전용 수정.
6. 테스트 공백 보강(해당 practice task 에 넣어도 됨).
7. 캡처 재생성.
8. 문서: 허브(준수표 포함) → API·SEQUENCES → practice 3종.

## 검증

- 세 practice 9개 모듈 Gradle 테스트 전부 통과(`auth-server` 를 띄우지 않고).
- 새 검사 스크립트로 12개 문서 위반 0, mermaid 전체 `mmdc` 렌더 성공.
- 준수표의 모든 행을 코드 근거와 대조(최종 리뷰).
- 1.1 은 수정 전 테스트가 실패함을 확인한 뒤 수정한다(TDD).

## 다루지 않는 것

- official·community 의 사용자별 MCP client(A 결정).
- HTTPS(준수표 12번), SSE 이벤트 `id`(19번) — 기존 결정 유지.
- scope 설계·step-up(`mcp-security-authz`), CIMD(보류 중).
