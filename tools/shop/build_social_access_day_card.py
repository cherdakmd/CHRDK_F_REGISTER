#!/usr/bin/env python3
"""Собирает карточку дневного тарифа social-access-day (15 ₽ / 1 день).

Текст рисуется локально (DejaVu), а не генерируется моделью: кириллица,
цена и срок должны быть точными. Иллюстрация общая с social-access.
"""
from __future__ import annotations

import argparse
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT = Path(__file__).resolve().parents[2]
ART = ROOT / "docs/shop/social-access-art.png"
OUT = ROOT / "docs/shop/social-access-day.png"
SIZE = 1200
BG = (16, 24, 30)
TEAL = (110, 231, 203)
TEAL_DIM = (82, 163, 148)
CREAM = (255, 246, 220)
WHITE = (232, 240, 238)
MUTED = (130, 148, 146)
FONT_DIR = Path("/usr/share/fonts/truetype/dejavu")
REGULAR = FONT_DIR / "DejaVuSans.ttf"
BOLD = FONT_DIR / "DejaVuSans-Bold.ttf"


def spaced(draw: ImageDraw.ImageDraw, text: str, y: int, font: ImageFont.FreeTypeFont,
           fill: tuple[int, int, int], spacing: int = 8) -> None:
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

    rng = random.Random(15031)
    for _ in range(55):
        x, y = rng.randrange(35, 1165), rng.randrange(35, 1165)
        if 60 < x < 1140 and 190 < y < 820:
            continue
        s = rng.choice((3, 3, 4, 5))
        draw.rectangle((x, y, x + s, y + s), fill=(38, 62, 60))

    draw.rectangle((30, 30, 1170, 1170), outline=TEAL_DIM, width=2)
    draw.line((50, 132, 50, 58, 265, 58), fill=TEAL, width=8, joint="curve")
    draw.line((940, 1145, 1150, 1145), fill=TEAL, width=8)

    spaced(draw, "ЧЕРДАК / МАГАЗИН", 76,
           ImageFont.truetype(REGULAR, 29), WHITE, spacing=10)
    centered(draw, "Вход без ВК и MAX", 133,
             ImageFont.truetype(BOLD, 55), TEAL)

    box = (66, 200, 1134, 812)
    with Image.open(art_path) as source:
        art = ImageOps.fit(source.convert("RGB"), (box[2] - box[0], box[3] - box[1]),
                           method=Image.Resampling.LANCZOS, centering=(0.5, 0.5))
    image.paste(art, box[:2])
    draw.rectangle(box, outline=TEAL, width=3)
    draw.line((66, 834, 1134, 834), fill=TEAL, width=3)

    centered(draw, "15 ₽", 890, ImageFont.truetype(BOLD, 86), CREAM)
    centered(draw, "1 ДЕНЬ / 24 ЧАСА", 966, ImageFont.truetype(BOLD, 42), TEAL)
    centered(draw, "ПРОБНЫЙ ВХОД БЕЗ ПРИВЯЗКИ К ВК И MAX", 1038,
             ImageFont.truetype(BOLD, 24), WHITE)
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
                        help="проверить карточку, арт и согласованность цены/срока с каталогом")
    args = parser.parse_args()
    if args.check:
        if not args.art.is_file():
            raise SystemExit(f"нет иллюстрации: {args.art}")
        with Image.open(args.art) as art:
            art.verify()
        if not args.out.is_file():
            raise SystemExit(f"нет карточки: {args.out}")
        with Image.open(args.out) as saved:
            if saved.size != (SIZE, SIZE) or saved.format != "PNG":
                raise SystemExit(f"social-access-day.png должна быть PNG {SIZE}×{SIZE}")
            saved.verify()

        import yaml
        config = yaml.safe_load((ROOT / "src/main/resources/config.yml").read_text(encoding="utf-8"))
        product = next((p for p in config["shop"]["products"]
                        if p["id"] == "social-access-day"), None)
        if product is None:
            raise SystemExit("в shop.products нет товара social-access-day")
        if product["price"] != 15 or product["days"] != 1:
            raise SystemExit("цена/срок дневного тарифа изменились — пересоберите PNG")
        if product.get("trademc-export") is not False:
            raise SystemExit("дневной тариф не должен попадать в комплект TradeMC")
        card = (ROOT / "docs/shop/social-access-day.md").read_text(encoding="utf-8")
        for expected in ("15 ₽", "1 день", "pass shopbuy %username% social-access-day",
                         "chrdksocial.bypass"):
            if expected not in card:
                raise SystemExit(f"в карточке товара нет '{expected}' — пересоберите PNG и текст")
        print("social-access-day.png: OK (1200×1200 PNG, арт и данные каталога сверены)")
    else:
        print(build(args.art, args.out))
