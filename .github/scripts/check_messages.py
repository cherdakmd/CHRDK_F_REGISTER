#!/usr/bin/env python3
"""Контракт «код ↔ messages.yml» для всех модулей репозитория.

Проверки (сборка падает при нарушении):
  1. каждый литеральный ключ в вызовах messages.send/get/raw/... существует в
     resources/messages.yml своего модуля (с учётом разворота обёртки, как в
     Messages.flatten ядра);
  2. каждый динамический префикс вида raw("art." + ...) покрыт хотя бы одним ключом;

Дополнительно печатает (не валит сборку):
  • вероятно мёртвые ключи messages.yml;
  • вызовы с ключом-переменной (проверить вручную при правках).

Запуск: python3 .github/scripts/check_messages.py
"""

from __future__ import annotations

import glob
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# ---------------------------------------------------------------------------
# Парсер messages.yml (терпит кавычки в ключах, списки и секции)
# ---------------------------------------------------------------------------

KEY_LINE = re.compile(r'^(\s*)(?:"([^"]+)"|([A-Za-z0-9_.-]+)):(?:\s+(.*))?$')


def parse_keys(path: str) -> tuple[set[str], list[str]]:
    """Ключи (соединённые точкой) + имена верхнего уровня файла.

    Ключ без значения в той же строке — это либо секция, либо начало списка:
    записываем и сам ключ (со списком он существует как значение), и продолжаем
    вложенность строками дочерних ключей.
    """
    keys: set[str] = set()
    stack: list[str] = []
    top_level: list[str] = []

    with open(path, encoding="utf-8") as handle:
        for raw in handle:
            line = raw.rstrip("\n")
            stripped = line.strip()
            if not stripped or stripped.startswith("#") or stripped == "---":
                continue
            if stripped.startswith("- ") or stripped == "-":
                continue
            match = KEY_LINE.match(line)
            if not match:
                continue
            indent = len(line) - len(line.lstrip(" "))
            key = match.group(2) or match.group(3)
            if key is None:
                continue
            has_value = match.group(4) not in (None, "")
            level = indent // 2
            while len(stack) > level:
                stack.pop()
            if level == 0 and key not in top_level:
                top_level.append(key)
            full = ".".join(stack[:level] + [key])
            keys.add(full)
            stack = stack[:level] + [key]
    return keys, top_level


def flatten_like_core(keys: set[str], top_level: list[str]) -> set[str]:
    """Имитация Messages.flatten: одиночная верхняя секция разворачивается."""
    if len(top_level) != 1:
        return keys
    prefix = top_level[0] + "."
    return {k[len(prefix):] if k.startswith(prefix) else k for k in keys}


# ---------------------------------------------------------------------------
# Литералы и префиксы в коде модуля
# ---------------------------------------------------------------------------

# messages().send(player, "key") / messages().get("key") / raw("key", ...) —
# ключ первым или (для send/sendList) вторым аргументом; «ключ + хвост» (» + ")
# не считается литералом — это динамический префикс.
CALL = re.compile(
    r'\.(send|sendList|get|getList|raw|mini|plain|has)\s*\(\s*'
    r'((?:[\w().]+\s*,\s*)?)"([a-z][a-z0-9.\-]*)"(?!\s*\+)'
)
FOREIGN_CALL = re.compile(r'\b(?!plugin\b)[a-z]\w*\.messages\(\)')
DYNAMIC = re.compile(r'(?:raw|mini|getList|get|send)\s*\([^)]*"([a-z][a-z0-9\-]*(?:\.[a-z0-9\-]*)*\.)"\s*\+')
VARIABLE_KEY = re.compile(r'\.(?:send|sendList|get|getList|raw|plain|has)\s*\(\s*(?:[\w().]+\s*,\s*)?(?![\w().]*")[a-zA-Z]\w*')


def literals_in(line: str) -> set[str]:
    found = set()
    for method, first_arg, literal in CALL.findall(line):
        # getList(loreKey, "day", ...) — «ключ» после переменной: принимаем только
        # точечные имена (плейсхолдеры короткие и без точек). Для send первый
        # аргумент — адресат, его пропуск не настораживает.
        if first_arg and method not in ("send", "sendList") and "." not in literal:
            continue
        found.add(literal)
    return found


def scan_module(module: str) -> list[str]:
    errors = []
    yml = os.path.join(module, "src/main/resources/messages.yml")
    keys_raw, top_level = parse_keys(yml)
    keys = flatten_like_core(keys_raw, top_level)

    literals: set[str] = set()
    dynamics: set[str] = set()
    variables: set[str] = set()
    for java in glob.glob(os.path.join(module, "src/main/java/**/*.java"), recursive=True):
        with open(java, encoding="utf-8") as handle:
            for line in handle:
                if "messages" not in line:
                    continue
                if FOREIGN_CALL.search(line):
                    # messages другого плагина — не наш контракт
                    for found in VARIABLE_KEY.findall(line):
                        pass
                    continue
                literals.update(literals_in(line))
                dynamics.update(DYNAMIC.findall(line))
                if VARIABLE_KEY.search(line) and not literals_in(line):
                    variables.add(line.strip()[:80])

    missing = sorted(k for k in literals if k not in keys)
    uncovered = sorted(p for p in dynamics if not any(k.startswith(p) for k in keys))
    def is_parent(key: str) -> bool:
        return any(other != key and other.startswith(key + ".") for other in keys)

    dead = sorted(
        k for k in keys
        if not is_parent(k) and k not in literals
        and not any(k.startswith(p) for p in dynamics)
    )
    name = os.path.basename(module) or "core"
    for key in missing:
        errors.append(f"[{name}] ключа «{key}» нет в messages.yml")
    for prefix in uncovered:
        errors.append(f"[{name}] префикс «{prefix}» не покрыт ни одним ключом messages.yml")
    if dead:
        print(f"[{name}] → мёртвых ключей: {len(dead)} (например: {dead[:5]})")
    if variables:
        for snippet in sorted(variables):
            print(f"[{name}] → ключ переменной (ручная сверка): {snippet}")
    print(
        f"[{name}] ключей в yml: {len(keys)}, литералов в коде: {len(literals)}, "
        f"динамических префиксов: {len(dynamics)} — {'OK' if not missing and not uncovered else 'FAIL'}"
    )
    return errors


# ---------------------------------------------------------------------------
# plugin.yml: «голый» скаляр с двоеточием — невалидный YAML, плагин не грузится.
# (В 1.4.19 и раньше 6 аддонов молча отваливались на сервере: description вида
# «Телепортация CHRDK: …» без кавычек ломал SnakeYAML.)
# ---------------------------------------------------------------------------

# key: <значение> — значение не в кавычках/не flow/не блочное
PLAIN_VALUE = re.compile(r"^\s*[A-Za-z0-9_.-]+:\s+([^\s\"'\[\{>|&*!%@`][^#\n]*\S)\s*$")


def scan_plugin_yml(module: str) -> list[str]:
    errors = []
    yml = os.path.join(module, "src/main/resources/plugin.yml")
    if not os.path.exists(yml):
        return errors
    name = os.path.basename(module) or "core"
    with open(yml, encoding="utf-8") as handle:
        for lineno, line in enumerate(handle, 1):
            match = PLAIN_VALUE.match(line.rstrip("\n"))
            if match and ": " in match.group(1):
                errors.append(
                    f"[{name}] plugin.yml:{lineno}: скаляр с двоеточием без кавычек: "
                    f"{line.strip()[:80]}"
                )
    print(f"[{name}] plugin.yml: двоеточий без кавычек {len(errors)} — {'OK' if not errors else 'FAIL'}")
    return errors


# ---------------------------------------------------------------------------
# «Glint-contract»: чтение состояния X без hasX() — краш 1.4.24 класса.
# На API 1.21.5+ геттеры компонентов (`getEnchantmentGlintOverride`,
# `getCustomModelData` и т.п.) падают, если компонент не задан. Правило:
# в пределах ±3 строк до вызова getX() обязан быть hasX().
# ---------------------------------------------------------------------------

GLINT_GETTER = re.compile(r"\.get(EnchantmentGlintOverride)\s*\(\s*\)")
GLINT_HAS = re.compile(r"\.has(EnchantmentGlintOverride)\s*\(\s*\)")


def scan_glint_contract(module: str) -> list[str]:
    errors = []
    name = os.path.basename(module) or "core"
    sources = glob.glob(os.path.join(module, "**/*.java"), recursive=True)
    checked = 0
    for path in sources:
        if "/build/" in path or "/target/" in path:
            continue
        with open(path, encoding="utf-8") as handle:
            lines = handle.readlines()
        for idx, line in enumerate(lines):
            if not GLINT_GETTER.search(line):
                continue
            window = lines[max(0, idx - 3): idx + 1]
            before_guard = any(GLINT_HAS.search(prev) for prev in window[:-1])
            after_guard = GLINT_HAS.search(lines[idx + 1]) if idx + 1 < len(lines) else None
            next_lines_guard = False
            for follow in lines[idx + 1: idx + 4]:
                if GLINT_HAS.search(follow):
                    next_lines_guard = True
                    break
            if before_guard or after_guard or next_lines_guard or "hasEnchantmentGlintOverride" in line:
                continue
            checked += 1
            errors.append(
                f"[{name}] {os.path.relpath(path, module)}:{idx + 1}: "
                f"getX() без hasX() в окрестности ({line.strip()[:80]})"
            )
    if checked:
        print(f"[{name}] glint-contract: нарушений {checked} — FAIL")
    return errors


def main() -> int:
    modules = [REPO]
    for entry in sorted(os.listdir(REPO)):
        path = os.path.join(REPO, entry)
        if os.path.isdir(path) and os.path.exists(
            os.path.join(path, "src/main/resources/messages.yml")
        ):
            modules.append(path)

    all_errors: list[str] = []
    for module in modules:
        all_errors.extend(scan_module(module))
        all_errors.extend(scan_plugin_yml(module))
        all_errors.extend(scan_glint_contract(module))
    # plugin.yml может лежать в модуле без messages.yml (или в ядре): добираем остальные
    for entry in sorted(os.listdir(REPO)):
        path = os.path.join(REPO, entry)
        if os.path.isdir(path) and path not in modules \
                and os.path.exists(os.path.join(path, "src/main/resources/plugin.yml")):
            all_errors.extend(scan_plugin_yml(path))

    if all_errors:
        print("\nКонтракт сообщений нарушен:")
        for error in all_errors:
            print(" - " + error)
        return 1
    print("\nКонтракт сообщений выполнен во всех модулях.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
