#!/usr/bin/env python3
"""Validate the general plugin release archive and standalone addon JARs (stdlib only)."""
from pathlib import Path
import re
import sys
import zipfile

# Отдельные опциональные аддоны публикуются самостоятельными JAR-файлами рядом с общим ZIP,
# но не включаются внутрь общего архива chrdk-plugins-<version>.zip.
STANDALONE_MODULES = {"vk"}


def scalar(text: str, key: str) -> str:
    match = re.search(r"^" + re.escape(key) + r":\s*([^\r\n]+)", text, re.MULTILINE)
    if not match:
        raise ValueError(f"plugin.yml: missing {key}")
    return match.group(1).strip().strip("\"'")


def expected_plugins(root: Path) -> set[str]:
    modules = re.findall(r"^include\s+['\"]([^'\"]+)['\"]", (root / "settings.gradle").read_text(), re.MULTILINE)
    pack_modules = [m for m in modules if m not in STANDALONE_MODULES]
    resources = [root / "src/main/resources/plugin.yml"]
    resources += [root / module / "src/main/resources/plugin.yml" for module in pack_modules]
    names = [scalar(p.read_text(encoding="utf-8"), "name") for p in resources]
    if len(names) != len(set(names)):
        raise ValueError("duplicate plugin name in source modules")
    return set(names)


def standalone_plugins(root: Path) -> dict[str, str]:
    modules = re.findall(r"^include\s+['\"]([^'\"]+)['\"]", (root / "settings.gradle").read_text(), re.MULTILINE)
    result: dict[str, str] = {}
    for module in modules:
        if module not in STANDALONE_MODULES:
            continue
        descriptor = (root / module / "src/main/resources/plugin.yml").read_text(encoding="utf-8")
        name = scalar(descriptor, "name")
        if name in result:
            raise ValueError(f"duplicate standalone plugin name {name}")
        result[name] = module
    return result


def verify_standalone_jar(jar: Path, expected_name: str, version: str) -> str:
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-rc\.[1-9]\d*)?", version):
        raise ValueError("standalone JAR requires a stable or numbered rc version")
    if not jar.is_file() or jar.is_symlink():
        raise ValueError(f"{jar}: missing or symlink")
    with zipfile.ZipFile(jar) as archive:
        bad = archive.testzip()
        if bad:
            raise ValueError(f"{jar.name}: invalid CRC for {bad}")
        if archive.namelist().count("plugin.yml") != 1:
            raise ValueError(f"{jar.name}: expected exactly one plugin.yml")
        descriptor = archive.read("plugin.yml").decode("utf-8")
        name = scalar(descriptor, "name")
        found_version = scalar(descriptor, "version")
        if name != expected_name:
            raise ValueError(f"{jar.name}: name {name}, expected {expected_name}")
        if found_version != version:
            raise ValueError(f"{jar.name}: version {found_version}, expected {version}")
    return jar.name


def verify(directory: Path, version: str, expected: set[str]) -> dict[str, str]:
    if not expected or not re.fullmatch(r"\d+\.\d+\.\d+(?:-rc\.[1-9]\d*)?", version):
        raise ValueError("release requires a non-empty plugin set and a stable or numbered rc version")
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
    if len(sys.argv) == 5 and sys.argv[1] == "--standalone":
        jar_name = verify_standalone_jar(Path(sys.argv[2]), sys.argv[3], sys.argv[4])
        print(f"Standalone plugin verified: {sys.argv[3]} ({jar_name}), version {sys.argv[4]}")
        raise SystemExit(0)
    if len(sys.argv) != 3:
        raise SystemExit("Usage: verify_release.py <directory> <version> | --standalone <jar> <plugin_name> <version>")
    root = Path(__file__).resolve().parents[2]
    plugins = verify(Path(sys.argv[1]), sys.argv[2], expected_plugins(root))
    print(f"Release verified: {len(plugins)} plugins, version {sys.argv[2]}, update memo present")
    for name, jar in sorted(plugins.items()):
        print(f"  {name}: {jar}")
