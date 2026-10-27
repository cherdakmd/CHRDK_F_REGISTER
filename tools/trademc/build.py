#!/usr/bin/env python3
"""Build/verify the Russian TradeMC hand-off from the shipped catalog (no network)."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import random
import re
import zipfile

import yaml

from audit import validate as audit_catalog, report as audit_report
from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "docs/trademc"
CATALOG = ROOT / "src/main/resources/config.yml"
FONT_ROOT = Path("/usr/share/fonts/truetype/dejavu")
ART = ROOT / "docs/ranks/shop/art"
RANK_ART = {1: "1-strannik", 2: "2-sledopyt", 3: "3-khranitel",
            4: "4-metsenat", 5: "5-pokrovitel"}
COLORS = {"flame": (255, 151, 63), "hearts": (255, 117, 170),
          "notes": (135, 228, 137), "sparkles": (255, 217, 119),
          "magic": (187, 152, 255), "aurora": (122, 225, 217),
          "pearl": (227, 218, 250), "lagoon": (102, 210, 244),
          "crimson": (246, 123, 151)}
TEXT_NAME = "КОМАНДЫ_МАГАЗИНА.txt"
MD_NAME = "КОМАНДЫ_МАГАЗИНА.md"
AUDIT_NAME = "АУДИТ_ТОВАРОВ.md"


def plain(value):
    return re.sub(r"<[^>]+>", "", str(value))


def version(root=ROOT):
    text = (root / "gradle.properties").read_text(encoding="utf-8")
    return re.search(r"^plugin_version=(\d+\.\d+\.\d+)$", text, re.M).group(1)


def catalog(root=ROOT):
    data = yaml.safe_load((root / "src/main/resources/config.yml").read_text(encoding="utf-8"))
    products = data["shop"]["products"]
    ids = [p["id"] for p in products]
    if len(ids) != len(set(ids)):
        raise ValueError("Duplicate product ID")
    # ID delivery does not require distinct prices, but the advertised shoppay alternative does.
    prices = [p["price"] for p in products]
    if len(prices) != len(set(prices)):
        raise ValueError("Ambiguous shoppay prices: omit that alternative before publishing")
    for p in products:
        if not re.fullmatch(r"[a-z0-9-]+", p["id"]) or p["price"] <= 0 or p["days"] <= 0:
            raise ValueError(f"Invalid product: {p}")
    audit_catalog(root, data, products)
    return data, products


def command(p):
    return f"pass shopbuy %username% {p['id']}"


def description(p, cfg):
    raw = p["description"]
    lines = [plain(line) for line in (raw if isinstance(raw, list) else [raw])]
    tier = p.get("rank", 0)
    if tier:
        if p["id"].endswith("-year"):
            monthly = next(x for x in cfg["shop"]["products"]
                           if x.get("rank") == tier and x["days"] == 30)
            lines.append(plain(monthly["description"]))
        bonus = cfg["ranks"]["bonuses"]
        xp = min(50, bonus["xp-percent"][tier])
        drop = min(25, bonus["drop-percent"][tier])
        extra = min(10, bonus["skill-levels"][tier])
        def read(module):
            return yaml.safe_load((ROOT / module / "src/main/resources/config.yml").read_text(encoding="utf-8"))
        homes = read("teleports")["homes"]["rank-limits"][tier]
        claims = read("claims")["limits"]["rank-chunks"][tier]
        lots = read("auction")["market"]["rank-lots"][tier]
        discounts = read("teleports")["costs"]["rank-discount-percent"][tier]
        slots = cfg["labels"]["display"]["slots"][tier]
        presets = cfg["labels"]["presets"]["slots"][tier]
        lines += [f"Лимиты: дома — {homes}; чанки привата — {claims}; лоты аукциона — {lots}.",
                  f"Скидка на платные телепорты: {discounts}%; цена не ниже 1 алмаза.",
                  f"Надписи после ника: {slots}; сохранённых пресетов: {presets}. Ник всегда первый и не занимает место.",
                  f"Опыт профессий: +{xp}%; потолок профессии: {25 + extra} (25 + {extra} от ранга).",
                  f"Количество добычи в находках и охоте: +{drop}% с округлением вниз; это не гарантированное удвоение каждого блока.",
                  "Вход без очереди в рамках вместимости сервера; оформление и возможности предыдущих ступеней."]
        cosmetics = yaml.safe_load((ROOT / "cosmetics/src/main/resources/config.yml").read_text(encoding="utf-8"))["cosmetics"]
        trails = [plain(x["name"]) for x in cosmetics["trail"]["defs"] if x.get("rank", 0) <= tier]
        lines.append("Шлейфы по рангу: " + "; ".join(trails) + ". Одновременно выбирается один.")
        if tier >= 4:
            lines.append("Премиум-трек боевого пропуска и эффект входа доступны, пока активен ранг IV–V.")
        if tier == 5:
            lines.append("Команда /hat (предмет в руке на голову); шлейф «Сияние».")
        lines.append("Платные цвета ника не входят даже в V: приобретаются отдельно.")
        lines.append("Повторная покупка продлевает срок только если весь оплаченный период помещается в горизонт 365 дней. Младший ранг не заменяет старший; повышение пересчитывает остаток по цене. Невозможная доставка уходит на ручную сверку, а не урезается.")
    elif p["id"] == "homes-plus":
        lines += ["Каждая покупка добавляет +8 мест на собственные 30 дней; повторная покупка не продлевает старый пакет.",
                  "Складывается с базой ранга и всеми активными пакетами, без общего игрового потолка. Ранг V + два пакета = 12 + 8 + 8 = 28 домов.",
                  "Количество пакетов учитывает ядро, не право LuckPerms. При истечении купленных мест сохранённые точки не удаляются."]
    elif p["id"].startswith("trail-"):
        lines.append("Выбор эффекта: /cosmetics. Одновременно используется один шлейф; можно отключить.")
    elif p["id"] == "join-effect":
        lines += ["Включается автоматически при следующем входе, пока активна покупка или ранг IV–V.",
                  "Отдельного переключателя этого эффекта в /cosmetics нет: команда управляет только шлейфом."]
    elif p["id"].startswith("nick-"):
        color = next(c for c in cfg["labels"]["colors"]["list"] if c.get("product") == p["id"])
        lines.append("Прямой выбор купленного цвета: /labels color " + color["id"] + ".")
        lines += ["Выбор в /labels color или /profile → Надписи. Нужна отдельная реальная покупка этого цвета.",
                  "Покупка цвета не добавляет шлейф, эффект входа, ранг или игровые бонусы. По окончании срока платный цвет недоступен, даже если выбран в сохранённом пресете."]
    elif p["id"] == "battlepass-premium":
        lines += ["Открывает золотую дорожку доступных заданий на 90 дней; задания всё равно нужно выполнять. Это срок доступа, не обещание длины сезона и не покупка уровней.",
                  "Награды прогресса — отдельные XP-бусты, а не выдача/продление ранга или других платных товаров. Запас буста по умолчанию 24 часа: расходуйте его перед забором новых наград.",
                  "Ранг IV–V уже открывает этот трек на время действия ранга; отдельная покупка не обязательна."]
    elif p["id"] in {"trails-pack", "look-pack"}:
        names = {x["id"]: plain(x["name"]) for x in cfg["shop"]["products"]}
        lines.append("Состав: " + "; ".join(names[x] for x in p["includes"]) + ".")
        lines.append("Каталожные шлейфы — только пять продаваемых; «Золотая пыль» и ранговое «Сияние» не входят.")
        lines.append("Уже купленные составляющие продлеваются отдельно; итоговые даты могут различаться. Если хотя бы одна часть не помещается в потолок, весь набор отправляется на ручную сверку без частичной выдачи.")
        lines.append("Один платёж — одна команда набора. Не добавляйте отдельные команды его составляющих.")
        if p["id"] == "look-pack":
            lines.append("Поштучно: 1 000 ₽; пять цветов + набор шлейфов: 904 ₽. Цена 599 ₽ экономит 305 ₽ относительно второго способа (401 ₽ относительно поштучного).")
            lines.append("Всего пять шлейфов и пять премиум-цветов. Ранг и эффект входа не входят.")
    if not p.get("rank") and p["id"] not in {"homes-plus", "trails-pack", "look-pack"}:
        lines.append("Повторная покупка добавляет срок к остатку, не усиливает эффект. Потолок 365 дней: если весь оплаченный срок не помещается, доставка не выполняется и требует ручной сверки с магазином.")
    return "\n".join(lines)


def image_name(index, p):
    return f"{index:02d}-{p['id']}.png"


def document(cfg, products):
    intro = f"""ЧЕРДАК — TRADEMC: ВСЕ ТОВАРЫ И КОМАНДЫ
Версия плагинов: {version()}. Каталог: {len(products)} товаров.
Цены и сроки: стандартные настройки этой версии, рубли / календарные дни.
Источник: src/main/resources/config.yml → shop.products.

КАК ЗАПОЛНИТЬ КАРТОЧКУ
1. Создайте товар с указанными ниже ценой, названием и сроком.
2. Прикрепите одноимённую картинку из images/ (PNG, 1200×1200).
3. Скопируйте описание и ОСНОВНУЮ КОМАНДУ. В панели команда без начального /.
4. Доставка — RCON в сервер Minecraft, не чат игрока и не консоль-прокси.
5. %username% — подстановка игрового ника в настройках команды TradeMC.
   Панель должна заменять её на реальный ник; не отправляйте строку буквально.
   Синтаксис доступных подстановок проверьте в своей панели. Это НЕ номер заказа.
6. В CHRDK_Core/config.yml включите trademc.enabled: true. Сохраните актуальный
   shop.products. Закройте RCON от посторонних (пароль, firewall, доверенный IP).
7. На один успешный платёж выполняется ровно ОДНА команда: shopbuy ИЛИ shoppay.
   Не выполняйте обе: они означают две доставки. Не запускайте команды «для теста»
   на рабочем аккаунте: это настоящая выдача. Подтвердите оплату перед доставкой.

Доверенная RCON-доставка магазина работает БЕЗ payment= и без номера заказа.
Идентификатор в локальном журнале — не подтверждение оплаты. Плагин не проверяет
платёж через API TradeMC и не может отличить повтор RCON-команды от новой покупки.
Не включайте автоматические повторные отправки без сверки результата доставки.
Цены панели должны совпадать с каталогом. ID-команда надёжнее команды по сумме.

Скопировать в карточку нужно только строку команды, не слова «Основная команда».
Описание ниже предназначено для покупателя; технические примечания вынесены сюда.
Лимиты и преимущества соответствуют стандартным конфигам; при изменении сервера
обновите описание и заново соберите комплект. Никаких бесплатных платных товаров
за игровой прогресс нет. Отдельный +10% XP-буст не является платным рангом.

"""
    chunks = [intro]
    for index, p in enumerate(products, 1):
        chunks.append(f"""{'=' * 74}
{index:02d}. {plain(p['name'])} — {p['days']} дней
ID: {p['id']}
Цена: {p['price']} ₽
Срок: {p['days']} дней
Картинка: images/{image_name(index, p)}

ОПИСАНИЕ ДЛЯ ТОВАРА:
{description(p, cfg)}

ОСНОВНАЯ КОМАНДА (рекомендуется, RCON):
{command(p)}

АЛЬТЕРНАТИВА ПО СУММЕ (вместо основной, НЕ вместе с ней):
pass shoppay %username% {p['price']}

""")
    chunks.append("""ДОПОЛНИТЕЛЬНЫЕ КОМАНДЫ И ПРИМЕЧАНИЯ ДЛЯ ВЛАДЕЛЬЦА

pass shopgift <ник_получателя> <id_товара> [ник_дарителя]
  Оплаченный подарок через доверенную RCON-доставку. Уголки и квадратные скобки
  показывают синтаксис, а не готовую строку панели. Не выдумывайте подстановки:
  панель должна уметь передавать отдельные поля получателя и дарителя. Если таких
  полей нет, покупатель просто указывает ник друга как игровой ник обычного товара
  и используется основная shopbuy-команда. Отдельная команда подарка не нужна.

pass shopbuy <ник> <id_товара> [дни] payment=<реальный_номер_платежа>
pass shoppay <ник> <сумма> payment=<реальный_номер_платежа>
pass shopgift <ник_получателя> <id_товара> [ник_дарителя] payment=<реальный_номер_платежа>
  Только для РУЧНОЙ серверной консоли с настоящим подтверждением оплаты.
  Не включать эти шаблоны в карточки TradeMC без номера заказа. Не придумывать
  payment= и не добавлять свой срок в стандартные карточки: там срок из каталога.
  Сумма и срок при ручном подтверждении должны соответствовать каталогу.

pass paidaudit
  Только уполномоченный администратор: отчёт по оплатам/доставкам и историческим
  платным правам; не меняет покупки и ничего не отзывает. Сверяйте с кабинетом магазина.
pass luckperms setup
  Однократная настройка групп сервером с LuckPerms; это не команда продажи.
  Купленные дома учитываются ядром независимо от наличия LP и его прав.

Команды для игрока (с / в игровом чате; НЕ добавлять в доставку TradeMC):
/rank my — активный ранг и срок; /rank shop — витрина; /rank top — топ IV–V.
/cosmetics — выбор/отключение шлейфа; эффект входа автоматический, здесь не отключается.
/labels color — выбор купленного цвета ника; /profile — оформление и надписи.
/bp — сезонные задания и доступные награды боевого пропуска.
/hat — предмет в руке на голову, доступен только рангу V.

Проверка перед открытием продаж: резервная копия → установка плагинов из одного
релиза → сверка настроек и цен → защищённый RCON → отдельная согласованная покупка
→ один вызов команды → проверка выдачи/срока → сверка журнала с магазином.
Если доставка отклонена по TIME_LIMIT, RANK_HIGHER или INVALID_BUNDLE, запись
попадает в pass pending. Это запрос ручной сверки, не доказательство оплаты:
сверьте кабинет и журнал, затем решите вопрос возврата или выдачи. Не подтверждайте
запись вслепую: команда approve не является повторной выдачей конкретного товара.
Пакеты не выдаются частично; оплаченные дни не срезаются до потолка.
При включённой платной Boosty-подписке бонусные дни зависят от настроек и остатка
до потолка; это не скидка цены TradeMC и не обещание бонуса поверх 365 дней.
Игроку не выдавайте административные права или права на shopbuy/shoppay/shopgift.
Предупреждение навыков «Двойная добыча» отключено; сама дополнительная добыча
осталась и по-прежнему выпадает на землю с прежним шансом и ограничениями.
""")
    return "".join(chunks)


def markdown_document(cfg, products):
    """Formatted mirror of TXT: identical product data and complete owner appendix."""
    txt = document(cfg, products)
    intro = txt.split("=" * 74, 1)[0].strip()
    intro = intro.replace("ЧЕРДАК — TRADEMC: ВСЕ ТОВАРЫ И КОМАНДЫ", "# ЧЕРДАК — TradeMC: все товары и команды", 1)
    intro = intro.replace("КАК ЗАПОЛНИТЬ КАРТОЧКУ", "## Как заполнить карточку", 1)
    for literal in ("%username%", "CHRDK_Core/config.yml", "trademc.enabled: true", "shop.products", "payment="):
        intro = intro.replace(literal, "`" + literal + "`")
    parts = [intro + f"\n\n## Каталог: {len(products)} товаров\n\n",
             "| № | Товар | Цена | Срок | Картинка |\n| --- | --- | ---: | ---: | --- |\n"]
    for i, p in enumerate(products, 1):
        parts.append(f"| {i:02d} | {plain(p['name'])} | {p['price']} ₽ | {p['days']} дней | [PNG](images/{image_name(i, p)}) |\n")
    for i, p in enumerate(products, 1):
        name, image = plain(p['name']), "images/" + image_name(i, p)
        parts.append(f"\n## {i:02d}. {name} — {p['days']} дней\n\n")
        parts.append(f"- **ID:** `{p['id']}`\n- **Цена:** {p['price']} ₽\n- **Срок:** {p['days']} дней\n- **Картинка:** [{Path(image).name}]({image})\n\n")
        parts.append(f"![{name} — {p['days']} дней, {p['price']} ₽]({image})\n\n### Описание для товара\n\n")
        parts.extend("- " + line + "\n" for line in description(p, cfg).splitlines())
        parts.append(f"\n### Основная команда — рекомендуется, RCON\n\n```text\n{command(p)}\n```\n\n")
        parts.append(f"### Альтернатива по сумме\n\n**Вместо основной команды, не вместе с ней:**\n\n```text\npass shoppay %username% {p['price']}\n```\n")
    appendix = txt.split("ДОПОЛНИТЕЛЬНЫЕ КОМАНДЫ И ПРИМЕЧАНИЯ ДЛЯ ВЛАДЕЛЬЦА\n", 1)[1].strip().splitlines()
    parts.append("\n## Дополнительные команды и примечания для владельца\n\n")
    i = 0
    while i < len(appendix):
        line = appendix[i]
        if line.startswith("pass "):
            commands = []
            while i < len(appendix) and appendix[i].startswith("pass "):
                commands.append(appendix[i])
                i += 1
            parts.append("```text\n" + "\n".join(commands) + "\n```\n\n")
            continue
        if line.startswith("/") and " — " in line:
            line = re.sub(r"(/[a-z]+(?: [a-z]+)?) —", r"`\1` —", line)
            parts.append("- " + line + "\n")
        elif line.startswith("Команды для игрока"):
            parts.append("### " + line.rstrip(":") + "\n\n")
        else:
            parts.append(line.lstrip() + "\n")
        i += 1
    return "".join(parts).rstrip() + "\n"


def font(size, bold=False):
    return ImageFont.truetype(str(FONT_ROOT / ("DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf")), size)


def centered(draw, xy, text, fill, size, bold=False):
    f = font(size, bold)
    while draw.textlength(text, font=f) > 1040 and size > 18:
        size -= 1
        f = font(size, bold)
    draw.text(xy, text, fill=fill, font=f, anchor="mt")


def artwork(p):
    """One distinct square per SKU. Existing rank art; original pixel icons otherwise."""
    tier = p.get("rank", 0)
    accent = [(171, 197, 213), (130, 220, 157), (106, 218, 225),
              (250, 208, 116), (207, 175, 255)][tier - 1] if tier else COLORS.get(p['id'].split('-')[-1], (120, 216, 207))
    base = Image.new("RGB", (300, 300), (14, 21, 31))
    d = ImageDraw.Draw(base)
    # Stable sparkle placement, including a different seed for monthly/yearly cards.
    rng = random.Random(p["id"])
    for _ in range(95):
        x, y = rng.randrange(8, 292), rng.randrange(10, 224)
        d.rectangle((x, y, x + 1, y + 1), fill=tuple(int(c * .25) for c in accent))
    d.rectangle((7, 7, 292, 292), outline=tuple(int(c * .45) for c in accent), width=1)
    d.line((13, 13, 65, 13), fill=accent, width=2)
    d.line((13, 13, 13, 33), fill=accent, width=2)
    d.line((235, 286, 286, 286), fill=accent, width=2)
    if tier:
        with Image.open(ART / (RANK_ART[tier] + ".png")) as art:
            crop = ImageOps.fit(art.convert("RGB"), (268, 153), method=Image.Resampling.LANCZOS)
        base.paste(crop, (16, 49))
        d = ImageDraw.Draw(base)
        d.rectangle((16, 49, 283, 202), outline=accent, width=1)
    else:
        # A raised pixel plinth places each original icon inside the same visual system.
        d.polygon([(60, 181), (150, 155), (240, 181), (150, 209)], fill=(29, 45, 59))
        d.polygon([(60, 181), (150, 209), (150, 218), (60, 190)], fill=(22, 32, 46))
        d.polygon([(150, 209), (240, 181), (240, 190), (150, 218)], fill=(37, 57, 68))
        pid = p["id"]
        if pid == "homes-plus":
            d.rectangle((95, 106, 205, 176), fill=(125, 100, 79))
            for y in range(113, 175, 12):
                d.line((95, y, 205, y), fill=(91, 69, 57), width=2)
            d.polygon([(80, 108), (150, 50), (220, 108)], fill=(104, 190, 170))
            d.polygon([(99, 96), (150, 55), (201, 96)], fill=(140, 221, 184))
            d.rectangle((137, 129, 163, 176), fill=(48, 52, 53))
            for x in (108, 177):
                d.rectangle((x, 122, x + 16, 143), fill=(140, 221, 236))
        elif pid.startswith("nick-"):
            d.rectangle((65, 78, 235, 151), fill=(31, 40, 56), outline=accent, width=3)
            d.polygon([(105, 151), (105, 171), (130, 151)], fill=accent)
            # Render a legible pixelated nickname, then color it in the purchased palette.
            mask = Image.new("L", (150, 32))
            ImageDraw.Draw(mask).text((75, 16), "Cherdak", font=font(27, True), anchor="mm", fill=255)
            colors = Image.new("RGB", mask.size)
            cd = ImageDraw.Draw(colors)
            palette = re.findall(r"#[0-9a-fA-F]{6}", p["name"])
            endpoints = [tuple(bytes.fromhex(h[1:])) for h in palette[:2]]
            for x in range(150):
                color = tuple(round(a + (b-a) * x / 149) for a, b in zip(*endpoints))
                cd.line((x, 0, x, 31), fill=color)
            base.paste(colors, (75, 100), mask)
        elif pid in {"trails-pack", "look-pack"}:
            d.rectangle((94, 113, 207, 176), fill=(109, 80, 53), outline=(240, 199, 105), width=3)
            d.polygon([(94, 113), (150, 81), (207, 113)], fill=(165, 119, 66))
            d.rectangle((146, 113, 157, 175), fill=(248, 215, 122))
            for i, color in enumerate(list(COLORS.values())[:5]):
                x, y = 78 + i * 37, 67 - (i % 2) * 18
                d.polygon([(x, y-9), (x+7, y), (x, y+9), (x-7, y)], fill=color)
            if pid == "look-pack":
                d.rectangle((119, 51, 180, 75), fill=(34, 42, 61), outline=(187, 152, 255), width=2)
                d.text((149, 63), "Aa", font=font(17, True), anchor="mm", fill=(227, 218, 250))
        elif pid == "battlepass-premium":
            d.rectangle((98, 66, 202, 170), fill=(135, 100, 50), outline=(255, 217, 119), width=4)
            for y in (80, 148):
                d.line((110, y, 190, y), fill=(218, 172, 87), width=3)
            d.polygon([(150, 85), (158, 108), (183, 108), (162, 124), (170, 149),
                       (150, 134), (130, 149), (138, 124), (117, 108), (142, 108)], fill=(255, 225, 143))
        elif pid == "join-effect":
            for x, y in ((150, 74), (96, 116), (203, 116), (118, 156), (185, 157)):
                d.polygon([(x, y-17), (x+6, y-5), (x+17, y), (x+6, y+5),
                           (x, y+17), (x-6, y+5), (x-17, y), (x-6, y-5)], fill=(252, 213, 104))
            d.rectangle((135, 114, 165, 159), fill=(146, 202, 103))
            d.rectangle((124, 123, 176, 137), fill=(146, 202, 103))
        elif pid.startswith("trail-"):
            for i in range(15):
                x, y = 83 + i*9, 153 - int(23 * __import__('math').sin(i / 2))
                d.rectangle((x, y, x+4, y+4), fill=tuple(int(c*.6) for c in accent))
            if pid == "trail-hearts":
                d.polygon([(150, 89), (130, 70), (112, 70), (100, 83), (100, 104),
                           (150, 153), (200, 104), (200, 83), (188, 70), (170, 70)], fill=accent)
            elif pid == "trail-notes":
                d.line((137, 133, 137, 73, 190, 59, 190, 120), fill=accent, width=9)
                d.rectangle((122, 124, 140, 139), fill=accent)
                d.rectangle((174, 111, 193, 126), fill=accent)
            elif pid == "trail-flame":
                d.polygon([(108, 126), (114, 92), (130, 106), (153, 54), (169, 83),
                           (175, 103), (191, 89), (196, 128), (175, 151), (132, 151)], fill=accent)
                d.polygon([(135, 131), (151, 92), (174, 133), (160, 150), (145, 150)], fill=(255, 224, 130))
            else:
                for x, y, r in ((150, 99, 38), (98, 69, 11), (204, 143, 14)):
                    d.polygon([(x, y-r), (x+9, y-9), (x+r, y), (x+9, y+9),
                               (x, y+r), (x-9, y+9), (x-r, y), (x-9, y-9)], fill=accent)
                if pid == "trail-magic":
                    d.rectangle((142, 91, 158, 107), fill=(232, 215, 255))
        else:
            raise ValueError(f"No artwork for product: {pid}")
    out = base.resize((1200, 1200), Image.Resampling.NEAREST)
    d = ImageDraw.Draw(out)
    centered(d, (600, 64), "Ч Е Р Д А К  /  М А Г А З И Н", (196, 208, 222), 23, True)
    title = plain(p["name"])
    centered(d, (600, 113), title, accent, 48, True)
    # Unified lower plate, with exactly one price and duration per SKU.
    d.rectangle((63, 833, 1136, 1121), fill=(18, 27, 39))
    d.line((64, 833, 1135, 833), fill=accent, width=3)
    centered(d, (600, 850), f"{p['price']} ₽", (246, 244, 232), 76, True)
    centered(d, (600, 945), f"{p['days']} ДНЕЙ", accent, 35, True)
    if tier:
        tagline = f"РАНГ {['I', 'II', 'III', 'IV', 'V'][tier-1]} · ОФОРМЛЕНИЕ И ВОЗМОЖНОСТИ"
    else:
        tagline = {"homes-plus": "+8 ДОМОВ · СКЛАДЫВАЕТСЯ С РАНГОМ И ПОКУПКАМИ",
                   "join-effect": "ТОТЕМНАЯ ВСПЫШКА И ЗВУК ПРИ ВХОДЕ",
                   "battlepass-premium": "ЗОЛОТАЯ ДОРОЖКА · ЗАДАНИЯ ТЕКУЩЕГО СЕЗОНА",
                   "trails-pack": "5 ШЛЕЙФОВ · ВЫБОР В /COSMETICS",
                   "look-pack": "5 ШЛЕЙФОВ + 5 ПРЕМИУМ-ЦВЕТОВ"}.get(p['id'],
                       "ПЛАТНЫЙ ЦВЕТ НИКА · ВЫБОР В /LABELS COLOR" if p['id'].startswith('nick-')
                       else "КОСМЕТИЧЕСКИЙ ШЛЕЙФ · ВЫБОР В /COSMETICS")
    centered(d, (600, 1028), tagline, (193, 205, 219), 23, True)
    centered(d, (600, 1076), "ТОЧНЫЙ ИГРОВОЙ НИК ПРИ ПОКУПКЕ", (126, 147, 167), 19)
    return out


def manifest(cfg, products):
    result = {"schemaVersion": 1, "pluginVersion": version(),
              "source": "src/main/resources/config.yml → shop.products", "products": []}
    for index, p in enumerate(products, 1):
        path = OUTPUT / "images" / image_name(index, p)
        result["products"].append({"id": p["id"], "name": plain(p["name"]),
            "priceRubles": p["price"], "days": p["days"], "command": command(p),
            "alternativeCommand": f"pass shoppay %username% {p['price']}",
            "description": description(p, cfg), "image": "images/" + path.name,
            "width": 1200, "height": 1200,
            "imageSha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    return result


def manifest_text(cfg, products):
    return json.dumps(manifest(cfg, products), ensure_ascii=False, indent=2) + "\n"


def check():
    cfg, products = catalog()
    expected = {image_name(i, p) for i, p in enumerate(products, 1)}
    actual = {p.name for p in (OUTPUT / "images").glob("*.png")}
    if actual != expected:
        raise ValueError(f"Missing/extra product images: {expected ^ actual}")
    hashes = set()
    for p in sorted((OUTPUT / "images").glob("*.png")):
        with Image.open(p) as image:
            if image.size != (1200, 1200) or image.format != "PNG":
                raise ValueError(f"Wrong image format/size: {p}")
            image.verify()
        hashes.add(hashlib.sha256(p.read_bytes()).hexdigest())
    if len(hashes) != len(products):
        raise ValueError("Duplicate product artwork")
    if (OUTPUT / TEXT_NAME).read_text(encoding="utf-8") != document(cfg, products):
        raise ValueError("Storefront TXT is stale: run tools/trademc/build.py --build")
    if (OUTPUT / MD_NAME).read_text(encoding="utf-8") != markdown_document(cfg, products):
        raise ValueError("Storefront Markdown is stale: run tools/trademc/build.py --build")
    if (OUTPUT / AUDIT_NAME).read_text(encoding="utf-8") != audit_report(ROOT, cfg, products, version()):
        raise ValueError("Storefront audit report is stale")
    if (OUTPUT / "manifest.json").read_text(encoding="utf-8") != manifest_text(cfg, products):
        raise ValueError("Storefront manifest/catalog/hash mismatch")
    print(f"TradeMC OK: {len(products)} products, commands, prices, durations and individual PNGs")


def build():
    cfg, products = catalog()
    (OUTPUT / "images").mkdir(parents=True, exist_ok=True)
    for index, p in enumerate(products, 1):
        artwork(p).save(OUTPUT / "images" / image_name(index, p), optimize=True)
    (OUTPUT / TEXT_NAME).write_text(document(cfg, products), encoding="utf-8")
    (OUTPUT / MD_NAME).write_text(markdown_document(cfg, products), encoding="utf-8")
    (OUTPUT / AUDIT_NAME).write_text(audit_report(ROOT, cfg, products, version()), encoding="utf-8")
    (OUTPUT / "manifest.json").write_text(manifest_text(cfg, products), encoding="utf-8")
    check()


def pack(destination=None):
    check()
    path = Path(destination) if destination else ROOT / f"build/trademc/chrdk-trademc-{version()}.zip"
    path.parent.mkdir(parents=True, exist_ok=True)
    files = [OUTPUT / TEXT_NAME, OUTPUT / MD_NAME, OUTPUT / AUDIT_NAME, OUTPUT / "manifest.json", OUTPUT / "README.md"]
    files += sorted((OUTPUT / "images").glob("*.png"))
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for file in files:
            info = zipfile.ZipInfo(file.relative_to(OUTPUT).as_posix(), (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, file.read_bytes())
    with zipfile.ZipFile(path) as archive:
        if archive.testzip() is not None:
            raise ValueError("Corrupt storefront ZIP")
    print(path)
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", action="store_true", help="Regenerate committed TXT, PNGs and manifest")
    parser.add_argument("--check", action="store_true", help="Validate the committed kit without changing files")
    parser.add_argument("--zip", action="store_true", help="Create the deterministic storefront archive")
    parser.add_argument("--output", help="Optional ZIP destination")
    args = parser.parse_args()
    if args.build:
        build()
    if args.zip:
        pack(args.output)
    elif args.check or not args.build:
        check()


if __name__ == "__main__":
    main()
