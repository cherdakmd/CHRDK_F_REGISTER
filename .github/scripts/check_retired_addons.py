#!/usr/bin/env python3
"""Остатки снятых аддонов: действующие исходники/ресурсы/вики, без стирания истории."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
ALIASES = r"boss|bosses|bossy|bosss|bossadmin|bossesadmin|treasures|treasuresadmin|stream|streams|strimer|events|event|sobytiya|eventsadmin|queue|qjoin|ochered"
PATTERNS = (
    re.compile(r"(?iu)вход\s+без\s+очереди|queue\.(?:regular-limit|rank-priority)"),
    re.compile(r"(?i)chrdk[_-]?(?:bosses|treasures|streams|events|queue)|ChrdkTreasureBookInfo"),
    re.compile(r"(?i)/(?:[a-z0-9_.-]+:)?(?:" + ALIASES + r")(?![a-z0-9_/-])"),
    re.compile(r"(?i)\b(?:bosses|treasures|streams|events|queue)\.html\b"),
    re.compile(r"(?i)(?:\bcommand|[\"']command[\"'])\s*:\s*[\"']?/?(?:[a-z0-9_.-]+:)?(?:"
               + ALIASES + r")(?![a-z0-9_/-])"),
)
RETIRED_PATHS = ("bosses", "treasures", "wiki/bosses.md", "wiki/treasures.md", "docs/TREASURES_ARSENAL.md", "streams", "wiki/streams.md", "events", "wiki/events.md", "queue", "wiki/queue.md")
GENERATED_DIRS = {"node_modules", "test-results", "playwright-report", "__pycache__"}
SUFFIXES = {".java", ".yml", ".yaml", ".md", ".py", ".js", ".html", ".gradle", ".properties"}


def active_files(root: Path) -> list[Path]:
    # Включая осиротевший модуль, который уже убрали из settings.gradle.
    trees = [root / "src/main", root / "wiki", root / "tools/wiki", *root.glob("*/src/main")]
    files = {p for tree in trees if tree.is_dir() for p in tree.rglob("*")
             if p.is_file() and p.suffix in SUFFIXES
             and not GENERATED_DIRS.intersection(p.relative_to(root).parts)}
    files.update(p for p in root.glob("*/build.gradle") if p.is_file())
    for name in ("settings.gradle", "build.gradle", "docs/RANKS-GUIDE.md", "docs/RANKS-DESIGN.md"):
        if (root / name).is_file():
            files.add(root / name)
    # Архивные release notes, аудиты и Java-тесты намеренно не входят в эти деревья.
    return sorted(p for p in files if not (
        p.parent == root / "src/main/resources" and p.name.startswith("presentation-legacy")
        and p.suffix == ".yml"))


def check(root: Path) -> list[str]:
    errors = [f"{name}: остался файл/каталог снятого аддона"
              for name in RETIRED_PATHS if (root / name).exists()]
    for path in active_files(root):
        relative = path.relative_to(root).as_posix()
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if any(pattern.search(line) for pattern in PATTERNS):
                errors.append(f"{relative}:{number}: ссылка на снятый аддон: {line.strip()}")
            if relative == "settings.gradle" and re.search(
                    r"(?i)^\s*include\s+['\"](?:bosses|treasures|streams|events|queue)['\"]", line):
                errors.append(f"{relative}:{number}: снятый модуль включён в сборку")
    return errors


def main() -> int:
    errors = check(ROOT)
    if errors:
        print("Найдены остатки снятых аддонов:", file=sys.stderr)
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("Bosses/Treasures/Streams/Events/Queue: активных ссылок, команд, страниц и модулей не найдено.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
