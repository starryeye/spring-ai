# 스킬 테스트 — `writing-practice-docs` (2026-09-25)

## RED

스킬 파일(SKILL.md·templates.md·check_docs.py 규칙)이 없는 상태에서 general-purpose subagent 5개를
같은 프롬프트로 병렬로 띄워 각각 `/tmp/skilltest/red-N.md` 를 쓰게 했다(N=1..5). 대상은
"`GET /oauth2/jwks` API 명세 한 절" + "MCP 서버의 access token 검증 시퀀스 한 절(mermaid 포함)".

### 규칙별 위반 수 (`check_docs.py`, 이 스킬 파일이 없던 시점의 규칙표 기준으로 사후 검사)

| 파일 | term | length | link | setext | mermaid | 합계 |
|---|---|---|---|---|---|---|
| red-1.md | 29 | 0 | 0 | 0 | 0 | 29 |
| red-2.md | 22 | 1 | 2 | 0 | 0 | 25 |
| red-3.md | 28 | 1 | 0 | 0 | 0 | 29 |
| red-4.md | 21 | 1 | 2 | 0 | 0 | 24 |
| red-5.md | 27 | 1 | 2 | 0 | 0 | 30 |

다섯 파일 모두 위반이 있으므로(0건인 파일 없음) RED 는 성립한다.

### 필드 완전성 — JWK 표 (RFC 7517 §5 `keys` REQUIRED · §4 `kty` REQUIRED, `use`·`key_ops`·`alg`·`kid`·`x5u`·`x5c`·`x5t`·`x5t#S256` OPTIONAL · RFC 7518 §6.3.1 RSA `n`·`e` REQUIRED)

| 파일 | `keys` | `kty` | `n`/`e` | `use`/`alg`/`kid` | `key_ops` | `x5u`/`x5c`/`x5t`/`x5t#S256` | 요구 수준 오류 |
|---|---|---|---|---|---|---|---|
| red-1.md | REQUIRED(MUST) 맞음 | REQUIRED 맞음 | REQUIRED 맞음 | OPTIONAL 맞음 | **행 없음(누락)** | **행 없음(누락)** | 없음 |
| red-2.md | MUST 맞음 | MUST 맞음 | MUST 맞음 | OPTIONAL 맞음 | **행 없음(누락)** | **행 없음(누락)** | 없음 |
| red-3.md | MUST 맞음 | REQUIRED 맞음 | REQUIRED 맞음 | OPTIONAL 맞음 | OPTIONAL 맞음(그룹 행) | OPTIONAL 맞음(그룹 행) | 없음 |
| red-4.md | REQUIRED 맞음 | REQUIRED 맞음 | REQUIRED 맞음 | `use` 를 "OPTIONAL, 단 서명·암호화 키를 함께 실으면 REQUIRED" 로 **원문에 없는 조건을 덧붙임** | **행 없음(누락)** | **행 없음(누락)** | `use` 요구 수준 임의 각색 |
| red-5.md | REQUIRED 맞음 | REQUIRED 맞음 | REQUIRED 맞음 | OPTIONAL 맞음 | OPTIONAL 맞음(그룹 행) | OPTIONAL 맞음(그룹 행) | 없음 |

세 파일(red-1, red-2, red-4)이 원문에 있는 OPTIONAL 필드(특히 `x5u`·`x5c`·`x5t`·`x5t#S256`, red-2·red-4 는 `key_ops` 도)를 표에서 통째로 빼먹었다.
red-4 는 `use` 의 요구 수준을 원문(RFC 7517 §4.2, 순수 OPTIONAL)에 없는 조건("함께 실으면 REQUIRED")으로 각색했다.

### 개발 과정·집필 과정 서술

다섯 파일 모두 "이 절을 쓰기 위해 직접 서버를 띄워 확인했다" 는 취지의 문장을 담고 있다. `check_docs.py`
의 `BANNED_PHRASES`(처음엔·고쳤·수정했·Task ·실측했·착각·버그를)는 "실수를 고친" 서술만 잡으므로 이
변형은 규칙상 걸리지 않지만, 대표 문장을 그대로 옮기면 다음과 같다.

- red-1 (L5): "아래 JWKS 응답은 이 조각을 쓰는 동안 `auth-server` 를 직접 띄워 `curl` 로 재현한 값이다"
- red-2 (L37): "관측(2026-09, 이미 기동해 있던 `auth-server` 에 직접 질의):"
- red-3 (L25): "**[관측]** 실제로 인가 서버를 띄워 확인했다."
- red-4 (L31): "**[관측]** — 로컬에서 이 practice 의 auth-server 만 띄우고 직접 호출한 결과다."
- red-5 (L6-7): "이 조각을 쓰며 `auth-server`(포트 9010)만 직접 띄워 확인한 값" / (L64-66) "확인: `grep -rl "JWKSource\|RSAKey\|JWKSet" auth-server/src/main/java` 결과 없음"

기존 `practice/MCP-AUTHORIZATION.md` 의 `[관측]` 은 캡처 파일 라벨(`C1`, `S17` 등)만 인용하고 "이 글을
쓰며 무엇을 했다" 는 서술이 없다(예: L54 "**[관측]** practice 를 실제로 띄워 주고받은 요청·응답이다.
출처 표기는 다음과 같다" — 뒤에 오는 것은 캡처 파일 경로 표, 집필 행위 서술이 아니다). 다섯 RED 파일은
이 스타일에서 벗어난다.

### 한 문단에 여러 층위를 섞음

`length` 위반(red-2 L76, red-3 L49, red-4 L20, red-5 L87)은 대부분 명세·구현·관측·평가를 한 문단에
이어 쓴 결과다. 대표 문장(red-4 L20, 5문장 한 문단):

> "이 글은 [`practice/MCP-AUTHORIZATION.md`] 의 문체 … 를 따르는 학습 문서 조각이다. `practice/mcp-security-authn-official` 하나만 다룬다."
> (뒤이어 목록 없이 인가 서버·MCP 서버 설명이 같은 문단에 이어짐)

red-1 만 예외적으로 `length` 위반이 없었다(짧은 문단·표 위주로 썼기 때문).

### 링크

`link` 위반(red-2, red-4, red-5)은 모두 존재하지 않는 파일·앵커를 가리킨다.

| 파일 | 줄 | 문제 |
|---|---|---|
| red-2.md | 34, 124 | `#s4-9` 앵커가 이 문서 안에 없음(원본 `MCP-AUTHORIZATION.md` 의 4.9절을 가리키려 한 것으로 보이나, 그 문서를 링크하지 않고 자기 문서 안의 앵커로 씀) |
| red-4.md | 3, 44 | `../../Users/starryeye/study/spring-ai/practice/MCP-AUTHORIZATION.md` — 저장소 루트 기준 상대 경로에 절대 경로 조각을 그대로 이어 붙여 실제로는 존재하지 않는 경로가 됨 |
| red-5.md | 4, 14 | `../../practice/MCP-AUTHORIZATION.md`(와 `#s5-3` 앵커) — `/tmp/skilltest/` 기준 상대 경로 단계 수가 저장소 경로와 맞지 않음 |

### RED 실패 ↔ SKILL.md 줄 짝지음

| RED 실패 | 막는 SKILL.md 줄 |
|---|---|
| `term` (한국어로 옮긴 전문 용어, 5/5 파일) | "문장 모양" 절 2번째 불릿 "전문 용어는 영어로 쓰고 조사만 한국어로 붙인다. 목록은 `terms.txt` 다." + "흔한 실수" 표 2행 |
| `length` (한 문단·항목 4~5문장, 4/5 파일) | "문장 모양" 절 첫 불릿 "한 문단·한 목록 항목은 3문장 이하다." + "흔한 실수" 표 3행 "한 문단에 배경·규칙·예외·구현을 모두" |
| `link` (없는 파일·앵커, 3/5 파일) | "검사" 절 "위반 0 이 될 때까지 고친다" — `check_docs.py` 가 `link` 규칙으로 그대로 잡아 준다 |
| OPTIONAL 필드(`key_ops`·`x5u`·`x5c`·`x5t`·`x5t#S256`) 표에서 누락 (red-1·2·4) | "명세 인용" 절 2번째 불릿 "API 표는 원문의 필드 목록에서 시작한다. REQUIRED·RECOMMENDED·OPTIONAL 을 모두 행으로 두고 …" |
| `use` 요구 수준을 원문에 없는 조건으로 각색 (red-4) | "명세 인용" 절 1번째 불릿 "요구 수준 … 은 원문 단어 그대로 쓴다" + "흔한 실수" 표 5행 |
| 관측 문장에 "이 글을 쓰며 직접 띄워 확인했다" 류 집필 과정 서술 (5/5 파일) | **막는 줄 없음 → 추가**: "명세 인용" 절에 새 불릿 "관측 문장에는 값만 남긴다. '이 문서를 쓰며 직접 띄워 확인했다'·'재현한 값이다' 처럼 이 글을 쓰는 동안 무엇을 했는지는 적지 않는다." 를 더함(아래 "SKILL.md 에 더한 줄" 참고) |

## SKILL.md 에 더한 줄

Step 4 실패 중 다섯 파일 모두에서 나타난 "관측 문장에 집필 과정을 서술함"(예: "이 조각을 쓰는 동안 …
직접 띄워 curl 로 재현한 값이다")은 기존 "흔한 실수" 표의 1행(`처음엔 …`, `… 를 고쳤다`, `Task 3 에서`)
이 잡는 "실수를 나중에 고쳤다" 식 서술과는 다르다 — "지금 이 글을 쓰면서 무엇을 확인했는지"를 담아,
`check_docs.py` 의 `BANNED_PHRASES` 로도 걸리지 않는다. 실제 `MCP-AUTHORIZATION.md` 의 `[관측]` 은
캡처 파일 라벨만 인용하고 집필 행위를 서술하지 않으므로, 이 간극을 막는 줄이 없었다.

"명세 인용" 절에 다음 한 줄을 더했다.

> 관측 문장에는 값만 남긴다. "이 문서를 쓰며 직접 띄워 확인했다"·"재현한 값이다" 처럼 이 글을 쓰는
> 동안 무엇을 했는지는 적지 않는다.

추가 후 `python3 .claude/skills/writing-practice-docs/scripts/check_docs.py .claude/skills/writing-practice-docs/SKILL.md .claude/skills/writing-practice-docs/templates.md` 는 `검사한 파일 2개, 위반 0건`, `wc -w SKILL.md` 는 520 단어(≤600)로 확인했다.

## TDD 근거

**RED** (`check_docs.py` 를 만들기 전, `test_check_docs.py`·`terms.txt` 만 있는 상태):

```
$ python3 -m unittest discover -s .claude/skills/writing-practice-docs/scripts -p 'test_*.py'
ERROR: test_check_docs (unittest.loader._FailedTest.test_check_docs)
...
ModuleNotFoundError: No module named 'check_docs'
FAILED (errors=1)
```

**GREEN** (`check_docs.py` 작성 후):

```
$ python3 -m unittest discover -s .claude/skills/writing-practice-docs/scripts -p 'test_*.py'
......................
----------------------------------------------------------------------
Ran 22 tests in 0.012s

OK
```

```
$ python3 .claude/skills/writing-practice-docs/scripts/check_docs.py practice/MCP-AUTHORIZATION.md | grep -c '\[mermaid\]\|\[link\]\|\[setext\]'
0
```
(같은 명령의 마지막 줄은 `검사한 파일 1개, 위반 1143건` — 대부분 `term`·`length` 이고, 이는 기존 문서가 이 스킬 기준을 아직 따르지 않는다는 뜻으로 정상이다.)

```
$ python3 .claude/skills/writing-practice-docs/scripts/check_docs.py .claude/skills/writing-practice-docs/SKILL.md .claude/skills/writing-practice-docs/templates.md
검사한 파일 2개, 위반 0건
```

## GREEN (스킬을 따라 쓴 문서)

SKILL.md·templates.md 를 먼저 읽고 그대로 따르라는 지시를 더한 프롬프트로 subagent 5개를 다시 띄워
`/tmp/skilltest/green-N.md` 를 받았다. `check_docs.py` 는 다섯 파일 모두 위반 0건이다(controller 확인,
아래에서 재확인).

```
$ for n in 1 2 3 4 5; do python3 .claude/skills/writing-practice-docs/scripts/check_docs.py /tmp/skilltest/green-$n.md | tail -1; done
검사한 파일 1개, 위반 0건   (green-1)
검사한 파일 1개, 위반 0건   (green-2)
검사한 파일 1개, 위반 0건   (green-3)
검사한 파일 1개, 위반 0건   (green-4)
검사한 파일 1개, 위반 0건   (green-5)
```

다섯 파일을 직접 읽고, RFC 7517 §4·§5(`/tmp/mcp-refs/rfc7517.txt`)·RFC 7518 §6.3.1(`rfc7518.txt`) 원문과
대조해 판정했다.

| 파일 | (a) 필드 전부·요구 수준이 원문과 같음 | (b) 집필 과정 서술 없음 | (c) 요약→하위 제목 계층 | (d) 용어가 영어 |
|---|---|---|---|---|
| green-1 | **실패** — `key_ops`·`x5u`·`x5c`·`x5t`·`x5t#S256` 행이 표에서 통째로 빠짐(§4.3, §4.6–§4.9 없음) | 통과 | 통과 | 통과 |
| green-2 | **실패** — `use` 를 "OPTIONAL, 서명·암호화 키가 함께 있으면 REQUIRED"로 쓰고 근거를 RFC 8414 §2 로 붙임(원문은 RFC 7517 §4.2 "OPTIONAL, unless the application requires its presence") — 원문에 없는 조건과 오출처 | 통과 | 통과 | 통과 |
| green-3 | 부분 — 필드는 전부 있음(x5u~x5t#S256 포함). 다만 `keys`·`kty`·`n`·`e` 의 표시를 원문 단어 "MUST" 대신 "REQUIRED" 로 씀(원문 wording 불일치) | 통과 | 통과 | 통과 |
| green-4 | **통과** — 필드 전부, 표시 단어(`MUST`/`OPTIONAL`)가 원문과 그대로 일치, 지어낸 조건 없음 | 통과 | 통과 | 통과 |
| green-5 | 부분 — 필드는 전부 있음. `keys` 는 "REQUIRED", `kty` 는 "MUST" 로 같은 문서 안에서도 표시 단어가 갈림(원문은 둘 다 MUST) | 통과 | 통과 | 부분 — mermaid Note 안 "Authorization Bearer 토큰"(한국어). 코드 블록이라 `check_docs.py` 는 잡지 않지만 다른 4개는 이 자리를 English 로 씀 |

세부:

- green-3·green-5 는 참고로 든 앵커(`/Users/starryeye/study/spring-ai/practice/MCP-AUTHORIZATION.md#e3`,
  `#s4-9`)가 옛 `MCP-AUTHORIZATION.md` 의 앵커를 그대로 쓴다. 그 문서는 Task 4 에서 다시 쓰며 앵커가
  바뀔 예정이라고 들었으므로, 이 판정에는 반영하지 않았다(기록만 남긴다). 절대경로(`/Users/...`)로 쓴 것도
  이 저장소에서는 `check_docs.py` 가 실제로 파일을 찾아 통과하지만, 다른 환경에서는 깨질 상대경로
  스타일 문제라 별개로 적어 둔다.
- 개발·집필 과정 서술("직접 띄워 확인했다"·"재현한 값"·"이 세션이 띄운")은 다섯 파일 어디에도 없다 —
  Step 5 에서 "명세 인용" 절에 더한 줄이 의도대로 작동했다.
- 다섯 파일 모두 "무엇을·왜" 요약 문장 뒤에 요청/응답/오류(또는 단계) 하위 구획이 오는 계층을 지켰다.

### RED 대비 요약

- `term`·`length`·`link` 세 규칙(RED 에서 5/5, 4/5, 3/5 파일이 위반)은 GREEN 다섯 파일 모두 0건이다 —
  기존 SKILL.md 줄(용어 영어 표기, 3문장 제한, 검사 스크립트로 링크 확인)이 그대로 작동했다.
- 집필 과정 서술(RED 5/5) 은 GREEN 에서 0/5 — Step 5 에서 더한 "명세 인용" 줄이 이 실패를 없앴다.
- 필드 완전성 누락은 RED 3/5(red-1·2·4) 에서 GREEN 1/5(green-1) 로 줄었지만 없어지지는 않았다.
- 요구 수준 원문 단어(MUST/OPTIONAL) 를 REQUIRED/RECOMMENDED 로 바꿔 쓰거나 조건을 지어내는 실패는
  RED 에서 1/5(red-4, `use` 만) 였는데 GREEN 에서는 오히려 3/5(green-2·3·5) 로 **늘었다** — RED 실패
  짝지음에서 이미 있던 줄("요구 수준은 원문 단어 그대로 쓴다")이 있었는데도 재발했다. `templates.md`
  의 표 틀이 예시 값으로 항상 `REQUIRED`/`OPTIONAL` 을 쓰는 것이, 원문이 실제로 `MUST`/`OPTIONAL` 을
  쓰는 절에서도 그 단어를 그대로 베끼게 만드는 것으로 보인다.

## REFACTOR

기준을 못 넘은 파일이 있다(green-1: 필드 누락, green-2: 조건 지어냄+오출처, green-3·green-5: MUST 를
REQUIRED 로 바꿔 씀). 가장 재현이 잦고(3/5) RED 에서보다 오히려 늘어난 실패인 "요구 수준 단어·조건을
원문과 다르게 씀" 을 막는 줄 하나를 "흔한 실수" 표에 더했다.

> | 원문의 MUST·OPTIONAL 을 REQUIRED·RECOMMENDED 로 바꿔 쓰거나, 원문에 없는 조건을 지어 붙임 | 원문이
> 쓴 단어와 조건을 그대로 옮긴다. RFC 2119 두 쌍(MUST/SHOULD/MAY, REQUIRED/RECOMMENDED/OPTIONAL)을
> 섞지 않는다 |

추가 후 재확인: `check_docs.py` 로 SKILL.md·templates.md 검사 위반 0건, `wc -w SKILL.md` 548 단어(≤600).

필드 완전성 누락(green-1)은 GREEN 5개 중 1개에서만 재현됐고, 나머지 4개는 이미 있던 "명세 인용" 절
2번째 줄("API 표는 원문의 필드 목록에서 시작한다 … 모두 행으로 두고 …")을 지켰다. 표본 하나만의
편차로 보고 이번에는 줄을 더하지 않았다. mermaid 안 한국어 용어(green-5) 도 1/5 편차라 이번에는
넘긴다 — 재발하면 두 가지 모두 다음 라운드에서 다시 본다.

GREEN 표본을 다시 뽑는 일은 controller 가 맡는다(코디네이터 지시). 이번 호출에서는 **커밋하지 않는다**
— brief 의 Step 8 은 "모두 넘었을 때"의 경로이고, 이번엔 줄을 더했으므로 그 경로를 타지 않았다.

## REFACTOR 2차 (채점 기준 정정)

**정정**: 위 "필드 완전성" 절의 표 제목과 이 문서 곳곳의 "RFC 7517 §5 `keys` REQUIRED · §4 `kty`
REQUIRED · RFC 7518 §6.3.1 RSA `n`·`e` REQUIRED" 표기는 brief Step 4 설명을 그대로 옮긴 것인데,
이 표기 자체가 원문과 다르다 — RFC 7517 §5 는 "The JSON object **MUST have** a 'keys' member",
§4.1 은 "This member **MUST be present** in a JWK", RFC 7518 §6.3.1 도 "The following members
**MUST be present** for RSA public keys" 라고 쓴다. 세 필드 모두 원문 단어는 `REQUIRED` 가 아니라
`MUST` 다(`use`·`key_ops`·`alg`·`kid`·`x5u`·`x5c`·`x5t`·`x5t#S256` 의 `OPTIONAL` 은 원문 단어와
그대로 일치하므로 정정 대상이 아니다). 이 정정은 위 GREEN 판정표의 결론을 바꾸지 않는다 — green-4 를
"통과"로, green-3·green-5 의 `keys`/`kty`/`n`/`e` REQUIRED 표기를 "원문 wording 불일치"로 매긴 것은
이미 이 기준(원문이 실제로 쓴 단어는 MUST)으로 채점한 것이었다. 다만 위 "필드 완전성" 표 제목과 RED
실패 짝지음 표의 서술은 brief 문구를 그대로 인용해 REQUIRED 를 정답처럼 적었으므로, 이 절이 그 부정확한
표기를 그대로 옮겼다는 점을 여기 정정해 둔다.

코디네이터 판정에 따라 **필드 누락(green-1)과 조건 지어냄(green-2, RED 의 red-4 와 같은 실패)은 표본
잡음이 아니라 막아야 할 실패**로 확정한다. 이 둘을 막기 위해 두 파일을 고쳤다.

**`templates.md` 엔드포인트 틀**: 요청·응답 표의 표시 칸 예시를 고정 텍스트 `REQUIRED`/`OPTIONAL` 에서
자리표시 `<원문 단어 — MUST·REQUIRED·OPTIONAL 등, 원문이 쓴 그대로>` 로 바꿔, 쓰는 사람이 항상 원문을
다시 확인하게 만들었다. 요청·응답 표 바로 아래에 각각 `원문 필드: <문서 §절> 에 정의된 <N>개 → 표
<N>행(필드가 없으면 "원문 필드: 없음")` 한 줄을 더해, 원문 필드 수와 표 행 수를 세어 맞추게 했다 —
green-1 이 `key_ops`·`x5u`·`x5c`·`x5t`·`x5t#S256` 행 자체를 빼먹은 실패를 이 칸이 잡아준다.

**`SKILL.md` "명세 인용"**: 새 불릿 하나를 더했다 — "표시 칸에는 원문이 그 필드에 실제로 붙인 단어를
쓴다. 조건이 있으면 그 조건이 적힌 원문 절 번호와 함께 쓴다: "OPTIONAL — §4.3 은 `use` 와 `key_ops`
를 함께 쓰지 않을 것(SHOULD NOT)"처럼. 절 번호 없이 조건을 지어내지 않는다." 이 줄이 green-2 의
"`use` 는 함께 있으면 REQUIRED(근거: RFC 8414 §2)"처럼 절 번호 없이 조건을 지어내는 실패를 막는다.
이전 REFACTOR(1차)에서 "흔한 실수" 표에 더했던 행("원문의 MUST·OPTIONAL 을 REQUIRED·RECOMMENDED 로
바꿔 쓰거나…")은 뜻이 겹쳐 이 새 불릿과 "SHOULD 를 '할 수 있다'로 옮김" 행 하나로 합치고, 중복 행은
지웠다.

**검사**: `check_docs.py` 로 SKILL.md·templates.md 위반 0건, `wc -w SKILL.md` 571 단어(≤600,
1차 548 단어에서 23 단어 늘어남), unittest 22/22 통과 — 아래 TDD 근거 참고.

```
$ python3 .claude/skills/writing-practice-docs/scripts/check_docs.py .claude/skills/writing-practice-docs/SKILL.md .claude/skills/writing-practice-docs/templates.md
검사한 파일 2개, 위반 0건

$ wc -w .claude/skills/writing-practice-docs/SKILL.md
     571 .claude/skills/writing-practice-docs/SKILL.md

$ python3 -m unittest discover -s .claude/skills/writing-practice-docs/scripts -p 'test_*.py'
......................
----------------------------------------------------------------------
Ran 22 tests in 0.011s

OK
```

이번에도 **커밋하지 않는다** — GREEN 재추출은 controller 가 맡는다.

## GREEN 2차

controller 가 `templates.md`·`SKILL.md` 를 다시 적용해 뽑은 5개 표본(`/tmp/skilltest/green2-1.md` ~
`green2-5.md`). `check_docs.py` 는 다섯 모두 위반 0건이다(controller 확인, 아래에서 재확인).

```
$ for n in 1 2 3 4 5; do python3 .claude/skills/writing-practice-docs/scripts/check_docs.py /tmp/skilltest/green2-$n.md | tail -1; done
검사한 파일 1개, 위반 0건   (green2-1 ~ green2-5, 다섯 모두)
```

1차와 같은 기준(Ruling 3 정정 반영 — `keys`·`kty`·RSA `n`·`e` 는 원문대로 `MUST`, 원문에 없는 조건을
지어내지 않음, 원문 필드 전부, 집필 과정 서술 없음, 계층, 영어 용어)으로 다섯 파일을 직접 읽고
`/tmp/mcp-refs/rfc7517.txt`·`rfc7518.txt`·`rfc6750.txt` 원문과 대조했다. 이번엔 `templates.md` 의
"원문 필드: … N개 → 표 N행" 칸이 실제 표 행 수와 맞는지도 하나씩 셌다.

| 파일 | 요구 수준 원문 일치(MUST/SHOULD 등) | 조건에 절 번호 | 필드 전부 + 카운트 줄 일치 | 집필 과정 서술 | 계층 | 용어 영어 |
|---|---|---|---|---|---|---|
| green2-1 | 통과 | 통과 — `key_ops`: "OPTIONAL(§4.3) — use 와 함께 쓰지 않을 것(SHOULD NOT)" | 통과 — "10개(keys 포함)+2개→표 12행", 실제 12행 | 통과 | 통과 | 통과 |
| green2-2 | 통과 | 통과 — 같은 형태로 §4.3 인용 | 통과 — "1개+9개+2개=12→표 12행", 실제 12행 | 통과 | 통과 | 통과 |
| green2-3 | 통과 | 통과 | 통과(참고) — "12개→표 9행(x5u·x5c·x5t·x5t#S256 을 한 행에 묶음)"이라고 그 자리에서 밝힘, 9행 실제와 일치 | 통과 | 통과 | 통과 |
| green2-4 | 통과 | 통과 | **실패** — "RFC 7517 §4 에 정의된 8개 + RFC 7518 §6.3.1 의 2개 → 표 10행"이라고 썼지만, §4 멤버는 `kty`·`use`·`key_ops`·`alg`·`kid`·`x5u`·`x5c`·`x5t`·`x5t#S256` 9개이고 표도 실제로 11행(9+2)이다 — 8·10 모두 실제보다 1 적다 | 통과 | 통과 | 통과 |
| green2-5 | **실패** — 본문·4단계 두 곳에서 "401 로 거부한다(**MUST**, RFC 6750 §3.1)"라고 썼지만 원문은 "the resource **SHOULD** respond with the HTTP 401" | 통과 | 통과 — "12개→표 12행", 실제 12행 | 통과 | 통과 | 통과 |

세부:

- green2-4 는 필드 자체는 9개(§4) + 2개(RSA) 를 표에 전부 실어 누락이 없다 — 실패는 그 아래에 붙인
  "원문 필드: … N개 → 표 N행" 문장의 산술이 표의 실제 행 수와 어긋난다는 점이다(8·10 vs 실제 9·11).
  green-1(1차) 이 필드 자체를 빼먹은 것과는 다른 종류의 실패다.
- green2-5 는 필드 완전성·조건 인용 모두 정확하다. 실패는 RFC 6750 §3.1 의 요구 수준 단어를 두 곳
  모두(서론 문장, 단계 4) `SHOULD` 대신 `MUST` 로 적은 것이다 — 1차 green-2/3/5 에서 보였던 "요구
  수준을 원문과 다르게 씀" 패턴이 다른 절(§4.1 류가 아니라 §3.1)에서 재발했다.
- green2-1·2·3 은 세 곳 모두(요구 수준, 조건+절 번호, 필드 카운트) 통과했다. green2-3 은 4개 X.509
  필드를 한 행에 묶으면서 필드 수(12)와 행 수(9)를 다르게 적었는데, 그 차이를 같은 줄에서 바로
  설명했으므로 산술 오류가 아니라 의도된 압축으로 판단해 통과로 매겼다.

### 1차 대비 요약

- 1차에서 실패했던 두 유형 — green-1 류 "필드 자체를 빼먹음", green-2 류 "조건을 절 번호 없이
  지어냄" — 은 2차 5개 어디에도 재현되지 않았다. `templates.md` 의 자리표시·필드 카운트 줄과
  `SKILL.md` 의 새 "명세 인용" 불릿이 의도대로 작동했다.
- 1차에서 3/5(green-2·3·5) 였던 "MUST 를 REQUIRED 로 바꿔 씀" 도 2차 5개 어디에도 없다 — `keys`·
  `kty`·`n`·`e` 모두 다섯 파일이 `MUST` 를 그대로 썼다.
- 대신 새로운 두 실패가 나타났다: (a) green2-4 의 "원문 필드 카운트 산술 오류"(카운트 줄 자체를
  틀리게 셈 — 필드 나열은 맞다), (b) green2-5 의 "RFC 6750 §3.1 요구 수준을 MUST 로 잘못 씀"(§4류가
  아닌 다른 절에서 같은 패턴이 재발). 둘 다 1차에서는 나타나지 않았던 자리에서 나온 변형이다.

## REFACTOR — 2차 결과: 기준 미달 2건, 커밋 보류

5개 중 2개(green2-4, green2-5)가 기준을 못 넘었으므로 커밋하지 않는다. 코디네이터 지시대로 SKILL.md
는 고치지 않고, 실패 내용과 레시피 제안만 남긴다.

| 실패 | 파일 | 막을 레시피 한 줄(제안, 아직 SKILL.md 에 넣지 않음) |
|---|---|---|
| "원문 필드: N개 → 표 N행" 의 N 을 실제 표 행 수와 다르게 셈(8·10 vs 실제 9·11) | green2-4 | "명세 인용" 에 추가 제안: "이 줄을 쓴 뒤 표의 행을 처음부터 다시 하나씩 세어 두 N 이 맞는지 확인한다 — 묶은 행이 있으면 그 사실을 같은 줄에 적는다." |
| RFC 6750 §3.1 의 `SHOULD` 를 `MUST` 로 씀(서론 문장과 단계 양쪽) | green2-5 | "명세 인용" 첫 불릿 뒤에 추가 제안: "같은 요구 수준을 문서 안에서 두 번 이상 인용할 때도 매번 그 절 원문을 다시 펼쳐 확인한다 — 앞서 쓴 문장을 그대로 베끼지 않는다." |

두 실패 모두 필드 목록·조건 인용 자체는 정확했고, "원문을 다시 확인하는 절차"가 한 번은 빠졌다는
공통점이 있다. 다음 GREEN 라운드로 넘기기 전에 이 두 레시피 중 하나 또는 둘 다를 SKILL.md 에
반영할지는 코디네이터 판단에 맡긴다.

커밋하지 않았다 — `.superpowers/` 는 여전히 건드리지 않았고, `SKILL.md`·`templates.md` 도 이번 호출에서
고치지 않았다(코디네이터 지시).

## 필드 수 산술 검사 자동화 (`field-count` 규칙)

controller 판정: REFACTOR 는 여기서 끝낸다(GREEN 3차 없음). green2-4 의 필드 카운트 산술 오류는
`check_docs.py` 에 새 규칙 `field-count` 로 자동화하고, green2-5 의 본문 요구 수준 오기(RFC 6750 §3.1
`SHOULD`→`MUST`)는 SKILL.md 에 줄을 더하지 않고 문서 task 의 원문 대조 리뷰가 맡는다.

**RED** — `FieldCountTest` 를 `test_check_docs.py` 에 먼저 추가하고(5개 케이스: 개수·행 수 일치,
행 수만 다름, 명시 개수·행 수 표기가 다름, "없음", 정수 하나가 아닌 형식) 구현 전에 돌렸다.

```
$ python3 -m unittest discover -s .claude/skills/writing-practice-docs/scripts -p 'test_*.py'
F.F.F......................
FAIL: test_명시한_개수와_표_행_표기가_다르면_걸린다 — AssertionError: 'field-count:1' not found in []
FAIL: test_정수_하나가_아닌_형식은_걸린다 — AssertionError: False is not true
FAIL: test_표_행이_하나_늘면_field_count_가_걸린다 — AssertionError: 'field-count:7' not found in []
----------------------------------------------------------------------
Ran 27 tests in 0.015s
FAILED (failures=3)
```

(나머지 2개는 "위반 없음"을 확인하는 케이스라 구현 전에도 참값으로 통과했다 — RED 는 나머지 3개로 확인된다.)

**GREEN** — `check_docs.py` 에 `check_field_counts(path, prose)` 와 도우미 `_table_rows_ending_at` 을
추가하고 `check_file` 에서 호출했다. 표시 규칙: "원문 필드:" 로 시작하는 줄에서 "없음" 이면 통과,
`<N>개 → 표 <M>행` 패턴을 못 찾으면(정수 하나가 아닌 형식) 위반, `N ≠ M` 이면 위반, `N == M` 인데
그 줄 바로 위(빈 줄 0~1개 사이)의 표 데이터 행 수(헤더·구분선 제외)가 `M` 과 다르면 위반. 모듈
docstring 에 규칙 이름 목록을 추가했다(`... mermaid, field-count, links-from, missing.`).

```
$ python3 -m unittest discover -s .claude/skills/writing-practice-docs/scripts -p 'test_*.py'
...........................
----------------------------------------------------------------------
Ran 27 tests in 0.012s
OK

$ python3 .claude/skills/writing-practice-docs/scripts/check_docs.py .claude/skills/writing-practice-docs/SKILL.md .claude/skills/writing-practice-docs/templates.md
검사한 파일 2개, 위반 0건

$ wc -w .claude/skills/writing-practice-docs/SKILL.md
     571 .claude/skills/writing-practice-docs/SKILL.md
```

`templates.md` 맨 위 안내 문단에 세 번째 문장을 더했다(2문장 → 3문장, 3문장 규칙 한도 안):
"'원문 필드' 뒤의 N 은 정수 하나이고 표의 데이터 행 수와 같아야 하며, `check_docs.py` 의
`field-count` 규칙이 그 둘을 센다." 이 문장은 코드 펜스 밖 프로즈라 `length`·`term` 검사 대상이지만
위반 0 을 유지한다.

### green2-1..5 에 새 규칙을 돌린 결과

기대(코디네이터 메시지)는 "green2-4 만 걸림"이었지만, 실제로는 **4/5(green2-1·2·3·4)** 가 걸렸다.
이유를 그대로 기록한다.

| 파일 | 결과 | 이유 |
|---|---|---|
| green2-1 | `field-count:38` 걸림 | "10개(`keys` 포함) + 2개 → 표 12행" 처럼 더해서 12를 만드는 서술이라, 정규식이 "→" 바로 앞의 마지막 숫자(`2`)만 N 으로 읽어 M(12)과 어긋난다 — 실제 표는 12행으로 맞다 |
| green2-2 | `field-count:36` 걸림 | 위와 같은 이유("1개 + 9개 + 2개 → 표 12행", 마지막 숫자 `2` 만 N 으로 읽힘). 실제 표도 12행으로 맞다 |
| green2-3 | `field-count:38` 걸림 | 4개 필드(`x5u`·`x5c`·`x5t`·`x5t#S256`)를 한 행에 묶어 "12개 → 표 9행"이라고 그 자리에서 밝혔는데, 규칙은 그 설명을 읽지 못하고 N(12)≠M(9) 그대로 위반으로 잡는다. 실제 표는 9행으로 M 과는 맞다 |
| green2-4 | `field-count:28`, `field-count:46` 두 건 걸림 | (a) 줄 28 "1개 → 표 1행": 그 위 응답 표가 `200 OK` 상태 행과 `keys` 필드 행을 한 표에 같이 둬서 실제 데이터 행이 2 — 텍스트의 "1행"과 다르다. (b) 줄 46 "8개+2개 → 표 10행": §4 공통 멤버는 9개(`kty`·`use`·`key_ops`·`alg`·`kid`·`x5u`·`x5c`·`x5t`·`x5t#S256`)인데 8개라 적었고, 표도 실제 11행이라 10과 다르다 — 원래 의도한 산술 오류가 이것이다 |
| green2-5 | 위반 없음 | "RFC 7517 §4·§5.1 과 RFC 7518 §6.3.1 에 정의된 12개 → 표 12행" 하나의 정수로만 썼고, 실제 표도 12행이라 그대로 일치한다 |

즉 이 규칙은 green2-4 가 의도한 진짜 산술 오류(§4 멤버 수를 8로 잘못 셈)는 정확히 잡아내지만, 여러
근거를 더해서 설명하는 서술(green2-1·2)이나 여러 필드를 한 행에 묶었다고 밝힌 서술(green2-3)까지
함께 걸러낸다 — "N 은 정수 하나" 라는 형식 제약이 산술이 맞는 서술적 표현도 위반으로 잡는다는 뜻이다.
이 규칙이 요구하는 형식은 앞으로 "원문 필드" 줄을 처음부터 하나의 최종 숫자로만 쓰라는 것과 같다.

## REFACTOR: 2차에서 종료 — 필드 수 칸은 check_docs.py field-count 로 자동화, 본문 요구 수준 오기는 문서 task 리뷰가 원문 대조
