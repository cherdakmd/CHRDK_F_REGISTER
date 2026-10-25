#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Сборка карточек витрины из готового арта.

Картинки рангов рисуются нейросетью в «майнкрафтовском» 3D-пиксельном стиле и
лежат в `art/` (1024×1024). Этот скрипт:

  1. добавляет к арту плашку с названием ранга, ценой и бонусом;
  2. собирает общую обложку `00-vse-ranki.png` (1780×900) из тех же артов;
  3. при `--zip` пересобирает архив для панели TradeMC.

Запуск:

    python3 docs/ranks/shop/compose_ai.py             # карточки + обложка
    python3 docs/ranks/shop/compose_ai.py --card 3    # только «Хранитель»
    python3 docs/ranks/shop/compose_ai.py --overview  # только обложка
    python3 docs/ranks/shop/compose_ai.py --zip       # и архив

Нужен Pillow и шрифт DejaVu Sans Bold (пакет fonts-dejavu-core).
Пиксельные надписи берутся из `generate.py` — там же лежит запасной полностью
векторный генератор карточек, если арт не подходит.
"""

import argparse
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
ART = os.path.join(HERE, "art")
sys.path.insert(0, HERE)

import generate as G  # noqa: E402  (pixel_text/frame/build_zip)

W = 1200          # размер готовой карточки
SHIFT = 64        # арт поднимаем вверх: низ карточки не должен лезть в подпись
PANEL = 914       # линия нижней панели
SIDE = 64         # отступ плашек от края

RANKS = [
    dict(file="1-strannik", level="I", name="Странник",
         accent=(168, 178, 194), month="99", year="990",
         bonus="+10 % опыта"),
    dict(file="2-sledopyt", level="II", name="Следопыт",
         accent=(96, 214, 146), month="159", year="1590",
         bonus="+20 % опыта · +10 % добычи"),
    dict(file="3-khranitel", level="III", name="Хранитель",
         accent=(90, 222, 232), month="229", year="2290",
         bonus="+30 % опыта · +15 % добычи"),
    dict(file="4-metsenat", level="IV", name="Меценат",
         accent=(255, 205, 84), month="299", year="2990",
         bonus="+40 % опыта · +20 % добычи"),
    dict(file="5-pokrovitel", level="V", name="Покровитель",
         accent=(190, 150, 255), month="365", year="3650",
         bonus="+50 % опыта · навык +10"),
]


def load_art(cfg):
    path = os.path.join(ART, cfg["file"] + ".png")
    if not os.path.exists(path):
        sys.exit("Нет арта %s — положите картинку 1024×1024 в docs/ranks/shop/art/." % path)
    return Image.open(path).convert("RGBA")


def panel(img, accent, top=PANEL, feather=150):
    """Затемнение снизу: у линии уже плотное, выше растворяется в арт."""
    ov = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(ov)
    start = top - feather
    for y in range(start, img.height):
        t = min(1.0, (y - start) / float(feather))
        d.line([(0, y), (img.width, y)], fill=(6, 8, 14, int(255 * (t ** 0.7))))
    out = Image.alpha_composite(img, ov)

    line = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(line).rectangle([0, top, img.width, top + 2], fill=tuple(accent) + (150,))
    return Image.alpha_composite(out, line)


def name_sprite(text, start=62, max_width=1080, min_size=40):
    size = start
    sprite = G.pixel_text(text, size, (255, 255, 255))
    while sprite.width > max_width and size > min_size:
        size -= 2
        sprite = G.pixel_text(text, size, (255, 255, 255))
    return sprite


def card(cfg, out):
    art = load_art(cfg).resize((W, W), Image.LANCZOS)

    # фон — этот же арт, размытый: не остаётся пустых полос по краям
    bg = art.filter(ImageFilter.GaussianBlur(40))
    bg = Image.eval(bg.convert("RGB"), lambda v: int(v * 0.5)).convert("RGBA")

    img = bg.copy()
    img.alpha_composite(art, (0, -SHIFT))
    img = panel(img, cfg["accent"])

    accent = cfg["accent"]

    step = G.pixel_text("СТУПЕНЬ " + cfg["level"], 22, accent)
    img.alpha_composite(step, ((W - step.width) // 2, PANEL + 16))

    nm = name_sprite(cfg["name"])
    img.alpha_composite(nm, ((W - nm.width) // 2, PANEL + 48))

    plate_top = PANEL + 136
    for x, label, value in ((SIDE, "30 ДНЕЙ", cfg["month"] + " ₽"),
                            (W // 2 + 8, "ГОД", cfg["year"] + " ₽")):
        width = W // 2 - SIDE - 8
        ov = Image.new("RGBA", (W, W), (0, 0, 0, 0))
        ImageDraw.Draw(ov).rounded_rectangle(
            [x, plate_top, x + width, plate_top + 64], radius=10,
            fill=tuple(accent) + (22,), outline=tuple(accent) + (150,), width=2)
        img.alpha_composite(ov)
        lab = G.pixel_text(label, 15, accent)
        img.alpha_composite(lab, (x + 18, plate_top + 23))
        val = G.pixel_text(value, 30, (255, 255, 255))
        img.alpha_composite(val, (x + width - 18 - val.width, plate_top + 15))

    bon = G.pixel_text(cfg["bonus"], 17, (200, 208, 226))
    img.alpha_composite(bon, ((W - bon.width) // 2, plate_top + 78))

    img.convert("RGB").save(out, "PNG", optimize=True)


def overview(out):
    W_, H_ = 1780, 900
    accent = (150, 130, 232)
    img = G.background((W_, H_), accent, seed=99)
    img = G.frame(img, accent)

    brand = G.pixel_text("CHRDK  ·  ГИЛЬДИЯ КЛАДОИСКАТЕЛЕЙ", 28, (238, 242, 250))
    G.shadowed(img, brand, ((W_ - brand.width) // 2, 38), offset=(3, 3), alpha=170)

    title = G.pixel_text("ПЯТЬ РАНГОВ", 72, (255, 255, 255))
    G.shadowed(img, title, ((W_ - title.width) // 2, 82), offset=(6, 6), alpha=165)

    sub = G.pixel_text("ВХОД БЕСПЛАТНЫЙ  ·  РАНГ — ЭТО КОМФОРТ, ОФОРМЛЕНИЕ И ТЕМП",
                       23, (226, 232, 242))
    img.alpha_composite(sub, ((W_ - sub.width) // 2, 176))

    bw, gap = 330, 12
    total = len(RANKS) * bw + (len(RANKS) - 1) * gap
    x0 = (W_ - total) // 2
    top = 226
    panel_bottom = 742

    for i, cfg in enumerate(RANKS):
        x = x0 + i * (bw + gap)

        # панель ступени
        G.plate(img, [x, top, x + bw, panel_bottom], cfg["accent"], alpha_fill=16, radius=16)

        thumb = bw - 36
        art = load_art(cfg).resize((thumb, thumb), Image.LANCZOS)
        mask = Image.new("L", (thumb, thumb), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, thumb - 1, thumb - 1], radius=12, fill=255)
        tile = Image.new("RGBA", (thumb, thumb), (0, 0, 0, 255))
        tile.paste(art, (0, 0), art)
        tile.putalpha(mask)
        tx, ty = x + 18, top + 18
        img.alpha_composite(tile, (tx, ty))

        rim = Image.new("RGBA", (W_, H_), (0, 0, 0, 0))
        ImageDraw.Draw(rim).rounded_rectangle([tx, ty, tx + thumb, ty + thumb], radius=12,
                                             outline=tuple(cfg["accent"]) + (170,), width=3)
        img.alpha_composite(rim)

        name_size = 40
        name = G.pixel_text(cfg["name"], name_size, (255, 255, 255))
        while name.width > bw - 20 and name_size > 20:
            name_size -= 2
            name = G.pixel_text(cfg["name"], name_size, (255, 255, 255))
        img.alpha_composite(name, (x + (bw - name.width) // 2, ty + thumb + 22))

        month = G.pixel_text(cfg["month"] + " ₽ / 30 дн.", 22, (236, 242, 250))
        img.alpha_composite(month, (x + (bw - month.width) // 2, ty + thumb + 82))
        year = G.pixel_text(cfg["year"] + " ₽ / год", 22, (198, 208, 226))
        img.alpha_composite(year, (x + (bw - year.width) // 2, ty + thumb + 122))
        bon = G.pixel_text(cfg["bonus"].split(" · ")[0], 20, cfg["accent"])
        img.alpha_composite(bon, (x + (bw - bon.width) // 2, ty + thumb + 168))

    foot = G.pixel_text("ПОДРОБНЕЕ — /RANK НА СЕРВЕРЕ  ·  ОПЛАТА В TRADEMC", 22, accent)
    img.alpha_composite(foot, ((W_ - foot.width) // 2, 806))

    img.convert("RGB").save(out, "PNG", optimize=True)


def main(argv):
    ap = argparse.ArgumentParser(description="Карточки витрины из готового арта")
    ap.add_argument("--card", type=int, choices=range(1, 6), help="только ступень 1–5")
    ap.add_argument("--overview", action="store_true", help="только общая обложка")
    ap.add_argument("--zip", action="store_true", help="пересобрать архив для TradeMC")
    ap.add_argument("--out", default=HERE, help="каталог для PNG (по умолчанию рядом с артом)")
    args = ap.parse_args(argv)

    if not os.path.exists(G.BOLD):
        sys.exit("Нет шрифта %s — поставьте пакет fonts-dejavu-core." % G.BOLD)

    if not args.overview:
        for i, cfg in enumerate(RANKS, start=1):
            if args.card and args.card != i:
                continue
            card(cfg, os.path.join(args.out, cfg["file"] + ".png"))
            print("card:", cfg["file"] + ".png")
    if args.card is None:
        overview(os.path.join(args.out, "00-vse-ranki.png"))
        print("overview: 00-vse-ranki.png")
    if args.zip:
        print("zip:", G.build_zip(args.out))


if __name__ == "__main__":
    main(sys.argv[1:])
