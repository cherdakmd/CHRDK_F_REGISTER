#!/usr/bin/env python3
"""Resolve and validate an explicit, versioned release artifact manifest."""
from __future__ import annotations

import json
from pathlib import Path
import re
import sys

from verify_release import expected_plugins, scalar, standalone_plugins

VERSION_PATTERN = re.compile(r"\d+\.\d+(?:\.\d+)?(?:-rc\.[1-9]\d*|-fix)?\Z")
MODULE_PATTERN = re.compile(r"[a-z][a-z0-9-]{0,47}\Z")
ARCHIVE_PATTERN = re.compile(r"chrdk-[a-z0-9]+(?:-[a-z0-9]+)*\Z")


def archive_name(root: Path, module: str) -> str:
    if module == "core":
        properties = (root / "gradle.properties").read_text(encoding="utf-8")
        match = re.search(r"^archives_base_name=([^\r\n]+)$", properties, re.MULTILINE)
        if not match:
            raise ValueError("gradle.properties: missing archives_base_name")
        return match.group(1).strip()
    modules = re.findall(r"^include\s+['\"]([^'\"]+)['\"]", (root / "settings.gradle").read_text(encoding="utf-8"), re.MULTILINE)
    if module not in modules:
        raise ValueError(f"unknown Gradle module: {module}")
    build = root / module / "build.gradle"
    if not build.is_file():
        raise ValueError(f"missing Gradle build file for {module}")
    text = build.read_text(encoding="utf-8")
    match = re.search(r"archivesName\s*=\s*['\"]([^'\"]+)['\"]", text)
    if not match:
        raise ValueError(f"{module}/build.gradle: missing fixed archivesName")
    return match.group(1)


def load_spec(root: Path, version: str) -> dict[str, str | bool]:
    if not VERSION_PATTERN.fullmatch(version):
        raise ValueError("invalid release version")
    path = root / "docs/releases" / f"{version}.json"
    if not path.is_file() or path.is_symlink():
        raise ValueError(f"missing release manifest: {path.relative_to(root)}")
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (UnicodeError, json.JSONDecodeError) as error:
        raise ValueError(f"invalid release manifest JSON: {error}") from error
    if not isinstance(raw, dict) or raw.get("version") != version:
        raise ValueError("manifest version does not match release version")
    kind = raw.get("kind")
    prerelease = raw.get("prerelease")
    if not isinstance(prerelease, bool) or ("-rc." in version) != prerelease:
        raise ValueError("manifest prerelease flag does not match the version")
    if kind == "full":
        if set(raw) != {"version", "kind", "prerelease"}:
            raise ValueError("full release manifest must not select a partial module set")
        if not expected_plugins(root) or not standalone_plugins(root):
            raise ValueError("full release has no complete plugin set")
        return {"version": version, "kind": "full", "prerelease": prerelease}
    if kind != "standalone" or set(raw) != {"version", "kind", "prerelease", "module", "plugin", "archive"}:
        raise ValueError("manifest kind must be full or an exact standalone plugin selection")
    module, plugin, archive = raw["module"], raw["plugin"], raw["archive"]
    if not isinstance(module, str) or not (module == "core" or MODULE_PATTERN.fullmatch(module)):
        raise ValueError("invalid standalone module")
    if not isinstance(plugin, str) or not re.fullmatch(r"CHRDK_[A-Za-z0-9]+(?:_[A-Za-z0-9]+)*", plugin):
        raise ValueError("invalid standalone plugin name")
    if not isinstance(archive, str) or not ARCHIVE_PATTERN.fullmatch(archive):
        raise ValueError("invalid standalone archive name")
    expected_archive = archive_name(root, module)
    if archive != expected_archive:
        raise ValueError(f"manifest archive {archive} does not match Gradle archive {expected_archive}")
    descriptor_path = root / ("src/main/resources/plugin.yml" if module == "core" else f"{module}/src/main/resources/plugin.yml")
    if not descriptor_path.is_file():
        raise ValueError(f"missing plugin descriptor for {module}")
    descriptor = scalar(descriptor_path.read_text(encoding="utf-8"), "name")
    if descriptor != plugin:
        raise ValueError(f"manifest plugin {plugin} does not match {module} descriptor {descriptor}")
    source = f"build/libs/{archive}-{version}.jar" if module == "core" else f"{module}/build/libs/{archive}-{version}.jar"
    jar = f"{archive}-{version}.jar"
    return {
        "version": version,
        "kind": "standalone",
        "prerelease": prerelease,
        "module": module,
        "plugin": plugin,
        "source": source,
        "jar": jar,
        "checksums": f"{archive}-{version}-SHA256SUMS.txt",
    }


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: release_spec.py <version>")
    root = Path(__file__).resolve().parents[2]
    spec = load_spec(root, sys.argv[1])
    for key, value in spec.items():
        print(f"{key}={str(value).lower() if isinstance(value, bool) else value}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as error:
        raise SystemExit(f"Release manifest rejected: {error}") from error
