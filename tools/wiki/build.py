#!/usr/bin/env python3
"""Сборка вики CHRDK в статический сайт для GitHub Pages.

Источник — `wiki/*.md` с front matter, навигация — `wiki/config.yml`.
Результат — `site/`: готовые HTML-страницы, стили, поиск и карта сайта.

Запуск:
    python3 tools/wiki/build.py            # собрать сайт
    python3 tools/wiki/build.py --check    # собрать и проверить ссылки/навигацию
"""

from __future__ import annotations

import argparse
import html
import json
import re
import shutil
import sys
from datetime import date
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - подсказка для локального запуска
    sys.exit("Нужен PyYAML: python3 -m pip install pyyaml")

ROOT = Path(__file__).resolve().parents[2]
WIKI_DIR = ROOT / "wiki"
OUT_DIR = ROOT / "site"
ASSET_DIR = ROOT / "tools" / "wiki" / "assets"
CONFIG_PATH = WIKI_DIR / "config.yml"

TRANSLIT = {
    "а": "a", "б": "b", "в": "v", "г": "g", "д": "d", "е": "e", "ё": "e",
    "ж": "zh", "з": "z", "и": "i", "й": "j", "к": "k", "л": "l", "м": "m",
    "н": "n", "о": "o", "п": "p", "р": "r", "с": "s", "т": "t", "у": "u",
    "ф": "f", "х": "h", "ц": "c", "ч": "ch", "ш": "sh", "щ": "shch", "ъ": "",
    "ы": "y", "ь": "", "э": "e", "ю": "ju", "я": "ja",
}

ICONS = {
    "compass": '<path d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Zm3.5 6.5-2.2 5.3-5.3 2.2 2.2-5.3 5.3-2.2Z"/>',
    "coins": '<ellipse cx="9" cy="7" rx="5" ry="3"/><path d="M4 7v4c0 1.7 2.2 3 5 3s5-1.3 5-3V7"/><path d="M4 11v4c0 1.7 2.2 3 5 3 1 0 2-.2 2.7-.5"/><ellipse cx="16" cy="15" rx="4" ry="2.4"/><path d="M12 15v3c0 1.3 1.8 2.4 4 2.4s4-1.1 4-2.4v-3"/>',
    "crown": '<path d="m3 7 4 3 5-6 5 6 4-3-2 12H5L3 7Z"/><path d="M5 21h14"/>',
    "map": '<path d="m3 6 6-2 6 2 6-2v14l-6 2-6-2-6 2V6Z"/><path d="M9 4v14M15 6v14"/>',
    "chart": '<path d="M4 20V4M4 20h16"/><path d="m7 15 4-5 3 3 5-7"/>',
    "sword": '<path d="m14 4 6-2-2 6L8 18l-4 2 2-4L16 6"/><path d="m5 15 4 4"/>',
    "shield": '<path d="M12 3 5 6v5c0 4.4 3 8.4 7 10 4-1.6 7-5.6 7-10V6l-7-3Z"/><path d="m9 12 2 2 4-5"/>',
    "users": '<circle cx="9" cy="8" r="3.2"/><path d="M3 20c0-3.3 2.7-5.5 6-5.5s6 2.2 6 5.5"/><circle cx="17" cy="9" r="2.6"/><path d="M16 14.6c2.8.2 5 2.2 5 5.4"/>',
    "book": '<path d="M4 19.5V5a2 2 0 0 1 2-2h13v18H6.5A2.5 2.5 0 0 0 4 21"/><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H19"/>',
}

BADGE = {
    "start": "Старт",
    "economy": "Алмазы",
    "ranks": "Ранги",
    "world": "Мир",
    "progress": "Прогресс",
    "adventure": "Приключения",
    "gear": "Снаряжение",
    "social": "Люди",
}


# ------------------------------------------------------------------ разбор файлов


def read_page(path: Path) -> tuple[dict, str]:
    text = path.read_text(encoding="utf-8")
    meta: dict[str, str] = {}
    body = text
    if text.startswith("---\n"):
        end = text.find("\n---\n", 4)
        if end == -1:
            raise ValueError(f"{path}: незакрытый front matter")
        for line in text[4:end].splitlines():
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            key, sep, value = line.partition(":")
            if not sep:
                raise ValueError(f"{path}: строка front matter без ':' — {line!r}")
            meta[key.strip()] = value.strip().strip('"').strip("'")
        body = text[end + 5:]
    if "title" not in meta:
        raise ValueError(f"{path}: во front matter нет title")
    if "description" not in meta:
        raise ValueError(f"{path}: во front matter нет description")
    return meta, body


def property_value(key: str, fallback: str = "") -> str:
    props = ROOT / "gradle.properties"
    if not props.exists():
        return fallback
    for line in props.read_text(encoding="utf-8").splitlines():
        if line.startswith(f"{key}="):
            return line.split("=", 1)[1].strip()
    return fallback


def platform() -> str:
    minecraft = property_value("minecraft_version", "26.3")
    build = ROOT / "build.gradle"
    java = "25"
    if build.exists():
        match = re.search(r"sourceCompatibility\s*=\s*JavaVersion\.VERSION_(\d+)", build.read_text(encoding="utf-8"))
        if match:
            java = match.group(1)
    return f"Purpur {minecraft} · Java {java}"


def addon_count() -> int:
    settings = ROOT / "settings.gradle"
    if not settings.exists():
        return 29
    return len(re.findall(r"^\s*include\s+'([^']+)'", settings.read_text(encoding="utf-8"), re.MULTILINE)) or 29


def version() -> str:
    props = ROOT / "gradle.properties"
    if props.exists():
        for line in props.read_text(encoding="utf-8").splitlines():
            if line.startswith("plugin_version="):
                return line.split("=", 1)[1].strip()
    return "dev"


# ------------------------------------------------------------------ markdown


def slugify(text: str) -> str:
    text = re.sub(r"`([^`]*)`", r"\1", text)
    text = re.sub(r"[*_\[\]()]", "", text)
    out = "".join(TRANSLIT.get(ch, ch) for ch in text.lower())
    out = re.sub(r"[^a-z0-9]+", "-", out).strip("-")
    return out or "section"


class Renderer:
    """Минимальный markdown-конвертер под разметку вики."""

    def __init__(self, slug: str, known_pages: set[str]):
        self.slug = slug
        self.known_pages = known_pages
        self.anchors: dict[str, int] = {}
        self.toc: list[tuple[str, str]] = []

    def anchor(self, text: str) -> str:
        base = slugify(text)
        count = self.anchors.get(base, 0) + 1
        self.anchors[base] = count
        return base if count == 1 else f"{base}-{count}"

    def link(self, label: str, target: str) -> str:
        target = target.strip()
        external = bool(re.match(r"^[a-z]+://", target)) or target.startswith("mailto:")
        if not external and target.endswith(".md"):
            target = target[:-3] + ".html"
        if not external and "#" in target:
            page, _, anchor = target.partition("#")
            if page and page.endswith(".md"):
                page = page[:-3] + ".html"
            target = f"{page}#{anchor}"
        attrs = ' target="_blank" rel="noopener"' if external else ""
        return f'<a href="{html.escape(target, quote=True)}"{attrs}>{label}</a>'

    def inline(self, text: str) -> str:
        codes: list[str] = []

        def stash_code(match: re.Match) -> str:
            codes.append(html.escape(match.group(1)))
            return f"\x00{len(codes) - 1}\x00"

        text = re.sub(r"`([^`]+)`", stash_code, text)
        text = html.escape(text)

        def make_link(match: re.Match) -> str:
            return self.link(match.group(1), match.group(2))

        text = re.sub(r"\[([^\]]+)\]\(([^)\s]+)\)", make_link, text)
        text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
        text = re.sub(r"(?<![\w*])\*([^*\n]+)\*(?![\w*])", r"<em>\1</em>", text)
        text = re.sub(r"~~([^~]+)~~", r"<del>\1</del>", text)
        text = re.sub(r"(?<![\w_])(?:https?://[^\s<]+)", lambda m: self.link(m.group(0), m.group(0)), text)

        def unstash(match: re.Match) -> str:
            return f"<code>{codes[int(match.group(1))]}</code>"

        return re.sub(r"\x00(\d+)\x00", unstash, text)

    @staticmethod
    def table(rows: list[str]) -> str:
        def cells(line: str) -> list[str]:
            return [cell.strip() for cell in line.strip().strip("|").split("|")]

        header = cells(rows[0])
        aligns: list[str] = []
        for raw in cells(rows[1]):
            left, right = raw.startswith(":"), raw.endswith(":")
            aligns.append("center" if left and right else "right" if right else "left" if left else "")
        body = [cells(row) for row in rows[2:]]
        out = ['<div class="table-wrap"><table><thead><tr>']
        for index, cell in enumerate(header):
            style = f' class="al-{aligns[index]}"' if index < len(aligns) and aligns[index] else ""
            out.append(f"<th{style}>{cell}</th>")
        out.append("</tr></thead><tbody>")
        for row in body:
            out.append("<tr>")
            for index, cell in enumerate(row):
                style = f' class="al-{aligns[index]}"' if index < len(aligns) and aligns[index] else ""
                out.append(f"<td{style}>{cell}</td>")
            out.append("</tr>")
        out.append("</tbody></table></div>")
        return "".join(out)

    def render(self, body: str) -> str:
        lines = body.split("\n")
        out: list[str] = []
        index = 0
        total = len(lines)
        while index < total:
            line = lines[index]
            stripped = line.strip()
            if not stripped:
                index += 1
                continue

            if stripped.startswith("```"):
                language = stripped[3:].strip()
                index += 1
                buffer: list[str] = []
                while index < total and not lines[index].strip().startswith("```"):
                    buffer.append(lines[index])
                    index += 1
                index += 1
                css = f' class="language-{html.escape(language)}"' if language else ""
                out.append(f"<pre><code{css}>{html.escape(chr(10).join(buffer))}</code></pre>")
                continue

            if stripped in {"---", "***"}:
                out.append("<hr>")
                index += 1
                continue

            heading = re.match(r"^(#{1,4})\s+(.*)$", stripped)
            if heading:
                level = len(heading.group(1))
                text = self.inline(heading.group(2))
                if level == 1:
                    out.append(f"<h1>{text}</h1>")
                else:
                    anchor = self.anchor(heading.group(2))
                    out.append(
                        f'<h{level} id="{anchor}">'
                        f'<a class="anchor" href="#{anchor}" aria-hidden="true">#</a>{text}'
                        f"</h{level}>"
                    )
                    if level == 2:
                        self.toc.append((anchor, heading.group(2)))
                index += 1
                continue

            if stripped.startswith("|"):
                rows: list[str] = []
                while index < total and lines[index].strip().startswith("|"):
                    rows.append(lines[index].strip())
                    index += 1
                if len(rows) >= 2 and re.match(r"^\|[\s:|-]+\|?$", rows[1]):
                    prepared = [rows[0]] + [rows[1]] + [self.inline_row(row) for row in rows[2:]]
                    out.append(self.table(prepared))
                else:
                    out.append("<pre><code>" + html.escape("\n".join(rows)) + "</code></pre>")
                continue

            if stripped.startswith(">"):
                buffer = []
                while index < total and lines[index].strip().startswith(">"):
                    buffer.append(lines[index].strip()[1:].strip())
                    index += 1
                inner = self.inline(" ".join(part for part in buffer if part))
                out.append(f'<blockquote><p>{inner}</p></blockquote>')
                continue

            list_item = re.match(r"^[-*]\s+(.*)$", stripped)
            ordered_item = re.match(r"^\d+\.\s+(.*)$", stripped)
            if list_item or ordered_item:
                ordered = ordered_item is not None
                marker = r"^\d+\.\s+(.*)$" if ordered else r"^[-*]\s+(.*)$"
                items: list[str] = []
                while index < total:
                    current = lines[index].strip()
                    match = re.match(marker, current)
                    if not match:
                        break
                    items.append(match.group(1).strip())
                    index += 1
                    # Свёрнутая строка продолжает текущий пункт, а не начинает новый.
                    while index < total:
                        following = lines[index].strip()
                        if not following:
                            break
                        if (
                            following.startswith(("```", "|", ">", "#"))
                            or following in {"---", "***"}
                            or re.match(r"^[-*]\s+", following)
                            or re.match(r"^\d+\.\s+", following)
                        ):
                            break
                        items[-1] = (items[-1] + " " + following).strip()
                        index += 1
                tag = "ol" if ordered else "ul"
                out.append(
                    f"<{tag}>"
                    + "".join(f"<li>{self.inline(item)}</li>" for item in items)
                    + f"</{tag}>"
                )
                continue

            buffer = []
            while index < total:
                current = lines[index].strip()
                if not current:
                    break
                if (
                    current.startswith(("```", "|", ">", "#"))
                    or current in {"---", "***"}
                    or re.match(r"^[-*]\s+", current)
                    or re.match(r"^\d+\.\s+", current)
                ):
                    break
                buffer.append(current)
                index += 1
            if buffer:
                out.append("<p>" + self.inline(" ".join(buffer)) + "</p>")
            else:  # pragma: no cover - защита от зацикливания
                index += 1
        return "\n".join(out)

    def inline_row(self, row: str) -> str:
        inner = row.strip().strip("|")
        return "|" + "|".join(self.inline(cell.strip()) for cell in inner.split("|")) + "|"


# ------------------------------------------------------------------ шаблоны


def icon_svg(name: str, size: int = 20) -> str:
    path = ICONS.get(name, ICONS["book"])
    return (
        f'<svg class="icon" width="{size}" height="{size}" viewBox="0 0 24 24" '
        f'fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" '
        f'stroke-linejoin="round" aria-hidden="true">{path}</svg>'
    )


def nav_html(groups: list[dict], pages: dict[str, dict], current: str) -> str:
    out = ['<nav class="nav" aria-label="Разделы вики">']
    for group in groups:
        out.append('<div class="nav-group">')
        out.append(
            f'<p class="nav-title">{icon_svg(group["icon"], 16)}<span>{html.escape(group["title"])}</span></p>'
        )
        out.append("<ul>")
        for slug in group["pages"]:
            page = pages[slug]
            active = ' class="is-active" aria-current="page"' if slug == current else ""
            out.append(f'<li><a href="{slug}.html"{active}>{html.escape(page["meta"]["title"])}</a></li>')
        out.append("</ul></div>")
    out.append("</nav>")
    return "".join(out)


def group_cards_html(groups: list[dict], pages: dict[str, dict]) -> str:
    out = ['<section class="group-cards" aria-label="Разделы вики">']
    for group in groups:
        links = "".join(
            f'<li><a href="{slug}.html">{html.escape(pages[slug]["meta"]["title"])}</a></li>'
            for slug in group["pages"]
            if slug != "index"
        )
        out.append(
            f'<article class="group-card"><header>{icon_svg(group["icon"], 22)}'
            f'<h2><a href="{next((slug for slug in group["pages"] if slug != "index"), "index")}.html">{html.escape(group["title"])}</a></h2>'
            f'<span class="badge">{BADGE.get(group["id"], "Раздел")}</span></header>'
            f'<p>{html.escape(group.get("description", ""))}</p>'
            f"<ul>{links}</ul></article>"
        )
    out.append("</section>")
    return "".join(out)


def toc_html(toc: list[tuple[str, str]], title: str) -> str:
    if not toc:
        return ""
    links = "".join(
        f'<li><a href="#{anchor}">{html.escape(text)}</a></li>' for anchor, text in toc
    )
    return f'<aside class="toc"><p class="toc-title">{html.escape(title)}</p><ul>{links}</ul></aside>'


PAGE_TEMPLATE = """<!doctype html>
<html lang="ru" data-theme="dark">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title} · {site}</title>
<meta name="description" content="{description}">
<link rel="canonical" href="{canonical}">
<meta property="og:type" content="website">
<meta property="og:title" content="{title} · {site}">
<meta property="og:description" content="{description}">
<meta property="og:url" content="{canonical}">
<meta name="theme-color" content="#0b0d12">
<link rel="icon" href="assets/favicon.svg" type="image/svg+xml">
<link rel="stylesheet" href="assets/style.css">
<script>try{{var t=localStorage.getItem("chrdk-wiki-theme");if(t==="light"||t==="dark")document.documentElement.dataset.theme=t;}}catch(e){{}}</script>
</head>
<body>
<a class="skip-link" href="#main">Перейти к содержимому</a>
<header class="topbar">
  <button class="icon-btn menu-btn" id="menuBtn" aria-label="Открыть меню" aria-expanded="false">{menu_icon}</button>
  <a class="brand" href="index.html">
    <span class="brand-mark">{brand_icon}</span>
    <span class="brand-text"><strong>CHRDK</strong><small>вики сервера</small></span>
  </a>
  <button class="search-trigger" id="searchTrigger" aria-label="Поиск по вики">
    {search_icon}<span>Поиск по вики</span><kbd>Ctrl K</kbd>
  </button>
  <div class="topbar-actions">
    <span class="version" title="Версия плагинов">v{version}</span>
    <button class="icon-btn" id="themeBtn" aria-label="Сменить тему">☾</button>
    <a class="icon-btn" href="{github}" target="_blank" rel="noopener" aria-label="GitHub">{github_icon}</a>
  </div>
</header>
<div class="layout">
  <aside class="sidebar" id="sidebar">
    {nav}
    <div class="sidebar-footer">
      <a href="{releases}" target="_blank" rel="noopener">Обновления сервера ↗</a>
      <p>Сборка вики: {built}</p>
    </div>
  </aside>
  <main id="main" class="content">
    <nav class="crumbs" aria-label="Путь"><a href="index.html">Вики</a><span>/</span><span>{group}</span></nav>
    {hero}
    <article class="page">
      {heading}
      <p class="lead">{lead}</p>
      {body}
    </article>
    {extra}
    <nav class="pager" aria-label="Соседние страницы">{pager}</nav>
    <footer class="footer">
      <p>CHRDK · вики для игроков · версия {version}</p>
      <p><a href="{github}" target="_blank" rel="noopener">Исходники</a> · <a href="{releases}" target="_blank" rel="noopener">Релизы</a> · <a href="{url}">Сайт</a></p>
    </footer>
  </main>
  {toc}
</div>
<div class="search-modal" id="searchModal" hidden>
  <div class="search-box" role="dialog" aria-modal="true" aria-labelledby="searchTitle">
    <div class="search-head">
      <span id="searchTitle" class="search-title">Поиск</span>
      <input id="searchInput" type="search" placeholder="Ранги, клады, приваты…" autocomplete="off" spellcheck="false">
      <button class="icon-btn" id="searchClose" aria-label="Закрыть поиск">✕</button>
    </div>
    <div class="search-results" id="searchResults"><p class="search-empty">Начните вводить запрос.</p></div>
  </div>
</div>
<div class="toast" id="toast" role="status" aria-live="polite"></div>
<script src="assets/app.js" defer></script>
</body>
</html>
"""


SVG = {
    "menu": '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M4 7h16M4 12h16M4 17h16"/></svg>',
    "brand": '<svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"><path d="m12 2 7 4.5v9L12 22l-7-6.5v-9L12 2Z"/><path d="m5 6.5 7 4.2 7-4.2M12 10.7V22"/></svg>',
    "search": '<svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><circle cx="11" cy="11" r="6.5"/><path d="m16 16 4.5 4.5"/></svg>',
    "github": '<svg width="19" height="19" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2a10 10 0 0 0-3.16 19.49c.5.09.68-.22.68-.48v-1.7c-2.78.6-3.37-1.34-3.37-1.34-.45-1.16-1.1-1.47-1.1-1.47-.9-.62.07-.6.07-.6 1 .07 1.53 1.03 1.53 1.03.88 1.52 2.3 1.08 2.86.83.09-.65.35-1.09.63-1.34-2.22-.25-4.55-1.11-4.55-4.94 0-1.09.39-1.98 1.03-2.68-.1-.25-.45-1.27.1-2.65 0 0 .84-.27 2.75 1.02a9.6 9.6 0 0 1 5 0c1.91-1.29 2.75-1.02 2.75-1.02.55 1.38.2 2.4.1 2.65.64.7 1.03 1.59 1.03 2.68 0 3.84-2.34 4.68-4.57 4.93.36.31.68.92.68 1.85v2.74c0 .27.18.58.69.48A10 10 0 0 0 12 2Z"/></svg>',
}


def build(check: bool) -> int:
    config = yaml.safe_load(CONFIG_PATH.read_text(encoding="utf-8"))
    site = config["site"]
    groups = config["groups"]
    version_str = version()
    built = date.today().isoformat()

    files = sorted(WIKI_DIR.glob("*.md"))
    raw: dict[str, tuple[dict, str]] = {}
    for path in files:
        raw[path.stem] = read_page(path)

    ordered = [slug for group in groups for slug in group["pages"]]
    problems: list[str] = []

    for group in groups:
        for slug in group["pages"]:
            if slug not in raw:
                problems.append(f"навигация ссылается на отсутствующий файл wiki/{slug}.md")
    for slug in raw:
        if slug not in ordered:
            problems.append(f"страница wiki/{slug}.md не добавлена ни в один раздел config.yml")
    if len(ordered) != len(set(ordered)):
        problems.append("страница указана в навигации больше одного раза")

    pages: dict[str, dict] = {}
    group_of: dict[str, dict] = {}
    for group in groups:
        for slug in group["pages"]:
            group_of[slug] = group

    known = set(raw)
    OUT_DIR.mkdir(exist_ok=True)
    for old in OUT_DIR.glob("*.html"):
        old.unlink()

    search_index: list[dict] = []
    anchors_by_page: dict[str, set[str]] = {}

    for slug in ordered:
        if slug not in raw:
            continue
        meta, body = raw[slug]
        renderer = Renderer(slug, known)
        rendered = renderer.render(body)
        anchors_by_page[slug] = set(renderer.anchors)

        title = meta["title"]
        group = group_of[slug]
        is_home = slug == "index"

        if is_home:
            heading = f'<p class="eyebrow">Добро пожаловать</p><h1 class="hero-title">{html.escape(title)}</h1>'
            hero = (
                '<section class="hero">'
                '<div class="hero-glow" aria-hidden="true"></div>'
                f'<p class="hero-kicker">{html.escape(platform())} · {len(groups)} разделов</p>'
                '<h1>Всё о сервере <span>CHRDK</span> — по-русски</h1>'
                f'<p class="hero-sub">{html.escape(site["description"])}</p>'
                '<div class="hero-actions">'
                '<a class="btn btn-primary" href="start.html">Первые шаги</a>'
                '<a class="btn" href="commands.html">Все команды</a>'
                '<a class="btn btn-ghost" href="ranks.html">Ранги</a>'
                '</div>'
                '<div class="hero-stats">'
                f'<div><strong>{addon_count()}</strong><span>аддонов</span></div>'
                '<div><strong>5</strong><span>рангов</span></div>'
                '<div><strong>196</strong><span>находок в кладах</span></div>'
                '<div><strong>30</strong><span>кастомных чар</span></div>'
                '</div>'
                '</section>'
            )
            heading = ""
            lead = ""
            extra = group_cards_html(groups, {
                s: {"meta": raw[s][0]} for s in raw
            })
        else:
            eyebrow = BADGE.get(group["id"], group["title"])
            hero = ""
            heading = (
                f'<p class="eyebrow">{html.escape(group["title"])} · {html.escape(eyebrow)}</p>'
                f'<h1>{html.escape(title)}</h1>'
            )
            lead = meta["description"]
            extra = ""

        pager_links = []
        position = ordered.index(slug)
        if position > 0:
            prev = ordered[position - 1]
            pager_links.append(
                f'<a class="pager-prev" href="{prev}.html"><span>Назад</span>'
                f'<strong>{html.escape(raw[prev][0]["title"])}</strong></a>'
            )
        else:
            pager_links.append("<span></span>")
        if position + 1 < len(ordered):
            nxt = ordered[position + 1]
            pager_links.append(
                f'<a class="pager-next" href="{nxt}.html"><span>Дальше</span>'
                f'<strong>{html.escape(raw[nxt][0]["title"])}</strong></a>'
            )
        else:
            pager_links.append("<span></span>")

        page_html = PAGE_TEMPLATE.format(
            title=html.escape(title),
            site=html.escape(site["title"]),
            description=html.escape(meta["description"], quote=True),
            url=site["url"],
            canonical=site["url"] if slug == "index" else f'{site["url"]}{slug}.html',
            slug=slug,
            version=html.escape(version_str),
            github=site["github"],
            releases=site["releases"],
            built=built,
            menu_icon=SVG["menu"],
            brand_icon=SVG["brand"],
            search_icon=SVG["search"],
            github_icon=SVG["github"],
            nav=nav_html(groups, {s: {"meta": raw[s][0]} for s in raw}, slug),
            group=html.escape(group["title"]),
            hero=hero,
            heading=heading,
            lead=html.escape(meta["description"]),
            body=rendered,
            extra=extra,
            pager="".join(pager_links),
            toc=toc_html(renderer.toc, "На этой странице"),
        )
        (OUT_DIR / f"{slug}.html").write_text(page_html, encoding="utf-8")

        plain = re.sub(r"<[^>]+>", " ", rendered)
        plain = html.unescape(re.sub(r"\s+", " ", plain)).strip()
        search_index.append(
            {
                "title": title,
                "url": f"{slug}.html",
                "group": group["title"],
                "headings": [text for _, text in renderer.toc],
                "text": plain[:1600],
            }
        )

    # ------------------------------------------------------------------ ассеты
    assets_out = OUT_DIR / "assets"
    if assets_out.exists():
        shutil.rmtree(assets_out)
    shutil.copytree(ASSET_DIR, assets_out)
    (OUT_DIR / ".nojekyll").write_text("", encoding="utf-8")
    (OUT_DIR / "assets" / "version.txt").write_text(version_str, encoding="utf-8")
    (OUT_DIR / "search-index.json").write_text(
        json.dumps(search_index, ensure_ascii=False, separators=(",", ":")), encoding="utf-8"
    )

    urls = "".join(
        f"<url><loc>{site['url']}{slug}.html</loc><changefreq>weekly</changefreq></url>"
        for slug in ordered
    )
    (OUT_DIR / "sitemap.xml").write_text(
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
        f"{urls}</urlset>",
        encoding="utf-8",
    )
    (OUT_DIR / "robots.txt").write_text(
        f"User-agent: *\nAllow: /\nSitemap: {site['url']}sitemap.xml\n", encoding="utf-8"
    )

    # ------------------------------------------------------------------ проверки
    link_re = re.compile(r'href="([^"#][^"]*?\.html)(?:#([^"]*))?"')
    for slug in ordered:
        path = OUT_DIR / f"{slug}.html"
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8")
        for target, anchor in link_re.findall(text):
            if target.startswith(("http://", "https://", "mailto:")):
                continue
            if not (OUT_DIR / target).exists():
                problems.append(f"{slug}.html: ссылка на отсутствующую страницу {target}")
            elif anchor and target.endswith(".html"):
                target_slug = target[:-5]
                if anchor not in anchors_by_page.get(target_slug, set()):
                    problems.append(f"{slug}.html: нет якоря #{anchor} на {target}")
        page_ids = set(re.findall(r'id="([^"]+)"', text))
        for anchor in re.findall(r'href="#([^"]+)"', text):
            if anchor not in page_ids:
                problems.append(f"{slug}.html: нет якоря #{anchor} на этой странице")
        for required in ("assets/style.css", "assets/app.js"):
            if required not in text:
                problems.append(f"{slug}.html: не подключён {required}")

    if problems:
        print("Проверка вики не прошла:", file=sys.stderr)
        for problem in problems:
            print("  •", problem, file=sys.stderr)
        return 1

    print(f"Готово: {len(ordered)} страниц → {OUT_DIR} (v{version_str})")
    if check:
        print("Ссылки, якоря и навигация в порядке.")
    return 0


def main() -> None:
    parser = argparse.ArgumentParser(description="Собрать вики CHRDK в site/")
    parser.add_argument("--check", action="store_true", help="проверить ссылки и навигацию")
    args = parser.parse_args()
    raise SystemExit(build(args.check))


if __name__ == "__main__":
    main()
