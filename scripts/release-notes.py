#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Достаёт раздел версии из CHANGELOG.md — им подписывается GitHub-релиз.

Использование:  python3 scripts/release-notes.py 1.6.5 > RELEASE_NOTES.md
Заголовки в CHANGELOG:  ## 1.6.5 — 2026-09-28
"""
import re
import sys
import pathlib

HEADING = re.compile(r"^##\s+\[?(\d+\.\d+\.\d+)\]?.*$", re.M)


def main() -> int:
    if len(sys.argv) != 2:
        print("нужна ровно одна версия: release-notes.py 1.6.5", file=sys.stderr)
        return 2
    version = sys.argv[1].lstrip("v")
    path = pathlib.Path(__file__).resolve().parent.parent / "CHANGELOG.md"
    if not path.exists():
        print(f"нет файла {path}", file=sys.stderr)
        return 1
    text = path.read_text(encoding="utf-8")

    matches = list(HEADING.finditer(text))
    for index, match in enumerate(matches):
        if match.group(1) != version:
            continue
        start = match.start()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        body = text[start:end].strip("\n")
        body = "\n".join(line for line in body.split("\n") if not line.startswith("---")).strip()
        print(body)
        return 0
    print(f"в CHANGELOG.md нет раздела версии {version}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
