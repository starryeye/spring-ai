# MCP 인증 학습 문서 재구성 — 설계

## 목표

MCP 와, MCP 를 쓰려고 만든 Authorization Server 를 학습하는 문서를 핵심만 남긴 계층형으로 다시 쓴다.
최상위와 각 practice 에 같은 3종 문서(허브 · API 명세 · 시퀀스)를 둔다.
코드·테스트·캡처는 바꾸지 않는다. 문서만 바꾼다.

## 문서 구성

| 위치 | 허브 | API 명세 | 시퀀스 |
|---|---|---|---|
| `practice/` | `MCP-AUTHORIZATION.md` (다시 씀) | `MCP-API-SPEC.md` (새로) | `MCP-SEQUENCES.md` (새로) |
| `practice/mcp-security-authn-official/` | `README.md` (다시 씀) | `API-SPEC.md` (새로) | `SEQUENCES.md` (새로) |
| `practice/mcp-security-authn-chat-memory/` | `README.md` (다시 씀) | `API-SPEC.md` (새로) | `SEQUENCES.md` (새로) |
| `practice/mcp-security-authn-community/` | `README.md` (다시 씀) | `API-SPEC.md` (새로) | `SEQUENCES.md` (새로) |

- 최상위 3종은 표준과 세 practice 전체를 다룬다. practice 3종은 그 프로젝트가 다루는 것만 다룬다.
- 같은 내용은 한 곳에만 둔다. 다른 곳에서는 한 줄 요약과 링크로 가리킨다.

## 작성 규칙 (12개 파일 공통)

### 계층

- 주제마다 2~3문장 요약을 먼저 쓰고, 하위 제목에서 다시 2~3문장씩 풀어 간다.
- 한 문단은 3문장을 넘기지 않는다. 나열은 표나 목록으로 쓴다.
- "2~3줄"은 마크다운 줄바꿈 위치에 따라 달라지므로 문장 수로 잰다.

### 명세 인용

- 조항 링크와 요구 수준(MUST·SHOULD·MAY, REQUIRED·RECOMMENDED·OPTIONAL)은 원문 그대로 둔다. 설명은 압축한다.
- API 명세 표는 원문 기준으로 REQUIRED·RECOMMENDED·OPTIONAL 필드를 빠짐없이 적는다. 구현을 보고 역으로 쓰지 않는다.
- 관측은 핵심만 "관측: …" 한 줄로 쓴다. 요청·응답 예시는 API 명세 파일에 엔드포인트당 하나만 둔다.

### 용어

- 전문 용어는 영어 그대로 쓴다. 한국어 조사는 붙여도 된다.
- 대상 예: authorization, authorization request, authorization response, token request, callback, Authorization Server, Resource Server, MCP Server, MCP Client, Protected Resource Metadata(PRM), Authorization Server Metadata, access token, refresh token, ID token, authorization code, confidential client, public client, pre-registration, Client ID Metadata Document(CIMD), Dynamic Client Registration(DCR), consent, redirect URI, audience, issuer, scope, PKCE, code verifier, code challenge, resource indicator, discovery, challenge, session, filter chain, bean.

### 쓰지 않는 것

- 개발 과정 이야기: "처음엔…", "고쳤다", "Task N", "실측했더니", 착각이나 버그를 고친 경위.
- README 의 실험 상세와 트러블슈팅 절.
- 예외로 남긴다: "Spring 기본 동작은 X 인데 명세는 Y 를 요구해서 이 practice 가 Z 로 채웠다". 명세와 비교하는 설명이기 때문이다.

### 범위

- MCP 와 우리가 만든 Authorization Server 만 다룬다.
- 쓰지 않는 기능(2026-07-28 stateless 전송, DCR, CIMD 구현)은 "다루지 않음"이나 "달라지는 점"으로 몇 줄만 쓴다.

## 최상위 3종

### `MCP-AUTHORIZATION.md` (허브)

1. 범위와 기준 리비전: 전송은 2025-11-25, 인가는 2025-11-25 에 2026-07-28 추가분을 더한 것.
2. 구성요소: 네 역할과 두 client(confidential·public), 신뢰 관계, 포트 표, 구현 위치 지도 표.
3. 전체 흐름 요약: 작은 다이어그램 하나와 `MCP-SEQUENCES.md` 링크.
4. 단계별 4.1~4.10: 401 challenge → PRM → Authorization Server Metadata → client 등록 → authorization request와 consent → callback `iss` → token → MCP 호출과 session → token 검증 → 만료와 refresh.
   - 각 단계의 틀: 무엇을 왜 하는가(2~3문장) → 명세 규칙(압축, 요구 수준 원문) → 이 practice 구현 → 작은 요약 다이어그램 → `MCP-API-SPEC.md`·`MCP-SEQUENCES.md` 링크.
5. 보안 고려사항: token passthrough, confused deputy, mix-up, open redirect와 PKCE, public client 사칭과 재동의, localhost HTTP.
6. 준수표: 열은 "항목 · 요구 수준 · 세 practice 결과 · 근거". 지금 문서의 1~26번 항목을 모두 옮긴다.
7. 2026-07-28 전송에서 달라지는 점: 몇 줄.
8. 다루지 않는 것.
9. 출처.

### `MCP-API-SPEC.md` (API 명세)

- 파일 첫머리에 공통 헤더(`MCP-Protocol-Version`, `Mcp-Session-Id`, `Origin`, `Accept`)와 표기법을 한 번만 정리한다.
- 엔드포인트 하나를 쓰는 틀: 목적 한 줄 → 근거 조항 → 요청 표 → 응답 표 → 오류 표 → 예시 하나(캡처 발췌, token 은 줄여서).
- 표 열: 이름 · 위치 · 표시(원문 요구 수준) · 설명 · 이 practice.
- 대상

| 구분 | 엔드포인트 |
|---|---|
| MCP Server | `POST /mcp`(token 없음 → 401), `GET /.well-known/oauth-protected-resource[/mcp]`, `POST /mcp`(Bearer: `initialize`·`notifications/initialized`·`tools/list`·`tools/call`), `GET /mcp`, `DELETE /mcp` |
| Authorization Server | `GET /.well-known/oauth-authorization-server`, `GET /.well-known/openid-configuration`, `GET /oauth2/authorize`, `POST /oauth2/authorize`(consent), Authorization Response(redirect), `POST /oauth2/token`(authorization_code), `POST /oauth2/token`(refresh_token), `GET /oauth2/jwks` |
| 명세만, 미구현 | Client ID Metadata Document(client 가 호스팅), `POST /register`(DCR, deprecated — 한 줄) |

### `MCP-SEQUENCES.md` (시퀀스)

각 시퀀스는 mermaid 다이어그램 하나와, 번호 단계마다 2~3문장 설명으로 쓴다.

1. 전체 구성요소 구조(flowchart): Browser, Agent(confidential client), Local MCP Client(public client), MCP Server, Authorization Server.
2. 등록
   - 2.1 Pre-registration: confidential client
   - 2.2 Pre-registration: public client
   - 2.3 CIMD: 명세 기준, 미구현 표시
   - 2.4 DCR: deprecated, 짧게
   - 2.5 Issuer binding
3. 런타임
   - 3.1 Discovery: 401 → PRM → Authorization Server Metadata
   - 3.2 Authorization: confidential client(로그인, PKCE, `resource`, `iss` 검증)
   - 3.3 Authorization: public client(매번 consent)
   - 3.4 Token request: 두 client 유형 비교
   - 3.5 MCP session: `initialize` → `initialized` → `tools/list` → `tools/call` → `DELETE`
   - 3.6 MCP Server 의 token 검증
   - 3.7 만료와 refresh(public client 는 refresh token 없음)
   - 3.8 주요 오류 경로: `aud` 불일치, `invalid_target`, `iss` 불일치, PKCE 누락

## practice 3종

### `README.md` (허브)

- 순서: 한 줄 소개 → 다루는 것 → 구성과 포트 → 모듈별 역할(2~3문장씩, `API-SPEC.md`·`SEQUENCES.md` 해당 절 링크) → 직접 쓴 코드 또는 설정 → 실행과 확인 → 학습 포인트 → 비목표 → 링크.
- 검증 시나리오는 "확인 방법 → 기대 결과"로 줄인다.
- 학습 포인트는 결론만 2~3문장씩 쓴다.

### `API-SPEC.md`

- 모듈(Authorization Server, MCP Server, agent)별 엔드포인트 표를 둔다.
- 표준 엔드포인트는 한 줄씩만 쓰고 `MCP-API-SPEC.md` 해당 절로 링크한다. 이 practice 의 값(포트·client_id·redirect URI)만 적는다.
- 프로젝트 고유 API 는 명세를 전부 적는다: agent `/api/chat`, MCP tool(`getStock`·`searchProducts`)의 입력 스키마와 응답, chat-memory 대화 API.

### `SEQUENCES.md`

- 표준 흐름을 이 practice 의 클래스·bean 이름으로 다시 그린다.

### practice 별 내용

- **official** (기준 문서이므로 자세히)
  - README: 공식 라이브러리만으로 직접 배선하는 방법, 세 앱의 직접 쓴 코드 표(클래스 → 역할 → 담당 명세 조항), 실행과 캡처 스크립트 3종.
  - API-SPEC: 세 모듈 전부.
  - SEQUENCES:
    - 모듈 내부 구성도
    - agent 로그인: discovery → PKCE·`resource` → `iss` 검증 → token 저장
    - MCP 호출 경로: SecurityContext 전달 → token 부착 → refresh
    - Authorization Server 내부: `resource` 검증 → `aud` 발급 → public client consent
- **chat-memory**
  - README: 사용자별 대화 격리 — `conversationId` 를 서버가 `Authentication` 에서 만든다는 설계, 격리 확인 방법, tool 결과가 memory 에 남는지. 인가 부분은 "official 과 같음"과 차이 표(포트·계정·client_id)로 끝낸다.
  - API-SPEC: 대화 API 전체 명세(`POST /api/chat?label=`, `GET /api/conversations`, `GET /api/conversations/{label}`). 표준 엔드포인트는 official 로 링크한다.
  - SEQUENCES: `conversationId` 를 만드는 과정, 대화를 격리해서 읽고 쓰는 과정, tool 호출이 memory 에 남는 과정. 인가 흐름은 official 로 링크한다.
- **community**
  - README: 모듈 3종의 자동 구성 표, 그리고 핵심인 대체 표("official 의 X → community 의 설정이나 확장점 Y", 직접 얹은 것 표시). 모듈이 조용히 물러나는 조건은 학습 포인트로 한두 줄만 쓴다.
  - API-SPEC: official 과 같다고 적고 차이만 표로 둔다(OIDC Discovery 켜는 방식, DCR 끔, 401 entry point). 각 엔드포인트를 누가 제공하는지(모듈인지 확장인지) 표시한다.
  - SEQUENCES: official 과 다른 지점만 그린다(모듈 자동 구성 bean 과 직접 얹은 확장을 구분해서). 나머지는 링크한다.

## 옮겨야 하는 내용 (누락 점검 기준)

- 지금 `MCP-AUTHORIZATION.md` 의 준수표 1~26번 항목과 판정.
- 지금 문서의 엔드포인트 명세 E1~E10 과 public client 관련 행.
- 조항 인용 전체와 출처 목록.
- 세 README 의 구성·포트·직접 쓴 코드 표·실행 방법·학습 포인트의 결론.

## 진행

- 이 설계 → 구현 계획 → subagent 실행. task 마다 리뷰 1회, 끝에 브랜치 전체 리뷰 1회.
- 순서: 최상위 3종(API 명세 → 시퀀스 → 허브) → official 3종 → chat-memory 3종 → community 3종.
- 옛 문서의 명세 인용·관측·판정은 옮기고, 서술은 이 규칙으로 새로 쓴다. 이전 판은 git 기록에 남는다.

## 검증

- **내용**
  - 조항 인용과 요구 수준을 원문과 대조한다(OAuth 2.1 draft-13, RFC, MCP 명세 페이지).
  - 예시 값을 캡처 파일(`docs/superpowers/captures/`)과 대조한다.
  - 클래스·bean·설정 이름을 코드와 대조한다.
- **기계 검사** (12개 파일)
  - 내부 링크와 앵커가 살아 있는가
  - mermaid 블록 문법이 맞는가
  - 제목으로 렌더링되는 `---`(바로 위가 빈 줄이 아닌 경우)가 없는가
  - 3문장을 넘는 문단이 없는가
  - 금지 표현(`처음엔`, `고쳤`, `Task `, `실측했`, `착각`)이 없는가
  - 용어 목록의 전문 용어를 한국어로 옮긴 곳(예: `인가 서버`, `인가 요청`, `토큰 요청`, `보호 리소스 메타데이터`, `액세스 토큰`, `동의 화면`)이 없는가
- **누락**: "옮겨야 하는 내용"이 새 문서 어딘가에 모두 있는가.

## 다루지 않는 것

- 코드·테스트·캡처 스크립트 변경.
- `docs/superpowers/` 아래의 설계·계획 문서 재작성.
- CIMD 구현(별도 practice 로 보류).
