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
    "skills_max": ("skills", "leveling.max-level"),
    "class_change": ("classes", "general.change-cooldown-days"),
    "class_respec": ("classes", "general.respec-cooldown-hours"),
    "quest_daily": ("quests", "general.claims-per-day"),
    "guild_create": ("guilds", "general.creation-cost"),
    "pet_level": ("pets", "levels.max-level"),
    "pet_radius": ("pets", "general.owner-radius"),
    "daily_rank": ("daily-rewards", "vip.min-rank"),
    "vk_link_boost": ("vk", "rewards.link-boost-hours"),
    "vk_member_boost": ("vk", "rewards.member-boost-hours"),
    "vk_donut_bonus": ("vk", "rewards.donut-shop-bonus-percent"),
    "enchant_max": ("enchants", "rules.max-per-item"),
    "legendary_max": ("enchants", "rules.max-legendary-per-item"),
    "artifact_pulse": ("artifacts", "general.passive-refresh-ticks"),
    "artifact_traits_max": ("artifacts", "traits.max-per-item"),
    "artifact_trait_chance": ("artifacts", "traits.chance-percent"),
    "artifact_cross_chance": ("artifacts", "traits.cross-chance-percent"),
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
    "honor",
}
CORE_STAFF = {"pass", "rankadmin", "socialspy", "mute", "unmute", "guard", "adminpanel", "honoradmin"}


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
        if name == "skills_steps":
            source = (
                self.root
                / "skills/src/main/java/ru/cherdakmd/chrdkskills/SkillRegistry.java"
            ).read_text(encoding="utf-8")
            values = re.findall(r"public static final int PERKS_PER_SKILL = (\d+);", source)
            if len(values) != 1:
                raise ValueError("Изменилась схема каталога ступеней навыков")
            return int(values[0])
        if name == "plugins":
            return len(self.modules)
        if name == "shop_count":
            shop = self.configs["diamond-shop"]
            books = shop.get("books", {})
            return (len(shop["items"])
                    + len(books.get("custom", {}).get("enchants", []))
                    + len(books.get("vanilla", {}).get("enchants", [])))
        if name == "book_count":
            books = self.configs["diamond-shop"].get("books", {})
            return (len(books.get("custom", {}).get("enchants", []))
                    + len(books.get("vanilla", {}).get("enchants", [])))
        if name == "buy_only":
            return sum(
                r.get("sell-enabled", True) is False
                for r in self.configs["diamond-shop"]["items"]
            )
        if name == "artifact_count":
            return len(self.artifact_catalog())
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

    def enchant_catalog(self, detailed=False):
        source = (
            self.root
            / "enchants/src/main/java/ru/cherdakmd/chrdkenchants/EnchantRegistry.java"
        ).read_text(encoding="utf-8")
        records = re.findall(
            r'^\s*register\("([^"\n]+)", (COMMON|RARE|EPIC|LEGENDARY), (\d+), ("[^"\n]+"|null), (LIGHT|CURSE), ([A-Z_, ]+)\);',
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
            "CHESTPLATE": "нагрудник или элитры",
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
        for id, rarity, level, conflict, polarity, supports in sorted(records):
            title = messages.get("en." + id + ".name") or messages.get("en", {}).get(id, {}).get("name")
            if not title:
                raise ValueError("Нет имени чары: " + id)
            if detailed:
                lore = messages.get("en." + id + ".lore", messages.get("en", {}).get(id, {}).get("lore"))
                if not isinstance(lore, list) or len(lore) < 2 or not all(isinstance(line, str) and clean(line) for line in lore):
                    raise ValueError("Нет подробного описания чары: " + id)
                conflicts = [other[0] for other in records if conflict != "null" and other[3] == conflict and other[0] != id]
                rows.append(f"### {clean(title)} — `{id}`\n\n"
                            f"{rarities[rarity].capitalize()} · {'проклятие' if polarity == 'CURSE' else 'светлая'} · максимум {level}. "
                            f"Снаряжение: {', '.join(groups[g.strip()] for g in supports.split(','))}.\n\n"
                            + "\n".join("- " + clean(line) for line in lore)
                            + "\n\nНесовместимо на одном предмете: " + (", ".join('`' + other + '`' for other in sorted(conflicts)) or "нет отдельной группы конфликтов") + ".")
                continue
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

    def artifact_catalog(self):
        base = self.root / "artifacts/src/main"
        source = (base / "java/ru/cherdakmd/chrdkartifacts/ArtifactRegistry.java").read_text(encoding="utf-8")
        records = re.findall(r'register\("([a-z-]+)", (GOOD|CURSED), (PASSIVE|ACTIVE), Material\.([A-Z_]+)(?:, (\d+), (\d+))?\);', source)
        if not records or len(records) != len({r[0] for r in records}) or {r[0] for r in records} != set(self.configs["artifacts"]["artifacts"]):
            raise ValueError("Реестр артефактов не совпадает с конфигурацией")
        messages = yaml.safe_load((base / "resources/messages.yml").read_text(encoding="utf-8"))["messages"]["art"]
        cards = []
        for id, side, mode, material, cooldown, xp in sorted(records):
            text = messages.get(id, {})
            details = text.get("details")
            if not text.get("name") or not isinstance(details, list) or len(details) < 2 or not all(isinstance(line, str) and clean(line) for line in details):
                raise ValueError("Нет подробного описания артефакта: " + id)
            settings = self.configs["artifacts"]["artifacts"][id]
            use = "Пассивный: носить при себе, ПКМ не нужен."
            if mode == "ACTIVE":
                use = f"Активный: ПКМ из руки; цена {settings.get('xp-cost-levels', int(xp))} ур. XP; откат {settings.get('cooldown-seconds', int(cooldown))} сек."
            cards.append(f"### {clean(text['name'])} — `{id}`\n\n"
                         + ("Светлый" if side == "GOOD" else "Проклятый") + ". " + use + "\n\n"
                         + "\n".join("- " + clean(line) for line in details))
        return cards

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
        if name == "shop_book_prices":
            prices = c["diamond-shop"]["books"]["custom"]["prices"]
            labels = {"common": "Обычная", "rare": "Редкая", "epic": "Эпическая", "legendary": "Легендарная"}
            return table(
                ["Редкость кастомной чары", "База за книгу, алм."],
                [(label, prices[key]) for key, label in labels.items()],
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
                "books-custom": "Книги чар: наши",
                "books-vanilla": "Книги чар: ванильные",
            }
            books = c["diamond-shop"].get("books", {})
            extra = {
                "books-custom": len(books.get("custom", {}).get("enchants", [])),
                "books-vanilla": len(books.get("vanilla", {}).get("enchants", [])),
            }
            return table(
                ["Раздел", "Товаров"],
                [
                    (
                        title,
                        sum(r["category"] == cat for r in c["diamond-shop"]["items"])
                        + extra.get(cat, 0),
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
        if name in ("forge_materials", "forge_shop"):
            base = self.root / "tier-forge/src/main"
            messages = yaml.safe_load((base / "resources/messages.yml").read_text(encoding="utf-8"))["tiers"]
            catalog = (self.root / "src/main/java/ru/cherdakmd/chrdkregister/api/ForgeMarketCatalog.java").read_text(encoding="utf-8")
            records = re.findall(
                r'new Sku\("([a-z]+)", "([A-Z_]+)", (\d+), (true|false), (true|false)\)', catalog)
            if len(records) != 8 or len({r[0] for r in records}) != 8:
                raise ValueError("Изменена схема восьми расходников кузни RC16")
            if name == "forge_shop":
                java = (base / "java/ru/cherdakmd/chrdktierforge/ForgeMaterial.java").read_text(encoding="utf-8")
                xp = {}
                for key in ("SEED_XP", "REPAIR_XP", "INSCRIBE_XP", "REMOVE_XP", "NAME_XP"):
                    match = re.search(r"public static final int " + key + r" = (\d+);", java)
                    if match is None: raise ValueError("Нет полной цены XP: " + key)
                    xp[key] = int(match[1])
                cost = {
                    "ascend": "1–16 кристаллов / 9–24 ур.",
                    "temper": "1 ядро / 8+2×ур. ковки",
                    "reforge": "1 печать / 12+3×индекс тира",
                    "seed": f"1 искра / {xp['SEED_XP']} ур.",
                    "repair": f"1 лом / {xp['REPAIR_XP']} ур.",
                    "inscribe": f"1 резец / {xp['INSCRIBE_XP']} ур.",
                    "remove": f"1 смывка / {xp['REMOVE_XP']} ур.",
                    "name": f"1 чернила / {xp['NAME_XP']} ур.",
                }
                return table(
                    ["SKU", "Материал", "Цена за штуку, алм.", "Полная цена операции", "Источник"],
                    [(id, material, int(price), cost[id],
                      "/shop forge")
                     for id, material, price, _seed, hunt in records],
                )
            cards = []
            for id, material, _price, _seed, _hunt in records:
                key = "forge." + id
                lines = messages.get(key + ".details")
                if not messages.get(key + ".name") or not isinstance(lines, list) or len(lines) < 4 or not all(isinstance(line, str) and clean(line) for line in lines):
                    raise ValueError("Нет инструкции расходника: " + id)
                cards.append("### " + clean(messages[key + ".name"]) + " — `" + id + "` (`" + material + "`)\n\n" + "\n".join("- " + clean(line) for line in lines))
            return "\n\n".join(cards)
        if name == "forge_costs":
            java = (self.root / "tier-forge/src/main/java/ru/cherdakmd/chrdktierforge/ForgeMaterial.java").read_text(encoding="utf-8")
            values = []
            for field in ("ASCEND_COSTS", "ASCEND_XP"):
                match = re.search(field + r' = \{([0-9, ]+)\};', java)
                if match is None: raise ValueError("Изменена схема цен возвышения")
                row = [int(n) for n in match[1].split(",")]
                if len(row) != 6 or any(n < 1 for n in row): raise ValueError("Неверные цены возвышения")
                values.append(row)
            registry = (self.root / "tier-forge/src/main/java/ru/cherdakmd/chrdktierforge/TierDef.java").read_text(encoding="utf-8")
            match = re.search(r'LADDER = List.of\((.*?)\);', registry, re.S)
            tiers = re.findall(r'"([a-z]+)"', match[1]) if match else []
            if len(tiers) != 7 or set(tiers) != set(c["tier-forge"]["tiers"]): raise ValueError("Изменена лестница тиров")
            messages = yaml.safe_load((self.root / "tier-forge/src/main/resources/messages.yml").read_text(encoding="utf-8"))["tiers"]
            return table(["Целевой тир", "Кристаллов", "Уровней XP"], [(messages["tier"][tier]["name"], values[0][i], values[1][i]) for i, tier in enumerate(tiers[1:])])
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
        if name == "artifact_details":
            return "\n\n".join(self.artifact_catalog())
        if name == "artifact_sources":
            sources = c["artifacts"]["sources"]
            return table(["Этап источника", "Ключ / тип", "Шанс, %"],
                         [("Генерация добычи", key, chance) for key, chance in sources["chests"].items()]
                         + [("Убийство с зачтённым игроком", key, chance) for key, chance in sources["mobs"].items()]
                         + [("Подходящее рыболовное сокровище", "Дополнительная находка", sources["fishing-treasure-percent"])])
        if name == "artifact_rewards":
            total = len(self.artifact_catalog())
            return table(["Веха, %", "Нужно разных ID", "Часов буста", "Алмазов в /bal"],
                         [(percent, (total * int(percent) + 99) // 100, reward.get("boost-hours", 0), reward.get("diamonds", 0))
                          for percent, reward in sorted(c["artifacts"]["collection"]["rewards"].items(), key=lambda pair: int(pair[0]))])
        if name == "artifact_traits":
            base = self.root / "artifacts/src/main"
            messages = yaml.safe_load((base / "resources/messages.yml").read_text(encoding="utf-8"))["messages"]
            traits = {key.removeprefix("trait."): value for key, value in messages.items() if key.startswith("trait.") and isinstance(value, dict)}
            registry = (base / "java/ru/cherdakmd/chrdkartifacts/ArtifactTraits.java").read_text(encoding="utf-8")
            records = re.findall(r'^\s*(good|bad|badRaw)\("([a-z-]+)"', registry, re.M)
            records += [("good" if sign == "GOOD" else "bad", id) for id, sign in re.findall(r'^\s*potion\("([a-z-]+)", Polarity\.(GOOD|BAD)', registry, re.M)]
            if {id for _, id in records} != set(traits):
                raise ValueError("Реестр свойств артефактов не совпадает с сообщениями")
            return table(["ID свойства", "Название", "Знак", "Обзор уровней I–III"],
                         [(id, traits[id]["name"], "▲ польза" if kind == "good" else "▼ вред", traits[id]["desc"]) for kind, id in sorted(records, key=lambda pair: pair[1])])
        if name == "enchant_sources":
            s = c["enchants"]["sources"]
            return table(
                ["Источник", "Шанс по конфигу, %"],
                [
                    ("Стол: дополнительная чара", s["table"]["chance-percent"]),
                    ("Стол: доля проклятий среди успешных доп. бросков", s["table"]["curse-chance-percent"]),
                    (
                        "Замена предложения библиотекаря",
                        s["villager"]["convert-chance-percent"],
                    ),
                ],
            )
        if name == "enchant_details":
            return "\n\n".join(self.enchant_catalog(detailed=True))
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
