#!/usr/bin/env python3
"""Собирает карточку товара social-access в стиле docs/trademc/images.

Текст рисуется локально (DejaVu), а не генерируется моделью: кириллица,
цена и срок должны быть точными. Иллюстрация лежит рядом в docs/shop/.
"""
from __future__ import annotations

import argparse
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT = Path(__file__).resolve().parents[2]
ART = ROOT / "docs/shop/social-access-art.png"
OUT = ROOT / "docs/shop/social-access.png"
SIZE = 1200
BG = (21, 20, 29)
LILAC = (185, 140, 255)
LILAC_DIM = (139, 106, 181)
CREAM = (255, 246, 220)
WHITE = (232, 228, 240)
MUTED = (138, 135, 152)
FONT_DIR = Path("/usr/share/fonts/truetype/dejavu")
REGULAR = FONT_DIR / "DejaVuSans.ttf"
BOLD = FONT_DIR / "DejaVuSans-Bold.ttf"


def spaced(draw: ImageDraw.ImageDraw, text: str, y: int, font: ImageFont.FreeTypeFont,
           fill: tuple[int, int, int], spacing: int = 8) -> None:
    """Центрированный текст с межбуквенным интервалом, как на карточках магазина."""
    widths = [draw.textlength(char, font=font) for char in text]
    total = sum(widths) + spacing * (len(text) - 1)
    x = (SIZE - total) / 2
    for char, width in zip(text, widths):
        draw.text((x, y), char, font=font, fill=fill, anchor="lm")
        x += width + spacing


def centered(draw: ImageDraw.ImageDraw, text: str, y: int, font: ImageFont.FreeTypeFont,
             fill: tuple[int, int, int]) -> None:
    draw.text((SIZE / 2, y), text, font=font, fill=fill, anchor="mm")


def build(art_path: Path = ART, out_path: Path = OUT) -> Path:
    image = Image.new("RGB", (SIZE, SIZE), BG)
    draw = ImageDraw.Draw(image)

    # Редкие «пылинки» на фоне, детерминированный узор.
    rng = random.Random(15030)
    for _ in range(55):
        x, y = rng.randrange(35, 1165), rng.randrange(35, 1165)
        if 60 < x < 1140 and 190 < y < 820:
            continue
        s = rng.choice((3, 3, 4, 5))
        draw.rectangle((x, y, x + s, y + s), fill=(52, 45, 68))

    draw.rectangle((30, 30, 1170, 1170), outline=LILAC_DIM, width=2)
    draw.line((50, 132, 50, 58, 265, 58), fill=LILAC, width=8, joint="curve")
    draw.line((940, 1145, 1150, 1145), fill=LILAC, width=8)

    spaced(draw, "ЧЕРДАК / МАГАЗИН", 76,
           ImageFont.truetype(REGULAR, 29), WHITE, spacing=10)
    centered(draw, "Вход без ВК и MAX", 133,
             ImageFont.truetype(BOLD, 55), LILAC)

    # Центральная иллюстрация: тот же прямоугольник, что у 26-all-inclusive.png.
    box = (66, 200, 1134, 812)
    with Image.open(art_path) as source:
        art = ImageOps.fit(source.convert("RGB"), (box[2] - box[0], box[3] - box[1]),
                           method=Image.Resampling.LANCZOS, centering=(0.5, 0.5))
    image.paste(art, box[:2])
    draw.rectangle(box, outline=LILAC, width=3)
    draw.line((66, 834, 1134, 834), fill=LILAC, width=3)

    centered(draw, "150 ₽", 890, ImageFont.truetype(BOLD, 86), CREAM)
    centered(draw, "30 ДНЕЙ", 966, ImageFont.truetype(BOLD, 42), LILAC)
    centered(draw, "ВХОД БЕЗ ПРИВЯЗКИ К ВК И MAX", 1038,
             ImageFont.truetype(BOLD, 26), WHITE)
    centered(draw, "ТОЧНЫЙ ИГРОВОЙ НИК ПРИ ПОКУПКЕ", 1085,
             ImageFont.truetype(REGULAR, 22), MUTED)

    out_path.parent.mkdir(parents=True, exist_ok=True)
    image.save(out_path, "PNG", optimize=True)
    return out_path


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--art", type=Path, default=ART)
    parser.add_argument("--out", type=Path, default=OUT)
    parser.add_argument("--check", action="store_true",
                        help="проверить, что сохранённая карточка воспроизводится байт в байт")
    args = parser.parse_args()
    if args.check:
        import hashlib
        import tempfile
        with Image.open(args.out) as saved:
            if saved.size != (SIZE, SIZE) or saved.format != "PNG":
                raise SystemExit(f"social-access.png должна быть PNG {SIZE}×{SIZE}")
        with tempfile.TemporaryDirectory() as tmp:
            rebuilt = build(args.art, Path(tmp) / "card.png")
            expected, actual = hashlib.sha256(args.out.read_bytes()).hexdigest(), \
                               hashlib.sha256(rebuilt.read_bytes()).hexdigest()
        if expected != actual:
            raise SystemExit(f"social-access.png устарела: {expected} != {actual}")
        print("social-access.png: OK")
    else:
        print(build(args.art, args.out))
