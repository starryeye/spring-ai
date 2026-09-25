"""check_docs.py 의 규칙마다 걸리는 예와 통과하는 예를 하나씩 둔다.

실행: python3 -m unittest discover -s .claude/skills/writing-practice-docs/scripts -p 'test_*.py'
"""
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


class SetextTest(unittest.TestCase):
    def test_글_바로_아래_구분선은_걸린다(self):
        self.assertIn("setext:2", rules("문단이다.\n---\n"))

    def test_빈_줄_뒤_구분선은_통과한다(self):
        self.assertEqual([], rules("문단이다.\n\n---\n"))

    def test_front_matter_는_구분선으로_보지_않는다(self):
        self.assertEqual([], rules("---\nname: x\n---\n\n본문이다.\n"))


class LengthTest(unittest.TestCase):
    def test_네_문장_문단은_걸린다(self):
        self.assertIn("length:1", rules("하나다. 둘이다. 셋이다. 넷이다.\n"))

    def test_세_문장_문단은_통과한다(self):
        self.assertEqual([], rules("하나다. 둘이다. 셋이다.\n"))

    def test_목록_항목은_따로_센다(self):
        self.assertEqual([], rules("- 하나다. 둘이다.\n- 셋이다. 넷이다.\n"))

    def test_절_번호의_점은_문장_끝이_아니다(self):
        self.assertEqual([], rules("OAuth 2.1 §4.3.1 과 RFC 8252 §7.3 을 본다.\n"))

    def test_코드_블록_안은_세지_않는다(self):
        self.assertEqual([], rules("```text\n하나. 둘. 셋. 넷.\n```\n"))

    def test_긴_fence_안의_짧은_fence_는_블록을_닫지_않는다(self):
        doc = "````markdown\n```mermaid\nx\n```\n[a](없음.md) 하나. 둘. 셋. 넷.\n````\n"
        self.assertEqual([], rules(doc))


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
        self.assertEqual([], rules(doc))

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
        self.assertEqual([], rules(self.OK))

    def test_선언_안_된_participant_는_걸린다(self):
        self.assertTrue(any(r.startswith("mermaid") for r in rules(self.OK.replace("C->>A", "C->>X"))))

    def test_닫히지_않은_블록은_걸린다(self):
        broken = self.OK.replace("            end\n", "")
        self.assertTrue(any(r.startswith("mermaid") for r in rules(broken)))

    def test_flowchart_괄호_짝(self):
        self.assertEqual([], rules("```mermaid\nflowchart LR\n  A[Agent] --> B[MCP Server]\n```\n"))
        self.assertTrue(rules("```mermaid\nflowchart LR\n  A[Agent --> B\n```\n"))


class FieldCountTest(unittest.TestCase):
    TABLE_3 = (
        "| 이름 | 설명 |\n"
        "|---|---|\n"
        "| a | 1 |\n"
        "| b | 2 |\n"
        "| c | 3 |\n"
    )
    TABLE_4 = TABLE_3 + "| d | 4 |\n"

    def test_표_행_수와_명시한_개수가_같으면_통과한다(self):
        doc = self.TABLE_3 + "\n원문 필드: RFC 7517 §4 에 정의된 3개 → 표 3행\n"
        self.assertEqual([], rules(doc))

    def test_표_행이_하나_늘면_field_count_가_걸린다(self):
        doc = self.TABLE_4 + "\n원문 필드: RFC 7517 §4 에 정의된 3개 → 표 3행\n"
        self.assertIn("field-count:8", rules(doc))

    def test_명시한_개수와_표_행_표기가_다르면_걸린다(self):
        doc = "원문 필드: RFC 7517 §4 에 정의된 8개 → 표 10행\n"
        self.assertIn("field-count:1", rules(doc))

    def test_원문_필드_없음은_통과한다(self):
        self.assertEqual([], rules("원문 필드: 없음(요청 파라미터가 없다)\n"))

    def test_정수_하나가_아닌_형식은_걸린다(self):
        found = rules("원문 필드: 8+2개 → 표 10행\n")
        self.assertTrue(any(r.startswith("field-count") for r in found))


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
