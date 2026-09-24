---
name: writing-practice-docs
description: Use when writing, rewriting, or reviewing learning documents under practice/ in this repository — a practice README.md, API-SPEC.md, SEQUENCES.md, or a top-level topic document such as MCP-AUTHORIZATION.md, MCP-API-SPEC.md, MCP-SEQUENCES.md
---

# Writing Practice Docs

## Overview

practice 학습 문서는 명세를 근거로, 핵심만 계층형으로 쓴다.
한 주제는 허브·API 명세·시퀀스 3종으로 나누고, 같은 내용은 한 곳에만 둔다.

## 문서 3종

| 종류 | 최상위(주제 전체) | practice(프로젝트 하나) |
|---|---|---|
| 허브 | `<TOPIC>.md` — 개념·규칙·단계 요약·준수표·보안 | `README.md` — 소개·구성·모듈 역할·직접 쓴 코드·실행·학습 포인트 |
| API 명세 | `<TOPIC>-API-SPEC.md` — 표준 엔드포인트 전체 명세 | `API-SPEC.md` — 모듈별 엔드포인트 표, 프로젝트 고유 API 전체 명세 |
| 시퀀스 | `<TOPIC>-SEQUENCES.md` — 표준 흐름 | `SEQUENCES.md` — 같은 흐름을 이 프로젝트의 클래스·bean 이름으로 |

- 최상위는 표준을, practice 는 구현을 맡는다. practice 는 표준 설명을 반복하지 않고 최상위 앵커로 링크한다.
- 기준 practice 를 확장한 practice 는 겹치는 부분을 "기준 practice 와 같음" 한 줄과 링크로 끝낸다.
- 라이브러리로 같은 기능을 대신한 practice 는 대체 표(기준의 X → 이 practice 의 설정·확장점 Y)를 중심에 둔다.

## 문장 모양

각 절은 이렇게 쓴다.

1. 요약 2~3문장: 무엇을, 왜.
2. 하위 제목마다 2~3문장: 한 단계 더 자세히.
3. 더 필요하면 그 아래 하위 제목에서 다시 2~3문장.

- 한 문단·한 목록 항목은 3문장 이하다. 나열은 표나 목록으로 쓴다.
- 전문 용어는 영어로 쓰고 조사만 한국어로 붙인다. 목록은 `terms.txt` 다.
- 문장은 동작과 명세를 말한다: "X 는 Y 를 한다(조항)". 결과만 쓴다.
- 명세와 구현이 다르면 이 모양으로 쓴다: "Spring 기본 동작은 X, 명세는 Y(조항), 이 practice 는 Z".
- 클래스·설정·파라미터 이름은 backtick 으로 감싼다.

## 명세 인용

- 근거는 원문이다. 조항 링크를 달고 요구 수준(MUST·SHOULD·MAY, REQUIRED·RECOMMENDED·OPTIONAL)은 원문 단어 그대로 쓴다. 설명은 압축한다.
- API 표는 원문의 필드 목록에서 시작한다. REQUIRED·RECOMMENDED·OPTIONAL 을 모두 행으로 두고, 쓰지 않는 필드는 "이 practice" 열에 "쓰지 않음"이라고 쓴다.
- 관측은 "관측: …" 한 줄로 쓴다. 요청·응답 예시는 API 명세에 엔드포인트당 하나, 캡처 원문 그대로(JWT 는 앞 20자, refresh token·authorization code 는 앞 12자 + `...`).
- 관측 문장에는 값만 남긴다. "이 문서를 쓰며 직접 띄워 확인했다"·"재현한 값이다" 처럼 이 글을 쓰는 동안 무엇을 했는지는 적지 않는다.
- 표시 칸에는 원문이 그 필드에 실제로 붙인 단어를 쓴다. 조건이 있으면 그 조건이 적힌 원문 절 번호와 함께 쓴다: "OPTIONAL — §4.3 은 `use` 와 `key_ops` 를 함께 쓰지 않을 것(SHOULD NOT)"처럼. 절 번호 없이 조건을 지어내지 않는다.

## 템플릿

허브 절, 엔드포인트, 시퀀스, README 틀은 `templates.md` 에 있다.

## 검사

```bash
python3 .claude/skills/writing-practice-docs/scripts/check_docs.py FILE...
python3 .claude/skills/writing-practice-docs/scripts/check_docs.py --links-from OLD.md --dropped DROPPED.txt FILE...
```

위반 0 이 될 때까지 고친다. 두 번째 형식은 옛 문서를 새로 쓸 때, 옛 링크가 빠지지 않았는지 본다. 문장 끝 판정은 근사치라서 `length` 가 걸리면 문장을 나눠서 맞춘다.

## 흔한 실수

| 실수 | 고친 모양 |
|---|---|
| `처음엔 …`, `… 를 고쳤다`, `Task 3 에서` | 지금 동작과 명세만 쓴다 |
| `인가 서버`, `토큰`, `동의 화면` | Authorization Server, token, consent |
| 한 문단에 배경·규칙·예외·구현을 모두 | 요약 2~3문장 + 하위 제목으로 나눈다 |
| 구현을 보고 필드 표를 채움 | 원문 필드 목록에서 시작해 "이 practice" 열로 대조한다 |
| SHOULD 를 "할 수 있다"로 옮김, 또는 조건을 절 번호 없이 지어냄 | 요구 수준 단어와 조건을 원문 그대로, 조건은 절 번호와 함께 쓴다("명세 인용" 참고) |
| 같은 표를 허브와 API 명세에 모두 | 한 곳에 두고 링크한다 |
| README 에 실험 상세·트러블슈팅 | 결론만 학습 포인트로 남긴다 |
| 구분선 `---` 바로 위에 글 | 빈 줄을 하나 둔다(없으면 윗줄이 제목이 된다) |
