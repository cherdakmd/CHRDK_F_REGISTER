#!/usr/bin/env python3
"""Отдельный комплект трёх клановых товаров. Ничего не публикует и не вызывает RCON."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

import yaml
from PIL import Image, ImageDraw, ImageFont, ImageOps, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs/clan-shop"
FONT = Path("/usr/share/fonts/truetype/dejavu")
SIZE = (1200, 1200)
SOURCE_VERSION = "1.4.278"
DETAILS = {
    "clan-name-style": {
        "kind": "name", "title": "Цвет названия клана", "heading": "ЦВЕТ НАЗВАНИЯ",
        "subtitle": "12 цветов и градиентов", "accent": (102, 226, 245), "example": "aurora",
        "description": "Пусть ваш клан узнают с первого взгляда! Выбирайте один из 12 цветов и градиентов для названия клана и его чат-тега. Оформление видно в информации о клане, профиле и карточке меню. Служебные красные и зелёные обозначения режима клана в TAB сохраняются.",
    },
    "clan-emblem": {
        "kind": "emblem", "title": "Значок клана", "heading": "ЗНАЧОК КЛАНА",
        "subtitle": "8 символов на выбор", "accent": (255, 210, 112), "example": "crown",
        "description": "Добавьте клану узнаваемый символ! Лицензия открывает выбор одного из 8 готовых значков: звёзды, ромб, искра, лилия, солнце, снежинка и луна. Значок дополняет название в информации о клане, профиле и карточке меню, а также чат-тег. Это символ оформления, не предмет с характеристиками.",
    },
    "clan-card-frame": {
        "kind": "frame", "title": "Рамка карточки клана", "heading": "РАМКА КАРТОЧКИ",
        "subtitle": "6 вариантов оформления меню", "accent": (202, 167, 255), "example": "amethyst",
        "description": "Сделайте меню своего клана особенным! Выбирайте одну из 6 декоративных рамок: золотую, аметистовую, океанскую, лесную, огненную или морозную. Рамка оформляет свободные края меню клана и не перекрывает кнопки. Это оформление интерфейса, не блок, не физический предмет и не портал.",
    },
}
COMMON = [
    "Личная лицензия покупателя на 30 дней. Срок начинается с выдачи покупки, а не с первого применения. Каждый товар приобретается отдельно; ранг, включая V, его не заменяет. В существующий набор «Всё включено» эти лицензии не входят.",
    "Все выбранные украшения одного покупателя действуют для одного его клана. Перенос всего выбора в другой свой клан доступен не чаще раза в реальные 24 часа, в том числе после первого назначения. Снятие эффекта не сбрасывает этот срок.",
    "Глава применяет свою покупку самостоятельно. Предложение обычного участника глава подтверждает командой /clan style accept Ник в течение 2 минут. Покупатель должен быть в сети, авторизован и оставаться в этом клане. При переносе одобряется весь выбранный комплект покупателя.",
    "Если покупатель выходит из клана, его исключают, покупка истекает или отзывается, его оформление перестаёт отображаться. Лицензия не переходит новому главе. Выбор сохраняется и может снова действовать при продлении покупки и действующем членстве. Новый клан с прежним названием не наследует оформление автоматически.",
    "При нескольких спонсорах одного типа показывается последний одобренный действующий вариант; после его снятия или истечения может вернуться вариант другого участника. Глава может убрать оформление клана, не отзывая личную покупку.",
    "Только внешний вид: без силы, дополнительных мест, прав на землю, изменений казны и боевых преимуществ. Можно сочетать три разных типа оформления при наличии трёх действующих лицензий.",
    "Повторная покупка продлевает срок этой лицензии, а не создаёт вторую независимую лицензию. Если весь оплаченный период не помещается в общий горизонт 365 дней, выдача требует ручной сверки; дни не должны молча обрезаться. Возможные бонусные дни действуют только по настройкам сервера, не обещаются этой карточкой.",
    "Картинка — художественная иллюстрация косметического оформления. Физические предметы и нарисованные постройки покупкой не выдаются.",
]


def catalog(root=ROOT):
    cfg = yaml.safe_load((root / "src/main/resources/config.yml").read_text(encoding="utf-8"))
    rows = cfg["shop"]["products"]
    result = []
    for pid, detail in DETAILS.items():
        matches = [p for p in rows if p["id"] == pid]
        if len(matches) != 1:
            raise ValueError("Нужен ровно один SKU: " + pid)
        p = matches[0]
        if p["days"] != 30 or p["price"] <= 0 or p.get("rank") or p.get("permissions") or p.get("commands") or p.get("includes"):
            raise ValueError("Изменились условия косметического товара: " + pid)
        result.append({**detail, "id": pid, "price": p["price"], "days": p["days"],
                       "command": f"pass shopbuy %username% {pid}", "image": f"images/{pid}.png"})
    return result


def descriptions(p):
    return [p["description"], *COMMON, "После покупки: /clan style — варианты, сроки и правила. Пример выбора: "
            + f"/clan style {p['kind']} {p['example']}."]


def font(size, bold=False):
    return ImageFont.truetype(str(FONT / ("DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf")), size)


def centered(draw, text, y, size, color, bold=False):
    f = font(size, bold)
    if draw.textlength(text, font=f) > 1080:
        raise ValueError("Заголовок не помещается: " + text)
    draw.text((600, y), text, font=f, fill=color, anchor="mt", stroke_width=1 if bold else 0)


def artwork(p):
    with Image.open(OUT / "art" / (p["id"] + ".png")) as source:
        source = source.convert("RGB")
        # Сохраняем центральный объект выбранной иллюстрации, без растяжения.
        backdrop = ImageOps.fit(source, SIZE, method=Image.Resampling.LANCZOS).filter(ImageFilter.GaussianBlur(20))
        image = Image.blend(backdrop, Image.new("RGB", SIZE, (8, 15, 28)), .6).convert("RGBA")
        if source.width > source.height * 1.4:
            source = ImageOps.fit(source, (source.height, source.height), method=Image.Resampling.LANCZOS)
        hero = ImageOps.contain(source, (1000, 920), method=Image.Resampling.LANCZOS).convert("RGBA")
        image.alpha_composite(hero, ((1200-hero.width)//2, 150))
    shade = Image.new("RGBA", SIZE)
    pixels = shade.load()
    for y in range(1200):
        top = max(0, (270-y)/270) * 245
        bottom = max(0, (y-815)/385) * 255
        alpha = min(255, int(max(top, bottom)))
        for x in range(1200):
            pixels[x, y] = (7, 14, 27, alpha)
    image = Image.alpha_composite(image, shade)
    d = ImageDraw.Draw(image)
    a = p["accent"]
    d.rounded_rectangle((28, 28, 1172, 1172), radius=32, outline=(*a, 140), width=2)
    centered(d, "CHERDAK  /  ОФОРМЛЕНИЕ КЛАНА", 55, 25, (184, 199, 218))
    centered(d, p["heading"], 106, 59, (248, 249, 255), True)
    centered(d, p["subtitle"], 184, 29, a)
    d.line((450, 240, 750, 240), fill=(*a, 150), width=2)
    centered(d, f"{p['price']} ₽", 944, 88, (255, 245, 218), True)
    centered(d, "ЛИЧНАЯ ЛИЦЕНЗИЯ  /  30 ДНЕЙ", 1050, 28, a, True)
    centered(d, "ТОЛЬКО ВНЕШНИЙ ВИД · БЕЗ УСИЛЕНИЙ", 1111, 23, (179, 194, 212))
    return image.convert("RGB")


def documents(products):
    md = ["# CHERDAK — три товара оформления клана\n\n",
          f"Материалы для механик версии {SOURCE_VERSION}. Подготовка карточек не означает публикацию магазина или установку плагинов.\n\n",
          "Для каждой карточки используйте её PNG, описание ниже, цену и **одну** основную RCON-команду.\n\n"]
    txt = ["CHERDAK — ОПИСАНИЯ ТРЁХ ТОВАРОВ КЛАНА\n\n"]
    commands = ["CHERDAK — КОМАНДЫ ДОСТАВКИ ТРЁХ ТОВАРОВ\n\n",
                "Только защищённый доверенный RCON после настоящей оплаты. Команды без начального /.\n",
                "В поле команды каждой карточки вставить ТОЛЬКО строку её товара.\n",
                "%username% — переменная ника покупателя; настройте её под вашу панель, если синтаксис отличается.\n",
                "Не выполнять все три строки для одной покупки. Не запускать эти команды для проверки оформления.\n",
                "Не дописывать срок, payment=, выдуманный номер заказа или альтернативную команду по цене.\n",
                "Одно выполнение — одна продажа/продление. Повтор RCON не распознаётся автоматически как дубль заказа.\n\n"]
    for p in products:
        md += [f"## {p['title']} — {p['price']} ₽ / {p['days']} дней\n\n",
               f"![{p['title']}]({p['image']})\n\nID: `{p['id']}` · PNG 1200 × 1200\n\n",
               "### Описание для карточки\n\n", "\n\n".join(descriptions(p)), "\n\n### Основная команда выдачи\n\n",
               f"```text\n{p['command']}\n```\n\n"]
        txt += [f"{p['title']} — {p['price']} ₽ / {p['days']} дней\n", "=" * 64 + "\n\n",
                "\n\n".join(descriptions(p)), "\n\n\n"]
        commands += [f"{p['title']} | {p['price']} ₽ | {p['days']} дней\n", p["command"] + "\n\n"]
    md += ["## Важно владельцу\n\nСм. [инструкцию](README.md) и [отдельный файл команд](КОМАНДЫ_RCON.txt). "
           "Игровые команды выбора оформления не являются командами выдачи и не должны исполняться из карточки магазина.\n"]
    return {name: text.rstrip() + "\n" for name, text in {
        "ТОВАРЫ.md": "".join(md), "ОПИСАНИЯ_ТОВАРОВ.txt": "".join(txt), "КОМАНДЫ_RCON.txt": "".join(commands)
    }.items()}


def manifest(products):
    rows = []
    for p in products:
        path = OUT / p["image"]
        rows.append({"id": p["id"], "name": p["title"], "priceRubles": p["price"], "days": p["days"],
                     "command": p["command"], "image": p["image"], "width": 1200, "height": 1200,
                     "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                     "sourceArtworkSha256": hashlib.sha256((OUT / "art" / (p["id"] + ".png")).read_bytes()).hexdigest(),
                     "description": "\n\n".join(descriptions(p))})
    return {"schemaVersion": 1, "sourcePluginVersion": SOURCE_VERSION, "publishedToStore": False,
            "rendererSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            "artwork": "AI-generated illustration selected by owner; exact titles and prices typeset separately", "products": rows}


def manifest_text(products):
    return json.dumps(manifest(products), ensure_ascii=False, indent=2) + "\n"


def check():
    products = catalog()
    if not (OUT / "README.md").is_file():
        raise ValueError("Отсутствует инструкция владельцу README.md")
    hashes = set()
    expected = {Path(p["image"]).name for p in products}
    if {p.name for p in (OUT / "images").glob("*.png")} != expected:
        raise ValueError("Неверный набор товарных PNG")
    for p in products:
        path = OUT / p["image"]
        with Image.open(path) as image:
            if image.size != SIZE or image.format != "PNG" or image.mode != "RGB":
                raise ValueError("Неверный формат картинки: " + str(path))
            image.verify()
        hashes.add(hashlib.sha256(path.read_bytes()).hexdigest())
    if len(hashes) != 3:
        raise ValueError("Картинки товаров должны различаться")
    for name, text in documents(products).items():
        if (OUT / name).read_text(encoding="utf-8") != text:
            raise ValueError("Устарел документ: " + name)
    if (OUT / "manifest.json").read_text(encoding="utf-8") != manifest_text(products):
        raise ValueError("Устарел manifest.json или изменилась картинка")
    print("Клановые товары: 3 PNG 1200×1200, цены, сроки, описания и команды сверены. Публикации и RCON нет.")


def build():
    products = catalog()
    (OUT / "images").mkdir(parents=True, exist_ok=True)
    for p in products:
        artwork(p).save(OUT / p["image"], optimize=True)
    for name, text in documents(products).items():
        (OUT / name).write_text(text, encoding="utf-8")
    (OUT / "manifest.json").write_text(manifest_text(products), encoding="utf-8")
    check()


def pack(destination):
    check()
    files = [OUT / "README.md", OUT / "manifest.json", *(OUT / name for name in documents(catalog())),
             *(OUT / p["image"] for p in catalog())]
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            archive.write(path, path.relative_to(OUT).as_posix())
    with zipfile.ZipFile(destination) as archive:
        if archive.testzip() is not None:
            raise ValueError("Повреждён ZIP")
    print(destination)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", action="store_true")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--zip", metavar="PATH")
    args = parser.parse_args()
    if args.build:
        build()
    elif not args.zip:
        check()
    if args.zip:
        pack(args.zip)
