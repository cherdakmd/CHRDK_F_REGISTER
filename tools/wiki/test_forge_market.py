"""RC16: контракт раздела Кузня / /shop forge, сверяемый с исходниками.

Это source-inspection и конфигурационные проверки, НЕ запуск Minecraft и НЕ
native CraftItemStack codec. Живой серверный smoke описан отдельно в
docs/audits/forge-shop-rc16.md и в этом наборе не выдаётся за проведённый.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / "src/main/java/ru/cherdakmd/chrdkregister"
FORGE = ROOT / "tier-forge/src/main/java/ru/cherdakmd/chrdktierforge"
SHOP = ROOT / "diamond-shop/src/main/java/ru/cherdakmd/diamondshop"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class ForgeMarketContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog = read(CORE / "api/ForgeMarketCatalog.java")
        cls.exchange = read(CORE / "api/ShopExchange.java")
        cls.inventory = read(CORE / "api/ShopInventory.java")
        cls.api = read(CORE / "api/ChrdkRegisterApi.java")
        cls.bank = read(ROOT / "src/main/java/ru/cherdakmd/chrdkregister/BankService.java")
        cls.forge_material = read(FORGE / "ForgeMaterial.java")
        cls.materials = read(FORGE / "ForgeMaterials.java")
        cls.diagnostics = read(FORGE / "ForgeInputDiagnostics.java")
        cls.items = read(FORGE / "TierItems.java")
        cls.anvil = read(FORGE / "ForgeAnvilListener.java")
        cls.service = read(SHOP / "ForgeShopService.java")
        cls.prices = read(SHOP / "ForgeShopPrices.java")
        cls.store = read(SHOP / "ShopStore.java")
        cls.journal = read(SHOP / "ShopJournal.java")
        cls.shop_config = yaml.safe_load(
            read(ROOT / "diamond-shop/src/main/resources/config.yml"))
        cls.forge_config = yaml.safe_load(
            read(ROOT / "tier-forge/src/main/resources/config.yml"))
        cls.messages = yaml.safe_load(
            read(ROOT / "tier-forge/src/main/resources/messages.yml"))["tiers"]

    def assemble_block(self):
        start = self.items.index("private ItemStack assemble")
        end = self.items.index("public void bumpUpgrade(", start)
        return self.items[start:end]

    def test_version_and_core_api_are_current_release(self):
        props = read(ROOT / "gradle.properties")
        version = re.search(r"^plugin_version=(.+)$", props, re.M)
        self.assertIsNotNone(version, "plugin_version обязателен")
        current = version.group(1).strip()
        self.assertRegex(current, r"^\d+\.\d+(?:\.\d+)?(?:-fix|-rc\.[1-9]\d*)?$", "версия соответствует стабильному/fix/RC формату")
        self.assertTrue((ROOT / "docs/releases" / (current + ".md")).is_file(),
                        "для текущего выпуска нужны полные заметки в архиве")
        if "-rc." not in current:
            self.assertTrue((ROOT / "docs/social/releases" / (current + ".json")).is_file(),
                            "для стабильного анонса нужна краткая заметка игрокам")
        else:
            self.assertTrue((ROOT / "docs/social/prereleases" / (current + ".json")).is_file(),
                            "без RC-заметки workflow молча пропустит VK-анонс с картинкой")
        self.assertIn("int API_VERSION = 44;", self.api)
        self.assertIn("ForgeMaterialSource forgeMaterialSource()", self.api)
        self.assertIn("ForgeMarketCatalog", self.exchange)
        self.assertIn("42", read(FORGE / "TierForgePlugin.java"))

    def test_core_whitelist_retained_but_shop_has_only_eight_forge_skus(self):
        records = re.findall(
            r'new Sku\("([a-z_]+)", "([A-Z_]+)", (\d+), (true|false), (true|false)\)',
            self.catalog,
        )
        self.assertEqual(18, len(records))
        expected = {
            "ascend": ("ECHO_SHARD", 128), "temper": ("NAUTILUS_SHELL", 96),
            "reforge": ("PAPER", 160), "seed": ("NETHER_STAR", 64),
            "repair": ("IRON_INGOT", 24), "inscribe": ("GOLD_NUGGET", 192),
            "remove": ("GUNPOWDER", 32), "name": ("INK_SAC", 16),
            **{f"skill_xp_{skill}": ("POTION", 96) for skill in (
                "mining", "woodcutting", "digging", "farming", "fishing",
                "combat", "archery", "defense", "alchemy", "enchanting")},
        }
        self.assertEqual(set(expected), {r[0] for r in records})
        for sku, material, price, seed, hunt in records:
            self.assertEqual(expected[sku], (material, int(price)))
        prices = self.shop_config["forge-shop"]["prices"]
        self.assertEqual({k: v[1] for k, v in expected.items() if not k.startswith("skill_xp_")}, prices)
        self.assertIn("if (isPotionSku(sku.id())) continue;", self.service)
        self.assertIn('if (isPotionSku(offer.sku())) return fail("forge.shop.closed", 0);', self.service)
        self.assertTrue(self.shop_config["forge-shop"]["enabled"])
        self.assertIn("validPrice", self.catalog)
        self.assertIn("закрыт", self.prices)

    def test_shop_config_matches_code_and_closes_invalid_price(self):
        self.assertIn("ForgeMarketCatalog.validPrice(value)", self.prices)
        self.assertIn("prices.put(sku.id(), -1L)", self.prices)
        self.assertIn("logger.warning", self.prices)
        self.assertIn("forge-shop.enabled", read(SHOP / "ForgeShopPrices.java"))
        self.assertIn("no-buyback", read(ROOT / "diamond-shop/src/main/resources/messages.yml"))

    def test_all_materials_are_shop_only_after_hunt_removal(self):
        self.assertNotIn("drop-percent", self.forge_config["forge-materials"])
        self.assertNotIn("QualifiedHuntKillEvent", self.materials)
        self.assertNotIn("import org.bukkit.event.entity.EntityDeathEvent", self.materials)
        self.assertNotIn("EntityDeathEvent event", self.materials)
        self.assertIn("SHOP_ONLY", self.forge_material)
        self.assertIn("public boolean shopOnly() { return true; }", self.forge_material)
        self.assertIn("public boolean huntDrop() { return false; }", self.forge_material)

    def test_xp_is_levels_with_exact_full_prices(self):
        for key, value in (("SEED_XP", 8), ("REPAIR_XP", 6),
                           ("INSCRIBE_XP", 10), ("REMOVE_XP", 4), ("NAME_XP", 2)):
            self.assertIn(f"public static final int {key} = {value};", self.forge_material)
        self.assertIn("ASCEND_COSTS = {1, 1, 2, 4, 8, 16};", self.forge_material)
        self.assertIn("ASCEND_XP = {9, 12, 15, 18, 21, 24};", self.forge_material)
        for key in ("forge.seed.details", "forge.repair.details", "forge.inscribe.details",
                    "forge.remove.details", "forge.name.details"):
            text = " ".join(self.messages[key])
            self.assertRegex(text, r"\d+ (?:уровней|уровня|уровень) XP")
            self.assertIn("ЛКМ", text)
            self.assertIn("слева", text)
            self.assertIn("справа", text)
            self.assertNotIn("/tiers forge", text)

    def test_seed_uid_is_12_hex_and_never_passes_36_char_uuid_to_valid_uid(self):
        self.assertIn("public static String seedUid(String canonicalUuid)", self.forge_material)
        self.assertIn("TierModifierIds.UID_LENGTH", self.forge_material)
        self.assertIn("ForgeMaterial.seedUid(canonical)", self.materials)
        self.assertIn("TierModifierIds.validUid(uidValue)", self.materials)
        self.assertIn("canonicalUuid(canonical)", self.materials)
        self.assertNotIn("validUid(canonical)", self.materials)
        self.assertIn("forge_seed_uuid", self.catalog)
        self.assertIn("forge_seed_stack1", self.catalog)
        self.assertIn("setMaxStackOne", self.materials)
        self.assertIn("UUID.randomUUID().toString()", self.materials)

    def test_seed_is_only_way_to_first_tier_and_preserves_foreign_metadata(self):
        # 7 ноября 2026: отказ искры больше не падает в общий forge-input
        # (тот текст требовал тир-вещь, хотя искре нужна вещь БЕЗ тира).
        self.assertIn("if (tierId != null) { fail(ForgeInputDiagnostics.SEED_TIERED); return null; }", self.materials)
        self.assertIn("plugin.config().find(\"common\")", self.materials)
        self.assertIn("forgeMaterialCopy(main, tier, properties, uidValue, next, null, false)", self.materials)
        self.assertIn("isUnidentified(main)", self.materials)
        self.assertIn("ForgeInputDiagnostics.of(main.getAmount(),", self.materials)
        self.assertIn("public static final String STACK = \"forge-input-stack\"", self.diagnostics)
        self.assertIn("refreshTierModifiers(copy); // migration-only", self.assemble_block())
        self.assertNotIn("applyEnchantments", self.assemble_block())

    def test_frozen_grant_is_one_native_prototype_and_legacy_receipts_survive(self):
        self.assertIn("String grant)", self.exchange)
        self.assertIn("public boolean carriesGrant()", self.exchange)
        self.assertIn("ForgeMarketCatalog.validTradeGrant(grant,material,units)", self.exchange)
        self.assertEqual(4, self.exchange.count("public ShopExchange(UUID t,UUID m,long s,UUID o,boolean buy"))
        self.assertIn("items[0] = copy", self.catalog)
        self.assertIn("new ItemStack[36]", self.catalog)
        self.assertIn("ChargeInventory.encode(items)", self.catalog)
        self.assertIn("ChargeInventory.decode(grant)[0]", self.catalog)
        self.assertIn("PersistentDataType.STRING", self.catalog)
        self.assertIn("getMaxStackSize() != 1 && !marked", self.catalog)

    def test_core_replays_payment_and_exact_clone_insertion_against_full_native_after(self):
        self.assertIn("public static ItemStack[] replay(ShopExchange t)", self.inventory)
        self.assertIn("public static boolean insertExact(ItemStack[] items, ItemStack prototype, int units)",
                      self.inventory)
        self.assertIn("stack.isSimilar(prototype)", self.inventory)
        self.assertIn("ChargeInventory.matches(items, t.after())", self.inventory)
        self.assertIn("ChargeInventory.matches(replayed,t.after())", self.bank.replace(" ", ""))
        self.assertIn("ShopInventory.replay(t)", self.bank)

    def test_previous_wal_is_used_without_new_wallet_or_separate_give(self):
        self.assertIn("prepareForge(", self.store)
        self.assertIn("boolean forge", self.journal)
        self.assertIn("if(forge) return valid()", self.journal)
        self.assertIn("if(!p.forge())", self.store)
        self.assertIn("service.recoverPending()", self.service)
        self.assertIn("ShopInventory.insertExact(after, offer.prototype(), units)", self.service)
        self.assertNotIn("addItem(", self.service)
        self.assertNotIn("dispatchCommand", self.service)
        self.assertIn("WholeTrades.take(amounts, values, needed)", self.service)
        self.assertIn("WholeTrades.payment(money, wallet, Math.multiplyExact(spent,physicalMinor))", self.service)
        self.assertIn("ShopExchange.fractional(trade,marketQuote.rateBps())", self.service)
        self.assertIn("service.balance(player)", self.service)

    def test_migration_only_lore_and_single_final_append(self):
        self.assertIn("private void refreshOwnedLore", self.items)
        self.assertIn("if (!recognized) return before;", read(FORGE / "TierLore.java"))
        self.assertIn("ItemStack forgeMaterialCopy(", self.items)
        self.assertIn("refreshTierModifiers(copy); // migration-only", self.items)
        self.assertIn("lore.add(tierLine(tier, kind));", self.items)
        self.assertEqual(1, self.assemble_block().count("lore.add(tierLine(tier, kind));"))
        self.assertTrue((ROOT / "tier-forge/src/main/resources/"
                         "tierforge-messages-legacy-1.4.282-rc15.yml").is_file())
        self.assertIn("tierforge-messages-legacy-1.4.282-rc15.yml", self.materials)
        self.assertIn("Messages.fromChecked", self.materials)

    def test_anvil_keeps_preview_choice_full_xp_and_revalidation(self):
        self.assertIn("AnvilCosts.price(view,state.plan.xp())", self.anvil)
        self.assertIn("setRepairItemCountCost(state.plan.units())", self.anvil)
        self.assertIn("current(player,view,state)", self.anvil)
        self.assertIn("state.plan.result().clone()", self.anvil)
        for sku in ("SEED", "REPAIR", "INSCRIBE", "REMOVE", "NAME"):
            self.assertIn("ForgeMaterial." + sku, self.anvil)

    def test_smoke_and_crash_atomic_are_not_claimed_as_done(self):
        audit = read(ROOT / "docs/audits/forge-shop-rc16.md")
        notes = read(ROOT / "docs/releases/1.4.282.md")
        memo = read(ROOT / "docs/releases/update-memo.txt")
        self.assertIn("НЕ ПРОВОДИЛСЯ", audit)
        self.assertIn("не заменяют", notes)
        self.assertIn("НЕ обещается общая crash-atomic", memo)
        self.assertIn("1.4.282-rc.16", memo)


if __name__ == "__main__":
    unittest.main()
