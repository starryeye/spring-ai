#!/usr/bin/env python3
"""문서의 mermaid 블록마다 PNG 그림을 만들고, 블록 아래에 그림 링크를 넣는다.

사용: python3 render_diagrams.py FILE...
그림은 <문서 폴더>/diagrams/<문서 stem>-<n>.png, 원본 해시는 <문서 폴더>/diagrams/.sources.json 이다.
mmdc 는 환경 변수 MMDC 로 경로를 줄 수 있고, 없으면 npx 로 mermaid-cli 를 부른다.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import shlex
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Callable

BLOCK = re.compile(r"^```mermaid\n(.*?)^```\n", re.S | re.M)
LINK_TEXT = "다이어그램 그림으로 보기"


def diagram_hash(source: str) -> str:
    return hashlib.sha256(source.encode("utf-8")).hexdigest()


def link_line(png_name: str) -> str:
    return f"[{LINK_TEXT}](diagrams/{png_name})"


def mmdc_runner(source: str, png: Path) -> None:
    command = shlex.split(os.environ.get("MMDC", "npx -y @mermaid-js/mermaid-cli"))
    with tempfile.NamedTemporaryFile("w", suffix=".mmd", delete=False, encoding="utf-8") as f:
        f.write(source)
        mmd = f.name
    try:
        subprocess.run([*command, "-i", mmd, "-o", str(png), "-b", "white", "-s", "2"],
                       check=True, stdout=subprocess.DEVNULL)
    finally:
        os.unlink(mmd)


def render_file(path: Path, runner: Callable[[str, Path], None] = mmdc_runner) -> None:
    text = path.read_text(encoding="utf-8")
    out_dir = path.parent / "diagrams"
    out_dir.mkdir(exist_ok=True)
    sources_file = out_dir / ".sources.json"
    sources = json.loads(sources_file.read_text(encoding="utf-8")) if sources_file.exists() else {}
    prefix = f"{path.stem}-"

    pieces, last, names = [], 0, []
    for n, m in enumerate(BLOCK.finditer(text), 1):
        name = f"{path.stem}-{n}.png"
        names.append(name)
        runner(m.group(1), out_dir / name)
        sources[name] = diagram_hash(m.group(1))
        pieces.append(text[last:m.end()])
        rest = text[m.end():]
        if not rest.lstrip("\n").startswith(f"[{LINK_TEXT}]"):
            pieces.append("\n" + link_line(name) + "\n")
        last = m.end()
    pieces.append(text[last:])
    path.write_text("".join(pieces), encoding="utf-8")

    for stale in [k for k in sources if k.startswith(prefix) and k not in names]:
        sources.pop(stale)
        (out_dir / stale).unlink(missing_ok=True)
    sources_file.write_text(json.dumps(dict(sorted(sources.items())), ensure_ascii=False, indent=2) + "\n",
                            encoding="utf-8")


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    for arg in argv:
        render_file(Path(arg))
        print(f"{arg}: 그림을 만들었다")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
