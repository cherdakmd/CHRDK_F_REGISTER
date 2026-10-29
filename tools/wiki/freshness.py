"""Контракт смысловой актуальности: изменившийся код требует явной сверки связанных страниц."""

from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import sys
import yaml

ROOT = Path(__file__).resolve().parents[2]
LOCK = "wiki/reviewed.json"


def page_info(path):
    text = path.read_text(encoding="utf-8")
    if not text.startswith("---\n"):
        raise ValueError(f"{path}: нет front matter")
    parts = text.split("---\n", 2)
    meta = yaml.safe_load(parts[1])
    modules = meta.get("review")
    if not isinstance(modules, list) or not modules:
        raise ValueError(f"{path}: нужен непустой review: [модули]")
    return meta, text


def fingerprint(root, modules):
    paths = set()
    for module in modules:
        base = root / ("src/main" if module == "core" else f"{module}/src/main")
        if not base.is_dir():
            raise ValueError(f"Неизвестный модуль review: {module}")
        paths.update(
            p
            for p in base.rglob("*")
            if p.is_file() and p.suffix in {".java", ".yml", ".json"}
        )
    paths.add(root / "tools/wiki/facts.py")
    digest = hashlib.sha256()
    for path in sorted(paths):
        digest.update(path.relative_to(root).as_posix().encode())
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def snapshots(root=ROOT):
    out = {}
    for path in sorted((root / "wiki").glob("*.md")):
        meta, text = page_info(path)
        out[path.stem] = {
            "sources": fingerprint(root, meta["review"]),
            "text": hashlib.sha256(text.encode()).hexdigest(),
        }
    return out


def check(root=ROOT):
    current = snapshots(root)
    path = root / LOCK
    saved = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
    problems = []
    for slug, now in current.items():
        old = saved.get("pages", {}).get(slug, {})
        if any(old.get(key) != value for key, value in now.items()):
            problems.append(
                f'{slug}: изменены текст или реализация. Сверьте страницу и выполните freshness.py --accept {slug} --reason "что проверено"'
            )
    if set(saved.get("pages", {})) - set(current):
        problems.append("В reviewed.json остались удалённые страницы")
    settings = (root / "settings.gradle").read_text(encoding="utf-8")
    import re

    required = {"core", *re.findall(r"^include\s+['\"]([^'\"]+)['\"]", settings, re.M)}
    covered = set()
    for page in (root / "wiki").glob("*.md"):
        covered.update(page_info(page)[0]["review"])
    if required - covered:
        problems.append(
            "Нет страниц для модулей: " + ", ".join(sorted(required - covered))
        )
    return problems


def accept(slugs, reason, root=ROOT):
    if len(reason.strip()) < 12:
        raise ValueError("Нужна содержательная причина сверки (не менее 12 символов)")
    current = snapshots(root)
    path = root / LOCK
    data = (
        json.loads(path.read_text(encoding="utf-8"))
        if path.exists()
        else {"schema": 1, "pages": {}}
    )
    for slug in slugs:
        if slug not in current:
            raise ValueError(f"Нет страницы {slug}")
        data["pages"][slug] = {**current[slug], "reason": reason.strip()}
    data["pages"] = {
        slug: entry for slug, entry in data["pages"].items() if slug in current
    }
    path.write_text(
        json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--accept", nargs="+", metavar="PAGE")
    parser.add_argument("--reason", default="")
    args = parser.parse_args()
    if args.accept:
        accept(args.accept, args.reason)
    errors = check()
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("Актуальность: все страницы сверены; все действующие модули представлены.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
