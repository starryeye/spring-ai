#!/usr/bin/env python3
"""practice 학습 문서의 형식 규칙을 검사한다(규칙: ../SKILL.md).

사용: python3 check_docs.py [--links-from OLD.md] [--dropped DROPPED.txt] FILE...
위반이 하나라도 있으면 `경로:줄: [규칙] 설명` 을 출력하고 종료 코드 1 로 끝난다.
규칙 이름: setext, narrative, term, particle-space, style, body-level, body-capture, body-test, html,
link, mermaid, diagram-link, diagram-missing, diagram-stale, cell-length, sentence-chars, links-from, missing.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import unquote

SKILL_DIR = Path(__file__).resolve().parent.parent
TERMS_FILE = SKILL_DIR / "terms.txt"

# 개발 과정 이야기를 드러내는 표현. 문서에는 결과와 명세만 남긴다.
BANNED_PHRASES = ["처음엔", "처음에는", "고쳤", "수정했", "Task ", "실측했", "착각", "버그를",
                  "바꿨", "추가했", "옮겼", "없앴", "확인했", "드러났"]
CELL_SENTENCE_LIMIT = 2
SENTENCE_CHAR_LIMIT = 150

FENCE = re.compile(r"^\s*(```+|~~~+)(.*)$")
HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")
ANCHOR_ID = re.compile(r'<a\s+id="([^"]+)"')
LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
LIST_ITEM = re.compile(r"^\s*(?:[-*+]|\d+\.)\s+")
RULE_LINE = re.compile(r"^\s*(-{3,}|={3,})\s*$")
URL = re.compile(r"https?://[^\s)\]>`\"']+")
TABLE_ROW = re.compile(r"^\s*\|.+\|\s*$")
TABLE_SEP = re.compile(r"^[\s|:-]+$")
SENTENCE_END = re.compile(r"(?<!\d)[.?!](?=\s|$|[)\]\"'])")
CELL_SPLIT = re.compile(r"(?<!\\)\|")

# 기계적 번역 어투. 자연스러운 한국어로 바꿔 쓴다(SKILL.md "문체").
STYLE_PHRASES = ["싣는", "싣고", "싣는다", "실린다", "배선", "물러나", "물러난", "드러나", "드러난"]
PARTICLE_SPACE = re.compile(
    r"(`[^`]+`|[A-Za-z0-9)\]])\s+(을|를|이|가|은|는|의|에|에서|에게|로|으로|와|과|도|만|까지|부터|처럼|보다)(?=[\s.,:;)!?]|$)")
# 한글은 \w 라서 \b 를 쓰면 "MUST로" 를 놓친다. 앞뒤가 영문자가 아닌지만 본다.
LEVEL_WORD = re.compile(r"(?<![A-Za-z])(MUST|SHOULD|MAY|REQUIRED|RECOMMENDED|OPTIONAL)(?![A-Za-z])")
# 캡처 번호는 두 자리까지(C18, P8-1). S256 같은 값은 캡처 번호가 아니다.
CAPTURE_ID = re.compile(r"(?<![A-Za-z0-9#])[CSP]\d{1,2}(?:-\d+)?(?![A-Za-z0-9])")
TEST_NAME = re.compile(r"\b[A-Z][A-Za-z0-9]*Tests?#")
HTML_TAG = re.compile(r"</?[A-Za-z][A-Za-z0-9]*(\s[^>]*)?/?>")
SPEC_SECTION = re.compile(r"^#{1,6}\s+(?:[\d.]+\s+)?명세 근거\s*$")
DIAGRAM_LINK = re.compile(r"^\[다이어그램 그림으로 보기\]\(diagrams/([^)]+\.png)\)\s*$")


@dataclass(frozen=True)
class Issue:
    path: str
    line: int
    rule: str
    message: str

    def __str__(self) -> str:
        return f"{self.path}:{self.line}: [{self.rule}] {self.message}"


def load_terms(path: Path = TERMS_FILE) -> list[tuple[re.Pattern, str]]:
    """terms.txt 한 줄은 `정규식<TAB>영어 표기`. 빈 줄과 # 주석은 건너뛴다."""
    terms = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        if not raw.strip() or raw.startswith("#"):
            continue
        pattern, english = raw.split("\t", 1)
        terms.append((re.compile(pattern), english.strip()))
    return terms


def strip_front_matter(text: str) -> str:
    """SKILL.md 의 YAML front matter 를 같은 줄 수의 빈 줄로 바꾼다(줄 번호 유지)."""
    if text.startswith("---\n"):
        end = text.find("\n---\n", 4)
        if end != -1:
            head = text[: end + 5]
            return "\n" * head.count("\n") + text[end + 5 :]
    return text


def split_blocks(text: str):
    """코드 밖 줄 [(줄 번호, 줄)] 과 코드 블록 [(여는 줄 번호, 언어, [줄])] 로 나눈다."""
    prose, blocks = [], []
    in_code, fence, lang, buf, start = False, "", "", [], 0
    for no, line in enumerate(text.splitlines(), 1):
        m = FENCE.match(line)
        if not in_code:
            if m:
                in_code, fence, lang, buf, start = True, m.group(1), m.group(2).strip(), [], no
                continue
            prose.append((no, line))
            continue
        stripped = line.strip()
        # 닫는 fence 는 여는 fence 와 같은 문자로, 길이가 같거나 길어야 한다(CommonMark).
        if stripped.startswith(fence) and set(stripped) == {fence[0]}:
            blocks.append((start, lang, buf))
            in_code = False
        else:
            buf.append(line)
    return prose, blocks


def clean(line: str) -> str:
    """inline code, 링크 대상, URL 을 지운다. 링크 글자는 남긴다."""
    line = re.sub(r"`[^`]*`", " ", line)
    line = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", line)
    return URL.sub(" ", line)


def sentence_count(text: str) -> int:
    """문장 끝(. ? !)을 센다. 숫자 사이의 점(2.1, §4.3)은 세지 않는다."""
    return len(SENTENCE_END.findall(clean(text)))


def sentences(text: str) -> list[str]:
    """inline code·링크 대상·URL 을 지운 뒤 문장 끝으로 나눈다."""
    flat = re.sub(r"\s+", " ", clean(text))
    return [s.strip() for s in SENTENCE_END.split(flat) if s.strip()]


def units(prose):
    """문단과 목록 항목을 한 단위로 묶는다: [(시작 줄 번호, 글)]."""
    out, cur = [], None
    for no, line in prose:
        s = line.strip()
        if not s or s.startswith(("#", "|", "<")) or RULE_LINE.match(line):
            if cur:
                out.append(cur)
            cur = None
            continue
        if s.startswith(">"):
            s = s.lstrip(">").strip()
        if LIST_ITEM.match(line):
            if cur:
                out.append(cur)
            cur = (no, LIST_ITEM.sub("", line).strip())
        elif cur:
            cur = (cur[0], cur[1] + " " + s)
        else:
            cur = (no, s)
    if cur:
        out.append(cur)
    return out


def check_setext(path: str, prose) -> list[Issue]:
    """바로 윗줄이 글인 `---`/`===` 는 구분선이 아니라 그 글을 제목으로 만든다."""
    issues, prev_no, prev = [], -1, ""
    for no, line in prose:
        if RULE_LINE.match(line) and prev_no == no - 1 and prev.strip():
            issues.append(Issue(path, no, "setext", "구분선 위에 빈 줄이 없어 윗줄이 제목이 된다"))
        prev_no, prev = no, line
    return issues


def check_words(path: str, prose, terms) -> list[Issue]:
    issues = []
    for no, line in prose:
        if line.lstrip().startswith("<a "):
            continue
        text = clean(line)
        for phrase in BANNED_PHRASES:
            if phrase in text:
                issues.append(Issue(path, no, "narrative", f"개발 과정 표현 '{phrase.strip()}'"))
        for pattern, english in terms:
            for m in pattern.finditer(text):
                issues.append(Issue(path, no, "term", f"'{m.group(0)}' 대신 '{english}'"))
    return issues


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


def slugify(heading: str) -> str:
    """GitHub 제목 앵커: 소문자, 글자·숫자·_·-·공백만 남기고 공백은 -."""
    t = re.sub(r"`", "", heading)
    t = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", t).strip().lower()
    t = re.sub(r"[^\w\- ]", "", t)
    return t.replace(" ", "-")


def anchors_of(text: str) -> set[str]:
    prose, _ = split_blocks(strip_front_matter(text))
    ids, seen = set(), {}
    for _, line in prose:
        ids.update(ANCHOR_ID.findall(line))
        h = HEADING.match(line)
        if h:
            base = slugify(h.group(2))
            n = seen.get(base, 0)
            seen[base] = n + 1
            ids.add(base if n == 0 else f"{base}-{n}")
    return ids


def check_links(path: Path, prose) -> list[Issue]:
    issues, cache = [], {}
    own = str(path)
    for no, line in prose:
        for target in LINK.findall(re.sub(r"`[^`]*`", " ", line)):
            if re.match(r"^[a-z]+:", target):
                continue
            file_part, _, anchor = target.partition("#")
            dest = path if not file_part else (path.parent / unquote(file_part)).resolve()
            if not dest.exists():
                issues.append(Issue(own, no, "link", f"없는 파일: {target}"))
                continue
            if anchor and dest.suffix == ".md":
                if dest not in cache:
                    cache[dest] = anchors_of(dest.read_text(encoding="utf-8"))
                if unquote(anchor) not in cache[dest]:
                    issues.append(Issue(own, no, "link", f"없는 앵커: {target}"))
    return issues


SEQ_PARTICIPANT = re.compile(r"^\s*(?:participant|actor)\s+([A-Za-z0-9_]+)")
SEQ_ARROW = re.compile(r"^\s*([A-Za-z0-9_]+)\s*(?:-{1,2}>>|-{1,2}>|-{1,2}x|-{1,2}\))\s*[+-]?\s*([A-Za-z0-9_]+)\s*:")
SEQ_NOTE = re.compile(r"^\s*Note\s+(?:over|left of|right of)\s+([A-Za-z0-9_]+)(?:\s*,\s*([A-Za-z0-9_]+))?\s*:", re.I)
SEQ_OPEN = re.compile(r"^\s*(?:alt|opt|loop|par|critical|break|rect)\b")
SEQ_MIDDLE = re.compile(r"^\s*(?:else|and|option)\b")
SEQ_END = re.compile(r"^\s*end\s*$")


def lint_mermaid(path: str, start: int, lines: list[str]) -> list[Issue]:
    """렌더러 없이 잡을 수 있는 mermaid 오류: 선언 안 된 participant, 짝 없는 블록, 세미콜론, 괄호 짝."""
    body = [l.strip() for l in lines if l.strip() and not l.strip().startswith("%%")]
    if not body:
        return [Issue(path, start, "mermaid", "빈 mermaid 블록")]
    issues = []
    if body[0] == "sequenceDiagram":
        declared, depth = set(), 0
        for off, line in enumerate(lines, 1):
            no, s = start + off, line.strip()
            if not s or s.startswith("%%") or s in ("sequenceDiagram", "autonumber"):
                continue
            if m := SEQ_PARTICIPANT.match(line):
                declared.add(m.group(1))
            elif SEQ_OPEN.match(line):
                depth += 1
            elif SEQ_MIDDLE.match(line):
                if depth == 0:
                    issues.append(Issue(path, no, "mermaid", "alt/par 밖의 else/and"))
            elif SEQ_END.match(line):
                depth -= 1
                if depth < 0:
                    issues.append(Issue(path, no, "mermaid", "짝 없는 end"))
                    depth = 0
            elif m := SEQ_NOTE.match(line):
                for p in filter(None, m.groups()):
                    if p not in declared:
                        issues.append(Issue(path, no, "mermaid", f"선언 안 된 participant: {p}"))
            elif m := SEQ_ARROW.match(line):
                for p in m.groups():
                    if p not in declared:
                        issues.append(Issue(path, no, "mermaid", f"선언 안 된 participant: {p}"))
                if ";" in s:
                    issues.append(Issue(path, no, "mermaid", "메시지의 ; 는 줄을 끊는다"))
            elif not s.startswith(("activate", "deactivate")):
                issues.append(Issue(path, no, "mermaid", f"해석할 수 없는 줄: {s[:40]}"))
        if depth > 0:
            issues.append(Issue(path, start, "mermaid", "닫히지 않은 alt/opt/loop/par 블록"))
    elif re.match(r"^(flowchart|graph)\s+(TB|TD|BT|RL|LR)\b", body[0]):
        for off, line in enumerate(lines, 1):
            for o, c in ("[]", "()", "{}"):
                if line.count(o) != line.count(c):
                    issues.append(Issue(path, start + off, "mermaid", f"괄호 짝이 맞지 않음: {o}{c}"))
    else:
        issues.append(Issue(path, start, "mermaid", f"지원하지 않는 다이어그램: {body[0][:30]}"))
    return issues


def diagram_hash(source: str) -> str:
    return hashlib.sha256(source.encode("utf-8")).hexdigest()


def check_style(path: str, prose) -> list[Issue]:
    issues = []
    for no, line in prose:
        if TABLE_SEP.match(line.strip()):
            continue
        without_urls = URL.sub(" ", re.sub(r"\]\([^)]*\)", "]", line))
        if PARTICLE_SPACE.search(without_urls):
            issues.append(Issue(path, no, "particle-space", "영어·코드 뒤 조사는 붙여 쓴다"))
        for phrase in STYLE_PHRASES:
            if phrase in clean(line):
                issues.append(Issue(path, no, "style", f"번역 어투 '{phrase}'"))
                break
        if HTML_TAG.search(re.sub(r"`[^`]*`", " ", line)):
            issues.append(Issue(path, no, "html", "HTML 태그·앵커 태그를 쓰지 않는다"))
    return issues


def check_body(path: Path, prose) -> list[Issue]:
    """안내서 본문 금지 항목. '명세 근거' 절과 reference-*.md 는 예외다."""
    if path.name.startswith("reference-"):
        return []
    issues, in_spec, spec_level = [], False, 0
    for no, line in prose:
        h = HEADING.match(line)
        if h:
            level = len(h.group(1))
            if SPEC_SECTION.match(line):
                in_spec, spec_level = True, level
            elif in_spec and level <= spec_level:
                in_spec = False
            continue
        if in_spec:
            continue
        text = clean(line)
        if LEVEL_WORD.search(text):
            issues.append(Issue(str(path), no, "body-level", "요구 수준 단어는 '명세 근거' 표에만 쓴다"))
        if CAPTURE_ID.search(text):
            issues.append(Issue(str(path), no, "body-capture", "캡처 번호는 본문에 쓰지 않는다"))
        if TEST_NAME.search(line):
            issues.append(Issue(str(path), no, "body-test", "테스트 이름은 본문에 쓰지 않는다"))
    return issues


def check_diagrams(path: Path, lines: list[str], blocks) -> list[Issue]:
    issues = []
    sources_file = path.parent / "diagrams" / ".sources.json"
    sources = json.loads(sources_file.read_text(encoding="utf-8")) if sources_file.exists() else {}
    n = 0
    for start, lang, body in blocks:
        if lang != "mermaid":
            continue
        n += 1
        expected = f"{path.stem}-{n}.png"
        end = start + len(body) + 1
        link_no = next((i for i in range(end + 1, len(lines) + 1) if lines[i - 1].strip()), None)
        m = DIAGRAM_LINK.match(lines[link_no - 1]) if link_no else None
        if not m or m.group(1) != expected:
            issues.append(Issue(str(path), start, "diagram-link",
                                f"mermaid 블록 아래에 [다이어그램 그림으로 보기](diagrams/{expected}) 가 없다"))
            continue
        if not (path.parent / "diagrams" / expected).exists():
            issues.append(Issue(str(path), link_no, "diagram-missing", f"그림이 없다: {expected} — render_diagrams.py 를 돌린다"))
        elif sources.get(expected) != diagram_hash("".join(l + "\n" for l in body)):
            issues.append(Issue(str(path), link_no, "diagram-stale", f"그림이 원본과 다르다: {expected} — render_diagrams.py 를 돌린다"))
    return issues


def check_file(path: Path, terms) -> list[Issue]:
    raw = path.read_text(encoding="utf-8")
    text = strip_front_matter(raw)
    prose, blocks = split_blocks(text)
    rel = str(path)
    issues = check_setext(rel, prose) + check_words(rel, prose, terms) + check_style(rel, prose)
    issues += check_cells(rel, prose) + check_sentence_chars(rel, prose)
    issues += check_body(path, prose)
    issues += check_links(path.resolve(), prose)
    issues += check_diagrams(path, text.splitlines(), blocks)
    for start, lang, lines in blocks:
        if lang == "mermaid":
            issues += lint_mermaid(rel, start, lines)
    return issues


def urls_of(text: str) -> dict[str, int]:
    """URL → 처음 나온 줄 번호. 끝의 문장부호는 뗀다."""
    found = {}
    for no, line in enumerate(text.splitlines(), 1):
        for u in URL.findall(line):
            found.setdefault(u.rstrip(".,;:"), no)
    return found


def check_links_from(old: Path, new_files: list[Path], dropped: Path | None) -> list[Issue]:
    """옛 문서의 URL 이 새 문서 묶음 어딘가에 있는지. 일부러 뺀 것은 dropped 에 `URL<TAB>이유`."""
    skip, issues = {}, []
    if dropped:
        for no, raw in enumerate(dropped.read_text(encoding="utf-8").splitlines(), 1):
            if not raw.strip() or raw.startswith("#"):
                continue
            url, _, reason = raw.partition("\t")
            if not reason.strip():
                issues.append(Issue(str(dropped), no, "links-from", f"뺀 이유가 없다: {url}"))
            skip[url.strip()] = reason
    present = set()
    for f in new_files:
        present |= set(urls_of(f.read_text(encoding="utf-8")))
    for url, no in urls_of(old.read_text(encoding="utf-8")).items():
        if url not in present and url not in skip:
            issues.append(Issue(str(old), no, "links-from", f"옮기지 않은 링크: {url}"))
    return issues


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", nargs="+", type=Path)
    parser.add_argument("--links-from", type=Path, help="옛 문서. 그 URL 이 FILES 에 모두 있어야 한다")
    parser.add_argument("--dropped", type=Path, help="일부러 뺀 URL 목록(URL<TAB>이유)")
    parser.add_argument("--terms", type=Path, default=TERMS_FILE)
    args = parser.parse_args(argv)

    terms = load_terms(args.terms)
    issues = []
    for f in args.files:
        if not f.exists():
            issues.append(Issue(str(f), 0, "missing", "파일이 없다"))
            continue
        issues += check_file(f, terms)
    if args.links_from:
        issues += check_links_from(args.links_from, [f for f in args.files if f.exists()], args.dropped)
    for issue in issues:
        print(issue)
    print(f"검사한 파일 {len(args.files)}개, 위반 {len(issues)}건")
    return 1 if issues else 0


if __name__ == "__main__":
    sys.exit(main())
