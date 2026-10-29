#!/usr/bin/env python3
"""Печатает ошибки JUnit-отчётов в аннотации GitHub Actions (видно в CI без скачивания артефактов)."""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    summary_lines = []
    failures = 0
    totals = {}
    for path in sorted(glob.glob("**/build/test-results/test/*.xml", recursive=True)):
        try:
            tree = ET.parse(path)
        except ET.ParseError as error:  # pragma: no cover - отчёт повреждён
            print(f"::error title=JUnit XML::{path}: {error}")
            continue
        module = "Core" if path.startswith("build/") else path.split("/")[0]
        totals[module] = totals.get(module, 0) + sum(1 for _ in tree.iter("testcase"))
        for case in tree.iter("testcase"):
            problems = list(case.iter("failure")) + list(case.iter("error"))
            if not problems:
                continue
            failures += 1
            name = f"{case.get('classname', '?')}.{case.get('name', '?')}"
            for problem in problems:
                message = " ".join((problem.get("message") or problem.text or "").split())[:600]
                print(f"::error title={name}::{message}")
            text = " ".join((problems[0].get("message") or problems[0].text or "").split())[:800]
            summary_lines.append(f"* **{name}** — {text}")
    counts = "### Проверенные тесты\n\n" + "\n".join(f"- {module}: {count}" for module, count in sorted(totals.items())) + f"\n\nВсего: {sum(totals.values())}; модулей с отчётами: {len(totals)}; ошибок: {failures}.\n"
    print(counts)
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(counts)
    if summary_lines:
        summary_lines.insert(0, "### Ошибки тестов\n")
        block = "\n".join(summary_lines) + "\n"
        print(block)
        if summary:
            with open(summary, "a", encoding="utf-8") as handle:
                handle.write(block)
    else:
        print("Проваленных тестов в build/test-results не найдено.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
