# CIMD(Client ID Metadata Document)와 공개 클라이언트 지원 — 설계

> **상태: 보류(2026-09-24).** 별도 practice 로 분리해 나중에 진행한다. 이 문서는 그때 쓸 설계다.
> 보류 시점의 결론: 클라이언트별 CIMD 지원이 갈린다 — Claude(Code·Desktop·Cowork)와 ChatGPT·Codex 는 지원하고,
> Gemini CLI 는 아직 지원하지 않는다([gemini-cli#25724](https://github.com/google-gemini/gemini-cli/issues/25724)).
> CIMD 를 지원하지 않는 클라이언트는 **사전 등록** 경로로 붙는다(명세 우선순위 1번). 사전 등록 자체는 세 practice 에 이미 동작한다 —
> 다만 지금 등록된 클라이언트는 에이전트 전용 기밀 클라이언트라, 로컬 MCP 클라이언트를 붙이려면 공개 클라이언트(`none` + PKCE + 루프백 리다이렉트) 항목이 필요하다.
> 미결 결정 하나: CIMD 의 `client_id` 는 HTTPS 여야 하는데 학습 환경은 localhost 다. 종단 캡처를 남기려면
> (1) 테스트로만 검증하거나 (2) 설정으로 루프백 HTTP 를 한시 허용하고 준수표에 위반으로 기록해야 한다.

## 목표

인증 practice 세 개의 인가 서버가 **사전 관계가 없는 MCP 클라이언트**(Claude, ChatGPT 등)를 받아들일 수 있게 한다.

1. **CIMD** — HTTPS URL 을 `client_id` 로 쓰는 클라이언트를 인가 서버가 해석·검증·캐시한다.
2. **공개 클라이언트** — 비밀 없는 클라이언트(`token_endpoint_auth_method: none`)를 허용하고 메타데이터에 광고한다.
3. 세 practice 의 README 와 `practice/MCP-AUTHORIZATION.md` 에 표준 내용을 기존 방식대로 기록한다.

DCR(RFC 7591)은 **다루지 않는다** — MCP 2026-07-28 에서 deprecated 이고, 이 저장소는 그 판단을 따른다.

## 왜 이것이 필요한가

MCP 2026-07-28 Client Registration 이 정한 클라이언트 우선순위는 **사전 등록 → CIMD → DCR(하위 호환) → 사용자 입력** 이다.
지금 세 practice 의 인가 서버는 사전 등록된 에이전트 하나만 안다. 사전 관계가 없는 클라이언트가 붙을 길이 없으므로, DCR 을 버린 이상 CIMD 가 유일한 자동 경로다.

## 기준 조항

| 출처 | 요구 |
|---|---|
| MCP 2026-07-28 Client Registration | AS 는 URL 형태 `client_id` 를 만나면 문서를 가져온다(SHOULD), 문서의 `client_id` 가 URL 과 일치하는지 검증(MUST), 인가 요청의 `redirect_uri` 를 문서 목록과 대조(MUST), JSON 구조·필수 필드 검증(MUST), HTTP 캐시 헤더 존중(SHOULD), `client_id_metadata_document_supported: true` 광고 |
| CIMD draft-00 §3 | `client_id` URL 은 https, 경로 필수, `.`·`..` 세그먼트 금지, 프래그먼트 금지, 사용자명·비밀번호 금지, 쿼리 비권장 |
| CIMD draft-00 §4.1 | 문서에 `client_id` 필수(URL 과 정확히 일치). `client_secret`·`client_secret_expires_at` 금지. `client_secret_post`·`client_secret_basic`·`client_secret_jwt` 인증 방식 금지 |
| MCP 2026-07-28 | 문서에 최소 `client_id`, `client_name`, `redirect_uris` 포함(MUST) |
| CIMD draft-00 §4.4 | 캐시 헤더 존중. 오류 응답·유효하지 않은 문서는 캐시 금지 |
| CIMD draft-00 §6.5 | SSRF — 사설·루프백 주소 회피, 비 HTTP 스킴 위험 인식 |
| CIMD draft-00 §6.6 | 응답 크기 제한. 권장 최대 5KB |
| CIMD draft-00 §6.4 | 인가 화면에 `client_id` 의 호스트명을 표시 |
| CIMD draft-00 §6.1 | `redirect_uris` 와 `client_id` 호스트의 관계에 제약을 둘 수 있다(MAY) |

## 현재 상태와 차이

| 항목 | 지금 | 필요 |
|---|---|---|
| URL 형태 `client_id` 해석 | 없음(사전 등록 하나뿐) | official·chat-memory 는 직접 구현, community 는 모듈 기능 활성화 |
| `client_id_metadata_document_supported` | 없음 | AS 메타데이터에 광고 |
| `none` 인증 방식 | 광고하지 않음(Spring 은 이 값을 절대 넣지 않는다 — `OAuth2AuthorizationServerMetadataEndpointFilter` 확인) | 메타데이터에 추가 |
| 공개 클라이언트 처리 | Spring 이 `PublicClientAuthenticationProvider` 로 이미 지원 | CIMD 클라이언트를 `NONE` + PKCE 필수로 만들어 준다 |
| 동의 화면 | `require-authorization-consent: false` | CIMD 클라이언트에만 동의 필요(아래 결정 참고) |

## 설계

### 공통 — CIMD 클라이언트의 모습

인가 서버는 URL `client_id` 를 만나면 그 문서를 가져와 다음 형태의 클라이언트로 변환한다.

- `clientId` = 문서 URL 그대로
- 인증 방식 = `none` (공개 클라이언트). 문서가 `private_key_jwt` 를 지정하면 그것을 쓰되, 이 practice 범위에서는 `none` 만 지원하고 나머지는 거부한다
- 그랜트 = `authorization_code` (+`refresh_token`)
- `redirect_uris` = 문서의 값
- PKCE 필수(`requireProofKey(true)`) — 비밀이 없으므로 코드 가로채기 방어가 PKCE 뿐이다
- 동의 필요(`requireAuthorizationConsent(true)`)
- scope = `openid`, `profile` (scope 설계는 다음 practice 주제)

### official (직접 구현)

새 클래스 넷을 만든다. 순수 Spring Security 7.1 에는 CIMD 가 없다.

| 클래스 | 책임 |
|---|---|
| `ClientIdMetadataDocumentProperties` | 허용 정책 — 최대 문서 크기(5KB), 캐시 기본·최대 수명, 연결·읽기 타임아웃, 루프백 허용 여부(테스트·학습용) |
| `ClientIdUrlValidator` | draft §3 의 URL 규칙 검사(https, 경로, `.`/`..` 금지, 프래그먼트 금지, 사용자 정보 금지) + §6.5 사설·루프백 차단 |
| `ClientIdMetadataDocumentRegisteredClientRepository` | URL `client_id` 해석 — 가져오기(크기·타임아웃 제한) → 검증(§4.1 금지 필드, `client_id` 일치, 필수 필드) → `RegisteredClient` 변환 → 캐시(`Cache-Control: max-age` 존중, 오류·무효 문서는 캐시하지 않음) |
| `DelegatingRegisteredClientRepository` | 사전 등록 저장소를 먼저 보고, 없으면 CIMD 저장소로 위임 |

`AuthorizationServerConfig` 는 위 저장소를 `RegisteredClientRepository` 빈으로 노출하고, 메타데이터 커스터마이저에 두 가지를 더한다.

- `client_id_metadata_document_supported: true`
- `token_endpoint_auth_methods_supported` 에 `none` 추가(AS 메타데이터와 OIDC 문서 양쪽)

### chat-memory

official 의 네 클래스와 설정 변경을 패키지·포트만 바꿔 이식한다. 대화 격리·CSRF 구성은 건드리지 않는다.

### community (모듈 활성화)

모듈에 이미 같은 기능이 들어 있다(`ClientIdMetadataDocumentRegisteredClientRepository`, `DefaultClientIdMetadataDocumentResolver`, `DefaultClientMetadataValidator`, `DelegatingRegisteredClientRepository`). `McpAuthorizationServerConfigurer.cimd(true)` 로 켜고, 모듈이 넣는 `client_id_metadata_document_supported` claim 이 우리 메타데이터 커스터마이저에 덮이지 않도록 한 람다에서 함께 설정한다(앞 작업에서 확인한 단일 필드 덮어쓰기 제약).

`none` 광고는 모듈이 하지 않으므로 우리 커스터마이저에서 더한다.

### 동의 화면

CIMD 클라이언트는 사전 관계가 없다. 사용자가 "누구에게" 접근을 허용하는지 볼 수 있어야 하므로 **CIMD 로 들어온 클라이언트에만** 동의를 요구한다(`RegisteredClient` 단위 설정이라 사전 등록 에이전트는 영향받지 않는다). 동의 화면에는 draft §6.4 대로 `client_id` 의 호스트명이 보여야 한다 — Spring 기본 동의 화면이 무엇을 표시하는지 확인하고, 부족하면 표시 방법을 정한다.

## 다루지 않는 것

| 항목 | 이유 |
|---|---|
| DCR | 2026-07-28 deprecated. 이 저장소의 결정 |
| `private_key_jwt` CIMD 클라이언트 | `jwks_uri` 검증·키 회전까지 따라오므로 범위를 넘는다. 문서가 요구하면 거부하고 그 사실을 문서에 적는다 |
| `logo_uri` 선취 캐시(§6.7) | 화면 자원 처리라 인가 흐름과 무관 |
| 도메인 신뢰 정책(§6.8) | 정책 영역. 허용 목록 훅만 남기고 기본은 비활성 |
| scope 설계·step-up | 다음 practice(`mcp-security-authz`) |
| HTTPS 공개 배포 | 학습용 localhost 유지. 준수표에 위반으로 이미 기록됨 |

## 검증

| 대상 | 방법 |
|---|---|
| URL 규칙 | 단위 테스트 — https 아님·경로 없음·`..` 포함·프래그먼트·사용자정보·사설 IP 각각 거부 |
| 문서 해석 | `MockRestServiceServer` — 정상 문서, `client_id` 불일치, 필수 필드 누락, `client_secret` 포함, 금지된 인증 방식, 5KB 초과, 캐시 헤더 존중, 오류 응답 미캐시 |
| 인가 흐름 | MockMvc — URL `client_id` 로 인가 요청 → 문서의 `redirect_uri` 면 통과, 아니면 거부. PKCE 없으면 거부. 토큰 요청이 `none` 으로 동작 |
| 메타데이터 | AS 문서와 OIDC 문서에 `client_id_metadata_document_supported: true` 와 `none` |
| 기존 회귀 | 사전 등록 에이전트의 기존 흐름(로그인·토큰·MCP 호출)이 그대로 통과 |
| 종단 | 세 practice 를 띄워 기존 캡처를 재생성하고, CIMD 클라이언트 흐름을 별도 캡처로 추가 |

## 문서 작업

- `practice/MCP-AUTHORIZATION.md` — 4.4절(클라이언트 등록)을 CIMD 중심으로 확장하고, 시퀀스 다이어그램 하나(CIMD 흐름)를 추가한다. 엔드포인트 명세에 클라이언트 메타데이터 문서 항목을 더하고, 준수표에 CIMD·공개 클라이언트 행을 추가한다. DCR 은 "다루지 않음"으로 유지하되 deprecated 근거를 명시한다.
- 세 practice README — 새 클래스와 설정, practice 별 구현 차이(직접 구현 vs 모듈 활성화)를 적는다.
- 출처는 기존 방식대로 조항 링크까지 남긴다.

## 작업 순서

1. official — URL 검증기·문서 해석·저장소·위임·메타데이터·동의, 테스트 포함
2. chat-memory 이식
3. community 모듈 활성화 + 메타데이터
4. 세 practice 종단 검증과 캡처(기존 재생성 + CIMD 흐름 추가)
5. 문서 갱신(학습 문서 → README)
