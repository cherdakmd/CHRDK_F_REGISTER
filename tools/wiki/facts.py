"""Публичные факты вики: только явно разрешённые поля поставляемых конфигов."""

from __future__ import annotations
import re
from pathlib import Path
import yaml

ROOT = Path(__file__).resolve().parents[2]

# Токен -> модуль, путь. Произвольное чтение YAML намеренно запрещено: в нём есть секреты.
FIELDS = {
    "password_min": ("core", "auth.min-password-length"),
    "password_max": ("core", "auth.max-password-length"),
    "chat_radius": ("core", "chat.local-radius"),
    "pay_min": ("core", "economy.min-pay"),
    "pay_max": ("core", "economy.max-pay"),
    "pay_tax": ("core", "economy.pay-tax-percent"),
    "goals_active": ("common-goals", "rotation.active-count"),
    "goals_days": ("common-goals", "rotation.period-days"),
    "boost_bank": ("core", "reward-boost.max-bank-hours"),
    "welcome_boost": ("core", "reward-boost.welcome-hours"),
    "claim_price": ("claims", "pricing.chunk-price"),
    "rent_min": ("claims", "pricing.rental-min-per-day"),
    "sethome_cost": ("teleports", "costs.sethome-diamonds"),
    "claim_upkeep": ("claims", "pricing.upkeep-per-day"),
    "claim_grace": ("claims", "pricing.grace-days"),
    "claim_tax": ("claims", "taxes.sale-tax-percent"),
    "claim_tp": ("claims", "teleport.cost-diamonds"),
    "rtp_cost": ("teleports", "costs.rtp-diamonds"),
    "tpa_cost": ("teleports", "costs.tpa-diamonds"),
    "home_extra": ("teleports", "homes.extra-limit"),
    "home_cost": ("teleports", "costs.home-diamonds"),
    "tp_delay": ("teleports", "teleport.delay-seconds"),
    "rtp_cooldown": ("teleports", "rtp.cooldown-seconds"),
    "auction_min": ("auction", "market.min-price"),
    "auction_max": ("auction", "market.max-price"),
    "auction_fee": ("auction", "market.fee-percent"),
    "auction_hours": ("auction", "market.duration-hours"),
    "orders_daily": ("orders", "limits.max-per-day"),
    "orders_slots": ("orders", "board.size"),
    "orders_hours": ("orders", "board.order-hours"),
    "ticket_cost": ("lottery", "tickets.cost-diamonds"),
    "ticket_max": ("lottery", "tickets.max-per-player"),
    "lottery_time": ("lottery", "draw.time"),
    "lottery_timezone": ("lottery", "draw.timezone"),
    "lottery_payout": ("lottery", "pot.payout-percent"),
    "profession_daily": ("professions", "general.daily-xp-cap"),
    "profession_change": ("professions", "general.choose-cooldown-hours"),
    "skills_max": ("skills", "leveling.max-level"),
    "class_change": ("classes", "general.change-cooldown-days"),
    "class_respec": ("classes", "general.respec-cooldown-hours"),
    "quest_daily": ("quests", "general.claims-per-day"),
    "party_size": ("party", "limits.max-size"),
    "party_radius": ("party", "bonus.radius"),
    "party_bonus": ("party", "bonus.max-percent"),
    "guild_create": ("guilds", "general.creation-cost"),
    "pet_level": ("pets", "levels.max-level"),
    "pet_radius": ("pets", "general.owner-radius"),
    "hunt_exchange": ("hunt", "limits.coins-per-diamond"),
    "hunt_daily": ("hunt", "limits.exchange-per-day"),
    "hunt_coins_daily": ("hunt", "limits.coins-per-day"),
    "daily_rank": ("daily-rewards", "vip.min-rank"),
    "ref_inviter": ("referrals", "bonus-hours.inviter"),
    "ref_invitee": ("referrals", "bonus-hours.invitee"),
    "enchant_max": ("enchants", "rules.max-per-item"),
    "legendary_max": ("enchants", "rules.max-legendary-per-item"),
    "season_name": ("battlepass", "battlepass.season-name"),
    "season_start": ("battlepass", "battlepass.season-start"),
}
PRODUCT_FIELDS = {
    "bundle_price": ("all-inclusive", "price"),
    "bundle_days": ("all-inclusive", "days"),
    "bundle_includes_days": ("all-inclusive", "includes-days"),
    "bp_product_days": ("battlepass-premium", "days"),
    "home_product_days": ("homes-plus", "days"),
}
CORE_PUBLIC = {
    "register",
    "login",
    "profile",
    "menu",
    "rank",
    "hat",
    "donate",
    "wiki",
    "labels",
    "captcha",
    "changepassword",
    "2fa",
    "chat",
    "msg",
    "reply",
    "ignore",
    "unignore",
    "logout",
    "session",
    "guide",
    "bal",
    "pay",
    "baltop",
}
CORE_STAFF = {"pass", "rankadmin", "socialspy", "mute", "unmute", "guard"}


def clean(value):
    return re.sub(r"<[^>]*>|&[0-9a-fk-or]", "", str(value)).strip()


def cell(value):
    return clean(value).replace("|", "\\|").replace("\n", " ")


def table(headers, rows):
    return "\n".join(
        [
            "| " + " | ".join(headers) + " |",
            "| " + " | ".join("---" for _ in headers) + " |",
            *["| " + " | ".join(cell(c) for c in row) + " |" for row in rows],
        ]
    )


class FactBook:
    def __init__(self, root=ROOT):
        self.root = Path(root)
        settings = (self.root / "settings.gradle").read_text(encoding="utf-8")
        self.modules = [
            "core",
            *re.findall(r"^include\s+['\"]([^'\"]+)['\"]", settings, re.M),
        ]
        self.configs = {}
        self.descriptors = {}
        for module in self.modules:
            base = self.root / (
                "src/main/resources"
                if module == "core"
                else f"{module}/src/main/resources"
            )
            self.configs[module] = yaml.safe_load(
                (base / "config.yml").read_text(encoding="utf-8")
            )
            self.descriptors[module] = yaml.safe_load(
                (base / "plugin.yml").read_text(encoding="utf-8")
            )
        for name in FIELDS:
            self.value(
                name
            )  # Смена схемы не должна незаметно превращаться в пустую подпись.

    def value(self, name):
        if name in PRODUCT_FIELDS:
            product, key = PRODUCT_FIELDS[name]
            found = [
                p
                for p in self.configs["core"]["shop"]["products"]
                if p["id"] == product
            ]
            if len(found) != 1 or key not in found[0]:
                raise ValueError("Изменена схема публичного товара: " + product)
            return clean(found[0][key])
        if name == "boost_percent":
            source = (
                self.root
                / "src/main/java/ru/cherdakmd/chrdkregister/RewardBoostMath.java"
            ).read_text(encoding="utf-8")
            values = re.findall(r"static final int PERCENT = (\d+);", source)
            if len(values) != 1:
                raise ValueError("Изменилась схема константы игрового буста")
            return int(values[0])
        if name == "plugins":
            return len(self.modules)
        if name == "shop_count":
            return len(self.configs["diamond-shop"]["items"])
        if name == "buy_only":
            return sum(
                r.get("sell-enabled", True) is False
                for r in self.configs["diamond-shop"]["items"]
            )
        if name == "enchant_count":
            return len(self.configs["enchants"]["enchants"])
        if name == "daily_days":
            return len(self.configs["daily-rewards"]["rewards"])
        if name not in FIELDS:
            raise ValueError(f"Неизвестный публичный факт: {name}")
        module, path = FIELDS[name]
        value = self.configs[module]
        for key in path.split("."):
            if not isinstance(value, dict) or key not in value:
                raise ValueError(f"Нет поля факта {name}: {module}/{path}")
            value = value[key]
        if not isinstance(value, (str, int, float)) or isinstance(value, bool):
            raise ValueError(f"Не скалярный факт: {name}")
        return clean(value)

    def commands(self, module=None):
        rows = []
        for m in self.modules:
            if module and m != module:
                continue
            desc = self.descriptors[m]
            for command, data in desc.get("commands", {}).items():
                command = str(command)
                data = data or {}
                if m == "core":
                    if command not in CORE_PUBLIC | CORE_STAFF:
                        raise ValueError(
                            f"Новая команда ядра требует классификации: {command}"
                        )
                    if command not in CORE_PUBLIC:
                        continue
                elif "admin" in command or ".admin" in data.get("permission", ""):
                    continue
                aliases = data.get("aliases", [])
                if isinstance(aliases, str):
                    aliases = [aliases]
                # /prof занят основной командой профессий, а не стабильным алиасом профиля.
                if m == "core" and command == "profile":
                    aliases = [a for a in aliases if a != "prof"]
                rows.append(
                    (
                        m,
                        command,
                        ", ".join("/" + str(a) for a in aliases) or "—",
                        data.get("description", ""),
                    )
                )
        return rows

    def enchant_catalog(self):
        source = (
            self.root
            / "enchants/src/main/java/ru/cherdakmd/chrdkenchants/EnchantRegistry.java"
        ).read_text(encoding="utf-8")
        records = re.findall(
            r'^\s*register\("([^"\n]+)", (COMMON|RARE|EPIC|LEGENDARY), (\d+), (?:"[^"\n]+"|null), (LIGHT|CURSE), ([A-Z_, ]+)\);',
            source,
            re.M,
        )
        if {row[0] for row in records} != set(self.configs["enchants"]["enchants"]):
            raise ValueError(
                "Реестр чар изменил схему или не совпадает с конфигурацией"
            )
        messages = yaml.safe_load(
            (self.root / "enchants/src/main/resources/messages.yml").read_text(
                encoding="utf-8"
            )
        )["enchants"]
        groups = {
            "SWORD": "меч",
            "AXE": "топор",
            "BOW": "лук",
            "CROSSBOW": "арбалет",
            "HELMET": "шлем",
            "CHESTPLATE": "нагрудник",
            "LEGGINGS": "поножи",
            "BOOTS": "ботинки",
            "PICKAXE": "кирка",
            "SHOVEL": "лопата",
            "HOE": "мотыга",
            "ANY": "любое снаряжение",
        }
        rarities = {
            "COMMON": "обычная",
            "RARE": "редкая",
            "EPIC": "эпическая",
            "LEGENDARY": "легендарная",
        }
        rows = []
        for id, rarity, level, polarity, supports in sorted(records):
            title = messages.get("en", {}).get(id, {}).get("name") or messages.get(
                "en." + id + ".name"
            )
            if not title:
                raise ValueError("Нет имени чары: " + id)
            rows.append(
                (
                    f"`{id}`",
                    title,
                    rarities[rarity],
                    level,
                    "проклятие" if polarity == "CURSE" else "светлая",
                    ", ".join(groups[g.strip()] for g in supports.split(",")),
                )
            )
        return rows

    def render_table(self, name):
        c = self.configs
        if name == "commands" or name.startswith("commands."):
            module = None if name == "commands" else name.split(".", 1)[1]
            if module and module not in self.modules:
                raise ValueError(f"Неизвестный модуль: {module}")
            return table(
                ["Команда", "Алиасы", "Назначение"],
                [(f"`/{cmd}`", a, d) for _, cmd, a, d in self.commands(module)],
            )
        if name == "shop":
            return table(
                ["Материал / ID", "Раздел", "База mid за штуку, алм.", "Тариф покупки", "Обратная скупка"],
                [
                    (
                        f"`{r['material'].lower()}`",
                        r["category"],
                        r["base-price"],
                        "×" + str(c["diamond-shop"]["pricing"]["purchase-multipliers"][r["category"]]),
                        "да" if r.get("sell-enabled", True) else "нет",
                    )
                    for r in c["diamond-shop"]["items"]
                ],
            )
        if name == "shop_categories":
            titles = {
                "blocks": "Блоки",
                "wood": "Дерево",
                "minerals": "Руды и металлы",
                "mobs": "Добыча мобов",
                "food": "Еда и ферма",
                "utility": "Полезное",
            }
            return table(
                ["Раздел", "Товаров"],
                [
                    (
                        title,
                        sum(r["category"] == cat for r in c["diamond-shop"]["items"]),
                    )
                    for cat, title in titles.items()
                ],
            )
        if name == "ranks":
            rows = []
            for r in c["core"]["ranks"]["levels"]:
                rank = r["level"]
                products = [
                    p for p in c["core"]["shop"]["products"] if p.get("rank") == rank
                ]
                rows.append(
                    (
                        rank,
                        r["name"],
                        next(p["price"] for p in products if p["days"] == 30),
                        next(p["price"] for p in products if p["days"] == 365),
                        c["teleports"]["homes"]["rank-limits"][rank],
                        c["claims"]["limits"]["rank-chunks"][rank],
                    )
                )
            return table(
                ["Ступень", "Ранг", "30 дней, ₽", "365 дней, ₽", "Дома", "Чанки"], rows
            )
        if name == "products":
            return table(
                ["ID", "Название", "Цена, ₽", "Дней"],
                [
                    (f"`{p['id']}`", p["name"], p["price"], p["days"])
                    for p in c["core"]["shop"]["products"]
                ],
            )
        if name == "menu":
            return table(
                ["Раздел", "Пунктов"],
                [
                    (
                        r["name"],
                        sum(
                            e.get("category") == r["id"]
                            for e in c["core"]["server-menu"]["items"]
                        ),
                    )
                    for r in c["core"]["server-menu"]["categories"]
                ],
            )
        if name == "limits":
            return table(
                ["Ступень (0 — без ранга)", "Дома", "Чанки", "Лоты аукциона"],
                [
                    (
                        i,
                        c["teleports"]["homes"]["rank-limits"][i],
                        c["claims"]["limits"]["rank-chunks"][i],
                        c["auction"]["market"]["rank-lots"][i],
                    )
                    for i in range(6)
                ],
            )
        if name == "titles":
            return table(
                ["Часов онлайн", "Звание"],
                [
                    (r["hours"], r["name"])
                    for r in sorted(
                        c["ranks"]["ranks"]["ladder"], key=lambda r: r["hours"]
                    )
                ],
            )
        if name == "tiers":
            return table(
                ["ID редкости", "Вес розыгрыша", "Базовый модификатор, %"],
                [
                    (k, v["weight"], v["percent"])
                    for k, v in c["tier-forge"]["tiers"].items()
                ],
            )
        if name == "daily":
            return table(
                ["День цикла", "Обычная награда", "Расширенная награда"],
                [
                    (
                        r["day"],
                        ", ".join(r.get("items", [])),
                        ", ".join(r.get("vip-items", [])),
                    )
                    for r in c["daily-rewards"]["rewards"]
                ],
            )
        if name == "enchant_sources":
            s = c["enchants"]["sources"]
            return table(
                ["Источник", "Шанс по конфигу, %"],
                [
                    ("Стол: дополнительная чара", s["table"]["chance-percent"]),
                    ("Стол: проклятие", s["table"]["curse-chance-percent"]),
                    ("Обычный моб", s["mobs"]["regular-chance-percent"]),
                    ("Элитный моб", s["mobs"]["elite-chance-percent"]),
                    (
                        "Замена предложения библиотекаря",
                        s["villager"]["convert-chance-percent"],
                    ),
                ],
            )
        if name == "enchants":
            return table(
                ["ID", "Название", "Редкость", "Макс. уровень", "Полюс", "Снаряжение"],
                self.enchant_catalog(),
            )
        if name == "modules":
            return table(
                ["Плагин", "Модуль"],
                [(self.descriptors[m]["name"], m) for m in self.modules],
            )
        raise ValueError(f"Неизвестная таблица: {name}")

    def expand(self, text):
        def replace(match):
            kind, name = match.groups()
            return str(self.value(name)) if kind == "fact" else self.render_table(name)

        result = re.sub(
            r"\{\{\s*(fact|table):([a-z_][a-z0-9_.-]*)\s*\}\}", replace, text
        )
        if "{{" in result or "}}" in result:
            raise ValueError("Необработанная директива вики")
        return result
