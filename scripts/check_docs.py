#!/usr/bin/env python3
"""
Docs lint: keeps the documents agents and people rely on from drifting.

Fails when
  1. a relative link or #anchor in a tracked Markdown file points nowhere;
  2. a "Passed" row in docs/TRACEABILITY.csv lacks the date and commit it passed on (AGENTS.md);
  3. docs/TRACEABILITY.csv names a test case ID that docs/testing/TEST_CASES.md has no row for.

Standard library only. Run from anywhere: python scripts/check_docs.py
"""
from __future__ import annotations

import csv
import os
import re
import subprocess
import sys
from functools import lru_cache
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parent.parent
TRACEABILITY = ROOT / "docs" / "TRACEABILITY.csv"
TEST_CASES = ROOT / "docs" / "testing" / "TEST_CASES.md"

LINK = re.compile(r"(?<!!)\[[^\]]*\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)")
IMAGE = re.compile(r"!\[[^\]]*\]\(\s*<?([^)\s>]+)>?\s*\)")
HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")
FENCE = re.compile(r"^\s*(```|~~~)")
INLINE_CODE = re.compile(r"`[^`]*`")
PASSED_PROOF = re.compile(r"\d{4}-\d{2}-\d{2}.*\b[0-9a-f]{7,40}\b")
TEST_CASE_ID = re.compile(r"\bTC-[A-Z][A-Z0-9-]*(?:\.\d+)*[a-z]\b")


def tracked_markdown() -> list[Path]:
    out = subprocess.run(["git", "ls-files", "*.md"], cwd=ROOT, capture_output=True, text=True, check=True).stdout
    return [ROOT / line for line in out.splitlines() if line]


def slug(heading: str) -> str:
    """GitHub's anchor for a heading: lower case, punctuation dropped, spaces to hyphens."""
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", heading)  # [text](link) -> text
    text = text.replace("`", "").replace("*", "").strip().lower()
    text = re.sub(r"[^\w\- ]", "", text)
    return text.replace(" ", "-")


@lru_cache(maxsize=None)
def anchors(path: Path) -> frozenset[str]:
    seen: dict[str, int] = {}
    result = set()
    in_fence = False
    for line in path.read_text(encoding="utf-8").splitlines():
        if FENCE.match(line):
            in_fence = not in_fence
            continue
        match = None if in_fence else HEADING.match(line)
        if not match:
            continue
        base = slug(match.group(2))
        count = seen.get(base, 0)
        seen[base] = count + 1
        result.add(base if count == 0 else f"{base}-{count}")
    # Explicit HTML anchors: <a id="..."> or <a name="...">
    for explicit in re.findall(r"<a\s+(?:id|name)=\"([^\"]+)\"", path.read_text(encoding="utf-8")):
        result.add(explicit)
    return frozenset(result)


def links_in(path: Path):
    in_fence = False
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if FENCE.match(line):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        line = INLINE_CODE.sub("", line)
        for pattern in (LINK, IMAGE):
            for match in pattern.finditer(line):
                yield number, match.group(1)


def check_links() -> list[str]:
    problems = []
    for source in tracked_markdown():
        for number, target in links_in(source):
            if re.match(r"^[a-z][a-z0-9+.-]*:", target, re.I):  # http:, https:, mailto:, ...
                continue
            where = f"{source.relative_to(ROOT).as_posix()}:{number}"
            file_part, _, anchor = target.partition("#")
            file_part = unquote(file_part)
            destination = source if not file_part else (source.parent / file_part).resolve()
            if not destination.exists():
                problems.append(f"{where}: link to missing {target}")
                continue
            if anchor and destination.is_file() and destination.suffix == ".md" and anchor not in anchors(destination):
                problems.append(f"{where}: link to missing heading #{anchor} in {destination.relative_to(ROOT).as_posix()}")
    return problems


def check_traceability() -> list[str]:
    problems = []
    known = set(TEST_CASE_ID.findall(TEST_CASES.read_text(encoding="utf-8")))
    with TRACEABILITY.open(encoding="utf-8", newline="") as handle:
        for line, row in enumerate(csv.DictReader(handle), start=2):
            status = row.get("Status", "")
            where = f"docs/TRACEABILITY.csv:{line}"
            if status.startswith("Passed") and not PASSED_PROOF.search(status):
                problems.append(f"{where}: \"{status}\" needs the date and commit it passed on, as Passed (YYYY-MM-DD, <commit>)")
            for test_case in TEST_CASE_ID.findall(row.get("Planned check", "")):
                if test_case not in known:
                    problems.append(f"{where}: {test_case} has no row in docs/testing/TEST_CASES.md")
    return problems


def main() -> int:
    problems = check_links() + check_traceability()
    for problem in problems:
        print(f"::error::{problem}" if "GITHUB_ACTIONS" in os.environ else problem)
    if problems:
        print(f"\n{len(problems)} docs problem(s).", file=sys.stderr)
        return 1
    print("Docs check passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
