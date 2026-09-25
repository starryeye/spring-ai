"""check_docs.py 의 규칙마다 걸리는 예와 통과하는 예를 하나씩 둔다.

실행: python3 -m unittest discover -s .claude/skills/writing-practice-docs/scripts -p 'test_*.py'
"""
import json
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_docs  # noqa: E402

TERMS = check_docs.load_terms()


def rules(text: str, name: str = "doc.md", extra: dict | None = None) -> list[str]:
    """임시 디렉터리에 문서를 쓰고 검사해 `규칙:줄` 목록을 돌려준다."""
    with tempfile.TemporaryDirectory() as d:
        for other, body in (extra or {}).items():
            (Path(d) / other).write_text(textwrap.dedent(body), encoding="utf-8")
        path = Path(d) / name
        path.write_text(textwrap.dedent(text), encoding="utf-8")
        return [f"{i.rule}:{i.line}" for i in check_docs.check_file(path, TERMS)]


def mermaid_rules(text: str) -> list[str]:
    """mermaid 문법 규칙만 본다(그림 링크 규칙은 DiagramTest 가 본다)."""
    return [r for r in rules(text) if r.startswith("mermaid")]


class SetextTest(unittest.TestCase):
    def test_글_바로_아래_구분선은_걸린다(self):
        self.assertIn("setext:2", rules("문단이다.\n---\n"))

    def test_빈_줄_뒤_구분선은_통과한다(self):
        self.assertEqual([], rules("문단이다.\n\n---\n"))

    def test_front_matter_는_구분선으로_보지_않는다(self):
        self.assertEqual([], rules("---\nname: x\n---\n\n본문이다.\n"))


class WordsTest(unittest.TestCase):
    def test_개발_과정_표현은_걸린다(self):
        self.assertIn("narrative:1", rules("처음엔 동작하지 않았다.\n"))

    def test_번역된_용어는_걸린다(self):
        found = rules("인가 서버가 토큰을 준다.\n")
        self.assertEqual(2, found.count("term:1"))

    def test_inline_code_와_코드_블록의_한국어는_통과한다(self):
        self.assertEqual([], rules("`토큰` 값을 본다.\n\n```json\n{\"text\": \"토큰\"}\n```\n"))

    def test_인가_는_단어일_때만_걸린다(self):
        self.assertEqual([], rules("이것은 무엇인가?\n"))
        self.assertIn("term:1", rules("인가 흐름을 본다.\n"))


class LinkTest(unittest.TestCase):
    def test_명시_앵커와_제목_앵커는_통과한다(self):
        doc = '<a id="e1"></a>\n\n## 4.1 Token 요청\n\n[e](#e1) [h](#41-token-요청)\n'
        self.assertEqual([], [r for r in rules(doc) if r.startswith("link")])

    def test_없는_앵커는_걸린다(self):
        self.assertIn("link:1", rules("[x](#nowhere)\n"))

    def test_다른_파일의_앵커를_확인한다(self):
        extra = {"other.md": '<a id="jwks"></a>\n'}
        self.assertEqual([], rules("[j](other.md#jwks)\n", extra=extra))
        self.assertIn("link:1", rules("[j](other.md#nope)\n", extra=extra))
        self.assertIn("link:1", rules("[j](missing.md)\n"))

    def test_외부_링크는_확인하지_않는다(self):
        self.assertEqual([], rules("[r](https://www.rfc-editor.org/rfc/rfc8707#section-2)\n"))


class MermaidTest(unittest.TestCase):
    OK = """\
        ```mermaid
        sequenceDiagram
            autonumber
            participant C as Client
            participant A as Authorization Server
            C->>A: GET /oauth2/authorize
            alt 올바름
                A-->>C: 302 code
            else 틀림
                A-->>C: 400
            end
            Note over C,A: 끝
        ```
        """

    def test_올바른_시퀀스는_통과한다(self):
        self.assertEqual([], mermaid_rules(self.OK))

    def test_선언_안_된_participant_는_걸린다(self):
        self.assertTrue(any(r.startswith("mermaid") for r in rules(self.OK.replace("C->>A", "C->>X"))))

    def test_닫히지_않은_블록은_걸린다(self):
        broken = self.OK.replace("            end\n", "")
        self.assertTrue(any(r.startswith("mermaid") for r in rules(broken)))

    def test_flowchart_괄호_짝(self):
        self.assertEqual([], mermaid_rules("```mermaid\nflowchart LR\n  A[Agent] --> B[MCP Server]\n```\n"))
        self.assertTrue(rules("```mermaid\nflowchart LR\n  A[Agent --> B\n```\n"))


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

    def test_코드_블록_안은_세지_않는다(self):
        self.assertEqual([], rules("```text\n" + "가" * 151 + "다.\n```\n"))

    def test_긴_fence_안의_짧은_fence_는_블록을_닫지_않는다(self):
        doc = "````markdown\n```mermaid\nx\n```\n[a](없음.md) " + "가" * 151 + "다.\n````\n"
        self.assertEqual([], rules(doc))

    def test_inline_code_와_URL_은_글자_수에_넣지_않는다(self):
        self.assertEqual([], rules("`" + "x" * 200 + "`를 https://example.com/" + "y" * 200 + " 로 보낸다.\n"))

    def test_표_칸의_긴_문장도_걸린다(self):
        doc = "| a | b |\n|---|---|\n| x | " + "가" * 151 + "다. |\n"
        self.assertIn("sentence-chars:3", rules(doc))


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


class ParticleSpaceTest(unittest.TestCase):
    def test_영어_뒤_띄어_쓴_조사는_걸린다(self):
        self.assertIn("particle-space:1", rules("MCP Server 가 응답한다.\n"))

    def test_코드_뒤_띄어_쓴_조사는_걸린다(self):
        self.assertIn("particle-space:1", rules("`resource` 를 보낸다.\n"))

    def test_붙여_쓴_조사는_통과한다(self):
        self.assertEqual([], rules("MCP Server가 `resource`를 보낸다.\n"))

    def test_조사가_아닌_말은_통과한다(self):
        self.assertEqual([], rules("token 없이 부른다.\n"))


class StyleTest(unittest.TestCase):
    def test_번역_어투는_걸린다(self):
        for phrase in ["싣는다", "배선", "물러난다", "드러난다"]:
            with self.subTest(phrase=phrase):
                self.assertIn("style:1", rules(f"값을 {phrase}.\n"))


class BodyTest(unittest.TestCase):
    def test_본문의_요구_수준_단어는_걸린다(self):
        self.assertIn("body-level:3", rules("# 제목\n\nclient는 확인해야 한다(**MUST**).\n"))

    def test_명세_근거_절의_요구_수준_단어는_통과한다(self):
        doc = "# 제목\n\n### 3.10 명세 근거\n\n| 내용 | 명세 | 요구 수준 |\n|---|---|---|\n| x | y | MUST |\n"
        self.assertEqual([], rules(doc))

    def test_명세_근거_다음_절에서는_다시_걸린다(self):
        doc = "### 명세 근거\n\nMUST 다.\n\n### 다음\n\nMUST 다.\n"
        self.assertEqual(["body-level:7"], [r for r in rules(doc) if r.startswith("body-level")])

    def test_본문의_캡처_번호는_걸린다(self):
        self.assertIn("body-capture:1", rules("C3 응답을 본다.\n"))

    def test_본문의_테스트_이름은_걸린다(self):
        self.assertIn("body-test:1", rules("`McpAuthorizationDiscoveryTest#발견은_한_번만_한다` 가 확인한다.\n"))

    def test_reference_문서는_본문_규칙을_적용하지_않는다(self):
        self.assertEqual([], rules("`resource`는 REQUIRED다(C2).\n", name="reference-api.md"))


class HtmlTest(unittest.TestCase):
    def test_앵커_태그는_걸린다(self):
        self.assertIn("html:1", rules('<a id="s1"></a>\n'))

    def test_인라인_html은_걸린다(self):
        self.assertIn("html:1", rules("<sub>작은 글씨</sub>\n"))

    def test_코드_안의_꺾쇠는_통과한다(self):
        self.assertEqual([], rules("`<이름>`을 바꾼다.\n\n```text\n<tag>\n```\n"))


class DiagramTest(unittest.TestCase):
    MERMAID = "```mermaid\nsequenceDiagram\n    participant A as A\n    participant B as B\n    A->>B: hi\n```\n"

    def doc_with_png(self, link: str, source_hash: str | None = None) -> list[str]:
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "diagrams").mkdir()
            (Path(d) / "diagrams" / "doc-1.png").write_bytes(b"png")
            body = "sequenceDiagram\n    participant A as A\n    participant B as B\n    A->>B: hi\n"
            digest = source_hash or check_docs.diagram_hash(body)
            (Path(d) / "diagrams" / ".sources.json").write_text(json.dumps({"doc-1.png": digest}), encoding="utf-8")
            path = Path(d) / "doc.md"
            path.write_text(self.MERMAID + "\n" + link + "\n", encoding="utf-8")
            return [f"{i.rule}:{i.line}" for i in check_docs.check_file(path, TERMS)]

    def test_그림_링크가_있고_최신이면_통과한다(self):
        self.assertEqual([], self.doc_with_png("[다이어그램 그림으로 보기](diagrams/doc-1.png)"))

    def test_그림_링크가_없으면_걸린다(self):
        self.assertIn("diagram-link:1", rules(self.MERMAID))

    def test_그림_파일이_없으면_걸린다(self):
        self.assertIn("diagram-missing:8", rules(self.MERMAID + "\n[다이어그램 그림으로 보기](diagrams/doc-1.png)\n"))

    def test_원본이_바뀌면_걸린다(self):
        self.assertIn("diagram-stale:8", self.doc_with_png("[다이어그램 그림으로 보기](diagrams/doc-1.png)", "0" * 64))


class LinksFromTest(unittest.TestCase):
    def test_옮기지_않은_링크와_뺀_이유(self):
        with tempfile.TemporaryDirectory() as d:
            old, new, dropped = Path(d) / "old.md", Path(d) / "new.md", Path(d) / "dropped.txt"
            old.write_text("[a](https://a.example/x) [b](https://b.example/y) [c](https://c.example/z)\n")
            new.write_text("[a](https://a.example/x)\n")
            dropped.write_text("https://b.example/y\t범위 밖\nhttps://c.example/z\n")
            found = [i.message for i in check_docs.check_links_from(old, [new], dropped)]
            self.assertEqual(1, len([m for m in found if "뺀 이유가 없다" in m]))
            self.assertEqual([], [m for m in found if "옮기지 않은 링크" in m])


if __name__ == "__main__":
    unittest.main()
