package ru.cherdakmd.chrdkenchants;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.Messages;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class UtilityEnchantTest {
    @TempDir Path dir;
    private static final Logger LOG = Logger.getLogger("UtilityEnchantTest");
    @Test void sixPositiveImplementedUtilitiesExtendTheCatalogToSeventyTwo() {
        assertEquals(6, UtilityEnchant.values().length);
        assertEquals(72, EnchantRegistry.size()); assertEquals(57, EnchantRegistry.lightCount());
        assertEquals(15, EnchantRegistry.curseCount());
        for (var utility : UtilityEnchant.values()) {
            var def = EnchantRegistry.byId(utility.id);
            assertNotNull(def); assertFalse(def.curse()); assertEquals(utility.maxLevel, def.maxLevel());
        }
        assertNull(UtilityEnchant.byId(null)); assertNull(UtilityEnchant.byId("crippling_edge"));
    }
    @Test void balanceClampsLevelsAirUnitsAndCooldowns() {
        for (int level : new int[]{Integer.MIN_VALUE, -1, 0, 1, 2, 3, 10, Integer.MAX_VALUE}) {
            assertTrue(UtilityEnchant.CAREFUL_WORK.chance(level) >= 10);
            assertTrue(UtilityEnchant.CAREFUL_WORK.chance(level) <= 30);
            assertTrue(UtilityEnchant.AIR_RESERVE.air(level) >= 40);
            assertTrue(UtilityEnchant.AIR_RESERVE.air(level) <= 60);
        }
        assertEquals(8000, UtilityEnchant.CAREFUL_WORK.cooldownMillis());
        assertEquals(60000, UtilityEnchant.AIR_RESERVE.cooldownMillis());
        assertEquals(0, UtilityEnchant.GARDEN_STEP.cooldownMillis());
        assertEquals(0, UtilityEnchant.QUIET_STEP.chance(1));
    }
    @Test void utilityMasterIsIndependentOfThePveMasterAndIndividualFlags() {
        var yaml = new YamlConfiguration();
        assertEquals(72, EnchantsConfig.parse(yaml, LOG).enabledEnchants().size());
        yaml.set("utility-effects.enabled", false);
        var disabled = EnchantsConfig.parse(yaml, LOG);
        assertEquals(66, disabled.enabledEnchants().size());
        assertTrue(disabled.isEnabled("crippling_edge")); assertTrue(disabled.isEnabled("phoenix"));
        for (var utility : UtilityEnchant.values()) assertFalse(disabled.isEnabled(utility.id));
        yaml.set("pve-effects.enabled", false);
        assertEquals(60, EnchantsConfig.parse(yaml, LOG).enabledEnchants().size());
        yaml.set("utility-effects.enabled", true); yaml.set("enchants.garden_step.enabled", false);
        assertFalse(EnchantsConfig.parse(yaml, LOG).isEnabled("garden_step"));
        assertTrue(EnchantsConfig.parse(yaml, LOG).isEnabled("quiet_step"));
    }
    @Test void utilitiesHaveNarrowItemSupportAndNormalSourceRarityGates() {
        assertTrue(EnchantRegistry.byId("careful_work").supports("NETHERITE_PICKAXE"));
        assertTrue(EnchantRegistry.byId("careful_work").supports("IRON_HOE"));
        assertFalse(EnchantRegistry.byId("careful_work").supports("DIAMOND_SWORD"));
        assertTrue(EnchantRegistry.byId("air_reserve").supports("TURTLE_HELMET"));
        assertFalse(EnchantRegistry.byId("air_reserve").supports("LEATHER_BOOTS"));
        for (String id : List.of("garden_step", "quiet_step")) {
            assertTrue(EnchantRegistry.byId(id).supports("IRON_BOOTS"));
            assertFalse(EnchantRegistry.byId(id).supports("IRON_CHESTPLATE"));
        }
        assertFalse(ru.cherdakmd.chrdkregister.api.EnchantBookPolicy.allows(EnchantRegistry.byId("quiet_step").rarity().name(), 1));
        assertTrue(ru.cherdakmd.chrdkregister.api.EnchantBookPolicy.allows(EnchantRegistry.byId("quiet_step").rarity().name(), 2));
    }
    @Test void workRequiresTheFinalBlockResultSameTickAndSameToolWithoutRepairingAnything() throws Exception {
        String source = source();
        for (String guard : List.of("context.source.isCancelled()", "context.tick != Bukkit.getCurrentTick()",
                "event.getDamage() <= 0", "PveEquipment.same(context.tool, event.getItem())",
                "PveEquipment.same(context.tool, player.getInventory().getItemInMainHand())",
                "event.setDamage(Math.max(0, event.getDamage() - 1))", "work.size() >= LIMIT")) assertTrue(source.contains(guard), guard);
        assertFalse(source.contains("setRemainingDurability")); assertFalse(source.contains("breakNaturally"));
    }
    @Test void airWaitsForFinalCancellationSessionWorldAndHelmetAndHonorsTheMaximum() throws Exception {
        String source = source();
        for (String guard : List.of("event.isCancelled()", "session.equals(sessions.get(id))", "generation != plugin.sourceGeneration()",
                "world.equals(player.getWorld().getUID())", "PveEquipment.same(expected, player.getInventory().getHelmet())",
                "Math.min(player.getMaximumAir()", "!player.isUnderWater()",
                "player.getRemainingAir() <= before", "airPending.containsKey(player.getUniqueId())")) assertTrue(source.contains(guard), guard);
    }
    @Test void stepAndGardenOnlySuppressTheirOwnEnvironmentalEvents() throws Exception {
        String source = source();
        for (String guard : List.of("event.getEvent() == GameEvent.STEP", "!player.isSprinting()", "!player.isGliding()", "!player.isFlying()",
                "event.getAction() == Action.PHYSICAL", "getType() == Material.FARMLAND")) assertTrue(source.contains(guard));
        assertFalse(source.contains("GameEvent.HIT_GROUND"));
        assertFalse(source.contains("setType(")); assertFalse(source.contains("addPotionEffect("));
    }
    @Test void persistentCooldownsAreNotDeletedOnQuitDeathReloadOrWorldChange() throws Exception {
        String source = source();
        assertTrue(source.contains("\"utility_work_ready\"")); assertTrue(source.contains("\"utility_air_ready\""));
        assertTrue(source.contains("set(workReady, PersistentDataType.LONG"));
        assertTrue(source.contains("set(airReady, PersistentDataType.LONG"));
        assertFalse(source.contains("remove(workReady")); assertFalse(source.contains("remove(airReady"));
        assertTrue(source.contains("PlayerQuitEvent")); assertTrue(source.contains("PlayerDeathEvent"));
        assertTrue(source.contains("PlayerChangedWorldEvent")); assertTrue(source.contains("PlayerGameModeChangeEvent"));
    }
    @Test void utilityCodeNeverAddsLootXpMoneyOrAttacksAndRequiresAuthentication() throws Exception {
        String source = source();
        for (String forbidden : List.of("dropItem", "addItem(", "setExpToDrop", "award(", ".damage(", "createExplosion", "setHealth("))
            assertFalse(source.contains(forbidden), forbidden);
        assertTrue(source.contains("player.getGameMode() == GameMode.SURVIVAL"));
        assertTrue(source.contains("player.hasPermission(\"chrdkenchants.use\")"));
        assertTrue(source.contains("plugin.coreApi().isAuthenticated(player)"));
    }
    @Test void realMessageLoaderFindsEveryNameAndDescription() throws Exception {
        try (var stream = getClass().getResourceAsStream("/messages.yml")) {
            assertNotNull(stream);
            var messages = Messages.from(stream, dir.resolve("messages.yml"), LOG);
            for (var utility : UtilityEnchant.values()) {
                assertTrue(messages.has("en." + utility.id + ".name"));
                assertTrue(messages.has("en." + utility.id + ".lore"));
                assertFalse(messages.plain("en." + utility.id + ".name").isBlank());
                assertTrue(messages.getList("en." + utility.id + ".lore").size() >= 2);
            }
        }
    }
    private String source() throws Exception {
        return Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/UtilityEffectsListener.java"));
    }
}
