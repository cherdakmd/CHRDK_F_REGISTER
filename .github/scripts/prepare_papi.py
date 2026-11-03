#!/usr/bin/env python3
"""Pinned official PlaceholderAPI release for compileOnly CI, never a deployment input."""
from __future__ import annotations

import hashlib
import io
from pathlib import Path
import urllib.error
import urllib.request
import zipfile

VERSION = "2.12.3"
# Official PlaceholderAPI/PlaceholderAPI GitHub release asset 465403523, API digest.
URL = f"https://github.com/PlaceholderAPI/PlaceholderAPI/releases/download/{VERSION}/PlaceholderAPI-{VERSION}.jar"
SHA256 = "fde03259f5af6938f3c33eeb4d814000a1adabf1d2304ce14970be81f609a437"
MAX_BYTES = 4 * 1024 * 1024
RELATIVE_PATH = f"build/dependencies/PlaceholderAPI-{VERSION}.jar"


def verify(data: bytes, expected: str = SHA256) -> None:
    if len(data) > MAX_BYTES or hashlib.sha256(data).hexdigest() != expected:
        raise ValueError("PlaceholderAPI release digest/size mismatch; refusing build input")
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        required = {"me/clip/placeholderapi/PlaceholderAPI.class", "me/clip/placeholderapi/expansion/PlaceholderExpansion.class"}
        if not required.issubset(jar.namelist()):
            raise ValueError("Pinned artifact does not contain the expected PlaceholderAPI classes")


def prepare(root: Path, *, opener=urllib.request.urlopen, expected: str = SHA256) -> Path:
    destination = root / RELATIVE_PATH
    if destination.exists():
        if destination.stat().st_size > MAX_BYTES:
            raise ValueError("Oversized PlaceholderAPI build cache")
        verify(destination.read_bytes(), expected)
        return destination
    # Public HTTPS asset; no token, mutable latest tag, untrusted mirror or TLS bypass.
    with opener(URL, timeout=30) as response:
        data = response.read(MAX_BYTES + 1)
    verify(data, expected)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(".jar.tmp")
    try:
        temporary.write_bytes(data)
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)
    print(f"Verified official PlaceholderAPI {VERSION} compileOnly input, SHA256 {expected}")
    return destination


if __name__ == "__main__":
    prepare(Path.cwd())
