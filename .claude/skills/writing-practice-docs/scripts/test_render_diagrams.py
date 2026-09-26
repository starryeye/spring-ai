"""render_diagrams.py 의 블록 추출·링크 삽입·해시 기록을 mmdc 없이 확인한다."""
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import render_diagrams  # noqa: E402

DOC = "# 제목\n\n```mermaid\nsequenceDiagram\n    participant A as A\n```\n\n본문\n\n```mermaid\nflowchart LR\n    A --> B\n```\n"


class RenderTest(unittest.TestCase):
    def render(self, text: str):
        calls = []

        def fake_runner(source: str, png: Path) -> None:
            calls.append((source, png.name))
            png.write_bytes(b"png")

        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "guide.md"
            path.write_text(text, encoding="utf-8")
            render_diagrams.render_file(path, fake_runner)
            sources = json.loads((Path(d) / "diagrams" / ".sources.json").read_text(encoding="utf-8"))
            return path.read_text(encoding="utf-8"), calls, sources, sorted(p.name for p in (Path(d) / "diagrams").iterdir())

    def test_블록마다_png를_만들고_링크를_넣는다(self):
        text, calls, sources, files = self.render(DOC)
        self.assertEqual(["guide-1.png", "guide-2.png"], [c[1] for c in calls])
        self.assertIn("```\n\n[다이어그램 그림으로 보기](diagrams/guide-1.png)\n", text)
        self.assertIn("[다이어그램 그림으로 보기](diagrams/guide-2.png)", text)
        self.assertEqual({".sources.json", "guide-1.png", "guide-2.png"}, set(files))
        self.assertEqual(render_diagrams.diagram_hash(calls[0][0]), sources["guide-1.png"])

    def test_이미_있는_링크는_두_번_넣지_않는다(self):
        once, _, _, _ = self.render(DOC)
        twice, _, _, _ = self.render(once)
        self.assertEqual(once, twice)

    def test_지워진_블록의_png와_해시는_정리한다(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "diagrams").mkdir()
            (Path(d) / "diagrams" / "guide-3.png").write_bytes(b"old")
            (Path(d) / "diagrams" / ".sources.json").write_text('{"guide-3.png": "x"}', encoding="utf-8")
            path = Path(d) / "guide.md"
            path.write_text(DOC, encoding="utf-8")
            render_diagrams.render_file(path, lambda s, p: p.write_bytes(b"png"))
            self.assertFalse((Path(d) / "diagrams" / "guide-3.png").exists())
            sources = json.loads((Path(d) / "diagrams" / ".sources.json").read_text(encoding="utf-8"))
            self.assertNotIn("guide-3.png", sources)

    def test_위에_블록이_새로_생기면_아래_링크_이름을_고친다(self):
        once, _, _, _ = self.render(DOC)
        new_block = "```mermaid\nflowchart TB\n    X --> Y\n```\n\n"
        shifted = once.replace("# 제목\n\n", "# 제목\n\n" + new_block, 1)
        text, calls, _, _ = self.render(shifted)
        self.assertEqual(["guide-1.png", "guide-2.png", "guide-3.png"],
                         re.findall(r"\(diagrams/(guide-\d+\.png)\)", text))
        self.assertEqual(["guide-1.png", "guide-2.png", "guide-3.png"], [c[1] for c in calls])

    def test_이름이_비슷한_다른_문서의_그림은_지우지_않는다(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "diagrams").mkdir()
            (Path(d) / "diagrams" / "guide-extra-1.png").write_bytes(b"other")
            (Path(d) / "diagrams" / ".sources.json").write_text('{"guide-extra-1.png": "x"}', encoding="utf-8")
            path = Path(d) / "guide.md"
            path.write_text(DOC, encoding="utf-8")
            render_diagrams.render_file(path, lambda s, p: p.write_bytes(b"png"))
            self.assertTrue((Path(d) / "diagrams" / "guide-extra-1.png").exists())
            sources = json.loads((Path(d) / "diagrams" / ".sources.json").read_text(encoding="utf-8"))
            self.assertEqual("x", sources["guide-extra-1.png"])

    def test_해시_기록이_없는_남은_그림도_정리한다(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "diagrams").mkdir()
            (Path(d) / "diagrams" / "guide-3.png").write_bytes(b"old")
            path = Path(d) / "guide.md"
            path.write_text(DOC, encoding="utf-8")
            render_diagrams.render_file(path, lambda s, p: p.write_bytes(b"png"))
            self.assertFalse((Path(d) / "diagrams" / "guide-3.png").exists())
