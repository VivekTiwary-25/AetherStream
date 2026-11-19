#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path
import subprocess
import sys


def marked_blocks(path):
    lines = Path(path).read_text(encoding="utf-8").splitlines()
    blocks = []
    index = 0
    while index < len(lines):
        if lines[index].strip() != "<!-- ci-check -->":
            index += 1
            continue
        fence_index = index + 1
        if fence_index >= len(lines) or not lines[fence_index].startswith("```"):
            raise SystemExit(f"{path}:{index + 1}: ci-check must be immediately before a code fence")
        language = lines[fence_index][3:].strip().split(maxsplit=1)[0].lower() if lines[fence_index][3:].strip() else ""
        if language not in {"bash", "sh", "shell"}:
            raise SystemExit(f"{path}:{fence_index + 1}: ci-check requires a bash/sh/shell block, got {language!r}")
        end = fence_index + 1
        while end < len(lines) and not lines[end].startswith("```"):
            end += 1
        if end == len(lines):
            raise SystemExit(f"{path}:{fence_index + 1}: unterminated marked code block")
        commands = "\n".join(lines[fence_index + 1:end])
        if commands.strip():
            blocks.append((fence_index + 1, commands))
        index = end + 1
    return blocks


count = 0
for document in ("DEMO.md", "docs/runbook.md"):
    for fence_line, commands in marked_blocks(document):
        count += 1
        print(f"\n== {document}:{fence_line} ==", flush=True)
        result = subprocess.run(
            ["bash", "-euo", "pipefail", "-c", "set -x\n" + commands],
            check=False,
        )
        if result.returncode:
            print(f"FAILED: command block {document}:{fence_line} (exit {result.returncode})", file=sys.stderr)
            raise SystemExit(result.returncode)
if not count:
    raise SystemExit("No ci-check shell blocks found in DEMO.md or docs/runbook.md")
print(f"Checked {count} documented command block(s).")
PY
