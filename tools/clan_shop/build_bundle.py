#!/usr/bin/env python3
"""Отдельная карточка комплекта. Старые три карточки и общая витрина не меняются."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import zipfile
import yaml
from PIL import Image, ImageDraw

SPEC = importlib.util.spec_from_file_location("clan_card_renderer", Path(__file__).with_name("build.py"))
renderer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(renderer)
ROOT = renderer.ROOT
OUT = ROOT / "docs/clan-shop/bundle"
PID = "clan-style-pack"
COMMAND = "pass shopbuy %username% clan-style-pack"
PARTS = {"clan-name-style", "clan-emblem", "clan-card-frame"}
DESCRIPTION = """Полное оформление клана — 449 ₽ / 30 дней

Соберите узнаваемый стиль своего клана: 12 цветов и градиентов названия, 8 значков и 6 рамок меню. Анимация бонусом: плавный перелив, золотой блик или мягкое пульсирование на выбор. Одновременно действует один вариант каждого типа.

Личные лицензии покупателя для одного своего клана. Участнику нужно согласие главы; все выбранные украшения переносятся вместе не чаще раза в реальные 24 часа. Выход покупателя из клана или истечение его права убирает соответствующий эффект. Покупка не переходит новому главе.

Срок начинается с выдачи, не с первого применения. Повторная покупка продлевает цвет, значок, рамку и бонусное право на анимацию на 30 дней, не обнуляя старые остатки. Если один из четырёх периодов не помещается целиком в горизонт 365 дней, нужна ручная сверка — частичная выдача и обрезка оплаченных дней не обещаются. Бонусные дни сервера действуют только при его настройках.

Анимация меняет цвет названия/тега, не буквы. Встроенный TAB обновляет кадры; в чате остаётся кадр отправки. Служебные маркеры мирного/боевого режима сохраняются. Рамка относится только к GUI. Выбор: /clan style. При нескольких спонсорах типа действует последний одобренный вариант с действующей лицензией; после его снятия может вернуться другой действующий выбор.

Только внешний вид: без боевой силы, мест, земли и доступа к казне. Ранг V и отдельная покупка трёх украшений бонусную анимацию не открывают. Старый набор «Всё включено» этих прав не содержит. Картинка — художественная иллюстрация; нарисованные предметы и постройки не выдаются.
"""
README = """# Полное оформление клана — отдельная карточка

**449 ₽ / 30 дней**. Механики комплекта требуют согласованных Core/Guilds 1.4.279+;
последний комплект плагинов 1.4.280 также добавляет список и вступление через GUI.
Релиз не устанавливает плагины на сервер автоматически.

- `clan-style-pack.png` — выбранная владельцем иллюстрация с точной типографикой, RGB PNG 1200×1200.
- `ОПИСАНИЕ.txt` — покупательский текст для карточки.
- `КОМАНДА_RCON.txt` — одна команда доставки и ограничения.
- `manifest.json` — цена, период, состав и SHA-256 изображения.

Создайте одну карточку товара; не добавляйте к ней команды составляющих.
Проверьте установленную версию, активный локальный каталог, цену и срок до продаж:
совпадающие владельческие SKU миграция не заменяет. Рецепт несовместимого комплекта
отклоняется. Самопроверка картинок не является разрешением выдать товар.

Команда — только доверенный защищённый RCON после реальной подтверждённой оплаты,
без начального `/`, срока или выдуманного `payment=`. Одна оплата — один вызов;
повтор не распознаётся автоматически как дубль заказа. Уникальность заказов,
ошибки доставки и спорные оплаты сверяются по журналу магазина/Core вручную.
Отзыв накопленных прав не равен возврату одного заказа. Секреты RCON игрокам не выдавать.

Три прежние карточки и общая историческая витрина не регенерированы.
Этот комплект **не публикует магазин, не вызывает RCON и не устанавливает сервер**.
"""
RCON = """Полное оформление клана | 449 ₽ | 30 дней
Только защищённый доверенный RCON после реальной оплаты. В карточке — одна строка:
pass shopbuy %username% clan-style-pack

%username% — переменная игрового ника вашей панели; при другом синтаксисе замените только её.
Без начального /, срока, payment= и отдельных команд составляющих.
Один вызов — одна продажа/продление. Автоматического распознавания дублей заказа нет.
Не выполнять для предпросмотра или проверки картинки.
"""


def product():
    rows = yaml.safe_load((ROOT / "src/main/resources/config.yml").read_text(encoding="utf-8"))["shop"]["products"]
    matches = [p for p in rows if p["id"] == PID]
    if len(matches) != 1:
        raise ValueError("Неоднозначный SKU комплекта")
    p = matches[0]
    if p["price"] != 449 or p["days"] != 30 or p.get("includes-days") != 30 or set(p.get("includes", [])) != PARTS:
        raise ValueError("Изменились условия комплекта; карточку нужно сверить")
    if p.get("group") or p.get("rank") or p.get("tag") or p.get("commands") or p.get("permissions") or p.get("trademc-export") is not False:
        raise ValueError("Изменились права или границы экспорта комплекта")
    return p


def manifest():
    return json.dumps({"id": PID, "minimumPluginVersion": "1.4.279", "priceRubles": 449, "days": 30,
                       "includesDays": 30, "parts": sorted(PARTS), "bonusEntitlement": PID,
                       "bonusAnimations": ["aurora", "shine", "pulse"], "command": COMMAND,
                       "image": PID + ".png", "width": 1200, "height": 1200, "publishedToStore": False,
                       "sha256": hashlib.sha256((OUT / (PID + ".png")).read_bytes()).hexdigest(),
                       "sourceArtworkSha256": hashlib.sha256((ROOT / "docs/clan-shop/art" / (PID + ".png")).read_bytes()).hexdigest(),
                       "rendererSha256": hashlib.sha256(Path(__file__).read_bytes() + Path(renderer.__file__).read_bytes()).hexdigest()},
                      ensure_ascii=False, indent=2) + "\n"


def documents():
    return {"README.md": README, "ОПИСАНИЕ.txt": DESCRIPTION, "КОМАНДА_RCON.txt": RCON}


def check():
    product()
    with Image.open(OUT / (PID + ".png")) as image:
        if image.size != (1200, 1200) or image.mode != "RGB" or image.format != "PNG":
            raise ValueError("Неверный формат карточки")
        image.verify()
    for name, text in {**documents(), "manifest.json": manifest()}.items():
        if (OUT / name).read_text(encoding="utf-8") != text:
            raise ValueError("Устарел файл: " + name)
    print("Клановый комплект: 449 ₽ / 30 дней, PNG и материалы сверены. Публикации и выдачи нет.")


def build():
    product(); OUT.mkdir(parents=True, exist_ok=True)
    image = renderer.artwork({"id": PID, "heading": "ПОЛНЫЙ КОМПЛЕКТ", "subtitle": "Цвет названия · значок · рамка",
                              "accent": (211, 172, 255), "price": 449})
    draw = ImageDraw.Draw(image)
    renderer.centered(draw, "АНИМАЦИЯ В ПОДАРОК", 264, 25, (255, 214, 126), True)
    image.save(OUT / (PID + ".png"), optimize=True)
    for name, text in {**documents(), "manifest.json": manifest()}.items():
        (OUT / name).write_text(text, encoding="utf-8")
    check()


def pack(destination):
    check()
    destination = Path(destination); destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as archive:
        for name in [*documents(), "manifest.json", PID + ".png"]:
            archive.write(OUT / name, name)
    with zipfile.ZipFile(destination) as archive:
        if archive.testzip() is not None: raise ValueError("Неверный CRC ZIP")
    print(destination)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", action="store_true")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--zip")
    args = parser.parse_args()
    if args.build: build()
    elif not args.zip: check()
    if args.zip: pack(args.zip)
