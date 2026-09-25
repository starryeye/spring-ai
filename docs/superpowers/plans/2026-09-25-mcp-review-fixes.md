# MCP 인증 practice 전체 리뷰 반영 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 전체 리뷰에서 나온 보안 결함·문서 오류·준수표 누락을 세 practice(official·chat-memory·community)와 문서 12개, 문서 스킬에 한 번에 반영한다.

**Architecture:** 코드는 practice 마다 같은 변경을 같은 이름으로 넣는다(official 이 기준, chat-memory 는 official 과 같은 코드, community 는 모듈 위에 얹는다). chat-memory 만 MCP session 을 사용자에 묶는다 — MCP Server 에 session binding filter, agent 에 사용자별 MCP client. 문서는 코드가 끝난 뒤 스킬 규칙(보강판)과 새 캡처를 근거로 고친다.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Security 7.1 / Spring Authorization Server 7.1, Spring AI 2.0.0, MCP Java SDK 2.0.0, `org.springaicommunity` MCP security 0.1.14(community), Python 3(검사 스크립트), bash·curl(캡처).

**Spec:** `docs/superpowers/specs/2026-09-25-mcp-review-fixes-design.md`

## Global Constraints

- 브랜치 `mcp-review-fixes` 에서 작업한다. `.superpowers/` 는 절대 `git add` 하지 않는다.
- Gradle 은 모듈 디렉터리마다 따로 돈다(`practice/<practice>/<module>/gradlew`). 실행 전 `export JAVA_HOME=$(find $HOME/.sdkman/candidates/java -maxdepth 1 -type d -name '21.*' | sort -V | tail -1)`.
- 테스트는 서버를 띄우지 않고 돈다. 서버를 띄워야 하는 캡처(Task 8)만 예외다. `run.sh` 를 포그라운드로 부르지 않는다(600초 제한).
- 포트로 프로세스를 내릴 때는 `lsof -ti tcp:PORT -sTCP:LISTEN` 으로 수신 프로세스만 고른다. zsh 에서는 `for p in $(...); do kill $p; done`.
- 파일마다 기존 들여쓰기를 따른다: official·chat-memory 의 auth-server·shop-mcp-server 는 탭, chat-memory shop-agent·community 는 4칸 공백(community 일부 파일은 섞여 있다 — 고치는 줄 주변을 따른다). 이 계획의 코드 블록은 official 기준 탭으로 적었다.
- 패키지: official `dev.starryeye.officialauthserver`·`dev.starryeye.officialmcpserver`·`dev.starryeye.officialagent`, chat-memory `dev.starryeye.memoryauthn.authserver`·`dev.starryeye.memoryauthn.mcpserver`·`dev.starryeye.memoryauthn.agent`, community `dev.starryeye.authserver`·`dev.starryeye.shopmcpserver`·`dev.starryeye.shopagent`.
- practice 별 값: official AS `http://localhost:9010`, MCP `http://localhost:8111/mcp`(Host `localhost:8111`), agent 8110 / chat-memory AS 9020, MCP 8131, agent 8130 / community AS 9000, MCP 8101, agent 8100.
- 코드 주석·javadoc 은 한국어, 동작과 명세만 쓴다. "처음엔·고쳤다·Task N·실측했다" 같은 개발 과정 서술을 쓰지 않는다.
- 문서(Task 9~12)는 프로젝트 스킬 `writing-practice-docs` 를 따른다: 계층형 2~3문장, 전문 용어는 영어(`terms.txt`), 명세 요구 수준은 원문 단어 그대로, 개발 과정 서술 금지. 새 검사 스크립트로 위반 0.
- 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- 결정 A: official·community 는 MCP session 을 사용자에 묶지 않고 준수표에 "아니오"와 이유만 적는다. chat-memory 만 구현한다.
- 결정 B: 9개 `application.yml` 에 `server.address: 127.0.0.1` 과 주석(뜻·근거·실제 배포 값).
- 결정 C: 세 agent 모두 `csrf.spa()` 로 `/api/chat` 을 포함해 CSRF 예외를 두지 않는다.
- 결정 D: community 는 모듈의 URL 기반 audience 계산을 유지하고, Host 검증이 먼저 막는다는 의존 관계를 테스트·문서로 고정한다.

## Review Focus

- 한 사용자가 브라우저 두 개로 로그인한 상태에서 한쪽을 로그아웃하면 공유된 사용자별 MCP client 가 닫힌다 — 다른 쪽의 다음 채팅은 새 client 를 열어 그대로 동작해야 한다(Task 6 `닫으면_session_을_끝내고_다음_요청은_새_client_를_연다`).
- 같은 사용자가 첫 채팅을 동시에 여러 번 보내도(더블클릭) MCP client 는 하나만 열려야 한다(Task 6 `같은_사용자의_동시_첫_요청도_client_를_하나만_연다`).
- MCP Server 재기동 뒤 agent 의 client 는 SDK 가 새 session 으로 다시 initialize 한다 — 같은 사용자가 연 두 번째 session 도 그 사용자에 묶이고 다른 사용자는 막혀야 한다(Task 6 `한_사용자가_연_여러_session_은_모두_그_사용자에_묶인다`).
- 다른 사용자가 남의 session 을 `DELETE` 로 끝내는 것도 막혀야 한다(Task 6 `다른_사용자는_남의_MCP_session_을_끝낼_수_없다`).
- public client 가 `openid` 없이 `profile` 만 요청하는 정상 경로는 scope 검증에 걸리지 않고 consent 화면으로 가야 한다(Task 2 `공개_클라이언트가_openid_없이_profile_만_요청해도_consent_화면을_거친다`).

---

## 파일 구조

| 파일 | 책임 | Task |
|---|---|---|
| `.claude/skills/writing-practice-docs/scripts/check_docs.py`·`test_check_docs.py`·`terms.txt`·`SKILL.md` | 문서 규칙과 검사 | 1 |
| `<practice>/auth-server/.../PublicClientScopeValidator.java`(새) | public client 의 consent 대상 없는 요청을 `invalid_scope` 로 거부 | 2 |
| `<practice>/auth-server/.../ResourceAudienceTokenCustomizer.java` | token 요청에서 새 `resource` 거부 | 2 |
| `community/auth-server/.../SingleResourceTokenRequestConverter.java`(새) | token 요청의 `resource` 여러 개를 `invalid_target` 으로 | 2 |
| `<practice>/shop-agent/.../McpAuthorizationDiscovery.java`·`DiscoveredClientRegistrationRepository.java` | issuer 확인을 AS metadata GET 앞으로, URL 스킴 검증 | 3 |
| `official·chat-memory/shop-mcp-server/.../McpTransportSecurityFilter.java`(새)·`McpTransportConfig.java` | Origin·Host 검증을 Spring Security 앞으로 | 4 |
| `<practice>/shop-agent/.../SecurityConfig.java`·`static/index.html` | `csrf.spa()` | 5 |
| `chat-memory/shop-mcp-server/.../McpSessionBindingFilter.java`(새) | session 을 token 의 사용자에 묶음 | 6 |
| `chat-memory/shop-agent/.../UserMcpClients.java`(새) 외 | 사용자별 MCP client | 6 |
| `<practice>/run.sh`·`stop.sh`·9개 `application.yml` | lsof, bind 주소 | 7 |
| `docs/superpowers/captures/*` | P8-1, 수신 주소 캡처 | 8 |
| `practice/MCP-*.md`, `practice/mcp-security-authn-*/*.md` | 문서 | 9~12 |

---

### Task 1: 문서 스킬과 검사 스크립트 보강

**Files:**
- Modify: `.claude/skills/writing-practice-docs/scripts/check_docs.py`
- Modify: `.claude/skills/writing-practice-docs/scripts/test_check_docs.py`
- Modify: `.claude/skills/writing-practice-docs/terms.txt`
- Modify: `.claude/skills/writing-practice-docs/SKILL.md`

**Interfaces:**
- Produces: 새 규칙 이름 `cell-length`, `sentence-chars`, `observation`. Task 9~12 가 이 스크립트로 위반 0 을 만든다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`test_check_docs.py` 끝(`LinksFromTest` 앞)에 추가한다.

```python
class CellTest(unittest.TestCase):
    def test_세_문장_표_칸은_걸린다(self):
        doc = "| a | b |\n|---|---|\n| x | 하나다. 둘이다. 셋이다. |\n"
        self.assertIn("cell-length:3", rules(doc))

    def test_두_문장_표_칸은_통과한다(self):
        doc = "| a | b |\n|---|---|\n| x | 하나다. 둘이다. |\n"
        self.assertEqual([], rules(doc))

    def test_이스케이프한_파이프는_칸을_나누지_않는다(self):
        doc = "| a | b |\n|---|---|\n| x \\| y | 하나다. |\n"
        self.assertEqual([], rules(doc))


class SentenceCharsTest(unittest.TestCase):
    def test_150자를_넘는_문장은_걸린다(self):
        self.assertIn("sentence-chars:1", rules("가" * 151 + "다.\n"))

    def test_150자_이하_문장은_통과한다(self):
        self.assertEqual([], rules("가" * 148 + "다.\n"))

    def test_inline_code_와_URL_은_글자_수에_넣지_않는다(self):
        self.assertEqual([], rules("`" + "x" * 200 + "` 를 https://example.com/" + "y" * 200 + " 로 보낸다.\n"))

    def test_표_칸의_긴_문장도_걸린다(self):
        doc = "| a | b |\n|---|---|\n| x | " + "가" * 151 + "다. |\n"
        self.assertIn("sentence-chars:3", rules(doc))


class ObservationTest(unittest.TestCase):
    def test_캡처_ID_없는_관측은_걸린다(self):
        self.assertIn("observation:1", rules("관측: 재기동마다 값이 바뀐다.\n"))

    def test_캡처_ID_가_있는_관측은_통과한다(self):
        self.assertEqual([], rules("관측: C1 은 `401` 을 받는다.\n"))
        self.assertEqual([], rules("관측: P8-1 은 `invalid_scope` 를 받는다.\n"))


class NarrativeMoreTest(unittest.TestCase):
    def test_과거형_작업_서술은_걸린다(self):
        for phrase in ["바꿨", "추가했", "옮겼", "없앴", "확인했", "드러났"]:
            with self.subTest(phrase=phrase):
                self.assertIn("narrative:1", rules(f"설정을 {phrase}다.\n"))


class TermsMoreTest(unittest.TestCase):
    def test_새_용어는_걸린다(self):
        for word in ["재동의", "인가된", "브라우저", "엔드포인트", "커뮤니티", "모듈"]:
            with self.subTest(word=word):
                self.assertIn("term:1", rules(f"{word} 를 본다.\n"))
```

- [ ] **Step 2: 실패를 확인한다**

Run: `python3 -m unittest discover -s .claude/skills/writing-practice-docs/scripts -p 'test_*.py'`
Expected: 새 테스트들이 FAIL(`cell-length`·`sentence-chars`·`observation` 규칙 없음, 새 용어·서술 없음). 기존 27개는 PASS.

- [ ] **Step 3: 검사 스크립트를 구현한다**

`check_docs.py` 상단 상수를 바꾸고 더한다.

```python
# 개발 과정 이야기를 드러내는 표현. 문서에는 결과와 명세만 남긴다.
BANNED_PHRASES = ["처음엔", "처음에는", "고쳤", "수정했", "Task ", "실측했", "착각", "버그를",
                  "바꿨", "추가했", "옮겼", "없앴", "확인했", "드러났"]
SENTENCE_LIMIT = 3
CELL_SENTENCE_LIMIT = 2
SENTENCE_CHAR_LIMIT = 150
```

```python
OBSERVATION = re.compile(r"^\s*(?:[-*]\s+)?관측:")
CAPTURE_ID = re.compile(r"(?<![A-Za-z0-9])[CSP]\d+(?:-\d+)?(?![A-Za-z0-9])")
SENTENCE_END = re.compile(r"(?<!\d)[.?!](?=\s|$|[)\]\"'])")
CELL_SPLIT = re.compile(r"(?<!\\)\|")
```

docstring 의 규칙 이름 줄에 `cell-length, sentence-chars, observation` 을 더한다. `sentence_count` 는 `SENTENCE_END` 를 쓰도록 바꾸고 아래 함수를 더한다.

```python
def sentence_count(text: str) -> int:
    """문장 끝(. ? !)을 센다. 숫자 사이의 점(2.1, §4.3)은 세지 않는다."""
    return len(SENTENCE_END.findall(clean(text)))


def sentences(text: str) -> list[str]:
    """inline code·링크 대상·URL 을 지운 뒤 문장 끝으로 나눈다."""
    flat = re.sub(r"\s+", " ", clean(text))
    return [s.strip() for s in SENTENCE_END.split(flat) if s.strip()]


def table_cells(prose):
    """표 데이터 행의 칸: [(줄 번호, 칸 글)]. 구분 행은 뺀다."""
    for no, line in prose:
        stripped = line.strip()
        if TABLE_ROW.match(line) and not TABLE_SEP.match(stripped):
            for cell in CELL_SPLIT.split(stripped.strip("|")):
                yield no, cell


def check_cells(path: str, prose) -> list[Issue]:
    issues = []
    for no, cell in table_cells(prose):
        if (n := sentence_count(cell)) > CELL_SENTENCE_LIMIT:
            issues.append(Issue(path, no, "cell-length", f"표 칸이 {n}문장이다(최대 {CELL_SENTENCE_LIMIT})"))
        issues += _long_sentences(path, no, cell)
    return issues


def check_sentence_chars(path: str, prose) -> list[Issue]:
    issues = []
    for no, text in units(prose):
        issues += _long_sentences(path, no, text)
    return issues


def _long_sentences(path: str, no: int, text: str) -> list[Issue]:
    return [
        Issue(path, no, "sentence-chars", f"문장이 {len(s)}자다(최대 {SENTENCE_CHAR_LIMIT}): {s[:30]}…")
        for s in sentences(text)
        if len(s) > SENTENCE_CHAR_LIMIT
    ]


def check_observations(path: str, prose) -> list[Issue]:
    """'관측:' 줄은 근거가 된 캡처 단계 ID(C<n>·S<n>·P<n>, 하위 단계는 P8-1)를 담아야 한다."""
    return [
        Issue(path, no, "observation", "관측 줄에 캡처 단계 ID(C<n>·S<n>·P<n>)가 없다")
        for no, line in prose
        if OBSERVATION.match(line) and not CAPTURE_ID.search(line)
    ]
```

`check_file` 에 연결한다.

```python
    issues = check_setext(rel, prose) + check_units(rel, prose) + check_words(rel, prose, terms)
    issues += check_cells(rel, prose) + check_sentence_chars(rel, prose) + check_observations(rel, prose)
    issues += check_field_counts(rel, prose)
```

`terms.txt` 의 `인가` 줄 lookahead 에 `된` 을 더하고 새 용어를 더한다.

```text
(?<![가-힣])인가(?=[\s을를은는이가의에와과도로된]|$)	authorization
재동의	re-consent
브라우저	browser
엔드포인트	endpoint
커뮤니티	community
모듈	module
```

(`인가된` 은 바뀐 `인가` 패턴이 잡는다 — `term` 설명은 `authorization` 으로 나온다.)

- [ ] **Step 4: 통과를 확인한다**

Run: `python3 -m unittest discover -s .claude/skills/writing-practice-docs/scripts -p 'test_*.py'`
Expected: 전부 PASS.

- [ ] **Step 5: SKILL.md 규칙을 더한다**

`## 문서 3종` 절 끝 목록에 더한다.

```markdown
- 범위는 MCP 와 그 Authorization Server 다. 테스트 배선·운영 절차·저장소 관리(캡처 파일 관리, git 상태)는 쓰지 않는다.
- 이동 링크: API 명세의 엔드포인트 절마다 근거 줄 옆에 허브 절·시퀀스 절 링크를 둔다. 최상위 시퀀스 절 끝에는 practice 시퀀스 링크 한 줄("구현: official … · community …")을 둔다.
- practice `SEQUENCES.md` 는 구성(components)·등록(registration)·런타임 절을 모두 둔다. 확장·대체 practice 는 기준 practice 절 링크 한 줄과 다른 점만 그린다.
- 여러 문서가 쓰는 표(포트·client_id 등)는 허브 한 곳에 두고 나머지는 링크한다.
- 기준 practice 문서는 단독으로 읽히게 쓴다. 다른 practice 와의 비교는 대체 표 한 곳에만 둔다.
```

`## 문장 모양` 목록에 더한다.

```markdown
- 표 칸은 2문장 이하, 한 문장은 150자 이하다(inline code·URL 제외).
```

`## 명세 인용` 의 관측 항목 뒤에 더한다.

```markdown
- "관측:" 줄에는 근거 캡처 단계 ID(`C<n>`·`S<n>`·`P<n>`, 하위 단계 `P8-1`)를 반드시 둔다. 캡처로 보이지 않는 동작은 관측이 아니라 코드 근거(클래스·테스트 이름)로 쓴다.
```

`## 검사` 절 코드 블록 아래에 더한다.

```markdown
mermaid 는 문법 검사 뒤 렌더도 확인한다: `npx -y @mermaid-js/mermaid-cli -i FILE.md -o /tmp/render.md` 가 오류 없이 끝나야 한다.
```

`## 흔한 실수` 표에 행을 더한다.

```markdown
| `재동의`, `브라우저`, `엔드포인트`, `모듈` | re-consent, browser, endpoint, module |
| 관측: 캡처 없이 "재기동마다 바뀐다" | 캡처 단계 ID 를 달거나, 코드 근거 문장으로 바꾼다 |
```

- [ ] **Step 6: 기존 문서에 돌려 새 규칙이 동작하는지 본다(수정은 Task 9~12)**

Run: `python3 .claude/skills/writing-practice-docs/scripts/check_docs.py practice/MCP-AUTHORIZATION.md practice/MCP-API-SPEC.md practice/MCP-SEQUENCES.md practice/mcp-security-authn-*/README.md practice/mcp-security-authn-*/API-SPEC.md practice/mcp-security-authn-*/SEQUENCES.md | tail -5`
Expected: 새 규칙(`sentence-chars`, `term`, `observation` 등) 위반이 나온다. 이 Task 에서는 고치지 않는다 — 개수만 커밋 메시지 본문에 적는다.

- [ ] **Step 7: 커밋**

```bash
git add .claude/skills/writing-practice-docs
git commit -m "docs(skill): 표 칸·문장 길이·관측 근거·용어 검사 추가

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Authorization Server — public client scope 검증, token 시점 `resource`, community 반복 `resource`

세 practice 의 `auth-server` 에 같은 변경을 넣는다. official 을 먼저 끝내고(TDD), chat-memory(코드가 official 과 같다 — 패키지만 다름)와 community 에 옮긴다.

**Files (practice 마다):**
- Create: `auth-server/src/main/java/<pkg>/PublicClientScopeValidator.java`
- Modify: `AuthorizationServerConfig.java`(official·chat-memory) / `McpAuthorizationStandardConfig.java`(community)
- Modify: `ResourceAudienceTokenCustomizer.java`
- Modify: `PublicClientConsentService.java`(javadoc), `src/main/resources/application.yml`(주석)
- Create(community): `auth-server/src/main/java/dev/starryeye/authserver/SingleResourceTokenRequestConverter.java`
- Test: `auth-server/src/test/java/<pkg>/AuthorizationServerStandardTest.java`

**Interfaces:**
- Produces: `PublicClientScopeValidator implements Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext>`(no-arg). Task 8 캡처 P8-1 이 `invalid_scope` 를 기대한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다(official)**

`AuthorizationServerStandardTest` 의 helper 들 뒤에 더한다.

```java
	/** public client 가 scope 만 바꿔 인가 요청한다. scope 가 null 이면 파라미터를 빼고 보낸다. */
	UriComponents 공개클라이언트_scope_인가요청(String scope) throws Exception {
		UriComponentsBuilder uri = 공개클라이언트_인가요청_URI(true, RESOURCE);
		if (scope == null) {
			uri.replaceQueryParam("scope");
		}
		else {
			uri.replaceQueryParam("scope", scope);
		}
		String location = this.mockMvc.perform(get(uri.encode().build().toUri()).session(this.session))
				.andExpect(status().is3xxRedirection())
				.andReturn().getResponse().getRedirectedUrl();
		return UriComponentsBuilder.fromUriString(location).build();
	}

	/** 기밀 client 의 인가 요청에서 scope 와 resource 값을 바꿔 보낸다. */
	UriComponents 인가요청(String scope, String... resources) throws Exception {
		UriComponentsBuilder uri = UriComponentsBuilder.fromPath("/oauth2/authorize")
				.queryParam("response_type", "code")
				.queryParam("client_id", CLIENT_ID)
				.queryParam("redirect_uri", REDIRECT_URI)
				.queryParam("scope", scope)
				.queryParam("state", "state-1")
				.queryParam("code_challenge", CODE_CHALLENGE)
				.queryParam("code_challenge_method", "S256");
		if (resources.length > 0) {
			uri.queryParam("resource", (Object[]) resources);
		}
		String location = this.mockMvc.perform(get(uri.encode().build().toUri()).session(this.session))
				.andExpect(status().is3xxRedirection())
				.andReturn().getResponse().getRedirectedUrl();
		return UriComponentsBuilder.fromUriString(location).build();
	}
```

테스트를 더한다.

```java
	@Test
	void 공개_클라이언트가_openid_만_요청하면_invalid_scope_다() throws Exception {
		// Spring 은 scope 가 openid 하나면 consent 를 건너뛴다. public client 가 그 길로 consent 없이
		// code 를 받으면 안 되므로(OAuth 2.1 §7.3.1) PublicClientScopeValidator 가 먼저 거부한다.
		UriComponents response = 공개클라이언트_scope_인가요청("openid");

		assertThat(응답파라미터(response, "error")).isEqualTo("invalid_scope");
		assertThat(응답파라미터(response, "code")).isNull();
		assertThat(응답파라미터(response, "state")).isEqualTo("state-1");
		assertThat(응답파라미터(response, "iss")).isEqualTo(ISSUER);
	}

	@Test
	void 공개_클라이언트가_scope_없이_요청하면_invalid_scope_다() throws Exception {
		// RFC 6749 §3.3 — scope 를 생략하면 기본값으로 처리하거나 invalid_scope 로 거부해야 한다(MUST).
		UriComponents response = 공개클라이언트_scope_인가요청(null);

		assertThat(응답파라미터(response, "error")).isEqualTo("invalid_scope");
		assertThat(응답파라미터(response, "code")).isNull();
	}

	@Test
	void 공개_클라이언트가_openid_없이_profile_만_요청해도_consent_화면을_거친다() throws Exception {
		MvcResult response = this.mockMvc
				.perform(get(공개클라이언트_인가요청_URI(true, RESOURCE).replaceQueryParam("scope", "profile")
						.encode().build().toUri()).session(this.session))
				.andReturn();

		assertThat(response.getResponse().getStatus()).isEqualTo(200);
		assertThat(response.getResponse().getContentAsString()).contains("Consent required");
	}

	@Test
	void 기밀_클라이언트는_openid_만_요청해도_곧장_code_를_받는다() throws Exception {
		UriComponents response = 인가요청("openid", RESOURCE);

		assertThat(응답파라미터(response, "code")).isNotBlank();
	}

	@Test
	void 인가_요청에_없던_resource_를_토큰_요청에서_정하면_invalid_target_이다() throws Exception {
		// resource 는 사용자가 consent 한 대상이다. 인가 요청에 없던 대상을 token 요청에서 새로 정하지 못한다.
		String body = 토큰요청(인가코드교환(인가코드(null), RESOURCE), 400);

		assertThat((String) JsonPath.read(body, "$.error")).isEqualTo("invalid_target");
	}

	@Test
	void 인가_요청의_resource_가_여러_개면_invalid_target_이다() throws Exception {
		// 이 Authorization Server 는 보호 리소스 하나만 다룬다. 값이 여러 개면 String[] 이 되어 허용 목록과 맞지 않는다.
		UriComponents response = 인가요청("openid profile", RESOURCE, OTHER_RESOURCE);

		assertThat(응답파라미터(response, "error")).isEqualTo("invalid_target");
		assertThat(응답파라미터(response, "code")).isNull();
	}

	@Test
	void 토큰_요청의_resource_가_여러_개면_invalid_target_이다() throws Exception {
		MultiValueMap<String, String> parameters = 인가코드교환(인가코드(RESOURCE), RESOURCE);
		parameters.add("resource", OTHER_RESOURCE);

		String body = 토큰요청(parameters, 400);

		assertThat((String) JsonPath.read(body, "$.error")).isEqualTo("invalid_target");
	}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd practice/mcp-security-authn-official/auth-server && ./gradlew test --tests '*AuthorizationServerStandardTest'`
Expected: `공개_클라이언트가_openid_만_요청하면_invalid_scope_다`(error 가 null — code 가 발급됨), `공개_클라이언트가_scope_없이_요청하면_invalid_scope_다`(200 consent — 3xx 기대 실패), `인가_요청에_없던_resource_를_토큰_요청에서_정하면_invalid_target_이다`(200) 가 FAIL. 나머지 새 테스트는 PASS(현재 동작을 고정한다).

- [ ] **Step 3: `PublicClientScopeValidator` 를 만든다(official)**

```java
package dev.starryeye.officialauthserver;

import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;

import java.util.function.Consumer;

/**
 * public client 가 consent 할 scope 없이 오면 인가 요청을 거부한다.
 *
 * <p>OAuth 2.1 §7.3.1 — 신원을 확인할 수 없는 client 의 요청은 이전 consent 가 있어도 처음처럼
 * 처리하고, consent 화면 없이 자동으로 처리하지 않는다(SHOULD NOT). Spring 은 요청 scope 가
 * {@code openid} 하나면 consent 를 건너뛰고, 사용자가 고른 scope 가 없으면 {@code openid} 도 붙이지
 * 않고 {@code access_denied} 로 끝낸다. 그래서 consent 를 강제하는 대신 consent 할 scope 가 없는
 * 요청을 consent 판정 전에 거부한다.
 *
 * <p>scope 를 생략한 요청은 RFC 6749 §3.3 에 따라 {@code invalid_scope} 다(기본값으로 처리하거나
 * 거부 — MUST). {@code openid} 하나뿐인 요청도 같은 오류로 거부한다.
 */
public class PublicClientScopeValidator implements Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> {

	static final String RFC_6749_SCOPE = "https://www.rfc-editor.org/rfc/rfc6749#section-3.3";

	@Override
	public void accept(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
		if (!context.getRegisteredClient().getClientAuthenticationMethods()
				.contains(ClientAuthenticationMethod.NONE)) {
			return;
		}
		OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
		boolean hasConsentableScope = request.getScopes().stream()
				.anyMatch(scope -> !OidcScopes.OPENID.equals(scope));
		if (!hasConsentableScope) {
			OAuth2Error error = new OAuth2Error(OAuth2ErrorCodes.INVALID_SCOPE,
					"A public client must request at least one scope other than openid", RFC_6749_SCOPE);
			throw new OAuth2AuthorizationCodeRequestAuthenticationException(error, request);
		}
	}
}
```

- [ ] **Step 4: 검증기 체인에 잇는다(official)**

`AuthorizationServerConfig#authorizationServerSecurityFilterChain`:

```java
		ResourceIndicatorValidator resourceValidator = new ResourceIndicatorValidator(resources);
		PublicClientScopeValidator publicClientScopeValidator = new PublicClientScopeValidator();
```

```java
									// 기본 검증(redirect_uri·scope) 뒤에 RFC 8707 resource 검증과
									// public client 의 scope 검증(OAuth 2.1 §7.3.1)을 잇는다.
									.authenticationProviders(providers -> providers.forEach(provider -> {
										if (provider instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider codeProvider) {
											codeProvider.setAuthenticationValidator(
													new OAuth2AuthorizationCodeRequestAuthenticationValidator()
															.andThen(resourceValidator)
															.andThen(publicClientScopeValidator));
										}
									})))
```

- [ ] **Step 5: token 시점 `resource` 를 막는다(official)**

`ResourceAudienceTokenCustomizer#customize` 의 불일치 검사 바로 뒤에 더한다.

```java
		// 인가 요청에 없던 리소스를 token 요청에서 새로 정하지 못하게 한다 — 사용자가 consent 한 대상이 아니다.
		if (requested != null && authorized == null) {
			throw invalidTarget("The authorization request did not include a resource");
		}
```

- [ ] **Step 6: 주석을 두 장치에 맞춘다(official)**

`PublicClientConsentService` 클래스 javadoc 마지막 문단을 바꾼다.

```java
 * <p>Spring 은 한 번 받은 동의를 이 서비스에 저장하고, 다음 인가 요청에서 저장된 동의가
 * 요청 scope 를 모두 덮으면 동의 화면을 건너뛴다. 공개 클라이언트면 저장하지 않고 조회에도
 * {@code null} 을 돌려준다. 동의를 받은 그 요청은 저장 여부와 상관없이 방금 고른 scope 로 코드를
 * 발급한다. Spring 이 저장과 무관하게 건너뛰는 경로(scope 가 {@code openid} 하나)는
 * {@link PublicClientScopeValidator} 가 막는다. 기밀 클라이언트는 위임한 서비스에 그대로 남긴다.
```

`application.yml` 의 `local-mcp-client` 아래 `require-authorization-consent: true` 주석 마지막 두 줄을 바꾼다.

```yaml
            # 비밀이 없어 "자기 자신임"을 증명하지 못하므로, 사용자가 무엇을 허용하는지
            # 직접 확인하는 단계를 둔다(OAuth 2.1 §7.3). 클라이언트 단위 설정이라
            # official-shop-agent 는 영향받지 않는다. PublicClientConsentService 는 이 클라이언트의
            # 동의를 기록하지 않고, PublicClientScopeValidator 는 동의할 scope 가 없는 요청(scope 없음,
            # openid 하나)을 invalid_scope 로 거부한다 — 두 장치로 모든 인가 요청이 동의 화면을 거친다(§7.3.1).
            require-authorization-consent: true
```

- [ ] **Step 7: official 테스트 통과를 확인한다**

Run: `cd practice/mcp-security-authn-official/auth-server && ./gradlew test`
Expected: PASS(기존 31 + 새 7 = 38).

- [ ] **Step 8: chat-memory 에 옮긴다**

chat-memory `auth-server` 의 `AuthorizationServerConfig`·`ResourceAudienceTokenCustomizer`·`PublicClientConsentService` 는 official 과 패키지만 다르다. Step 1·3~6 을 패키지 `dev.starryeye.memoryauthn.authserver` 로 그대로 적용한다(yml 주석의 `official-shop-agent` 는 `memory-agent`). 테스트 상수는 그 파일의 값(`CLIENT_ID = "memory-agent"` 등)을 그대로 쓴다.

Run: `cd practice/mcp-security-authn-chat-memory/auth-server && ./gradlew test`
Expected: 새 테스트를 먼저 넣은 상태에서 3개 FAIL → 구현 뒤 PASS(38).

- [ ] **Step 9: community 에 옮긴다**

1. Step 1 테스트를 community `AuthorizationServerStandardTest`(4칸 들여쓰기, `CLIENT_ID = "shop-agent"`)에 넣는다. 더해서 모듈의 `(String)` 캐스트 경로를 고정하는 테스트를 넣는다.

```java
    @Test
    void openid_없는_토큰_요청의_resource_가_여러_개여도_invalid_target_이다() throws Exception {
        // 모듈 ResourceIdentifierAudienceTokenCustomizer 는 openid 가 없으면 resource 를 (String) 으로
        // 캐스트한다. 값이 여러 개면 String[] 이라 캐스트가 실패하므로, SingleResourceTokenRequestConverter
        // 가 그 전에 invalid_target 으로 막는다.
        String code = 응답파라미터(인가요청("profile", RESOURCE), "code");
        MultiValueMap<String, String> parameters = 인가코드교환(code, RESOURCE);
        parameters.add("resource", OTHER_RESOURCE);

        String body = 토큰요청(parameters, 400);

        assertThat((String) JsonPath.read(body, "$.error")).isEqualTo("invalid_target");
    }
```

Run: `cd practice/mcp-security-authn-community/auth-server && ./gradlew test --tests '*AuthorizationServerStandardTest'`
Expected: official 과 같은 3개 FAIL, 그리고 `openid_없는_토큰_요청의_resource_가_여러_개여도_invalid_target_이다` 가 `ClassCastException` 으로 ERROR. `공개_클라이언트가_scope_없이_요청하면_invalid_scope_다` 는 모듈 `McpNoScopeClientConsentNotRequired` 때문에 code 가 발급되어 FAIL 한다.

2. `PublicClientScopeValidator` 를 패키지 `dev.starryeye.authserver`(4칸)로 만든다. javadoc 첫 문단 끝에 한 문장을 더한다: `community 모듈의 McpNoScopeClientConsentNotRequired 는 scope 가 없는 요청도 consent 를 건너뛴다 — 이 검증기가 그 경로도 막는다.`

3. `SingleResourceTokenRequestConverter` 를 만든다.

```java
package dev.starryeye.authserver;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.authentication.AuthenticationConverter;

/**
 * token 요청에 {@code resource} 가 여러 개면 {@code invalid_target} 으로 거부한다(RFC 8707 §2).
 *
 * <p>Spring 의 token 요청 converter 는 값이 여러 개인 파라미터를 {@code String[]} 로 넘긴다. 모듈의
 * {@code ResourceIdentifierAudienceTokenCustomizer} 는 그 값을 {@code (String)} 으로 캐스트하므로 500 이 된다.
 * 이 converter 는 기본 converter 들보다 먼저 돌아 그 전에 막고, 나머지 요청은 {@code null} 을 돌려
 * 다음 converter 에 넘긴다.
 */
public class SingleResourceTokenRequestConverter implements AuthenticationConverter {

    @Override
    public Authentication convert(HttpServletRequest request) {
        String[] resources = request.getParameterValues(McpResourceProperties.RESOURCE_PARAMETER);
        if (resources != null && resources.length > 1) {
            throw new OAuth2AuthenticationException(new OAuth2Error(McpResourceProperties.INVALID_TARGET,
                    "Only one resource is supported per token request", McpResourceProperties.RFC_8707));
        }
        return null;
    }
}
```

(community `McpResourceProperties` 에 `RESOURCE_PARAMETER`·`INVALID_TARGET`·`RFC_8707` 이 official 과 같이 있는지 확인한다. 없으면 official 과 같은 상수를 더한다.)

4. `McpAuthorizationStandardConfig#mcpAuthorizationStandardCustomizer` 를 바꾼다.

```java
        return configurer -> configurer
                // 기본 검증(redirect_uri·scope) 뒤에 RFC 8707 resource 검증과
                // public client 의 scope 검증(OAuth 2.1 §7.3.1)을 잇는다.
                .authorizationCodeRequestValidator(new OAuth2AuthorizationCodeRequestAuthenticationValidator()
                        .andThen(new ResourceIndicatorValidator(resources))
                        .andThen(new PublicClientScopeValidator()))
                .authorizationServer(authorizationServer -> authorizationServer
                        // RFC 8707: 모듈 customizer 의 (String) 캐스트보다 먼저 resource 개수를 본다.
                        .tokenEndpoint(token -> token.accessTokenRequestConverter(new SingleResourceTokenRequestConverter()))
                        // RFC 9207: 성공·오류 응답 모두에 iss 를 싣는다.
                        .authorizationEndpoint(authorization -> authorization
```

(이후 체인은 그대로 둔다.)

5. 같은 클래스 javadoc 의 두 번째 문단(`모듈도 {@code resource} 를 {@code aud} 로 넣는 커스터마이저 … aud 를 채운다.`)을 바꾼다.

```java
 * <p>모듈의 {@code ResourceIdentifierAudienceTokenCustomizer} 는 access token 이면서 {@code openid} 가
 * 승인됐을 때만 건너뛰고, ID token 에는 {@code aud} 를 {@code resource} 로 덮어쓴다. 이 practice 의
 * agent 는 로그인(openid)으로 token 을 받으므로 access token 의 {@code aud} 가 비고 ID token 의
 * {@code aud} 는 틀어진다. 아래 {@code ResourceAudienceTokenCustomizer} 가 모듈 것 뒤에 실행되어
 * access token 에 {@code aud=resource} 를 넣고 ID token 의 {@code aud} 를 client_id 로 되돌린다.
```

6. community `ResourceAudienceTokenCustomizer` 에 Step 5 의 검사를 넣는다(access token 분기 안, 불일치 검사 뒤).

7. community `PublicClientConsentService` javadoc 과 `application.yml` 주석을 Step 6 과 같이 바꾼다(`official-shop-agent` 는 `shop-agent`).

Run: `cd practice/mcp-security-authn-community/auth-server && ./gradlew test`
Expected: PASS(기존 33 + 새 8 = 41).

- [ ] **Step 10: 커밋**

```bash
git add practice/mcp-security-authn-official/auth-server practice/mcp-security-authn-chat-memory/auth-server practice/mcp-security-authn-community/auth-server
git commit -m "fix(auth-server): public client 는 consent 할 scope 가 있어야 하고, token 요청에서 resource 를 새로 정할 수 없다

- PublicClientScopeValidator: scope 없음·openid 하나면 invalid_scope (RFC 6749 §3.3, OAuth 2.1 §7.3.1)
- ResourceAudienceTokenCustomizer: 인가 요청에 없던 resource 는 invalid_target
- community: token 요청의 resource 여러 개를 모듈 캐스트 전에 invalid_target 으로

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: agent discovery — issuer 확인을 metadata 요청 앞으로, URL 스킴 검증

**Files (practice 마다 `shop-agent`):**
- Modify: `McpAuthorizationDiscovery.java`, `DiscoveredClientRegistrationRepository.java`
- Test: `McpAuthorizationDiscoveryTest.java`, `DiscoveredClientRegistrationRepositoryTest.java`, `ShopAgentApplicationTests.java`, `TokenRefreshTest.java`

**Interfaces:**
- Produces: `McpAuthorizationDiscovery#discover(String resourceUrl, String trustedIssuer)` — 한 인자 버전은 없앤다. `DiscoveredClientRegistrationRepository` 는 `properties.credentialsIssuer()` 를 넘긴다. 이후 Task 의 테스트에서 discovery 를 mock 할 때 `discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER)` 를 쓴다.

- [ ] **Step 1: 실패하는 테스트를 쓴다(official)**

`McpAuthorizationDiscoveryTest` 의 기존 `this.discovery.discover(RESOURCE)` 호출을 모두 `this.discovery.discover(RESOURCE, ISSUER)` 로 바꾸고 테스트를 더한다.

```java
	@Test
	void PRM_의_Authorization_Server_가_자격증명의_issuer_가_아니면_metadata_를_요청하지_않는다() {
		// PRM 이 가리키는 주소로 곧장 GET 하면 공격자가 고른 주소로 요청이 나간다(SSRF).
		// 자격증명이 묶인 issuer 가 아니면 그 metadata 도 요청하지 않는다 — 기대하지 않은 요청이
		// 나가면 MockRestServiceServer 가 AssertionError 를 던져 이 테스트가 실패한다.
		챌린지("Bearer resource_metadata=\"http://localhost:8111/.well-known/oauth-protected-resource/mcp\"");
		응답("http://localhost:8111/.well-known/oauth-protected-resource/mcp", """
				{"resource":"http://localhost:8111/mcp","authorization_servers":["http://evil.example"]}""");

		assertThatExceptionOfType(McpDiscoveryException.class)
				.isThrownBy(() -> this.discovery.discover(RESOURCE, ISSUER))
				.withMessageContaining("http://evil.example");
		this.server.verify();
	}

	@Test
	void authorization_endpoint_가_http_URL_이_아니면_진행하지_않는다() {
		// MCP Security Best Practices — client 는 authorization URL 을 열기 전에 스킴을 확인한다(MUST).
		챌린지("Bearer resource_metadata=\"http://localhost:8111/.well-known/oauth-protected-resource/mcp\"");
		응답("http://localhost:8111/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
		응답("http://localhost:9010/.well-known/oauth-authorization-server",
				AUTHORIZATION_SERVER_METADATA.replace("http://localhost:9010/oauth2/authorize", "javascript:alert(1)"));

		assertThatExceptionOfType(McpDiscoveryException.class)
				.isThrownBy(() -> this.discovery.discover(RESOURCE, ISSUER))
				.withMessageContaining("authorization_endpoint");
	}

	@Test
	void token_endpoint_가_http_URL_이_아니면_진행하지_않는다() {
		챌린지("Bearer resource_metadata=\"http://localhost:8111/.well-known/oauth-protected-resource/mcp\"");
		응답("http://localhost:8111/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
		응답("http://localhost:9010/.well-known/oauth-authorization-server",
				AUTHORIZATION_SERVER_METADATA.replace("http://localhost:9010/oauth2/token", "file:///etc/passwd"));

		assertThatExceptionOfType(McpDiscoveryException.class)
				.isThrownBy(() -> this.discovery.discover(RESOURCE, ISSUER))
				.withMessageContaining("token_endpoint");
	}
```

`DiscoveredClientRegistrationRepositoryTest`: `given(this.discovery.discover(DiscoveryFixtures.RESOURCE))` 를 모두 `given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER))` 로, `should(times(1)).discover(anyString())` 를 `should(times(1)).discover(anyString(), anyString())` 로 바꾼다. `자격증명이_묶인_인가_서버가_아니면_쓰지_않는다` 테스트는 지우고(검사가 discovery 로 옮겨 갔다) 이것으로 바꾼다.

```java
	@Test
	void 자격증명이_묶인_issuer_를_discovery_에_넘긴다() {
		given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER))
				.willReturn(DiscoveryFixtures.discovered());

		repository().findByRegistrationId("authserver");

		then(this.discovery).should().discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER);
	}
```

`ShopAgentApplicationTests` 의 `given(this.discovery.discover(DiscoveryFixtures.RESOURCE))` 를 `given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER))` 로 바꾼다.

`TokenRefreshTest` 가 실제 bean 메서드를 거치게 바꾼다. 손으로 만들던 refresh client 세 줄을 지우고 이것으로 바꾼다.

```java
        // 운영과 같은 bean 메서드로 refresh client 를 만든다 — resource 파라미터를 싣는 것도 그 메서드의 일이다.
        DiscoveredClientRegistrationRepository discovered = mock(DiscoveredClientRegistrationRepository.class);
        given(discovered.discovered()).willReturn(DiscoveryFixtures.discovered());
        var refreshTokenClient = new McpSecurityConfig().refreshTokenTokenResponseClient(discovered);
        refreshTokenClient.setRestClient(builder.build());
```

(`import static org.mockito.BDDMockito.given; import static org.mockito.Mockito.mock;` 를 더한다. `RESOURCE` 상수는 `DiscoveryFixtures.RESOURCE` 와 같은 값이다.)

- [ ] **Step 2: 실패를 확인한다**

Run: `cd practice/mcp-security-authn-official/shop-agent && ./gradlew test`
Expected: 컴파일 실패(`discover(String, String)` 없음).

- [ ] **Step 3: discovery 를 구현한다(official)**

`McpAuthorizationDiscovery`:

```java
	/**
	 * @param trustedIssuer 자격증명이 등록된 Authorization Server. PRM 이 다른 곳을 가리키면 그 metadata 도
	 *                      요청하지 않고 멈춘다(MCP 2026-07-28 issuer binding, Security Best Practices — SSRF).
	 */
	public DiscoveredAuthorization discover(String resourceUrl, String trustedIssuer) {
		Map<String, Object> protectedResource = protectedResourceMetadata(resourceUrl);

		if (!(protectedResource.get("authorization_servers") instanceof List<?> servers) || servers.isEmpty()) {
			throw new McpDiscoveryException("보호 리소스 메타데이터에 authorization_servers 가 없다: " + resourceUrl);
		}
		String issuer = String.valueOf(servers.get(0));
		if (!trustedIssuer.equals(issuer)) {
			throw new McpDiscoveryException(
					"자격증명은 %s 에 등록된 것인데 PRM 이 가리키는 인가 서버는 %s 다 — 메타데이터를 요청하지 않는다"
							.formatted(trustedIssuer, issuer));
		}

		return new DiscoveredAuthorization((String) protectedResource.get("resource"), issuer,
				authorizationServerMetadata(issuer));
	}
```

`authorizationServerMetadata` 의 S256 검사 뒤, `return metadata;` 앞에 더한다.

```java
			// MCP Security Best Practices: authorization URL 을 열기 전에 스킴을 확인한다(MUST).
			requireHttpUrl(metadata, "authorization_endpoint");
			requireHttpUrl(metadata, "token_endpoint");
```

```java
	private static void requireHttpUrl(Map<String, Object> metadata, String name) {
		Object value = metadata.get(name);
		String scheme = null;
		if (value instanceof String url) {
			try {
				scheme = URI.create(url).getScheme();
			}
			catch (IllegalArgumentException ex) {
				scheme = null;
			}
		}
		if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
			throw new McpDiscoveryException("인가 서버 메타데이터의 %s 가 http(s) URL 이 아니다: %s".formatted(name, value));
		}
	}
```

클래스 javadoc 의 순서 목록을 바꾼다.

```java
 *   <li>{@code authorization_servers} 가 자격증명이 등록된 issuer 인지 먼저 확인한다(아니면 더 요청하지 않는다)</li>
 *   <li>인가 서버 메타데이터를 RFC 8414 → OIDC 순서로 찾는다</li>
 *   <li>메타데이터의 {@code issuer} 가 같은지, PKCE {@code S256} 을 지원하는지,
 *       {@code authorization_endpoint}·{@code token_endpoint} 가 http(s) 인지 확인한다</li>
```

`DiscoveredClientRegistrationRepository`: `state()` 에서 `this.discovery.discover(this.properties.resourceUrl(), this.properties.credentialsIssuer())` 를 부르고, `registration()` 첫머리의 issuer 비교 블록을 지운다. 클래스 javadoc 마지막 문단을 바꾼다.

```java
 * <p>자격증명(client_id/secret)은 특정 인가 서버에 등록된 것이다. 발견이 다른 인가 서버를 가리키면
 * 그 메타데이터도 요청하지 않고 멈춘다({@link McpAuthorizationDiscovery#discover(String, String)}) —
 * 가짜 인가 서버로 비밀을 흘리지도, 공격자가 고른 주소로 요청을 보내지도 않기 위해서다.
```

- [ ] **Step 4: official 통과를 확인한다**

Run: `cd practice/mcp-security-authn-official/shop-agent && ./gradlew test`
Expected: PASS(기존 31 + 새 3 = 34, 지운 1 + 더한 1).

- [ ] **Step 5: chat-memory 에 옮긴다**

chat-memory `shop-agent` 의 두 클래스와 테스트는 official 과 들여쓰기(4칸)·패키지만 다르다. Step 1·3 을 그대로 적용한다(테스트 상수: RESOURCE `http://localhost:8131/mcp`, ISSUER `http://localhost:9020`, well-known URL 의 포트도 8131·9020).

Run: `cd practice/mcp-security-authn-chat-memory/shop-agent && ./gradlew test`
Expected: PASS.

- [ ] **Step 6: community 에 옮긴다**

community `McpAuthorizationDiscovery` 는 PRM 을 모듈 `McpMetadataDiscoveryService` 로 얻는다. `discover` 를 바꾼다.

```java
    public DiscoveredAuthorization discover(String resourceUrl, String trustedIssuer) {
        ProtectedResourceMetadata protectedResource;
        try {
            protectedResource = this.protectedResourceDiscovery.getMcpMetadata(resourceUrl).protectedResourceMetadata();
        }
        catch (IllegalStateException | RestClientException ex) {
            // 모듈은 resource 불일치·metadata 없음을 IllegalStateException 으로, 401 이 아닌 오류 응답을
            // RestClientException 으로 올린다.
            throw new McpDiscoveryException("보호 리소스 메타데이터를 얻지 못했다: " + ex.getMessage());
        }

        List<String> servers = protectedResource.authorizationServers();
        if (servers == null || servers.isEmpty()) {
            throw new McpDiscoveryException("보호 리소스 메타데이터에 authorization_servers 가 없다: " + resourceUrl);
        }
        String issuer = servers.get(0);
        if (!trustedIssuer.equals(issuer)) {
            throw new McpDiscoveryException(
                    "자격증명은 %s 에 등록된 것인데 PRM 이 가리키는 인가 서버는 %s 다 — 메타데이터를 요청하지 않는다"
                            .formatted(trustedIssuer, issuer));
        }

        return new DiscoveredAuthorization(protectedResource.resource(), issuer, authorizationServerMetadata(issuer));
    }
```

(`import org.springframework.web.client.RestClientException;`) `requireHttpUrl` 과 그 호출은 Step 3 과 같다. `DiscoveredClientRegistrationRepository` 와 두 repository·application 테스트, `TokenRefreshTest`(community 는 `McpSecurityConfig.buildAuthorizedClientManager(...)` 를 쓰고 요청 attribute 를 싣는다 — refresh client 를 만드는 세 줄만 Step 1 처럼 바꾼다)는 Step 1·3 과 같다.

community `McpAuthorizationDiscoveryTest` 에 Step 1 의 세 테스트(RESOURCE `http://localhost:8101/mcp`, ISSUER `http://localhost:9000`)와, official 에만 있던 세 경우를 더한다. `챌린지()` helper 는 헤더 값을 받는 overload 를 더한다.

```java
    void 챌린지(String wwwAuthenticate) {
        this.server.expect(requestTo(RESOURCE)).andExpect(method(HttpMethod.POST))
                .andRespond(withUnauthorizedRequest().header("WWW-Authenticate", wwwAuthenticate));
    }

    @Test
    void 메타데이터의_resource_가_요청한_URL_과_다르면_실패한다() {
        챌린지();
        응답("http://localhost:8101/.well-known/oauth-protected-resource/mcp", """
                {"resource":"http://localhost:8101/other","authorization_servers":["http://localhost:9000"]}""");

        assertThatExceptionOfType(McpDiscoveryException.class)
                .isThrownBy(() -> this.discovery.discover(RESOURCE, ISSUER));
    }

    @Test
    void 챌린지에_위치가_없고_경로형도_없으면_루트_well_known_으로_간다() {
        this.server.expect(requestTo(RESOURCE)).andExpect(method(HttpMethod.POST))
                .andRespond(withUnauthorizedRequest());
        없음("http://localhost:8101/.well-known/oauth-protected-resource/mcp");
        응답("http://localhost:8101/.well-known/oauth-protected-resource", """
                {"resource":"http://localhost:8101","authorization_servers":["http://localhost:9000"]}""");
        응답("http://localhost:9000/.well-known/oauth-authorization-server", AUTHORIZATION_SERVER_METADATA);

        DiscoveredAuthorization discovered = this.discovery.discover(RESOURCE, ISSUER);

        // 루트형 메타데이터의 리소스 식별자는 서버 루트다.
        assertThat(discovered.resource()).isEqualTo("http://localhost:8101");
        this.server.verify();
    }

    @Test
    void 토큰_없는_요청에_오류_응답이_오면_discovery_가_실패한다() {
        this.server.expect(requestTo(RESOURCE)).andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertThatExceptionOfType(McpDiscoveryException.class)
                .isThrownBy(() -> this.discovery.discover(RESOURCE, ISSUER));
    }
```

(`import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;`)

Run: `cd practice/mcp-security-authn-community/shop-agent && ./gradlew test`
Expected: 새 테스트를 먼저 넣으면 컴파일 실패 → 구현 뒤 PASS(기존 21 + 새 6 = 27).

- [ ] **Step 7: 커밋**

```bash
git add practice/mcp-security-authn-official/shop-agent practice/mcp-security-authn-chat-memory/shop-agent practice/mcp-security-authn-community/shop-agent
git commit -m "fix(agent): 신뢰한 issuer 가 아니면 AS metadata 를 요청하지 않고, endpoint 스킴을 확인한다

- discover(resourceUrl, trustedIssuer): issuer binding 을 metadata GET 앞으로(SSRF)
- authorization_endpoint·token_endpoint 는 http(s) 만(Security Best Practices MUST)
- TokenRefreshTest 가 실제 refresh client bean 메서드를 거친다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: MCP Server — Origin·Host 검증을 인증 앞으로, token 검증 테스트 보강, community audience 의존 테스트

**Files:**
- Create: `official·chat-memory/shop-mcp-server/src/main/java/<pkg>/McpTransportSecurityFilter.java`
- Modify: `official·chat-memory/shop-mcp-server/src/main/java/<pkg>/McpTransportConfig.java`
- Test: 세 practice `McpAuthorizationStandardTest.java`, `ShopMcpServerApplicationTests.java`

**Interfaces:**
- Produces: `McpAuthorizationStandardTest` 의 token helper 세 개 — Task 6 이 `토큰(ISSUER, RESOURCE, "alice")` 를 쓴다.
  - `static String 토큰(String issuer, String audience)` — subject `"user"`
  - `static String 토큰(String issuer, String audience, String subject)`
  - `static String 토큰(String issuer, String audience, String subject, Instant expiresAt, RSAKey key)`
  - `static RSAKey rsaKey()` — 새 RSA 키(kid `test-key`)

- [ ] **Step 1: token helper 를 나누고 실패하는 테스트를 쓴다(official)**

`McpAuthorizationStandardTest` 의 `static { ... }` 블록과 `토큰(...)` 을 바꾼다.

```java
	static final RSAKey KEY = rsaKey();

	static RSAKey rsaKey() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			var keyPair = generator.generateKeyPair();
			return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
					.privateKey((RSAPrivateKey) keyPair.getPrivate())
					.keyID(KEY_ID)
					.build();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	static String 토큰(String issuer, String audience) {
		return 토큰(issuer, audience, "user");
	}

	static String 토큰(String issuer, String audience, String subject) {
		return 토큰(issuer, audience, subject, Instant.now().plusSeconds(300), KEY);
	}

	static String 토큰(String issuer, String audience, String subject, Instant expiresAt, RSAKey key) {
		NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(issuer)
				.subject(subject)
				.audience(List.of(audience))
				.issuedAt(expiresAt.minusSeconds(600))
				.expiresAt(expiresAt)
				.build();
		return encoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID).build(), claims)).getTokenValue();
	}
```

`iss_가_다른_토큰은_거부한다` 에 `.andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.containsString("error=\"invalid_token\"")))` 를 더하고 테스트를 더한다.

```java
	@Test
	void 만료된_토큰은_거부한다() throws Exception {
		// Spring JwtTimestampValidator 의 기본 clock skew 는 60초다. 그보다 오래 지난 token 을 쓴다.
		this.mockMvc.perform(mcp(토큰(ISSUER, RESOURCE, "user", Instant.now().minusSeconds(120), KEY), null, HOST))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate",
						org.hamcrest.Matchers.containsString("error=\"invalid_token\"")));
	}

	@Test
	void 다른_키로_서명한_토큰은_거부한다() throws Exception {
		// kid 는 같지만 JWKS 의 공개 키로 서명이 맞지 않는다.
		this.mockMvc.perform(mcp(토큰(ISSUER, RESOURCE, "user", Instant.now().plusSeconds(300), rsaKey()), null, HOST))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate",
						org.hamcrest.Matchers.containsString("error=\"invalid_token\"")));
	}

	@Test
	void token_이_없어도_허용되지_않은_Origin_은_인증보다_먼저_403이다() throws Exception {
		// Origin 검증이 Spring Security 뒤에 있으면 token 없는 요청은 401 을 받고 Origin 검사에 닿지 않는다.
		this.mockMvc.perform(mcp(null, "http://evil.example", HOST))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist("WWW-Authenticate"));
	}

	@Test
	void token_이_없어도_허용되지_않은_Host_는_인증보다_먼저_421이다() throws Exception {
		this.mockMvc.perform(mcp(null, null, "evil.example:8111"))
				.andExpect(status().is(421))
				.andExpect(header().doesNotExist("WWW-Authenticate"));
	}
```

`ShopMcpServerApplicationTests#토큰_없이_MCP_엔드포인트를_호출하면_401이다` 요청에 `.header("Host", "localhost:8111")` 를 더하고 javadoc 을 바꾼다.

```java
	/**
	 * 401 과 함께 {@code WWW-Authenticate} 스킴이 {@code Bearer} 인지 본다. Boot 기본 보안(Basic)으로
	 * 막혀도 401 이므로, 스킴이 OAuth2 Resource Server 가 연결됐다는 증거다.
	 * {@code Host} 는 {@code McpTransportSecurityFilter} 가 인증보다 먼저 보므로 허용 값으로 싣는다.
	 */
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd practice/mcp-security-authn-official/shop-mcp-server && ./gradlew test`
Expected: `token_이_없어도_…_403이다`·`…_421이다` 가 FAIL(401). 나머지 PASS.

- [ ] **Step 3: `McpTransportSecurityFilter` 를 만든다(official)**

```java
package dev.starryeye.officialmcpserver;

import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MCP endpoint 의 {@code Origin}·{@code Host} 를 인증보다 먼저 검사한다(MCP 2025-11-25 Transports — Security Warning).
 *
 * <p>검사 규칙은 SDK {@link DefaultServerTransportSecurityValidator} 를 그대로 쓴다. SDK 는 이 검사를
 * transport 안에서 하므로 Spring Security 가 먼저 돌면 token 없는 요청은 401 을 받고 검사에 닿지 않는다.
 * 이 filter 를 security filter chain 앞에 두어 token 과 무관하게 잘못된 {@code Origin} 은 403,
 * {@code Host} 는 421 로 막는다. 응답 본문은 SDK transport 와 같은 평문 메시지다.
 */
public class McpTransportSecurityFilter extends OncePerRequestFilter {

	private final ServerTransportSecurityValidator validator;

	public McpTransportSecurityFilter(ServerTransportSecurityValidator validator) {
		this.validator = validator;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		try {
			this.validator.validateHeaders(headers(request));
		}
		catch (ServerTransportSecurityException ex) {
			response.setStatus(ex.getStatusCode());
			response.setContentType("text/plain;charset=UTF-8");
			response.getWriter().write(ex.getMessage() == null ? "" : ex.getMessage());
			return;
		}
		chain.doFilter(request, response);
	}

	/** SDK WebMvc transport 와 같은 모양: 소문자 헤더 이름 → 값 목록. */
	private static Map<String, List<String>> headers(HttpServletRequest request) {
		Map<String, List<String>> headers = new HashMap<>();
		for (String name : Collections.list(request.getHeaderNames())) {
			headers.put(name.toLowerCase(Locale.ROOT), Collections.list(request.getHeaders(name)));
		}
		return headers;
	}
}
```

- [ ] **Step 4: `McpTransportConfig` 를 바꾼다(official)**

`webMvcStreamableServerTransportProvider` bean 메서드를 지운다(자동 구성 bean 이 같은 설정으로 대신 만든다 — `McpServerStreamableHttpWebMvcAutoConfiguration`, validator 만 없다). 그 import(`JacksonMcpJsonMapper`, `WebMvcStreamableServerTransportProvider`)도 지우고 아래 bean 을 더한다. 클래스 javadoc 을 바꾼다.

```java
/**
 * MCP endpoint 에만 거는 servlet filter 두 개를 등록한다.
 *
 * <ul>
 *   <li>{@link McpTransportSecurityFilter} — {@code Origin}·{@code Host} 검증. Spring Security 보다 먼저 돈다.</li>
 *   <li>{@link McpProtocolVersionFilter} — {@code MCP-Protocol-Version} 검증. 인증 뒤에 돈다.</li>
 * </ul>
 *
 * <p>Streamable HTTP transport 는 Spring AI 자동 구성 bean 을 그대로 쓴다.
 */
@Configuration
public class McpTransportConfig {

	@Bean
	public FilterRegistrationBean<McpTransportSecurityFilter> mcpTransportSecurityFilter(
			McpServerStreamableHttpProperties properties, @Value("${server.port}") int port) {
		DefaultServerTransportSecurityValidator validator = DefaultServerTransportSecurityValidator.builder()
				// browser 에서 직접 부를 일이 없으므로 허용 Origin 을 두지 않는다. Origin 이 실려 오면 403 이다.
				.allowedHosts(List.of("localhost:" + port, "127.0.0.1:" + port))
				.build();
		FilterRegistrationBean<McpTransportSecurityFilter> registration =
				new FilterRegistrationBean<>(new McpTransportSecurityFilter(validator));
		registration.addUrlPatterns(properties.getMcpEndpoint());
		// Spring Security filter chain 보다 먼저 돈다 — 인증 전에 막는다.
		registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1);
		return registration;
	}
```

(`import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;` — `javap -cp <spring-boot-security-4.1.0.jar> org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties` 로 `DEFAULT_FILTER_ORDER` 가 있는지 확인한다. `mcpProtocolVersionFilter` bean 은 그대로 둔다.)

- [ ] **Step 5: official 통과를 확인한다**

Run: `cd practice/mcp-security-authn-official/shop-mcp-server && ./gradlew test`
Expected: PASS(기존 22 + 새 4 = 26). 기존 `허용되지_않은_Origin_은_403이다`·`허용되지_않은_Host_는_421이다`·`Origin_없는_서버간_요청은_통과한다` 도 PASS.

- [ ] **Step 6: chat-memory 에 옮긴다**

chat-memory `shop-mcp-server` 의 `McpTransportConfig`·테스트는 official 과 패키지만 다르다(`HOST = "localhost:8131"`, Host 포트 8131). Step 1·3·4 를 그대로 적용한다.

Run: `cd practice/mcp-security-authn-chat-memory/shop-mcp-server && ./gradlew test`
Expected: PASS(26).

- [ ] **Step 7: community 테스트를 보강한다(D)**

community 는 모듈 `OriginValidationFilter` 가 이미 인증 앞에 있다. Step 1 의 token helper·만료·다른 키·`iss` 헤더 테스트와 `token_이_없어도_…_403이다`·`…_421이다`(Host 포트 8101)를 넣고, audience 의존 관계를 고정하는 테스트를 더한다.

```java
    @Test
    void Host_를_바꾸고_그_Host_용_token_을_실어도_audience_계산_전에_421이다() throws Exception {
        // 모듈 AudienceValidationJwtDecoder 는 기대 aud 를 요청 URL 로 계산한다. Host 검증이 없으면
        // evil Host 로 계산한 aud 와 token 의 aud 가 맞아 통과한다 — OriginValidationFilter 가 먼저 막는다.
        this.mockMvc.perform(mcp(토큰(ISSUER, "http://evil.example:8101/mcp"), null, "evil.example:8101"))
                .andExpect(status().is(421));
    }
```

`ShopMcpServerApplicationTests#토큰_없이_MCP_엔드포인트를_호출하면_401이다` javadoc 을 바꾼다(모듈 이름으로).

```java
    /**
     * 401 과 함께 {@code WWW-Authenticate} 스킴이 {@code Bearer} 인지 본다. Boot 기본 보안(Basic)으로
     * 막혀도 401 이므로, 스킴이 모듈의 OAuth2 Resource Server 설정이 연결됐다는 증거다.
     * {@code Host} 는 모듈 {@code OriginValidationFilter} 가 인증보다 먼저 보므로 허용 값으로 싣는다.
     */
```

Run: `cd practice/mcp-security-authn-community/shop-mcp-server && ./gradlew test`
Expected: PASS(기존 22 + 새 5 = 27). 새 테스트는 현재 동작을 고정한다(구현 변경 없음).

- [ ] **Step 8: 커밋**

```bash
git add practice/mcp-security-authn-official/shop-mcp-server practice/mcp-security-authn-chat-memory/shop-mcp-server practice/mcp-security-authn-community/shop-mcp-server
git commit -m "fix(mcp-server): Origin·Host 검증을 Spring Security 앞으로, token 검증 테스트 보강

- official·chat-memory: McpTransportSecurityFilter 가 인증 전에 403/421
- 세 practice: 만료·다른 키·iss 불일치가 invalid_token
- community: Host 조작 + 그 Host 용 aud token 도 421(audience 계산 전)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: agent CSRF — `csrf.spa()`

**Files:**
- Modify: 세 practice `shop-agent/src/main/java/<pkg>/SecurityConfig.java`, `shop-agent/src/main/resources/static/index.html`
- Create: official·community `shop-agent/src/test/java/<pkg>/ChatCsrfTest.java`
- Modify: chat-memory `shop-agent/src/test/java/dev/starryeye/memoryauthn/agent/ConversationControllerTest.java`

**Interfaces:**
- Consumes: Task 3 의 `discover(RESOURCE, ISSUER)`.

- [ ] **Step 1: 실패하는 테스트를 쓴다(official)**

```java
package dev.starryeye.officialagent;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/chat} 도 CSRF 를 검사한다. {@code csrf.spa()} 가 준 {@code XSRF-TOKEN} 쿠키 값을
 * {@code X-XSRF-TOKEN} 헤더로 되돌려 보내는 index.html 의 흐름을 그대로 밟는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatCsrfTest {

	/** Boot 4.1 의 {@code @AutoConfigureMockMvc} 는 springSecurity() 를 붙이지 않는다 — {@code @WithMockUser} 에 필요하다. */
	@TestConfiguration
	static class SecurityMockMvcSupport {

		@Bean
		MockMvcBuilderCustomizer securityMockMvcBuilderCustomizer() {
			return builder -> builder.apply(SecurityMockMvcConfigurers.springSecurity());
		}
	}

	@MockitoBean
	McpAuthorizationDiscovery discovery;

	@MockitoBean
	ChatModel chatModel;

	@MockitoBean
	ToolCallbackProvider toolCallbackProvider;

	@Autowired
	MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER))
				.willReturn(DiscoveryFixtures.discovered());
		given(this.toolCallbackProvider.getToolCallbacks()).willReturn(new ToolCallback[0]);
		given(this.chatModel.stream(any(Prompt.class))).willReturn(
				Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("재고는 3개입니다"))))));
	}

	@Test
	@WithMockUser
	void CSRF_토큰_없이_채팅하면_403() throws Exception {
		this.mockMvc.perform(post("/api/chat").content("노트북 재고 있어?"))
				.andExpect(status().isForbidden());
	}

	@Test
	@WithMockUser
	void 페이지가_준_XSRF_TOKEN_을_헤더로_보내면_채팅이_시작된다() throws Exception {
		Cookie token = this.mockMvc.perform(get("/index.html"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getCookie("XSRF-TOKEN");
		assertThat(token).isNotNull();

		this.mockMvc.perform(post("/api/chat").cookie(token).header("X-XSRF-TOKEN", token.getValue())
						.content("노트북 재고 있어?"))
				.andExpect(request().asyncStarted());
	}
}
```

(ChatClient 가 `chatModel.getDefaultOptions()` 를 불러 NPE 가 나면 `given(this.chatModel.getDefaultOptions()).willReturn(ChatOptions.builder().build());` 를 `setUp` 에 더한다.)

- [ ] **Step 2: 실패를 확인한다**

Run: `cd practice/mcp-security-authn-official/shop-agent && ./gradlew test --tests '*ChatCsrfTest'`
Expected: `CSRF_토큰_없이_채팅하면_403` FAIL(`/api/chat` 이 CSRF 예외라 통과), `…채팅이_시작된다` FAIL(쿠키 없음 — 기본 repository 는 session 저장).

- [ ] **Step 3: `csrf.spa()` 로 바꾼다(official)**

`SecurityConfig` 의 `.csrf(csrf -> csrf.ignoringRequestMatchers("/api/chat"))` 와 그 위 주석 6줄을 바꾼다.

```java
                // /api/chat 도 CSRF 를 검사한다. csrf.spa() 는 JS 가 읽을 수 있는 XSRF-TOKEN 쿠키를
                // 응답에 싣고, index.html 이 그 값을 X-XSRF-TOKEN 헤더로 되돌려 보낸다. 다른 사이트의
                // 페이지는 이 쿠키를 읽지 못하므로 사용자 몰래 채팅(=MCP tool 호출)을 보낼 수 없다.
                .csrf(csrf -> csrf.spa())
```

`index.html` 의 `async function ask()` 위에 더하고 fetch 를 바꾼다.

```javascript
    // csrf.spa() 가 심어 둔 XSRF-TOKEN 쿠키를 읽어 X-XSRF-TOKEN 헤더로 되돌려 보낸다.
    function csrfHeaders() {
        const match = document.cookie.match('(?:^|;\\s*)XSRF-TOKEN=([^;]*)');
        return match ? {'X-XSRF-TOKEN': decodeURIComponent(match[1])} : {};
    }
```

```javascript
            const res = await fetch('/api/chat', {method: 'POST', headers: csrfHeaders(), body: message});
```

- [ ] **Step 4: official 통과를 확인한다**

Run: `cd practice/mcp-security-authn-official/shop-agent && ./gradlew test`
Expected: PASS.

- [ ] **Step 5: community 에 옮긴다**

Step 1·3 을 community(패키지 `dev.starryeye.shopagent`, 4칸)에 적용한다. community `ChatController` 의 `.contextWrite(...)` 는 그대로 둔다.

Run: `cd practice/mcp-security-authn-community/shop-agent && ./gradlew test`
Expected: 테스트 먼저 FAIL 2 → 구현 뒤 PASS.

- [ ] **Step 6: chat-memory 를 `csrf.spa()` 로 통일한다**

`SecurityConfig` 에서 `.csrf(csrf -> csrf.csrfTokenRepository(...).csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))` 와 `.addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)` 를 `.csrf(csrf -> csrf.spa())` 한 줄로 바꾸고, 내부 클래스 `SpaCsrfTokenRequestHandler`·`CsrfCookieFilter` 와 쓰지 않게 된 import 를 지운다. 메서드 javadoc 을 바꾼다.

```java
    /**
     * {@code /api/chat} 과 {@code DELETE /api/conversations/{label}} 모두 CSRF 를 검사한다 — 예외가 없다.
     * {@code csrf.spa()} 는 JS 가 읽을 수 있는 {@code XSRF-TOKEN} 쿠키를 응답에 싣고, index.html 이 그 값을
     * {@code X-XSRF-TOKEN} 헤더로 되돌려 보낸다.
     */
```

`index.html` 의 `csrfToken()` 위 주석을 바꾼다.

```javascript
    // csrf.spa() 가 심어 둔 XSRF-TOKEN 쿠키를 읽어 요청 헤더로 되돌려 보낸다.
    // 값이 없으면 헤더를 생략한다 — 서버가 403 으로 알려준다.
```

`ConversationControllerTest` 에 테스트 두 개를 더한다(`import jakarta.servlet.http.Cookie;`, `post`, `org.assertj.core.api.Assertions`).

```java
	@Test
	@WithMockUser(username = "alice")
	void CSRF_토큰_없이_채팅하면_403() throws Exception {
		mockMvc.perform(post("/api/chat").param("label", "default").content("안녕"))
				.andExpect(status().isForbidden());
	}

	/** index.html 의 흐름: 페이지가 준 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 보낸다. */
	@Test
	@WithMockUser(username = "alice")
	void 페이지가_준_XSRF_TOKEN_을_헤더로_보내면_지울_수_있다() throws Exception {
		Cookie token = mockMvc.perform(get("/index.html"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getCookie("XSRF-TOKEN");
		org.assertj.core.api.Assertions.assertThat(token).isNotNull();

		mockMvc.perform(delete("/api/conversations/default").cookie(token).header("X-XSRF-TOKEN", token.getValue()))
				.andExpect(status().isNoContent());
	}
```

Run: `cd practice/mcp-security-authn-chat-memory/shop-agent && ./gradlew test`
Expected: PASS(기존 테스트 포함 — `.with(csrf())` 를 쓰는 테스트도 통과한다).

- [ ] **Step 7: 커밋**

```bash
git add practice/mcp-security-authn-official/shop-agent practice/mcp-security-authn-community/shop-agent practice/mcp-security-authn-chat-memory/shop-agent
git commit -m "fix(agent): /api/chat 도 CSRF 검사 — 세 practice 를 csrf.spa() 로 통일

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: chat-memory — MCP session 을 사용자에 묶기

MCP Server 는 session 을 token 의 사용자(`sub`)에 묶고, agent 는 사용자마다 MCP client 를 따로 둔다. client 는 만들 때 받은 주인의 token 만 싣는다 — SDK `McpSyncClient#closeGracefully` 는 transport context 를 넘기지 않아, SecurityContext 에서 token 을 찾는 방식으로는 session 을 끝내는 `DELETE` 에 token 이 붙지 않기 때문이다.

**Files:**
- Create: `practice/mcp-security-authn-chat-memory/shop-mcp-server/src/main/java/dev/starryeye/memoryauthn/mcpserver/McpSessionBindingFilter.java`
- Modify: `.../mcpserver/SecurityConfig.java`
- Test: `.../mcpserver/McpAuthorizationStandardTest.java`
- Create: `practice/mcp-security-authn-chat-memory/shop-agent/src/main/java/dev/starryeye/memoryauthn/agent/UserMcpClients.java`
- Modify: `.../agent/OAuth2TokenAttachingRequestCustomizer.java`, `McpSecurityConfig.java`, `SecurityConfig.java`, `ChatController.java`, `ChatClientConfig.java`, `ShopAgentApplication.java`, `ConversationId.java`·`ConversationController.java`(javadoc), `src/main/resources/application.yml`
- Delete: `.../agent/SecurityMcpTransportContextProvider.java`, `src/test/.../SecurityMcpTransportContextProviderTest.java`
- Test: Create `UserMcpClientsTest.java`, `ChatControllerTest.java`; Modify `OAuth2TokenAttachingRequestCustomizerTest.java`, `ShopAgentApplicationTests.java`

**Interfaces:**
- Consumes: Task 4 의 `토큰(String issuer, String audience, String subject)`.
- Produces:
  - `UserMcpClients(Function<Authentication, McpSyncClient> clientFactory)`
  - `ToolCallbackProvider UserMcpClients#toolsFor(Authentication user)`
  - `void UserMcpClients#close(String username)`
  - `void UserMcpClients#onSessionDestroyed(SessionDestroyedEvent event)`
  - `OAuth2TokenAttachingRequestCustomizer(OAuth2AuthorizedClientManager, String clientRegistrationId, Authentication owner)`

- [ ] **Step 1: 서버 — 실패하는 테스트를 쓴다**

chat-memory `McpAuthorizationStandardTest` 에 더한다(`import static org.assertj.core.api.Assertions.assertThat;`, `import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;`).

```java
	static final String INITIALIZED = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";

	static MockHttpServletRequestBuilder mcpSession(String token, String sessionId, String body) {
		return post("/mcp")
				.contentType(MediaType.APPLICATION_JSON)
				.header("Accept", "application/json, text/event-stream")
				.header("Host", HOST)
				.header("Authorization", "Bearer " + token)
				.header("Mcp-Session-Id", sessionId)
				.header("MCP-Protocol-Version", "2025-11-25")
				.content(body);
	}

	static MockHttpServletRequestBuilder mcpDelete(String token, String sessionId) {
		return delete("/mcp")
				.header("Host", HOST)
				.header("Authorization", "Bearer " + token)
				.header("Mcp-Session-Id", sessionId)
				.header("MCP-Protocol-Version", "2025-11-25");
	}

	String session_을_연다(String user) throws Exception {
		String sessionId = this.mockMvc.perform(mcp(토큰(ISSUER, RESOURCE, user), null, HOST))
				.andExpect(status().isOk())
				.andReturn().getResponse().getHeader("Mcp-Session-Id");
		assertThat(sessionId).isNotBlank();
		return sessionId;
	}

	/*
	 * MCP Security Best Practices — Session Hijacking: session ID 를 사용자에 묶는다(SHOULD).
	 * McpSessionBindingFilter 가 session 을 연 사용자(token 의 sub)와 요청한 사용자를 비교한다.
	 */

	@Test
	void 다른_사용자가_남의_MCP_session_ID_를_쓰면_403이다() throws Exception {
		String sessionId = session_을_연다("alice");

		this.mockMvc.perform(mcpSession(토큰(ISSUER, RESOURCE, "bob"), sessionId, INITIALIZED))
				.andExpect(status().isForbidden());
	}

	@Test
	void session_을_연_사용자는_그_session_을_계속_쓴다() throws Exception {
		String sessionId = session_을_연다("alice");

		this.mockMvc.perform(mcpSession(토큰(ISSUER, RESOURCE, "alice"), sessionId, INITIALIZED))
				.andExpect(status().isAccepted());
	}

	@Test
	void 한_사용자가_연_여러_session_은_모두_그_사용자에_묶인다() throws Exception {
		String first = session_을_연다("alice");
		String second = session_을_연다("alice");

		this.mockMvc.perform(mcpSession(토큰(ISSUER, RESOURCE, "alice"), first, INITIALIZED))
				.andExpect(status().isAccepted());
		this.mockMvc.perform(mcpSession(토큰(ISSUER, RESOURCE, "alice"), second, INITIALIZED))
				.andExpect(status().isAccepted());
		this.mockMvc.perform(mcpSession(토큰(ISSUER, RESOURCE, "bob"), second, INITIALIZED))
				.andExpect(status().isForbidden());
	}

	@Test
	void 다른_사용자는_남의_MCP_session_을_끝낼_수_없다() throws Exception {
		String sessionId = session_을_연다("alice");

		this.mockMvc.perform(mcpDelete(토큰(ISSUER, RESOURCE, "bob"), sessionId))
				.andExpect(status().isForbidden());
		this.mockMvc.perform(mcpSession(토큰(ISSUER, RESOURCE, "alice"), sessionId, INITIALIZED))
				.andExpect(status().isAccepted());
	}

	@Test
	void DELETE_로_끝난_session_은_묶음이_풀리고_transport_가_모르는_session_으로_404_를_준다() throws Exception {
		String sessionId = session_을_연다("alice");

		this.mockMvc.perform(mcpDelete(토큰(ISSUER, RESOURCE, "alice"), sessionId))
				.andExpect(status().isOk());
		this.mockMvc.perform(mcpSession(토큰(ISSUER, RESOURCE, "bob"), sessionId, INITIALIZED))
				.andExpect(status().isNotFound());
	}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd practice/mcp-security-authn-chat-memory/shop-mcp-server && ./gradlew test --tests '*McpAuthorizationStandardTest'`
Expected: `…403이다`·`…끝낼_수_없다`·`여러_session…` FAIL(bob 이 202/200 을 받음). 나머지 PASS.

- [ ] **Step 3: 서버 — `McpSessionBindingFilter` 를 만든다**

```java
package dev.starryeye.memoryauthn.mcpserver;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP session 을 그 session 을 연 사용자에 묶는다(MCP Security Best Practices — Session Hijacking, SHOULD).
 *
 * <p>session ID 는 인증을 대신하지 않는다 — 모든 요청은 먼저 token 으로 인증된다. 이 filter 는 그 뒤에
 * 돌며, 요청의 {@code Mcp-Session-Id} 가 다른 사용자(token 의 {@code sub})에게 발급된 것이면 403 으로 막는다.
 *
 * <p>묶는 시점은 transport 가 응답에 {@code Mcp-Session-Id} 헤더를 쓰는 순간이다(응답 wrapper). 본문이
 * 나가기 전에 묶이므로 client 가 다음 요청을 보낼 때는 이미 묶여 있다. {@code DELETE} 로 session 이
 * 끝나면 묶음을 지운다.
 */
public class McpSessionBindingFilter extends OncePerRequestFilter {

	static final String SESSION_ID_HEADER = "Mcp-Session-Id";

	private final Map<String, String> owners = new ConcurrentHashMap<>();

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null) {
			chain.doFilter(request, response);
			return;
		}
		String user = authentication.getName();
		String sessionId = request.getHeader(SESSION_ID_HEADER);
		if (sessionId != null) {
			String owner = this.owners.get(sessionId);
			if (owner != null && !owner.equals(user)) {
				response.sendError(HttpServletResponse.SC_FORBIDDEN, "MCP session belongs to another user");
				return;
			}
		}

		chain.doFilter(request, new BindingResponse(response, user));

		if ("DELETE".equals(request.getMethod()) && sessionId != null && response.getStatus() < 300) {
			this.owners.remove(sessionId, user);
		}
	}

	/** transport 가 {@code Mcp-Session-Id} 를 쓰는 순간 그 session 을 현재 사용자에 묶는다. */
	private final class BindingResponse extends HttpServletResponseWrapper {

		private final String user;

		BindingResponse(HttpServletResponse response, String user) {
			super(response);
			this.user = user;
		}

		@Override
		public void setHeader(String name, String value) {
			bind(name, value);
			super.setHeader(name, value);
		}

		@Override
		public void addHeader(String name, String value) {
			bind(name, value);
			super.addHeader(name, value);
		}

		private void bind(String name, String value) {
			if (SESSION_ID_HEADER.equalsIgnoreCase(name) && value != null) {
				owners.putIfAbsent(value, this.user);
			}
		}
	}
}
```

`SecurityConfig#securityFilterChain` 의 `.csrf(csrf -> csrf.disable())` 앞에 더한다(`import org.springframework.security.web.access.intercept.AuthorizationFilter;`). bean 으로 등록하지 않는다 — bean 이면 Boot 가 servlet filter 로도 한 번 더 건다.

```java
				// MCP session 을 token 의 사용자에 묶는다. 인증·인가를 마친 요청만 이 filter 에 닿는다.
				.addFilterAfter(new McpSessionBindingFilter(), AuthorizationFilter.class)
```

클래스 javadoc 목록에 `<li>MCP session 을 token 의 사용자에 묶기 — {@link McpSessionBindingFilter}</li>` 를 더하고 "세 가지"를 "네 가지"로 바꾼다.

Run: `cd practice/mcp-security-authn-chat-memory/shop-mcp-server && ./gradlew test`
Expected: PASS(Task 4 뒤 26 + 새 5 = 31).

- [ ] **Step 4: agent — 실패하는 테스트를 쓴다**

`UserMcpClientsTest`(새, 4칸):

```java
package dev.starryeye.memoryauthn.agent;

import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserMcpClientsTest {

    private final Map<String, List<McpSyncClient>> opened = new ConcurrentHashMap<>();

    private final UserMcpClients clients = new UserMcpClients(owner -> {
        McpSyncClient client = mock(McpSyncClient.class);
        this.opened.computeIfAbsent(owner.getName(), name -> new CopyOnWriteArrayList<>()).add(client);
        return client;
    });

    static Authentication user(String name) {
        return new UsernamePasswordAuthenticationToken(name, "n/a", List.of());
    }

    @Test
    void 사용자마다_다른_client_를_열고_initialize_한다() {
        this.clients.toolsFor(user("alice"));
        this.clients.toolsFor(user("bob"));

        assertThat(this.opened.get("alice")).hasSize(1);
        assertThat(this.opened.get("bob")).hasSize(1);
        verify(this.opened.get("alice").get(0)).initialize();
        verify(this.opened.get("bob").get(0)).initialize();
    }

    @Test
    void 같은_사용자는_client_를_다시_쓴다() {
        ToolCallbackProvider first = this.clients.toolsFor(user("alice"));
        ToolCallbackProvider second = this.clients.toolsFor(user("alice"));

        assertThat(second).isSameAs(first);
        assertThat(this.opened.get("alice")).hasSize(1);
    }

    @Test
    void 같은_사용자의_동시_첫_요청도_client_를_하나만_연다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<ToolCallbackProvider>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> this.clients.toolsFor(user("alice"))));
            }
            for (Future<ToolCallbackProvider> result : results) {
                result.get();
            }
        }
        finally {
            pool.shutdownNow();
        }

        assertThat(this.opened.get("alice")).hasSize(1);
    }

    @Test
    void 닫으면_session_을_끝내고_다음_요청은_새_client_를_연다() {
        this.clients.toolsFor(user("alice"));

        this.clients.close("alice");
        this.clients.toolsFor(user("alice"));

        verify(this.opened.get("alice").get(0)).closeGracefully();
        assertThat(this.opened.get("alice")).hasSize(2);
    }

    @Test
    void initialize_가_실패하면_client_를_닫고_다음_요청에서_다시_연다() {
        List<McpSyncClient> created = new CopyOnWriteArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        UserMcpClients flaky = new UserMcpClients(owner -> {
            McpSyncClient client = mock(McpSyncClient.class);
            if (attempts.getAndIncrement() == 0) {
                when(client.initialize()).thenThrow(new IllegalStateException("MCP Server 가 아직 뜨지 않았다"));
            }
            created.add(client);
            return client;
        });

        assertThatThrownBy(() -> flaky.toolsFor(user("alice"))).isInstanceOf(IllegalStateException.class);
        flaky.toolsFor(user("alice"));

        verify(created.get(0)).closeGracefully();
        assertThat(created).hasSize(2);
    }

    @Test
    void HTTP_session_이_끝나면_그_session_사용자의_client_를_닫는다() {
        this.clients.toolsFor(user("alice"));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(user("alice")));

        this.clients.onSessionDestroyed(new HttpSessionDestroyedEvent(session));

        verify(this.opened.get("alice").get(0)).closeGracefully();
    }

    @Test
    void 종료할_때_모든_client_를_닫는다() {
        this.clients.toolsFor(user("alice"));
        this.clients.toolsFor(user("bob"));

        this.clients.destroy();

        verify(this.opened.get("alice").get(0)).closeGracefully();
        verify(this.opened.get("bob").get(0)).closeGracefully();
    }
}
```

`OAuth2TokenAttachingRequestCustomizerTest` 를 새 생성자에 맞게 다시 쓴다(`authorizedClientWithToken`·`newBuilder` helper 는 그대로 둔다. `SecurityMcpTransportContextProvider`·`Map` import 는 지운다. `ArgumentCaptor`, `verify` 를 쓴다).

```java
    private final Authentication alice = new UsernamePasswordAuthenticationToken("alice", "n/a", List.of());

    @Test
    void 주인의_토큰을_Bearer_로_붙이고_registrationId_와_주인을_넘긴다() {
        OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
        when(manager.authorize(any(OAuth2AuthorizeRequest.class))).thenReturn(authorizedClientWithToken("abc123"));
        var customizer = new OAuth2TokenAttachingRequestCustomizer(manager, REGISTRATION_ID, this.alice);
        HttpRequest.Builder builder = newBuilder();

        customizer.customize(builder, "POST", URI.create("http://localhost:8131/mcp"), "{}", McpTransportContext.EMPTY);

        assertThat(builder.build().headers().firstValue(HttpHeaders.AUTHORIZATION)).contains("Bearer abc123");
        ArgumentCaptor<OAuth2AuthorizeRequest> request = ArgumentCaptor.forClass(OAuth2AuthorizeRequest.class);
        verify(manager).authorize(request.capture());
        assertThat(request.getValue().getClientRegistrationId()).isEqualTo(REGISTRATION_ID);
        assertThat(request.getValue().getPrincipal()).isSameAs(this.alice);
    }

    @Test
    void transport_context_없이_나가는_session_종료_DELETE_에도_토큰을_붙인다() {
        // McpSyncClient#closeGracefully 는 transport context 를 넘기지 않는다.
        OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
        when(manager.authorize(any(OAuth2AuthorizeRequest.class))).thenReturn(authorizedClientWithToken("abc123"));
        var customizer = new OAuth2TokenAttachingRequestCustomizer(manager, REGISTRATION_ID, this.alice);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:8131/mcp")).DELETE();

        customizer.customize(builder, "DELETE", URI.create("http://localhost:8131/mcp"), null, McpTransportContext.EMPTY);

        assertThat(builder.build().headers().firstValue(HttpHeaders.AUTHORIZATION)).contains("Bearer abc123");
    }

    @Test
    void 인가된_클라이언트를_못_찾으면_헤더를_붙이지_않는다() {
        OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
        when(manager.authorize(any(OAuth2AuthorizeRequest.class))).thenReturn(null);
        var customizer = new OAuth2TokenAttachingRequestCustomizer(manager, REGISTRATION_ID, this.alice);
        HttpRequest.Builder builder = newBuilder();

        customizer.customize(builder, "POST", URI.create("http://localhost:8131/mcp"), "{}", McpTransportContext.EMPTY);

        assertThat(builder.build().headers().firstValue(HttpHeaders.AUTHORIZATION)).isEmpty();
    }
```

`ChatControllerTest`(새, 탭 — `ConversationControllerTest` 와 같은 모양):

```java
package dev.starryeye.memoryauthn.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 채팅 요청의 배선: 사용자의 conversationId 로 기억하고, 그 사용자의 MCP tool 을 넘기고, 로그아웃이 client 를 닫는다. */
@SpringBootTest
@AutoConfigureMockMvc
class ChatControllerTest {

	@TestConfiguration
	static class SecurityMockMvcSupport {

		@Bean
		MockMvcBuilderCustomizer securityMockMvcBuilderCustomizer() {
			return builder -> builder.apply(SecurityMockMvcConfigurers.springSecurity());
		}
	}

	@MockitoBean
	ChatModel chatModel;

	@MockitoBean
	UserMcpClients userMcpClients;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ChatMemory chatMemory;

	@BeforeEach
	void setUp() {
		this.chatMemory.clear("alice:default");
		ToolCallbackProvider noTools = () -> new ToolCallback[0];
		given(this.userMcpClients.toolsFor(any())).willReturn(noTools);
		given(this.chatModel.stream(any(Prompt.class))).willReturn(
				Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("재고는 3개입니다"))))));
	}

	String 채팅(String message) throws Exception {
		MvcResult started = this.mockMvc.perform(post("/api/chat").param("label", "default").content(message).with(csrf()))
				.andExpect(request().asyncStarted())
				.andReturn();
		return this.mockMvc.perform(asyncDispatch(started))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	@Test
	@WithMockUser(username = "alice")
	void 대화는_로그인한_사용자의_conversationId_로_기억된다() throws Exception {
		assertThat(채팅("노트북 재고 있어?")).isEqualTo("재고는 3개입니다");

		assertThat(this.chatMemory.get("alice:default")).extracting(Message::getText)
				.containsExactly("노트북 재고 있어?", "재고는 3개입니다");
	}

	@Test
	@WithMockUser(username = "alice")
	void 요청한_사용자의_MCP_tool_을_넘긴다() throws Exception {
		채팅("노트북 재고 있어?");

		then(this.userMcpClients).should().toolsFor(argThat(user -> "alice".equals(user.getName())));
	}

	@Test
	@WithMockUser(username = "alice")
	void 로그아웃하면_그_사용자의_MCP_client_를_닫는다() throws Exception {
		this.mockMvc.perform(post("/logout").with(csrf()))
				.andExpect(status().is3xxRedirection());

		then(this.userMcpClients).should().close("alice");
	}
}
```

(ChatClient 가 `chatModel.getDefaultOptions()` 를 불러 NPE 가 나면 Task 5 Step 1 의 주석대로 stub 한다.)

`ShopAgentApplicationTests`: `MCP_클라이언트에_인증_커스터마이저가_꽂힌다` 를 지우고(`McpClient` import 도) 이것으로 바꾼다. `서블릿_요청이_필요없는_인가_매니저를_쓴다` 의 javadoc 은 "요청 thread 밖(tool 호출, session 종료 `DELETE`)에서도 주인의 token 을 꺼낼 수 있어야 한다. 매니저 타입이 바뀌면 그 성질이 깨진다." 로 바꾼다.

```java
    /** MCP client 는 사용자마다 따로 만든다. 자동 구성의 공유 client 는 없어야 한다. */
    @Test
    void 공유_MCP_client_없이_사용자별_client_저장소를_쓴다() {
        assertThat(applicationContext.getBeansOfType(io.modelcontextprotocol.client.McpSyncClient.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(UserMcpClients.class)).hasSize(1);
    }
```

- [ ] **Step 5: 실패를 확인한다**

Run: `cd practice/mcp-security-authn-chat-memory/shop-agent && ./gradlew test`
Expected: 컴파일 실패(`UserMcpClients` 없음, 생성자 인자 수).

- [ ] **Step 6: agent 를 구현한다**

`UserMcpClients`(새, 4칸):

```java
package dev.starryeye.memoryauthn.agent;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.session.SessionDestroyedEvent;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * 로그인한 사용자마다 MCP client 하나를 둔다.
 *
 * <p>MCP Server 는 session 을 그 session 을 연 사용자에 묶는다({@code McpSessionBindingFilter}). client 하나를
 * 모든 사용자가 나눠 쓰면 한 session 에 여러 사용자의 token 이 실려 서버가 막는다. 그래서 principal 이름마다
 * client 를 따로 만들어 첫 채팅 때 {@code initialize} 하고, 로그아웃·HTTP session 종료·애플리케이션 종료 때
 * 닫는다({@code DELETE} 로 MCP session 종료).
 *
 * <p>client 는 만들 때 받은 {@link Authentication}(주인)의 token 만 싣는다({@link OAuth2TokenAttachingRequestCustomizer}).
 */
public class UserMcpClients implements DisposableBean {

    private final Function<Authentication, McpSyncClient> clientFactory;

    private final ConcurrentMap<String, UserClient> clients = new ConcurrentHashMap<>();

    public UserMcpClients(Function<Authentication, McpSyncClient> clientFactory) {
        this.clientFactory = clientFactory;
    }

    /** 그 사용자의 MCP tool. 처음 부르면 client 를 만들고 initialize 한다. 같은 사용자의 동시 호출도 하나만 연다. */
    public ToolCallbackProvider toolsFor(Authentication user) {
        return this.clients.computeIfAbsent(user.getName(), name -> open(user)).tools();
    }

    /** 그 사용자의 client 를 닫는다. 다음 {@link #toolsFor} 는 새 client 를 연다. */
    public void close(String username) {
        UserClient client = this.clients.remove(username);
        if (client != null) {
            client.client().closeGracefully();
        }
    }

    /** HTTP session 이 끝나면(만료·로그아웃) 그 session 의 사용자 client 를 닫는다. */
    @EventListener
    public void onSessionDestroyed(SessionDestroyedEvent event) {
        for (SecurityContext context : event.getSecurityContexts()) {
            Authentication authentication = context.getAuthentication();
            if (authentication != null) {
                close(authentication.getName());
            }
        }
    }

    @Override
    public void destroy() {
        List.copyOf(this.clients.keySet()).forEach(this::close);
    }

    private UserClient open(Authentication owner) {
        McpSyncClient client = this.clientFactory.apply(owner);
        try {
            client.initialize();
        }
        catch (RuntimeException ex) {
            client.closeGracefully();
            throw ex;
        }
        return new UserClient(client, SyncMcpToolCallbackProvider.builder().mcpClients(client).build());
    }

    private record UserClient(McpSyncClient client, ToolCallbackProvider tools) {
    }
}
```

`OAuth2TokenAttachingRequestCustomizer` 를 다시 쓴다.

```java
package dev.starryeye.memoryauthn.agent;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;

import java.net.URI;
import java.net.http.HttpRequest;

/**
 * 이 MCP client 의 주인(로그인한 사용자)의 access token 을 MCP 로 나가는 모든 HTTP 요청에 붙인다.
 *
 * <p>token 은 agent 의 것이 아니라 사용자의 것이다. {@code authorization_code} 로 발급되어 {@code sub} 가
 * 로그인한 사람이다.
 *
 * <p>주인은 client 를 만들 때 정해지고({@link UserMcpClients}) 요청의 SecurityContext 를 보지 않는다. 그래서
 * 요청 thread 밖에서 도는 tool 호출과, transport context 없이 나가는 session 종료 {@code DELETE} 에도 같은
 * 사용자의 token 이 실린다.
 *
 * <p>{@code authorize(...)} 가 던지는 예외(token endpoint 장애 등)는 잡지 않고 그대로 올린다 — token 없이
 * 요청을 보내 401 로 뭉개지 않기 위해서다.
 */
public class OAuth2TokenAttachingRequestCustomizer implements McpSyncHttpClientRequestCustomizer {

    private static final Logger log = LoggerFactory.getLogger(OAuth2TokenAttachingRequestCustomizer.class);

    private final OAuth2AuthorizedClientManager authorizedClientManager;

    private final String clientRegistrationId;

    private final Authentication owner;

    public OAuth2TokenAttachingRequestCustomizer(OAuth2AuthorizedClientManager authorizedClientManager,
                                                 String clientRegistrationId, Authentication owner) {
        this.authorizedClientManager = authorizedClientManager;
        this.clientRegistrationId = clientRegistrationId;
        this.owner = owner;
    }

    @Override
    public void customize(HttpRequest.Builder builder, String method, URI endpoint, String body,
                          McpTransportContext context) {
        OAuth2AuthorizedClient authorizedClient = this.authorizedClientManager.authorize(OAuth2AuthorizeRequest
                .withClientRegistrationId(this.clientRegistrationId)
                .principal(this.owner)
                .build());

        if (authorizedClient == null) {
            log.debug("{} 의 authorized client 가 없다 — token 을 붙이지 않는다 ({} {})",
                    this.owner.getName(), method, endpoint);
            return;
        }

        builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + authorizedClient.getAccessToken().getTokenValue());
    }
}
```

`McpSecurityConfig`: `mcpAuthenticationCustomizer`·`mcpTokenAttachingCustomizer` bean 과 `McpClientCustomizer` import 를 지우고 더한다(`io.modelcontextprotocol.client.McpClient`, `io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport`, `io.modelcontextprotocol.spec.McpSchema`, `org.springframework.security.web.session.HttpSessionEventPublisher`, `java.net.URI`, `java.time.Duration`). `authorizedClientService` bean javadoc 의 "리액터 스레드에서도 토큰을 붙인다" 는 "요청 밖(tool 호출, session 종료)에서도 토큰을 붙인다" 로 바꾼다.

```java
    private static final McpSchema.Implementation CLIENT_INFO = new McpSchema.Implementation("memory-shop-agent", "0.0.1");

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    /**
     * 사용자별 MCP client. 각 client 의 transport 는 주인의 token 만 싣는다.
     * 연결 주소는 발견의 출발점인 {@code mcp.authorization.resource-url} 이다.
     */
    @Bean
    public UserMcpClients userMcpClients(McpAuthorizationProperties properties,
            OAuth2AuthorizedClientManager authorizedClientManager) {
        URI resource = URI.create(properties.resourceUrl());
        String origin = resource.getScheme() + "://" + resource.getRawAuthority();
        return new UserMcpClients(owner -> McpClient.sync(HttpClientStreamableHttpTransport.builder(origin)
                        .endpoint(resource.getRawPath())
                        .httpRequestCustomizer(new OAuth2TokenAttachingRequestCustomizer(authorizedClientManager,
                                REGISTRATION_ID, owner))
                        .build())
                .clientInfo(CLIENT_INFO)
                .requestTimeout(REQUEST_TIMEOUT)
                .build());
    }

    /** HTTP session 종료를 {@code SessionDestroyedEvent} 로 알린다. {@link UserMcpClients} 가 이 event 로 client 를 닫는다. */
    @Bean
    public static HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }
```

`SecurityConfig#securityFilterChain` 에 `UserMcpClients userMcpClients` 인자를 더하고 `.oauth2Client(...)` 뒤에 더한다.

```java
                // 로그아웃하면 그 사용자의 MCP client 를 닫는다(DELETE 로 MCP session 종료).
                .logout(logout -> logout.addLogoutHandler((request, response, authentication) -> {
                    if (authentication != null) {
                        userMcpClients.close(authentication.getName());
                    }
                }))
```

`ChatController`: 생성자에 `UserMcpClients userMcpClients` 를 더하고 `.advisors(...)` 뒤에 `.toolCallbacks(this.userMcpClients.toolsFor(authentication))` 를 넣는다. 메서드 javadoc 을 바꾼다.

```java
    /**
     * {@code conversationId} 는 client 가 보내지 않고 {@link Authentication} 에서 만든다
     * ({@link ConversationId}). client 가 고를 수 있는 것은 label 뿐이다.
     *
     * <p>MCP tool 은 요청한 사용자의 MCP client 에서 가져온다({@link UserMcpClients}). 그 client 의
     * MCP session 과 token 은 이 사용자에게만 묶여 있다.
     */
```

`ChatClientConfig#shopChatClient` 에서 `ObjectProvider<ToolCallbackProvider>` 인자와 `ifAvailable` 줄을 지우고(import 도) javadoc 을 바꾼다: `MCP tool 은 기본값으로 두지 않는다. 요청마다 그 사용자의 MCP client 에서 받아 넘긴다({@link ChatController}).`

`ShopAgentApplication`: `Hooks.enableAutomaticContextPropagation()` 줄·주석과 `reactor.core.publisher.Hooks` import 를 지운다.

`SecurityMcpTransportContextProvider.java` 와 그 테스트를 지운다(`git rm`).

`application.yml` 의 `spring.ai.mcp.client` 블록 전체를 바꾼다.

```yaml
    mcp:
      client:
        # MCP client 자동 구성을 끈다. client 는 사용자마다 따로 만든다(UserMcpClients) —
        # 자동 구성의 client 하나를 모든 사용자가 나눠 쓰면 MCP Server 가 session 을 사용자에 묶을 수 없다.
        # 연결 주소는 mcp.authorization.resource-url 을 쓴다.
        enabled: false
```

`ConversationId`·`ConversationController`·`SecurityConfig` javadoc 의 "부모 practice" 는 뜻에 따라 "`chat-memory` practice"(MCP 없는 메모리 practice) 또는 "official·community practice" 로 바꾼다(`grep -rn "부모" practice/mcp-security-authn-chat-memory/*/src` 결과를 모두 확인한다. `UserConfig`·`ConversationControllerTest` 의 줄도 같은 기준).

- [ ] **Step 7: 통과를 확인한다**

Run: `cd practice/mcp-security-authn-chat-memory/shop-agent && ./gradlew test`
Expected: PASS. `grep -rn "SecurityMcpTransportContextProvider\|enableAutomaticContextPropagation\|McpClientCustomizer" practice/mcp-security-authn-chat-memory/shop-agent/src` 결과가 없다.

- [ ] **Step 8: 커밋**

```bash
git add -A practice/mcp-security-authn-chat-memory/shop-mcp-server practice/mcp-security-authn-chat-memory/shop-agent
git commit -m "feat(chat-memory): MCP session 을 사용자에 묶는다

- MCP Server: McpSessionBindingFilter — 남의 session ID 는 403, DELETE 로 묶음 해제
- agent: UserMcpClients — 사용자별 McpSyncClient, 로그아웃·session 종료 때 닫음
- token 은 client 의 주인에게 묶음(closeGracefully 의 DELETE 에도 token)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 스크립트·bind 주소·남은 주석

**Files:**
- Modify: `practice/mcp-security-authn-{official,chat-memory,community}/run.sh`·`stop.sh`
- Modify: 9개 `practice/mcp-security-authn-*/*/src/main/resources/application.yml`
- Modify: official `shop-agent/src/main/java/dev/starryeye/officialagent/ChatController.java`, `OAuth2TokenAttachingRequestCustomizer.java`, `shop-agent/src/main/resources/application.yml`

- [ ] **Step 1: lsof 를 수신 프로세스로 좁힌다**

세 practice 의 `stop.sh` 두 곳(`pids=$(lsof -ti tcp:"$port" 2>/dev/null || true)`)과 `run.sh` 한 곳(`if lsof -ti tcp:"$port" > /dev/null 2>&1; then`)에 `-sTCP:LISTEN` 을 더한다: `lsof -ti tcp:"$port" -sTCP:LISTEN`. `stop.sh` 에 연결만 한 프로세스(browser 등)를 종료하지 않는다는 주석 한 줄을 첫 사용처 위에 둔다: `# -sTCP:LISTEN: 그 포트에서 수신하는 프로세스만 고른다. 연결만 한 프로세스(browser, 다른 서버)는 건드리지 않는다.`

Run: `bash -n practice/mcp-security-authn-*/run.sh practice/mcp-security-authn-*/stop.sh && grep -n "lsof" practice/mcp-security-authn-*/run.sh practice/mcp-security-authn-*/stop.sh`
Expected: 문법 오류 없음, 모든 줄에 `-sTCP:LISTEN`.

- [ ] **Step 2: 9개 앱을 127.0.0.1 에만 bind 한다**

각 `application.yml` 의 `server:` 바로 아래, `port:` 위에 넣는다.

```yaml
server:
  # 서버가 연결을 받을 네트워크 주소. 127.0.0.1 이면 이 기기 안에서만 접속할 수 있다
  # (MCP 2025-11-25 Transports: 로컬에서 도는 서버는 localhost(127.0.0.1)에만 bind 하는 것이 좋다, SHOULD).
  # 실제 배포에서는 컨테이너·reverse proxy 뒤라면 0.0.0.0(모든 인터페이스)이나 그 네트워크의 인터페이스
  # 주소를 쓰고, 외부에 무엇을 열지는 proxy·방화벽이 정한다.
  address: 127.0.0.1
  port: 9010
```

(포트 값은 파일마다 그대로 둔다.)

- [ ] **Step 3: official 의 틀린 주석을 고친다**

`ChatController#chat` javadoc 을 바꾼다.

```java
    /**
     * MCP tool 호출은 요청 thread 밖(reactor)에서 돈다. {@code ShopAgentApplication} 이 켠
     * {@code Hooks.enableAutomaticContextPropagation()} 이 Spring Security 의 {@code ThreadLocalAccessor} 로
     * SecurityContext 를 그 thread 에 옮기고, {@link SecurityMcpTransportContextProvider} 가 거기서 사용자를 읽는다.
     * community practice 는 같은 일을 모듈의 {@code .contextWrite(...)} 로 한다.
     */
```

`OAuth2TokenAttachingRequestCustomizer` 클래스 javadoc 의 첫 문단과 마지막 문단을 바꾼다.

```java
 * MCP 로 나가는 모든 HTTP 요청에 로그인한 사용자의 액세스 토큰을 붙인다.
 * community practice 에서는 모듈의 {@code OAuth2AuthorizationCodeSyncHttpRequestCustomizer} 가 같은 일을 한다.
```

```java
 * <p>{@code authorizedClientManager.authorize(...)} 가 던지는 예외(token endpoint 장애 등)는 잡지 않고
 * 그대로 올린다. 인증이 없거나 authorized client 가 없을 때는 DEBUG 로그만 남기고 token 없이 보낸다 —
 * MCP Server 가 401 로 알려준다.
```

`shop-agent/src/main/resources/application.yml` 의 `# 직접 측정한 결과는 README §7.1 참고.` 줄을 지운다(바로 위 세 줄이 이유를 이미 말한다).

official `OAuth2TokenAttachingRequestCustomizerTest#인증이_있으면_토큰을_Bearer_로_헤더에_붙인다` 끝에 authorize 요청 내용을 확인하는 검증을 더한다(`org.mockito.ArgumentCaptor`, `verify`).

```java
        ArgumentCaptor<OAuth2AuthorizeRequest> request = ArgumentCaptor.forClass(OAuth2AuthorizeRequest.class);
        verify(manager).authorize(request.capture());
        assertThat(request.getValue().getClientRegistrationId()).isEqualTo(REGISTRATION_ID);
        assertThat(request.getValue().getPrincipal()).isSameAs(authentication);
```

- [ ] **Step 4: 전체 테스트**

Run(각 모듈 디렉터리에서): `./gradlew test` — 9개 모듈.
Expected: 전부 PASS. `server.address` 는 MockMvc 테스트에 영향이 없다.

- [ ] **Step 5: 커밋**

```bash
git add practice/mcp-security-authn-official practice/mcp-security-authn-chat-memory practice/mcp-security-authn-community
git commit -m "chore(practice): 127.0.0.1 bind, lsof 는 수신 프로세스만, 틀린 주석 정리

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: 캡처 — P8-1 과 수신 주소

**Files:**
- Modify: `docs/superpowers/captures/mcp-authorization-public-client.sh`
- Modify(다시 뜸): `docs/superpowers/captures/2026-09-25-{official,chat-memory,community}-public-client.txt`
- Create: `docs/superpowers/captures/2026-09-25-listen-addresses.txt`

**Interfaces:**
- Consumes: Task 2(`invalid_scope`), Task 7(`server.address`).
- Produces: 캡처 단계 ID `P8-1`, `S21`(새 파일). Task 9~12 가 인용한다.

- [ ] **Step 1: 스크립트에 P8-1 을 더한다**

`public_authorize` 아래에 helper 를 더한다.

```bash
# 공개 클라이언트의 인가 요청(PKCE 포함)에서 scope 만 바꾼다. $1 = scope 값
public_authorize_scope() {
  curl -si -c "$JAR" -b "$JAR" -G "$AS/oauth2/authorize" \
    --data-urlencode 'response_type=code' --data-urlencode "client_id=$PUBLIC_CLIENT_ID" \
    --data-urlencode "redirect_uri=$PUBLIC_REDIRECT_URI" --data-urlencode "scope=$1" \
    --data-urlencode 'state=public-state' --data-urlencode "code_challenge=$CHALLENGE" \
    --data-urlencode 'code_challenge_method=S256' --data-urlencode "resource=$MCP"
}
```

`P8` 단계 뒤, `P9` 앞에 더한다.

```bash
step "P8-1. 오류: openid 하나만 요청 — 공개 클라이언트는 invalid_scope (RFC 6749 §3.3 · OAuth 2.1 §7.3.1)"
# Spring 은 scope 가 openid 하나면 동의를 건너뛴다. PublicClientScopeValidator 가 그 전에 거부한다.
public_authorize_scope openid | tidy | grep -iE '^(HTTP|Location)'
```

- [ ] **Step 2: 세 practice 를 띄워 P 캡처를 다시 뜬다**

practice 마다 `auth-server` 와 `shop-mcp-server` 만 띄운다(agent·ollama 불필요).

```bash
export JAVA_HOME=$(find $HOME/.sdkman/candidates/java -maxdepth 1 -type d -name '21.*' | sort -V | tail -1)
cd practice/mcp-security-authn-official
mkdir -p logs
( cd auth-server && nohup ./gradlew bootRun -q > ../logs/auth-server.log 2>&1 < /dev/null & )
( cd shop-mcp-server && nohup ./gradlew bootRun -q > ../logs/shop-mcp-server.log 2>&1 < /dev/null & )
```

`curl -s -o /dev/null -w '%{http_code}' http://localhost:9010/.well-known/oauth-authorization-server` 가 200, `http://localhost:8111/.well-known/oauth-protected-resource/mcp` 가 200 이 될 때까지 폴링한다(최대 120초, Monitor 또는 짧은 반복). 그리고:

```bash
cd /Users/starryeye/study/spring-ai/docs/superpowers/captures
./mcp-authorization-public-client.sh > 2026-09-25-official-public-client.txt
```

chat-memory 는 `AS=http://localhost:9020 MCP_BASE=http://localhost:8131 CONFIDENTIAL_CLIENT_ID=memory-agent CONFIDENTIAL_CLIENT_SECRET=memory-agent-secret CONFIDENTIAL_REDIRECT_URI=http://localhost:8130/login/oauth2/code/authserver LOGIN_USERNAME=alice LOGIN_PASSWORD=alice`, community 는 `AS=http://localhost:9000 MCP_BASE=http://localhost:8101 CONFIDENTIAL_CLIENT_ID=shop-agent CONFIDENTIAL_CLIENT_SECRET=shop-agent-secret CONFIDENTIAL_REDIRECT_URI=http://localhost:8100/login/oauth2/code/authserver` 로 같은 스크립트를 돌린다(각 파일 첫 줄의 기존 환경값과 대조한다).

Expected: 세 파일의 `P8-1` 단계가 `HTTP/1.1 302` 와 `Location: http://127.0.0.1:8123/callback?error=invalid_scope&...&state=public-state&iss=...` 를 담는다. 나머지 단계는 이전 캡처와 같은 결과다(`git diff --stat` 으로 날짜·값 변화만인지 본다).

- [ ] **Step 3: 수신 주소를 캡처한다**

세 practice 의 9개 앱을 모두 띄운다(agent 는 `cd shop-agent && nohup ./gradlew bootRun -q > ../logs/shop-agent.log 2>&1 < /dev/null &`, 기동에 ollama 는 필요 없다). 모두 뜬 뒤:

```bash
{
  printf '# 수신 주소 — %s (lsof -nP -iTCP -sTCP:LISTEN)\n' "$(date +%F)"
  printf '\n\n===== S21. 세 practice 9개 앱의 수신 주소 (lsof) =====\n'
  lsof -nP -iTCP -sTCP:LISTEN \
    | awk '$9 ~ /:(9000|9010|9020|8100|8101|8110|8111|8130|8131)$/ { print $1, $8, $9, $10 }' | sort -k3
} > docs/superpowers/captures/2026-09-25-listen-addresses.txt
```

Expected: 9줄 모두 `127.0.0.1:<port>`(`*:<port>` 없음).

- [ ] **Step 4: 서버를 내린다**

```bash
for port in 9000 9010 9020 8100 8101 8110 8111 8130 8131; do
  for p in $(lsof -ti tcp:$port -sTCP:LISTEN); do kill $p; done
done
```

Run: `lsof -nP -iTCP -sTCP:LISTEN | grep -E ':(9000|9010|9020|8100|8101|8110|8111|8130|8131) '`
Expected: 출력 없음.

- [ ] **Step 5: 커밋**

```bash
git add docs/superpowers/captures
git commit -m "docs(captures): P8-1 openid 단독 invalid_scope, 9개 앱 수신 주소

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: 허브 `practice/MCP-AUTHORIZATION.md`

**Files:**
- Modify: `practice/MCP-AUTHORIZATION.md`

**Interfaces:**
- Consumes: Task 2~8 의 클래스·테스트 이름, 캡처 `P8-1`·`S21`(`2026-09-25-listen-addresses.txt`).
- Produces: 준수표 28~33행과 새 절 앵커. Task 10~12 가 링크한다 — 새 앵커를 만들면 `<a id="...">` 로 명시한다.

**REQUIRED SUB-SKILL:** 프로젝트 스킬 `writing-practice-docs` 를 불러 따른다.

- [ ] **Step 1: 준수표를 코드와 맞춘다(§6)**

각 행의 official·chat-memory·community 칸과 근거를 아래대로 바꾼다. 판정은 쓰기 전에 코드·테스트로 확인한다.

| 행 | 바꿀 내용 |
|---|---|
| 8 | community 칸에 "기대 `aud` 를 요청 URL 로 계산 — Host 검증(`OriginValidationFilter`)이 먼저 와야 한다, 테스트 `Host_를_바꾸고_그_Host_용_token_을_실어도_audience_계산_전에_421이다`" |
| 9 | "요청별 issuer 기록" 단서를 10번에서 옮겨 온다(2026-07-28 Authorization Response Validation) |
| 10 | 위 단서를 뺀다. 근거에 `McpAuthorizationDiscovery#discover(String, String)` 를 더한다 |
| 11 | official·chat-memory 근거를 `McpTransportSecurityFilter`(Spring Security 앞) + 테스트 `token_이_없어도_허용되지_않은_Origin_은_인증보다_먼저_403이다` 로. community 는 모듈 `OriginValidationFilter`(인증 앞) |
| 16 | "PRM 에 `scopes_supported` 가 없으면 scope 를 생략한다(SHOULD) — agent 는 OIDC 로그인 때문에 `openid profile` 을 보낸다" 한 줄 |
| 25 | 근거를 두 장치로: `PublicClientScopeValidator`(scope 없음·`openid` 하나 → `invalid_scope`, P8-1) + `PublicClientConsentService`(저장된 consent 무시, P8) |
| 27 | 근거에 "issuer binding 확인이 Authorization Server Metadata GET 앞 — `PRM_의_Authorization_Server_가_자격증명의_issuer_가_아니면_metadata_를_요청하지_않는다`" |

새 행 28~33(요구 수준은 원문 단어 그대로, 조항 링크를 단다):

| # | 항목 | 수준 | official | chat-memory | community |
|---|---|---|---|---|---|
| 28 | MCP session 을 사용자에 묶기 | SHOULD (Security Best Practices — Session Hijacking) | **아니오** — agent 가 MCP client 하나를 모든 사용자와 공유한다 | 예 — `McpSessionBindingFilter` + `UserMcpClients` | **아니오** — official 과 같은 이유, 모듈 `sessionBinding()` 은 켜지 않음 |
| 29 | session ID 가 인증을 대신하지 않음, 예측할 수 없는 값 | MUST | 예 — 모든 요청 token 검증, SDK 가 UUID 로 session ID 생성 | 예 — 같음 | 예 — 같음 |
| 30 | 로컬 서버는 127.0.0.1 에 bind | SHOULD (Transports) | 예 — `server.address: 127.0.0.1`, S21 | 예 — 같음 | 예 — 같음 |
| 31 | 지원하지 않는 `MCP-Protocol-Version` 에 `400` | MUST (Transports) | 예 — `McpProtocolVersionFilter`, C15 | 예 — 같음 | 예 — `McpProtocolVersionFilterConfig` |
| 32 | redirect URI 정확 일치 | MUST (Security Best Practices) | 예 — Spring `OAuth2AuthorizationCodeRequestAuthenticationValidator`, P10-1 | 예 — 같음 | 예 — 같음 |
| 33 | authorization URL 스킴 검증 | MUST (Security Best Practices) | 예 — `McpAuthorizationDiscovery#requireHttpUrl` | 예 — 같음 | 예 — 같음 |

28행 아래(또는 session 을 다루는 §5 절)에 한 문장을 더한다: MCP 2026-07-28 은 protocol-level session 과 `Mcp-Session-Id` 를 없앴고([SEP-2567](https://github.com/modelcontextprotocol/modelcontextprotocol/pull/2567), [2026-07-28 Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports)), 호출 사이 상태는 서버가 만든 핸들을 tool 인자로 넘기며 매 호출 `(핸들, 인증 정보)` 를 검증하는 설계 권고(비규범)로 옮겨 갔다. 이 practice 의 tool 은 상태가 없어 핸들이 필요 없다.

29행의 "UUID" 는 SDK 소스(`McpStreamableServerSession` 또는 session factory)에서 확인한 뒤 쓴다. 32행 캡처 ID 는 P 캡처에서 경로가 다른 루프백 redirect 거부 단계 번호를 확인해 쓴다. 각 행의 "절" 칸은 관련 절 앵커로 링크한다.

- [ ] **Step 2: 본문을 고친다**

- §4.4(public client)·§5.6(re-consent): consent 를 지키는 두 장치와 `invalid_scope` 경로를 "Spring 기본 동작은 X, 명세는 Y, 이 practice 는 Z" 모양으로 쓴다. 근거: OAuth 2.1 §7.3.1, RFC 6749 §3.3, 관측 P8·P8-1.
- §4.10 또는 SSRF 를 다루는 §5 절: discovery 순서에 "issuer binding 확인 → AS metadata GET", authorization URL 스킴 확인을 더한다.
- Origin·Host 를 다루는 절: 세 practice 모두 인증 앞에서 검사한다. community 는 기대 `aud` 계산이 요청 URL(Host)에 의존하므로 Host 검증이 먼저 와야 한다(결정 D).
- session 을 다루는 절(없으면 §5 에 `<a id="s5-8"></a>` 절을 새로 둔다): session ID 는 인증을 대신하지 않는다, chat-memory 는 session 을 사용자에 묶고 official·community 는 묶지 않는 이유(한 client 공유).
- 로컬 bind: Transports SHOULD, `server.address`, 관측 S21.
- 스펙 2.1 사실 오류: "supplement 에 원본 token 이 남아 있다" 문단 삭제, :464 부근 S13 은 "모르는 session"(끝난 session 은 S16), `PublicClientConsentService#findById` 는 public client 일 때만 `null`.
- 스펙 2.2: 포트·client_id 표는 §2 한 곳에만 둔다(다른 곳의 같은 표는 링크로). SDK 버전 설명은 §1 한 곳에만 둔다(나머지 반복 삭제).

- [ ] **Step 3: 검사한다**

Run: `python3 .claude/skills/writing-practice-docs/scripts/check_docs.py practice/MCP-AUTHORIZATION.md && npx -y @mermaid-js/mermaid-cli -i practice/MCP-AUTHORIZATION.md -o /tmp/render-hub.md`
Expected: 위반 0, 렌더 성공(mermaid 가 없으면 두 번째 명령은 건너뛴다).

- [ ] **Step 4: 커밋**

```bash
git add practice/MCP-AUTHORIZATION.md
git commit -m "docs: MCP 허브 — 준수표 정정·새 항목, consent·SSRF·Origin·session·bind 서술

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: `practice/MCP-API-SPEC.md`·`practice/MCP-SEQUENCES.md`

**Files:**
- Modify: `practice/MCP-API-SPEC.md`, `practice/MCP-SEQUENCES.md`

**REQUIRED SUB-SKILL:** 프로젝트 스킬 `writing-practice-docs`.

- [ ] **Step 1: API 명세를 고친다**

- 엔드포인트 절마다 근거 줄 옆에 허브 절·시퀀스 절 링크를 둔다(스킬의 이동 링크 규칙).
- `/oauth2/authorize` 오류 표: public client 의 `invalid_scope`(scope 없음·`openid` 하나, P8-1), `resource` 여러 개 → `invalid_target`.
- `/oauth2/token` 오류 표: 인가 요청에 없던 `resource` → `invalid_target`, `resource` 여러 개 → `invalid_target`(community 는 converter 가 먼저).
- `/mcp`: `Origin`·`Host` 오류(403·421)가 token 과 무관하게 인증 전에 나온다. chat-memory 의 남의 `Mcp-Session-Id` → `403`(`DELETE` 포함).
- 새 문장이 150자·표 칸 2문장 규칙을 지키게 쓴다.

- [ ] **Step 2: 시퀀스를 고친다**

- 각 절 끝에 practice 시퀀스 링크 한 줄: `구현: [official](mcp-security-authn-official/SEQUENCES.md#…) · [community](…) · [chat-memory](…)`. 앵커는 Task 11·12 에서 만들 절 이름에 맞춘다(Task 11·12 가 끝난 뒤 링크 검사로 확인한다 — 이 Task 에서는 절 이름을 `registration`·`mcp-server-validation` 과 기존 절 이름으로 둔다).
- 단계 설명의 MUST 재인용은 허브 앵커 링크로 바꾼다.
- discovery 시퀀스: issuer binding 확인이 AS metadata GET 앞, 스킴 확인.
- MCP 요청 시퀀스: `Origin`·`Host` 검사 → token 검증 → (chat-memory) session binding → `MCP-Protocol-Version`.

- [ ] **Step 3: 검사한다**

Run: `python3 .claude/skills/writing-practice-docs/scripts/check_docs.py practice/MCP-API-SPEC.md practice/MCP-SEQUENCES.md && npx -y @mermaid-js/mermaid-cli -i practice/MCP-SEQUENCES.md -o /tmp/render-seq.md`
Expected: practice 시퀀스 앵커 링크를 뺀 위반 0(그 링크는 Task 12 뒤에 0 이 된다), 렌더 성공.

- [ ] **Step 4: 커밋**

```bash
git add practice/MCP-API-SPEC.md practice/MCP-SEQUENCES.md
git commit -m "docs: MCP API·시퀀스 — 이동 링크, scope·resource·Origin·session 오류 경로

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: official practice 문서 3종

**Files:**
- Modify: `practice/mcp-security-authn-official/README.md`, `API-SPEC.md`, `SEQUENCES.md`

**REQUIRED SUB-SKILL:** 프로젝트 스킬 `writing-practice-docs`.

- [ ] **Step 1: README 를 official 단독으로 다시 쓴다**

- community 기준 서술("community 에서 배운 것", "보였지만" 류)을 걷어내고 official 을 기준 practice 로 단독 서술한다. 비교는 community README 의 대체 표 한 곳에만 둔다.
- :158-159 의 캡처 근거 없는 관측은 캡처 ID 를 달거나 코드 근거 문장으로 바꾸고, 근거가 없으면 지운다.
- "직접 쓴 코드" 표에 `PublicClientScopeValidator`, `McpTransportSecurityFilter` 를 더하고 `McpTransportConfig` 설명을 바꾼다(transport 는 자동 구성, filter 두 개 등록).
- 학습 포인트: consent 두 장치, Origin·Host 가 인증 앞, SSRF 순서·스킴 확인, 127.0.0.1 bind, `/api/chat` CSRF(`csrf.spa()`), session 을 묶지 않는 이유(한 client 공유 → 허브 28행 링크).
- 포트·client_id 표는 허브 §2 링크로 바꾼다.

- [ ] **Step 2: API-SPEC 을 고친다**

- 모듈별 엔드포인트 표의 오류 응답에 Task 10 과 같은 항목을 이 practice 의 클래스 이름으로 적는다.
- agent `/api/chat`: CSRF 헤더 `X-XSRF-TOKEN` 필수, 없으면 `403`.

- [ ] **Step 3: SEQUENCES 에 절을 더한다**

- `registration` 절(`<a id="registration"></a>`): yml pre-registration — confidential client(`official-shop-agent`)와 public client(`local-mcp-client`)가 `OAuth2AuthorizationServerPropertiesMapper` 로 `RegisteredClient` 가 되는 흐름, 검증기 체인(`OAuth2AuthorizationCodeRequestAuthenticationValidator` → `ResourceIndicatorValidator` → `PublicClientScopeValidator`).
- `mcp-server-validation` 절(`<a id="mcp-server-validation"></a>`): `McpTransportSecurityFilter` → `SecurityConfig`(Bearer token) → `JwtDecoder`(서명·`iss`·`aud`) → `McpProtocolVersionFilter` → transport.
- discovery 절: `discover(resourceUrl, trustedIssuer)` 순서.

- [ ] **Step 4: 검사한다**

Run: `python3 .claude/skills/writing-practice-docs/scripts/check_docs.py practice/mcp-security-authn-official/*.md practice/MCP-SEQUENCES.md && npx -y @mermaid-js/mermaid-cli -i practice/mcp-security-authn-official/SEQUENCES.md -o /tmp/render-official.md`
Expected: official 문서 위반 0, 렌더 성공.

- [ ] **Step 5: 커밋**

```bash
git add practice/mcp-security-authn-official
git commit -m "docs(official): README 단독 서술, 등록·MCP Server 검증 시퀀스, 리뷰 반영

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: chat-memory·community practice 문서 6종

**Files:**
- Modify: `practice/mcp-security-authn-chat-memory/README.md`, `API-SPEC.md`, `SEQUENCES.md`
- Modify: `practice/mcp-security-authn-community/README.md`, `API-SPEC.md`, `SEQUENCES.md`

**REQUIRED SUB-SKILL:** 프로젝트 스킬 `writing-practice-docs`.

- [ ] **Step 1: chat-memory 문서**

- README: "직접 쓴 코드" 표(`ConversationId`·`ConversationController`·`ChatMemoryConfig`·`UserMcpClients`·`McpSessionBindingFilter`), 대화 격리에 이어 MCP session 도 사용자별이라는 학습 포인트(token 은 client 의 주인에게 묶임 — `closeGracefully` 의 `DELETE` 에도 token), `SecurityMcpTransportContextProvider`·context propagation 이 없는 이유. "tool 결과와 memory" 중복 절 하나 삭제. 범위 밖 서술(테스트 배선·git 상태·설계 문서 링크) 삭제.
- API-SPEC: `/mcp` 의 남의 `Mcp-Session-Id` → `403`(POST·`DELETE`), 끝난 session → `404`. agent `POST /logout` 이 그 사용자의 MCP client 를 닫는다.
- SEQUENCES: 겹치는 절은 official 절 링크 한 줄. 다른 점만 그린다 — 첫 채팅의 사용자별 client 생성·`initialize`·session binding, 로그아웃·session 종료 때 `DELETE`. "경로 문자" 서술은 코드 규칙("영숫자·한글·`_`·`-` 외 문자")으로 바꾼다.

- [ ] **Step 2: community 문서**

- README :10 "모듈이 discovery 미지원" → 지원하지 않는 부분만(AS metadata discovery·PKCE 확인·`resource`·`iss`). 대체 표: official `ChatController` 는 `Hooks.enableAutomaticContextPropagation`, community 는 `.contextWrite(...)`, 제공 열은 "직접 얹은 확장". 모듈 customizer 가 ID token `aud` 를 `resource` 로 덮어쓰고 `ResourceAudienceTokenCustomizer` 가 client_id 로 되돌린다. 기대 `aud` 는 요청 URL 로 계산하므로 Host 검증이 먼저 와야 한다(결정 D, 테스트 이름). `SingleResourceTokenRequestConverter`·`PublicClientScopeValidator`(모듈 `McpNoScopeClientConsentNotRequired` 경로도 막음)를 직접 얹은 확장에 더한다.
- API-SPEC: `/oauth2/token` 의 `resource` 여러 개 → `invalid_target`(converter).
- SEQUENCES: 토큰 발급 다이어그램에 ID token 분기(모듈 → `aud=resource`, practice customizer → `aud=client_id`)를 더한다. :207 `iss` 비교 서술은 "metadata `issuer` 일치" 로 바꾼다. 등록·MCP Server 검증은 official 절 링크 + 다른 점(모듈 `OriginValidationFilter`, `AudienceValidationJwtDecoder`).

- [ ] **Step 3: 전체 검사**

Run: `python3 .claude/skills/writing-practice-docs/scripts/check_docs.py practice/MCP-AUTHORIZATION.md practice/MCP-API-SPEC.md practice/MCP-SEQUENCES.md practice/mcp-security-authn-*/README.md practice/mcp-security-authn-*/API-SPEC.md practice/mcp-security-authn-*/SEQUENCES.md`
Expected: 12개 문서 위반 0(Task 10 의 practice 시퀀스 링크 포함).

Run: `for f in practice/MCP-SEQUENCES.md practice/mcp-security-authn-*/SEQUENCES.md practice/MCP-AUTHORIZATION.md; do npx -y @mermaid-js/mermaid-cli -i $f -o /tmp/render-$(basename $(dirname $f))-$(basename $f) || echo "FAIL $f"; done`
Expected: FAIL 줄 없음.

- [ ] **Step 4: 커밋**

```bash
git add practice/mcp-security-authn-chat-memory practice/mcp-security-authn-community
git commit -m "docs(chat-memory, community): session 묶기·모듈 대체 표·ID token aud 분기

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## 최종 확인

- 9개 모듈 `./gradlew test` 전부 PASS.
- 12개 문서 검사 위반 0, mermaid 렌더 성공.
- 준수표 1~33행을 코드·테스트·캡처와 한 행씩 대조한다(최종 리뷰).
- `git status` 에 `.superpowers/`·`logs/` 가 스테이징되지 않았다.
