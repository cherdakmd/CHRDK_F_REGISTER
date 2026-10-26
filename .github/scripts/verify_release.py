#!/usr/bin/env python3
"""Validate the complete plugin release before uploading it (stdlib only)."""
from pathlib import Path
import re
import sys
import zipfile


def scalar(text: str, key: str) -> str:
    match = re.search(r"^" + re.escape(key) + r":\s*([^\r\n]+)", text, re.MULTILINE)
    if not match:
        raise ValueError(f"plugin.yml: missing {key}")
    return match.group(1).strip().strip("\"'")


def expected_plugins(root: Path) -> set[str]:
    modules = re.findall(r"^include\s+['\"]([^'\"]+)['\"]", (root / "settings.gradle").read_text(), re.MULTILINE)
    resources = [root / "src/main/resources/plugin.yml"]
    resources += [root / module / "src/main/resources/plugin.yml" for module in modules]
    names = [scalar(p.read_text(encoding="utf-8"), "name") for p in resources]
    if len(names) != len(set(names)):
        raise ValueError("duplicate plugin name in source modules")
    return set(names)


def verify(directory: Path, version: str, expected: set[str]) -> dict[str, str]:
    if not expected or not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError("release requires a non-empty plugin set and a stable version")
    jars = sorted(directory.glob("*.jar"))
    actual = {}
    for jar in jars:
        with zipfile.ZipFile(jar) as archive:
            bad = archive.testzip()
            if bad:
                raise ValueError(f"{jar.name}: invalid CRC for {bad}")
            if archive.namelist().count("plugin.yml") != 1:
                raise ValueError(f"{jar.name}: expected exactly one plugin.yml")
            descriptor = archive.read("plugin.yml").decode("utf-8")
            name = scalar(descriptor, "name")
            found_version = scalar(descriptor, "version")
            if found_version != version:
                raise ValueError(f"{jar.name}: version {found_version}, expected {version}")
            if name in actual:
                raise ValueError(f"duplicate plugin {name}: {actual[name]} and {jar.name}")
            actual[name] = jar.name
    if set(actual) != expected:
        raise ValueError(f"incomplete release: missing={sorted(expected - set(actual))}, unexpected={sorted(set(actual) - expected)}")
    memo = directory / "ОБНОВЛЕНИЕ.txt"
    if not memo.is_file() or not memo.read_text(encoding="utf-8").strip():
        raise ValueError("missing or empty ОБНОВЛЕНИЕ.txt")
    return actual


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: verify_release.py <directory> <version>")
    root = Path(__file__).resolve().parents[2]
    plugins = verify(Path(sys.argv[1]), sys.argv[2], expected_plugins(root))
    print(f"Release verified: {len(plugins)} plugins, version {sys.argv[2]}, update memo present")
    for name, jar in sorted(plugins.items()):
        print(f"  {name}: {jar}")
