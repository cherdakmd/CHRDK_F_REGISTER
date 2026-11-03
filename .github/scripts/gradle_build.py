#!/usr/bin/env python3
"""Run Gradle; retry only proven transient dependency transport failures, never tests/compiler errors."""
from __future__ import annotations

import os
from pathlib import Path
import re
import subprocess
import sys
import time

DEPENDENCY = re.compile(r"Could not (?:resolve|download|get resource|GET|HEAD)\b", re.I)
TRANSIENT = re.compile(
    r"(?:Read|Connect(?:ion)?) timed? out|(?:Read|Connect(?:ion)?) timeout|Connection reset|"
    r"Premature end of Content-Length|Unexpected end of file from server|"
    r"Received status code (?:429|502|503|504)\b", re.I
)
HARD_FAILURE = re.compile(
    r"\berror:|There were failing tests|\b\d+ tests? completed,\s*\d+ failed|"
    r"Compilation failed|OutOfMemoryError|Could not find [^\n]+|"
    r"Received status code (?:400|401|403|404)\b|PKIX path building failed|"
    r"Task .* > .* FAILED", re.I
)


def retryable(text: str) -> bool:
    """All Gradle failure sections must be dependency+transport failures; unknown means stop."""
    if HARD_FAILURE.search(text):
        return False
    sections = re.findall(r"\* What went wrong:\s*(.*?)(?=\n\* Try:|\Z)", text, re.S)
    if not sections:
        return False
    # --continue can report several task errors inside a single failure section.
    for section in sections:
        tasks = re.split(r"(?=Execution failed for task )", section)
        for block in tasks:
            if not block.strip():
                continue
            if not DEPENDENCY.search(block) or not TRANSIENT.search(block):
                return False
    return True


def execute(command: list[str], root: Path, log: Path) -> int:
    with log.open("w", encoding="utf-8") as output:
        with subprocess.Popen(command, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, encoding="utf-8", errors="replace") as process:
            assert process.stdout is not None
            for line in process.stdout:
                output.write(line)
                print(line, end="", flush=True)
            return process.wait()


def build(root: Path, command: list[str], *, attempts: int = 3, delay: int = 15,
          runner=execute, sleeper=time.sleep) -> int:
    if not 1 <= attempts <= 3 or not 0 <= delay <= 60:
        raise ValueError("Retry budget outside allowed bounds")
    for attempt in range(1, attempts + 1):
        log = root / f"build-attempt-{attempt}.log"
        code = runner(command, root, log)
        text = log.read_text(encoding="utf-8", errors="replace")
        (root / "build.log").write_text(text, encoding="utf-8")
        if code == 0:
            return 0
        if code < 0 or attempt == attempts or not retryable(text):
            return code
        message = f"Transient dependency download failure; retry {attempt + 1}/{attempts} after {delay}s. Tests/compiler errors are not retried."
        print(f"::warning title=Dependency transport::{message}", flush=True)
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary:
            with open(summary, "a", encoding="utf-8") as out:
                out.write(f"\n- {message}\n")
        sleeper(delay)
    raise AssertionError("Unreachable")


def main() -> int:
    from prepare_papi import prepare, RELATIVE_PATH
    root=Path.cwd()
    prepare(root)
    return build(root, ["./gradlew", "--no-daemon", f"-Pchrdk.papiJar={RELATIVE_PATH}", "build", "--continue"])


if __name__ == "__main__":
    sys.exit(main())
