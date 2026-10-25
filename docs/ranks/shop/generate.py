#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Генератор картинок витрины рангов CHRDK.

Стиль намеренно «майнкрафтовый»: крупные пиксельные надписи, изометрические
блоки (куб ранга, бочка, сундук, земля) и блочная рамка вокруг карточки.

Запуск (нужен Pillow):

    python3 docs/ranks/shop/generate.py            # перерисовать всё
    python3 docs/ranks/shop/generate.py --card 3   # только 3-khranitel.png

Файлы пишутся рядом со скриптом: 1200×1200 карточки рангов и
00-vse-ranki.png (1780×900) — общая обложка витрины.
"""

import argparse
import math
import os
import random
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

# ─────────────────────────── палитра и данные рангов ────────────────────────

RANKS = [
    dict(
        level="I", file="1-strannik", name="Странник", emblem="star4",
        accent=(168, 178, 194), block=(126, 136, 152), seed=11,
        tag="первый шаг в гильдию", month="99", year="990",
        short=["без очереди", "4 дома · 28 чанков · 12 лотов", "+10 % опыта"],
        bonus="+10 % опыта",
    ),
    dict(
        level="II", file="2-sledopyt", name="Следопыт", emblem="diamond_ring",
        accent=(96, 214, 146), block=(64, 168, 110), seed=22,
        tag="тебя видно, и ты растёшь быстрее", month="159", year="1590",
        short=["3 шлейфа и зелёный ник", "6 домов · 34 чанка · 15 лотов",
               "+20 % опыта · +10 % добычи"],
        bonus="+20 % опыта",
    ),
    dict(
        level="III", file="3-khranitel", name="Хранитель", emblem="star4_outline",
        accent=(90, 222, 232), block=(52, 170, 186), seed=33,
        tag="сервер знает тебя по имени", month="229", year="2290",
        short=["строка входа в чате", "8 домов · 40 чанков · 18 лотов",
               "+30 % опыта · +15 % добычи"],
        bonus="+30 % опыта",
    ),
    dict(
        level="IV", file="4-metsenat", name="Меценат", emblem="star5",
        accent=(255, 205, 84), block=(214, 164, 52), seed=44,
        tag="премиум без отдельной покупки", month="299", year="2990",
        short=["батлпасс в подарок", "10 домов · 44 чанка · 24 лота",
               "+40 % опыта · +20 % добычи"],
        bonus="+40 % опыта",
    ),
    dict(
        level="V", file="5-pokrovitel", name="Покровитель", emblem="crown",
        accent=(190, 150, 255), block=(140, 90, 220), seed=55,
        glyph=(255, 228, 130),
        tag="потолок сервера — выше не продаётся", month="365", year="3650",
        short=["/hat и шлейф «Сияние»", "12 домов · 48 чанков · 30 лотов",
               "+50 % опыта · навык +10"],
        bonus="+50 % опыта",
    ),
]


# ──────────────────────────────── утилиты ───────────────────────────────────

def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c)


def layer(size):
    return Image.new("RGBA", size, (0, 0, 0, 0))


def centered(img, sprite, y, width=None):
    w = width or img.width
    img.alpha_composite(sprite, ((w - sprite.width) // 2, y))


# ───────────────────────── пиксельный текст (Minecraft) ─────────────────────

def _text_mask(text, target_px, scale, font_path):
    base = max(6, int(round(target_px / scale)))
    font = ImageFont.truetype(font_path, base)
    bbox = font.getbbox(text)
    w = max(1, bbox[2] - bbox[0])
    h = max(1, bbox[3] - bbox[1])
    mask = Image.new("L", (w + 2, h + 2), 0)
    ImageDraw.Draw(mask).text((1 - bbox[0], 1 - bbox[1]), text, font=font, fill=255)
    # уменьшаем до «пиксельной» сетки и растягиваем NEAREST — так буквы
    # становятся ступенчатыми, как в игре, а не сглаженными.
    mask = mask.resize(((w + 2) * scale, (h + 2) * scale), Image.NEAREST)
    return mask.point(lambda a: 255 if a > 110 else 0)


def pixel_text(text, target_px, color, scale=2, font_path=BOLD):
    mask = _text_mask(text, target_px, scale, font_path)
    out = Image.new("RGBA", mask.size, tuple(color) + (255,))
    out.putalpha(mask)
    return out


def pixel_width(text, target_px, scale=2, font_path=BOLD):
    base = max(6, int(round(target_px / scale)))
    font = ImageFont.truetype(font_path, base)
    bbox = font.getbbox(text)
    return (bbox[2] - bbox[0] + 2) * scale


def pixel_fit(text, max_width, start, scale=2, min_size=40, step=2):
    size = start
    while size > min_size and pixel_width(text, size, scale) > max_width:
        size -= step
    return size


# ─────────────────────────── эмблемы рангов (пиксель) ───────────────────────

EMBLEM_SS = 8      # суперсэмплинг
EMBLEM_GRID = 16   # пиксельная сетка спрайта


def _emblem_polys(kind, S):
    c = S / 2.0
    fill, holes = [], []
    if kind == "star4":
        fill.append([
            (c + (S * 0.47 if i % 2 == 0 else S * 0.12) * math.cos(math.pi / 4 * i - math.pi / 2),
             c + (S * 0.47 if i % 2 == 0 else S * 0.12) * math.sin(math.pi / 4 * i - math.pi / 2))
            for i in range(8)
        ])
    elif kind == "diamond_ring":
        r = S * 0.47
        fill.append([(c, c - r), (c + r, c), (c, c + r), (c - r, c)])
        r2 = S * 0.24
        holes.append([(c, c - r2), (c + r2, c), (c, c + r2), (c - r2, c)])
    elif kind == "star4_outline":
        outer, inner = [], []
        for i in range(8):
            ang = math.pi / 4 * i - math.pi / 2
            r = S * 0.47 if i % 2 == 0 else S * 0.12
            outer.append((c + r * math.cos(ang), c + r * math.sin(ang)))
            r2 = S * 0.30 if i % 2 == 0 else S * 0.055
            inner.append((c + r2 * math.cos(ang), c + r2 * math.sin(ang)))
        fill.append(outer)
        holes.append(inner)
    elif kind == "star5":
        fill.append([
            (c + (S * 0.47 if i % 2 == 0 else S * 0.20) * math.cos(math.pi / 5 * i - math.pi / 2),
             c + (S * 0.47 if i % 2 == 0 else S * 0.20) * math.sin(math.pi / 5 * i - math.pi / 2))
            for i in range(10)
        ])
    elif kind == "crown":
        w = S * 0.44
        base_top = c + S * 0.10
        fill.append([(c - w, base_top), (c + w, base_top),
                     (c + w, c + S * 0.34), (c - w, c + S * 0.34)])
        for dx, h in ((-0.34, 0.44), (0.0, 0.52), (0.34, 0.44)):
            x = c + S * dx
            top = c - S * h
            fill.append([(x - S * 0.11, base_top + S * 0.02), (x, top),
                         (x + S * 0.11, base_top + S * 0.02)])
            r = S * 0.085
            cy = top - r * 1.35
            fill.append([(x, cy - r), (x + r, cy), (x, cy + r), (x - r, cy)])
    else:
        raise ValueError("unknown emblem: %s" % kind)
    return fill, holes


def emblem(kind, color, cell=10):
    """Пиксельная эмблема ранга; cell — размер «пикселя» в готовом изображении."""
    S = EMBLEM_GRID * EMBLEM_SS
    mask = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(mask)
    fill, holes = _emblem_polys(kind, S)
    for p in fill:
        d.polygon(p, fill=255)
    for p in holes:
        d.polygon(p, fill=0)
    small = mask.resize((EMBLEM_GRID, EMBLEM_GRID), Image.BOX).point(lambda a: 255 if a > 120 else 0)

    size = EMBLEM_GRID * cell
    out = layer((size, size))
    if cell >= 4:
        outline = Image.new("RGBA", small.size, shade(color, 0.35) + (255,))
        outline.putalpha(small.filter(ImageFilter.MaxFilter(3)))
        out.alpha_composite(outline.resize((size, size), Image.NEAREST))
    body = Image.new("RGBA", small.size, tuple(color) + (255,))
    body.putalpha(small)
    out.alpha_composite(body.resize((size, size), Image.NEAREST))
    return out


# ───────────────────────── изометрические блоки ─────────────────────────────

def iso_cube(s, base, seed=1, kind="block"):
    """Изометрический куб с пиксельной текстурой и вариациями (бочка/сундук)."""
    hh = s * 0.5
    sh = int(s * 0.78)
    w = 2 * s
    h = int(2 * hh + sh)
    top, left, right = shade(base, 1.34), shade(base, 0.96), shade(base, 0.70)
    img = layer((w, h))
    d = ImageDraw.Draw(img)
    cx = s
    top_pts = [(cx, 0), (w, hh), (cx, 2 * hh), (0, hh)]
    left_pts = [(0, hh), (cx, 2 * hh), (cx, 2 * hh + sh), (0, hh + sh)]
    right_pts = [(cx, 2 * hh), (w, hh), (w, hh + sh), (cx, 2 * hh + sh)]
    d.polygon(top_pts, fill=top + (255,))
    d.polygon(left_pts, fill=left + (255,))
    d.polygon(right_pts, fill=right + (255,))

    cell = max(7, s // 12)
    tex = layer((w, h))
    td = ImageDraw.Draw(tex)
    rnd = random.Random(seed)
    for gx in range(0, w, cell):
        for gy in range(0, h, cell):
            k = rnd.choice([0.97, 0.99, 1.0, 1.02, 1.04])
            cy = gy + cell / 2.0
            col = (0, 0, 0, 0)
            if cy <= 2 * hh:
                dx = abs((gx + cell / 2.0) - cx) / (w / 2.0)
                if dx <= 1 and hh * dx <= cy <= 2 * hh - hh * dx:
                    col = shade(top, k) + (255,)
            else:
                col = shade(left if gx + cell / 2.0 <= cx else right, k) + (255,)
            if col[3]:
                td.rectangle([gx, gy, gx + cell - 2, gy + cell - 2], fill=col)
    clip = Image.new("L", (w, h), 0)
    cd = ImageDraw.Draw(clip)
    for pts in (top_pts, left_pts, right_pts):
        cd.polygon(pts, fill=255)
    img = Image.composite(Image.alpha_composite(img, tex), img, clip)

    d = ImageDraw.Draw(img)
    d.line(top_pts + [top_pts[0]], fill=shade(top, 1.45) + (255,), width=max(2, s // 34))
    for pts in (left_pts, right_pts):
        d.line([pts[0], pts[1]], fill=shade(top, 1.28) + (190,), width=max(1, s // 60))
        d.line([pts[2], pts[3]], fill=(0, 0, 0, 150), width=max(1, s // 70))

    if kind == "barrel":
        for f in (0.22, 0.60):
            yy = 2 * hh + sh * f
            for a, b in (((2, hh + sh * f), (cx, yy)), ((cx, yy), (w - 2, hh + sh * f))):
                d.line([a, b], fill=(44, 30, 18, 235), width=max(3, s // 22))
        for f in (0.25, 0.5, 0.75):
            x0 = int(w * f)
            y0 = hh + abs(x0 - cx) * 0.5
            d.line([(x0, y0), (x0, y0 + sh)], fill=(0, 0, 0, 80), width=max(2, s // 40))
    elif kind == "chest":
        yy = 2 * hh + sh * 0.30
        for a, b in (((2, hh + sh * 0.30), (cx, yy)), ((cx, yy), (w - 2, hh + sh * 0.30))):
            d.line([a, b], fill=(52, 32, 12, 235), width=max(3, s // 20))
        d.rectangle([cx - 7, yy + 2, cx + 7, yy + max(10, s // 7)], fill=(70, 48, 20, 255))
        d.rectangle([cx - 4, yy + 5, cx + 4, yy + max(8, s // 8)], fill=(228, 190, 90, 255))
    return img


# ────────────────────────────── фон и рамка ─────────────────────────────────

def background(size, accent, seed=7):
    w, h = size
    grad = Image.new("RGBA", (1, h))
    for y in range(h):
        t = max(0.0, 1.0 - y / (h * 0.95))
        c = tuple(int(12 + (accent[i] * 0.20 - 12) * t) for i in range(3))
        grad.putpixel((0, y), c + (255,))
    img = grad.resize((w, h))

    noise = layer((w, h))
    nd = ImageDraw.Draw(noise)
    rnd = random.Random(seed)
    for _ in range(int(w * h / 550)):
        x = rnd.randrange(0, w, 4)
        y = rnd.randrange(0, h, 4)
        nd.rectangle([x, y, x + 3, y + 3], fill=(255, 255, 255, rnd.choice([3, 4, 5, 7])))
    img = Image.alpha_composite(img, noise)

    glow = layer((w, h))
    gd = ImageDraw.Draw(glow)
    r_max = int(min(w, h) * 0.46)
    for r in range(r_max, 0, -8):
        a = int(9 * (1 - r / r_max) ** 1.4)
        gd.ellipse([w // 2 - r, h * 0.26 - r, w // 2 + r, h * 0.26 + r],
                   fill=tuple(accent) + (a,))
    return Image.alpha_composite(img, glow.filter(ImageFilter.GaussianBlur(44)))


def frame(img, accent):
    w, h = img.size
    ov = layer((w, h))
    d = ImageDraw.Draw(ov)
    d.rectangle([18, 18, w - 19, h - 19], outline=(255, 255, 255, 30), width=2)
    d.rectangle([30, 30, w - 31, h - 31], outline=tuple(accent) + (70,), width=2)
    step = 40
    for x in range(54, w - 54, step):
        for y in (20, h - 32):
            d.rectangle([x, y, x + 22, y + 12],
                        fill=tuple(accent) + (70 if (x // step) % 2 else 34,))
    for y in range(54, h - 54, step):
        for x in (20, w - 32):
            d.rectangle([x, y, x + 12, y + 22],
                        fill=tuple(accent) + (70 if (y // step) % 2 else 34,))
    img.alpha_composite(ov)
    return img


def shadowed(img, sprite, xy, offset=(8, 8), alpha=160):
    sh = Image.new("RGBA", sprite.size, (0, 0, 0, 255))
    sh.putalpha(sprite.getchannel("A").point(lambda a: int(a * alpha / 255)))
    img.alpha_composite(sh, (xy[0] + offset[0], xy[1] + offset[1]))
    img.alpha_composite(sprite, xy)


def plate(img, box, accent, alpha_fill=30, radius=16):
    ov = layer(img.size)
    ImageDraw.Draw(ov).rounded_rectangle(
        box, radius=radius, fill=tuple(accent) + (alpha_fill,),
        outline=tuple(accent) + (150,), width=3)
    img.alpha_composite(ov)


def ground_row(img, accent, y, seed, size=58, alpha=0.9):
    rnd = random.Random(seed)
    base = mix((54, 62, 78), accent, 0.16)
    for i, x in enumerate(range(16, img.width - 10, int(size * 2.05))):
        block = iso_cube(size, base, seed=seed + i)
        block.putalpha(block.getchannel("A").point(lambda a: int(a * alpha)))
        img.alpha_composite(block, (x, y + rnd.randint(-5, 5)))


# ─────────────────────────────── карточка ранга ─────────────────────────────

def card(cfg, path):
    accent = cfg["accent"]
    W = 1200
    img = background((W, W), accent, cfg["seed"])
    img = frame(img, accent)

    brand = pixel_text("CHRDK  ·  ГИЛЬДИЯ КЛАДОИСКАТЕЛЕЙ", 32, (238, 242, 250))
    shadowed(img, brand, ((W - brand.width) // 2, 42), offset=(3, 3), alpha=170)

    glyph = emblem(cfg["emblem"], cfg.get("glyph", accent), cell=10)
    shadowed(img, glyph, ((W - glyph.width) // 2, 84), offset=(8, 8), alpha=120)

    cube = iso_cube(108, cfg["block"], seed=cfg["seed"], kind=cfg.get("kind", "block"))
    img.alpha_composite(cube, ((W - cube.width) // 2, 224))

    barrel = iso_cube(68, (128, 84, 44), seed=cfg["seed"] + 31, kind="barrel")
    shadowed(img, barrel, (136, 288), offset=(6, 6), alpha=120)
    chest = iso_cube(64, (156, 100, 42), seed=cfg["seed"] + 32, kind="chest")
    shadowed(img, chest, (1002, 296), offset=(6, 6), alpha=120)
    for (x, y, col, s2) in ((292, 258, cfg["block"], 20), (886, 246, accent, 16)):
        it = iso_cube(s2, col, seed=x)
        it.putalpha(it.getchannel("A").point(lambda a: int(a * 0.9)))
        img.alpha_composite(it, (x, y))

    step = pixel_text("СТУПЕНЬ " + cfg["level"], 44, accent)
    img.alpha_composite(step, ((W - step.width) // 2, 428))

    name_size = pixel_fit(cfg["name"], 1030, 168)
    name = pixel_text(cfg["name"], name_size, (255, 255, 255))
    name_y = 470
    shadowed(img, name, ((W - name.width) // 2, name_y), offset=(8, 8), alpha=165)

    tag_size = 27
    while tag_size > 18 and pixel_width(cfg["tag"].upper(), tag_size) > 1000:
        tag_size -= 1
    tag = pixel_text(cfg["tag"].upper(), tag_size, (226, 232, 242))
    img.alpha_composite(tag, ((W - tag.width) // 2, min(name_y + name.height + 12, 650)))

    y = 704
    for line in cfg["short"]:
        img.alpha_composite(iso_cube(15, cfg["block"], seed=cfg["seed"] + len(line)), (108, y + 8))
        img.alpha_composite(pixel_text(line, 28, (240, 244, 250)), (146, y))
        y += 40

    for x, w, label, value, hint in (
            (96, 486, "30 ДНЕЙ", cfg["month"] + " ₽", "разовый платёж"),
            (618, 486, "ГОД", cfg["year"] + " ₽", "два месяца в подарок")):
        plate(img, [x, 834, x + w, 966], accent)
        img.alpha_composite(pixel_text(label, 25, accent), (x + 26, 850))
        val = pixel_text(value, 58, (255, 255, 255))
        img.alpha_composite(val, (x + w - 26 - val.width, 862))
        img.alpha_composite(pixel_text(hint, 21, (255, 255, 255)), (x + 26, 926))

    foot = pixel_text("КУПИТЬ: /RANK НА СЕРВЕРЕ", 25, accent)
    img.alpha_composite(foot, ((W - foot.width) // 2, 992))

    ground_row(img, accent, 1044, cfg["seed"] + 11)

    img.convert("RGB").save(path, "PNG", optimize=True)


# ───────────────────────────── общая обложка ────────────────────────────────

def overview(path):
    W, H = 1780, 900
    accent = (150, 130, 232)
    img = background((W, H), accent, seed=99)
    img = frame(img, accent)

    brand = pixel_text("CHRDK  ·  ГИЛЬДИЯ КЛАДОИСКАТЕЛЕЙ", 28, (238, 242, 250))
    shadowed(img, brand, ((W - brand.width) // 2, 40), offset=(3, 3), alpha=170)

    title = pixel_text("ПЯТЬ РАНГОВ", 74, (255, 255, 255))
    shadowed(img, title, ((W - title.width) // 2, 84), offset=(6, 6), alpha=165)

    sub = pixel_text("ВХОД БЕСПЛАТНЫЙ  ·  РАНГ — ЭТО КОМФОРТ, ОФОРМЛЕНИЕ И ТЕМП",
                     24, (226, 232, 242))
    img.alpha_composite(sub, ((W - sub.width) // 2, 172))

    bw, gap = 316, 22
    total = len(RANKS) * bw + (len(RANKS) - 1) * gap
    x0 = (W - total) // 2
    top, bottom = 232, 812

    for i, cfg in enumerate(RANKS):
        x = x0 + i * (bw + gap)
        plate(img, [x, top, x + bw, bottom], cfg["accent"], alpha_fill=20, radius=18)

        glyph = emblem(cfg["emblem"], cfg.get("glyph", cfg["accent"]), cell=7)
        img.alpha_composite(glyph, (x + (bw - glyph.width) // 2, top + 26))

        step = pixel_text("СТУПЕНЬ " + cfg["level"], 22, cfg["accent"])
        img.alpha_composite(step, (x + (bw - step.width) // 2, top + 158))

        name_size = pixel_fit(cfg["name"], bw - 52, 40)
        name = pixel_text(cfg["name"], name_size, (255, 255, 255))
        shadowed(img, name, (x + (bw - name.width) // 2, top + 194),
                 offset=(4, 4), alpha=150)

        cube = iso_cube(30, cfg["block"], seed=cfg["seed"] + 7)
        img.alpha_composite(cube, (x + (bw - cube.width) // 2, top + 262))

        for j, (label, value) in enumerate((("30 ДНЕЙ", cfg["month"] + " ₽"),
                                            ("ГОД", cfg["year"] + " ₽"))):
            yy = top + 380 + j * 74
            img.alpha_composite(pixel_text(label, 20, cfg["accent"]), (x + 24, yy))
            val = pixel_text(value, 40, (255, 255, 255))
            img.alpha_composite(val, (x + bw - 24 - val.width, yy + 24))

        img.alpha_composite(pixel_text(cfg["bonus"], 20, (236, 240, 248)),
                            (x + (bw - pixel_width(cfg["bonus"], 20)) // 2, top + 526))

    foot = pixel_text("ПОДРОБНЕЕ — /RANK НА СЕРВЕРЕ  ·  ОПЛАТА В TRADEMC", 24, accent)
    img.alpha_composite(foot, ((W - foot.width) // 2, 846))

    img.convert("RGB").save(path, "PNG", optimize=True)


def build_zip(out_dir):
    """Собирает архив для панели TradeMC: images/*.png + README.txt."""
    import zipfile

    readme = os.path.join(out_dir, "README.txt")
    if not os.path.exists(readme):
        sys.exit("Нет %s — без него архив неполный." % readme)

    names = ["00-vse-ranki.png"] + [cfg["file"] + ".png" for cfg in RANKS]
    for name in names:
        if not os.path.exists(os.path.join(out_dir, name)):
            sys.exit("Нет %s — сначала нарисуйте картинки." % name)

    target = os.path.join(os.path.dirname(out_dir.rstrip(os.sep)), "chrdk-shop-images.zip")
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        z.writestr("images/", b"")
        for name in names:
            z.write(os.path.join(out_dir, name), "images/" + name)
        with open(readme, encoding="utf-8") as f:
            # utf-8-sig: Блокнот Windows иначе покажет кириллицу кракозябрами
            z.writestr("README.txt", f.read().encode("utf-8-sig"))
    return target


def main(argv):
    ap = argparse.ArgumentParser(description="Картинки витрины рангов CHRDK")
    ap.add_argument("--card", type=int, choices=range(1, 6),
                    help="нарисовать только карточку ступени 1–5")
    ap.add_argument("--overview", action="store_true", help="только общая обложка")
    ap.add_argument("--out", default=HERE, help="каталог для PNG (по умолчанию рядом)")
    ap.add_argument("--zip", action="store_true",
                    help="пересобрать ../chrdk-shop-images.zip для TradeMC")
    args = ap.parse_args(argv)

    if not os.path.exists(BOLD):
        sys.exit("Нет шрифта %s — поставьте пакет fonts-dejavu-core." % BOLD)

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
        print("zip:", build_zip(args.out))


if __name__ == "__main__":
    main(sys.argv[1:])
