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
    "а": "a",
    "б": "b",
    "в": "v",
    "г": "g",
    "д": "d",
    "е": "e",
    "ё": "e",
    "ж": "zh",
    "з": "z",
    "и": "i",
    "й": "j",
    "к": "k",
    "л": "l",
    "м": "m",
    "н": "n",
    "о": "o",
    "п": "p",
    "р": "r",
    "с": "s",
    "т": "t",
    "у": "u",
    "ф": "f",
    "х": "h",
    "ц": "c",
    "ч": "ch",
    "ш": "sh",
    "щ": "shch",
    "ъ": "",
    "ы": "y",
    "ь": "",
    "э": "e",
    "ю": "ju",
    "я": "ja",
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
        meta = yaml.safe_load(text[4:end])
        if not isinstance(meta, dict):
            raise ValueError(f"{path}: front matter должен быть объектом")
        body = text[end + 5 :]
    if "title" not in meta:
        raise ValueError(f"{path}: во front matter нет title")
    if "description" not in meta:
        raise ValueError(f"{path}: во front matter нет description")
    return meta, body


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
        if (
            target.startswith("//")
            or re.match(r"^[a-z][a-z0-9+.-]*:", target, re.I)
            and not target.startswith(("https://", "http://", "mailto:"))
        ):
            raise ValueError("Недопустимая ссылка: " + target)
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
        fragments = []

        def stash(value):
            fragments.append(value)
            return f"\x00{len(fragments)-1}\x00"

        text = re.sub(
            r"`([^`]+)`",
            lambda m: stash("<code>" + html.escape(m[1]) + "</code>"),
            text,
        )
        text = re.sub(
            r"\[([^\]]+)\]\(([^)\s]+)\)",
            lambda m: stash(self.link(html.escape(m[1]), m[2])),
            text,
        )
        text = html.escape(text)
        text = re.sub(
            r"(?<![\w_])https?://[^\s<]+",
            lambda m: stash(self.link(m[0], html.unescape(m[0]))),
            text,
        )
        text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
        text = re.sub(r"(?<![\w*])\*([^*\n]+)\*(?![\w*])", r"<em>\1</em>", text)
        return re.sub(r"\x00(\d+)\x00", lambda m: fragments[int(m[1])], text)

    @staticmethod
    def table(rows: list[str]) -> str:
        def cells(line: str) -> list[str]:
            return [
                cell.strip() for cell in re.split(r"(?<!\\)\|", line.strip().strip("|"))
            ]

        header = cells(rows[0])
        aligns: list[str] = []
        for raw in cells(rows[1]):
            left, right = raw.startswith(":"), raw.endswith(":")
            aligns.append(
                "center"
                if left and right
                else "right" if right else "left" if left else ""
            )
        body = [cells(row) for row in rows[2:]]
        out = ['<div class="table-wrap"><table><thead><tr>']
        for index, cell in enumerate(header):
            style = (
                f' class="al-{aligns[index]}"'
                if index < len(aligns) and aligns[index]
                else ""
            )
            out.append(f"<th{style}>{cell}</th>")
        out.append("</tr></thead><tbody>")
        for row in body:
            out.append("<tr>")
            for index, cell in enumerate(row):
                style = (
                    f' class="al-{aligns[index]}"'
                    if index < len(aligns) and aligns[index]
                    else ""
                )
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

            if stripped in {":::html", "::: raw"}:
                # Блок сырого HTML для витрин, шагов и аккордеонов:
                # открывается строкой :::html, закрывается строкой :::.
                index += 1
                block: list[str] = []
                while index < total and lines[index].strip() != ":::":
                    block.append(lines[index].rstrip())
                    index += 1
                index += 1  # закрывающая ::: (или конец файла)
                out.append("\n".join(block))
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
                out.append(
                    f"<pre><code{css}>{html.escape(chr(10).join(buffer))}</code></pre>"
                )
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
                        f'{text}<a class="anchor" href="#{anchor}" aria-label="Ссылка на раздел: {html.escape(heading.group(2),quote=True)}">#</a>'
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
                    prepared = (
                        [rows[0]]
                        + [rows[1]]
                        + [self.inline_row(row) for row in rows[2:]]
                    )
                    out.append(self.table(prepared))
                else:
                    out.append(
                        "<pre><code>" + html.escape("\n".join(rows)) + "</code></pre>"
                    )
                continue

            if stripped.startswith(">"):
                buffer = []
                while index < total and lines[index].strip().startswith(">"):
                    buffer.append(lines[index].strip()[1:].strip())
                    index += 1
                inner = self.inline(" ".join(part for part in buffer if part))
                out.append(f"<blockquote><p>{inner}</p></blockquote>")
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
                            following.startswith(("```", "|", ">", "#", "<"))
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
                    current.startswith(("```", "|", ">", "#", "<"))
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
        return (
            "|"
            + "|".join(
                self.inline(cell.strip().replace(r"\|", "|")).replace("|", "&#124;")
                for cell in re.split(r"(?<!\\)\|", inner)
            )
            + "|"
        )


# ------------------------------------------------------------------ страницы


def source_commit() -> str:
    import os, subprocess

    supplied = os.environ.get("GITHUB_SHA", "")
    if re.fullmatch(r"[0-9a-f]{40}", supplied):
        return supplied
    return subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
    ).strip()


def nav_html(groups, raw, current):
    out = ['<nav aria-label="Разделы справочника">']
    for group in groups:
        out.append(f'<section class="nav-group"><h2>{html.escape(group["title"])}</h2>')
        for slug in group["pages"]:
            active = ' aria-current="page"' if slug == current else ""
            out.append(
                f'<a href="{slug}.html"{active}>{html.escape(raw[slug][0]["title"])}</a>'
            )
        out.append("</section>")
    return "".join(out) + "</nav>"


def cards_html(groups, raw):
    out = ['<section class="chapter-grid" aria-label="Все разделы">']
    for i, g in enumerate(groups, 1):
        links = "".join(
            f'<a href="{slug}.html">{html.escape(raw[slug][0]["title"])} <span aria-hidden="true">↗</span></a>'
            for slug in g["pages"]
            if slug != "index"
        )
        out.append(
            f'<article class="chapter"><span class="chapter-num">{i:02d} /</span><h2>{html.escape(g["title"])}</h2><p>{html.escape(g["description"])}</p><div>{links}</div></article>'
        )
    return "".join(out) + "</section>"


def home_html(book, groups):
    return f"""<section class="hero"><div class="hero-copy"><p class="eyebrow">ПОЛЕВОЙ СПРАВОЧНИК · CHRDK</p>
<h1>Большой мир.<br>Понятные <em>правила.</em></h1>
<p>Построить дом, освоить ремесло, собрать отряд. Все системы сервера — от первого входа до собственной истории.</p>
<div class="hero-actions"><a class="button primary" href="start.html">Я здесь впервые <span aria-hidden="true">↗</span></a><a class="button" href="commands.html">Найти команду</a></div>
<div class="hero-stats"><div><strong>{book.value('plugins')}</strong><span>плагинов в исходниках</span></div><div><strong>{book.value('shop_count')}</strong><span>товаров в /shop</span></div><div><strong>{len(groups)}</strong><span>разделов справочника</span></div></div></div>
<div class="hero-art"><img src="assets/img/hero.jpg" width="1376" height="768" alt="Блочный мир CHRDK: остров, крепость и портал" fetchpriority="high"><div class="art-label"><span class="status-dot"></span> ВХОД БЕСПЛАТНЫЙ · ПАРОЛЬ ОБЯЗАТЕЛЕН</div></div></section>
<div class="field-note"><strong>Сверяемся с кодом, а не с памятью.</strong><span>Таблицы берутся из конфигов. Настройки вашего сервера могут отличаться. <a href="about.html">Как читать вики ↗</a></span></div>"""


PAGE_TEMPLATE = """<!doctype html>
<html lang="ru" data-theme="dark"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title} — CHRDK</title><meta name="description" content="{description}">
<link rel="canonical" href="{canonical}"><meta property="og:type" content="website">
<meta property="og:title" content="{title} — CHRDK"><meta property="og:description" content="{description}">
<meta property="og:url" content="{canonical}"><meta property="og:image" content="{url}assets/img/hero.jpg">
<meta property="og:locale" content="ru_RU"><meta name="theme-color" content="#111a17">
<link rel="icon" href="assets/favicon.svg" type="image/svg+xml"><link rel="stylesheet" href="assets/style.css?v={asset_revision}">
<script>try{{let t=localStorage.getItem('chrdk-wiki-theme');if(t==='light'||t==='dark')document.documentElement.dataset.theme=t}}catch(e){{}}</script>
<noscript><style>.sidebar{{position:static!important;transform:none!important;display:block!important}}.layout{{display:block!important}}.search-trigger,.menu-btn{{display:none!important}}</style></noscript>
</head><body data-revision="{commit}">
<a class="skip-link" href="#main">Перейти к содержимому</a>
<header class="topbar"><button class="icon-button menu-btn" id="menuBtn" aria-label="Открыть разделы" aria-controls="sidebar" aria-expanded="false"><svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="square"><path d="M4 6h16M4 12h16M4 18h16"/></svg></button>
<a class="brand" href="index.html"><span class="brand-mark" aria-hidden="true"><svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="square"><path d="m3 7 9-5 9 5v10l-9 5-9-5Z M3 7l9 5 9-5 M12 12v10"/></svg></span><span><strong>CHRDK</strong><small>ПОЛЕВОЙ СПРАВОЧНИК</small></span></a>
<button class="search-trigger" id="searchTrigger" aria-label="Поиск по справочнику"><svg class="search-icon" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="square"><circle cx="10" cy="10" r="6"/><path d="m15 15 6 6"/></svg><span>Поиск по миру и правилам</span><kbd>Ctrl K</kbd></button>
<div class="top-actions"><a class="version" href="updates.html" title="Версия исходников, не установленного сервера">v{version}</a><button class="icon-button" id="themeBtn" aria-label="Сменить тему"><svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="square"><circle cx="12" cy="12" r="8"/><path d="M12 4a8 8 0 0 1 0 16Z" fill="currentColor"/></svg></button><a class="github-link" href="{github}" target="_blank" rel="noopener">GitHub ↗</a></div></header>
<div class="layout"><aside class="sidebar" id="sidebar">{nav}<div class="nav-footer"><span class="status-dot"></span> Из исходников<br><a href="{github}/commit/{commit}">{short_commit}</a> · {built}</div></aside>
<main class="content" id="main"><nav class="breadcrumbs" aria-label="Путь"><a href="index.html">Справочник</a><span>/</span><span>{group}</span></nav>
{hero}<article class="page">{heading}<p class="lead">{lead}</p>{body}</article>{cards}
<nav class="pager" aria-label="Соседние страницы">{pager}</nav>
<section class="page-source"><strong>Основано на исходниках</strong><p>Стандартная поставка · {version} · настройки сервера могут отличаться.</p><a href="{github}/blob/{commit}/wiki/{slug}.md">Текст страницы ↗</a><a href="{github}/tree/{commit}">Реализация ↗</a></section>
<footer class="footer"><strong>CHRDK /</strong><span>Выживание. Ремесло. Своя история.</span><a href="{releases}">Релизы ↗</a><a href="about.html">О справочнике</a></footer></main>
<aside class="toc" aria-label="На этой странице"><p>НА ЭТОЙ СТРАНИЦЕ</p>{toc}</aside></div>
<dialog class="search-modal" id="searchModal" aria-labelledby="searchTitle"><div class="search-head"><label id="searchTitle" for="searchInput">Что ищем?</label><button class="icon-button" id="searchClose" aria-label="Закрыть поиск">×</button></div>
<input id="searchInput" type="search" placeholder="Например: аренда, пересадка, iron_ingot" autocomplete="off" spellcheck="false" autofocus>
<p class="search-hint">Несколько слов уточняют поиск · ↑ ↓ выбор · Enter открыть · Esc закрыть</p><div id="searchResults" class="search-results" aria-live="polite"></div></dialog>
<div class="toast" id="toast" role="status" aria-live="polite"></div><script src="assets/app.js?v={asset_revision}" defer></script></body></html>"""


def build(check: bool) -> int:
    import hashlib

    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from facts import FactBook
    from freshness import check as freshness_check

    problems = []
    if check:
        problems.extend(freshness_check(ROOT))
        if problems:
            print("Нужна сверка вики:\n" + "\n".join(problems), file=sys.stderr)
            return 1
    book = FactBook(ROOT)
    config = yaml.safe_load(CONFIG_PATH.read_text(encoding="utf-8"))
    site = config["site"]
    groups = config["groups"]
    raw = {p.stem: read_page(p) for p in sorted(WIKI_DIR.glob("*.md"))}
    ordered = [s for g in groups for s in g["pages"]]
    if set(ordered) != set(raw) or len(ordered) != len(set(ordered)):
        print(
            "Навигация должна содержать каждую страницу ровно один раз.",
            file=sys.stderr,
        )
        return 1
    commit = source_commit()
    version_str = version()
    built = date.today().isoformat()
    asset_revision = hashlib.sha256(
        (ASSET_DIR / "style.css").read_bytes() + (ASSET_DIR / "app.js").read_bytes()
    ).hexdigest()[:12]
    OUT_DIR.mkdir(exist_ok=True)
    for old in OUT_DIR.glob("*.html"):
        old.unlink()
    anchors = {}
    search = []
    for i, slug in enumerate(ordered):
        meta, source = raw[slug]
        body = book.expand(source)
        renderer = Renderer(slug, set(raw))
        rendered = renderer.render(body)
        anchors[slug] = set(renderer.anchors)
        group = next(g for g in groups if slug in g["pages"])
        toc = "".join(
            f'<a href="#{anchor}">{html.escape(title)}</a>'
            for anchor, title in renderer.toc
        )
        pager = "".join(
            f'<a href="{ordered[j]}.html"><small>{label}</small>{html.escape(raw[ordered[j]][0]["title"])} <span aria-hidden="true">{arrow}</span></a>'
            for j, label, arrow in [(i - 1, "НАЗАД", "←"), (i + 1, "ДАЛЬШЕ", "→")]
            if 0 <= j < len(ordered)
        )
        page = PAGE_TEMPLATE.format(
            title=html.escape(meta["title"]),
            description=html.escape(meta["description"], quote=True),
            canonical=site["url"] + ("" if slug == "index" else slug + ".html"),
            url=site["url"],
            github=site["github"],
            releases=site["releases"],
            version=version_str,
            asset_revision=asset_revision,
            commit=commit,
            short_commit=commit[:7],
            built=built,
            slug=slug,
            nav=nav_html(groups, raw, slug),
            group=html.escape(group["title"]),
            hero=home_html(book, groups) if slug == "index" else "",
            heading=(
                ""
                if slug == "index"
                else f'<header class="page-heading"><p class="eyebrow">{html.escape(group["title"])}</p><h1>{html.escape(meta["title"])}</h1></header>'
            ),
            lead=html.escape(meta["description"]),
            body=rendered,
            cards=cards_html(groups, raw) if slug == "index" else "",
            toc=toc,
            pager=pager,
        )
        (OUT_DIR / (slug + ".html")).write_text(page, encoding="utf-8")
        plain = html.unescape(re.sub("<[^>]+>", " ", rendered))
        plain = re.sub(r"\s+", " ", plain).strip()
        search.append(
            {
                "title": meta["title"],
                "url": slug + ".html",
                "group": group["title"],
                "headings": [t for _, t in renderer.toc],
                "text": plain,
            }
        )
    assets = OUT_DIR / "assets"
    if assets.exists():
        shutil.rmtree(assets)
    shutil.copytree(ASSET_DIR, assets)
    (OUT_DIR / ".nojekyll").write_text("")
    (assets / "version.txt").write_text(version_str)
    (OUT_DIR / "search-index.json").write_text(
        json.dumps(search, ensure_ascii=False), encoding="utf-8"
    )
    (OUT_DIR / "build-info.json").write_text(
        json.dumps(
            {
                "version": version_str,
                "commit": commit,
                "pages": len(ordered),
                "plugins": len(book.modules),
                "built": built,
            },
            ensure_ascii=False,
        )
        + "\n",
        encoding="utf-8",
    )
    urls = "".join(f'<url><loc>{site["url"]}{s}.html</loc></url>' for s in ordered)
    (OUT_DIR / "sitemap.xml").write_text(
        '<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
        + urls
        + "</urlset>",
        encoding="utf-8",
    )
    (OUT_DIR / "robots.txt").write_text(
        f'User-agent: *\nAllow: /\nSitemap: {site["url"]}sitemap.xml\n'
    )
    for slug in ordered:
        text = (OUT_DIR / (slug + ".html")).read_text(encoding="utf-8")
        for target in re.findall(r'(?:href|src)="([^"]+)"', text):
            if target.startswith(("http://", "https://", "mailto:")):
                continue
            path, _, anchor = target.partition("#")
            path = path.split("?", 1)[0]
            if path and not (OUT_DIR / path).is_file():
                problems.append(f"{slug}: нет файла {path}")
            target_slug = path[:-5] if path.endswith(".html") else slug
            if (
                anchor
                and anchor not in anchors.get(target_slug, set())
                and anchor not in {"main"}
            ):
                problems.append(f"{slug}: нет якоря {target}")
        if "{{" in text:
            problems.append(f"{slug}: не раскрыты данные")
    if problems:
        print("\n".join(problems), file=sys.stderr)
        return 1
    print(
        f'Готово: {len(ordered)} страниц → {OUT_DIR} (v{version_str}); данные и ссылки проверены; сверка источников: {"проверена" if check else "не запрошена"}.'
    )
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    raise SystemExit(build(args.check))


if __name__ == "__main__":
    main()
