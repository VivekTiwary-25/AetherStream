#!/usr/bin/env python3
"""Check relative Markdown links and heading fragments in tracked Markdown files."""

from __future__ import annotations

import re
import subprocess
import sys
import unicodedata
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[2]
LINK = re.compile(r"!?\[[^\]]*\]\(\s*(?:<([^>]+)>|([^\s)]+))(?:\s+[^)]*)?\)")
REFERENCE = re.compile(r"!?\[([^\]]*)\]\[([^\]]*)\]")
REFERENCE_DEF = re.compile(r"^\s*\[([^\]]+)\]:\s*<?(\S+?)>?(?:\s+.*)?$")
INLINE_CODE = re.compile(r"(`+).*?\1")
HTML_COMMENT = re.compile(r"<!--.*?-->", re.DOTALL)


def slug(text: str) -> str:
    text = re.sub(r"<[^>]*>", "", text)
    text = re.sub(r"[`*_~]", "", text)
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode().lower()
    return re.sub(r"[^\w -]", "", text).strip().replace(" ", "-")


def headings(path: Path) -> set[str]:
    lines = path.read_text(encoding="utf-8").splitlines()
    result: set[str] = set()
    counts: dict[str, int] = {}
    in_fence = False
    fence_char = ""
    for line in lines:
        fence = re.match(r"^\s*(`{3,}|~{3,})", line)
        if fence:
            char = fence.group(1)[0]
            if not in_fence:
                in_fence, fence_char = True, char
            elif char == fence_char:
                in_fence = False
            continue
        if in_fence:
            continue
        match = re.match(r"^\s{0,3}#{1,6}\s+(.+?)\s*#*\s*$", line)
        if not match:
            continue
        base = slug(match.group(1))
        n = counts.get(base, 0)
        counts[base] = n + 1
        result.add(base if n == 0 else f"{base}-{n}")
    return result


def main() -> int:
    listing = subprocess.run(
        ["git", "ls-files", "-z", "--", "*.md"], cwd=ROOT,
        check=True, stdout=subprocess.PIPE,
    ).stdout
    files = [Path(p.decode()) for p in listing.split(b"\0") if p]
    files = [p for p in files if p.parts[0] not in {"swarm", "boss"}]
    text_by_path = {p: (ROOT / p).read_text(encoding="utf-8") for p in files}
    anchors = {p: headings(ROOT / p) for p in files}
    errors = 0
    external: set[str] = set()

    for source in files:
        text = HTML_COMMENT.sub("", text_by_path[source])
        text = INLINE_CODE.sub("", text)
        definitions: dict[str, str] = {}
        for line in text.splitlines():
            definition = REFERENCE_DEF.match(line)
            if definition:
                definitions[definition.group(1).strip().lower()] = definition.group(2)
        links = [(m.group(1) or m.group(2), m.start()) for m in LINK.finditer(text)]
        for match in REFERENCE.finditer(text):
            label = (match.group(2) or match.group(1)).strip().lower()
            if label in definitions:
                links.append((definitions[label], match.start()))
        for target, position in links:
            target = target.strip().strip("'\"")
            if target.startswith("//"):
                external.add("https:" + target)
                continue
            parts = urlsplit(target)
            if parts.scheme:
                if parts.scheme in {"http", "https"}:
                    external.add(target)
                continue
            if not parts.path:
                destination = source
                resolved = ROOT / destination
            else:
                raw_path = unquote(parts.path)
                resolved = (ROOT / source.parent / raw_path).resolve()
                try:
                    relative = resolved.relative_to(ROOT)
                except ValueError:
                    print(f"{source}: link escapes repository: {target}")
                    errors += 1
                    continue
                destination = Path(relative)
            if not resolved.exists():
                print(f"{source}: missing path: {target}")
                errors += 1
                continue
            if resolved.is_dir():
                index = resolved / "README.md"
                if not index.is_file():
                    continue
                resolved = index
                destination = Path(index.relative_to(ROOT))
            fragment = unquote(parts.fragment)
            if fragment:
                if resolved.suffix.lower() == ".md":
                    target_anchors = anchors.get(destination)
                    if target_anchors is None:
                        target_anchors = headings(resolved)
                    if fragment not in target_anchors:
                        print(f"{source}: missing anchor #{fragment}: {target}")
                        errors += 1
                elif fragment.startswith("#"):
                    continue
    for link in sorted(external):
        print(f"external (not checked): {link}")
    if errors:
        print(f"Found {errors} broken relative link(s).", file=sys.stderr)
        return 1
    print(f"Checked {len(files)} tracked Markdown files; no broken relative links.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
