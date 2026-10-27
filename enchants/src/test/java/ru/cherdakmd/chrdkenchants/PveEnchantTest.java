package ru.cherdakmd.chrdkenchants;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.Messages;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class PveEnchantTest {
    @TempDir Path dir;
    private static final Logger LOG = Logger.getLogger("PveEnchantTest");

    @Test void catalogContainsSixImplementedPositiveDefinitionsAndSixtyLegacyDefinitions() {
        assertEquals(6, PveEnchant.values().length);
        assertEquals(60, EnchantRegistry.all().stream().filter(def -> PveEnchant.byId(def.id()) == null && UtilityEnchant.byId(def.id()) == null).count());
        for (var effect : PveEnchant.values()) {
            var def = EnchantRegistry.byId(effect.id);
            assertNotNull(def); assertFalse(def.curse()); assertEquals(effect.maxLevel, def.maxLevel());
            assertTrue(def.rarity() == EnchantDef.Rarity.RARE || def.rarity() == EnchantDef.Rarity.EPIC);
            assertNotNull(def.conflictGroup());
            assertTrue(effect.effect.equals("SLOWNESS") || effect.effect.equals("WEAKNESS"));
        }
        assertNull(PveEnchant.byId(null)); assertNull(PveEnchant.byId("phoenix"));
    }
    @Test void chancesDurationsAndCooldownsAreBoundedEvenForInvalidPdcLevels() {
        for (var effect : PveEnchant.values()) {
            for (int level : new int[]{Integer.MIN_VALUE, 0, 1, 2, 3, 10, Integer.MAX_VALUE}) {
                assertTrue(effect.level(level) >= 1 && effect.level(level) <= effect.maxLevel);
                assertTrue(effect.chance(level) >= 15 && effect.chance(level) <= 30);
                assertTrue(effect.ticks(level) >= 40 && effect.ticks(level) <= 80);
                assertTrue(effect.cooldownSeconds >= 12 && effect.cooldownSeconds <= 15);
            }
        }
        assertEquals(30, PveEnchant.CRIPPLING_EDGE.chance(3));
        assertEquals(80, PveEnchant.PINNING_SHOT.ticks(3));
        assertEquals(60, PveEnchant.SAPPING_SHOT.ticks(2));
    }
    @Test void pairedControlsConflictButUnrelatedEnchantmentsStillFit() {
        for (var pair : List.of(List.of("crippling_edge", "sapping_edge"), List.of("pinning_shot", "sapping_shot"),
                List.of("briar_guard", "faltering_guard"))) {
            var first = EnchantRegistry.byId(pair.get(0)); var second = EnchantRegistry.byId(pair.get(1));
            assertEquals(first.conflictGroup(), second.conflictGroup());
            String material = first.supports("DIAMOND_SWORD") ? "DIAMOND_SWORD" : first.supports("BOW") ? "BOW" : "IRON_CHESTPLATE";
            assertTrue(EnchantRoller.applicable(List.of(second), material, Map.of(first.id(), 1), 3, 1).isEmpty());
            assertFalse(EnchantRoller.applicable(List.of(second), material, Map.of("scholar", 1), 3, 1).isEmpty());
        }
    }
    @Test void sourceTierOneIncludesRareControlsButNotEpicControls() {
        for (var effect : PveEnchant.values()) {
            var def = EnchantRegistry.byId(effect.id);
            assertEquals(def.rarity() == EnchantDef.Rarity.RARE,
                    ru.cherdakmd.chrdkregister.api.EnchantBookPolicy.allows(def.rarity().name(), 1));
            assertTrue(ru.cherdakmd.chrdkregister.api.EnchantBookPolicy.allows(def.rarity().name(), 2));
        }
    }
    @Test void masterAndIndividualFlagsStopNewSourcesWithoutRemovingLegacyEnchantments() {
        var yaml = new YamlConfiguration();
        assertEquals(70, EnchantsConfig.parse(yaml, LOG).enabledEnchants().size());
        yaml.set("pve-effects.enabled", false);
        var disabled = EnchantsConfig.parse(yaml, LOG);
        assertEquals(64, disabled.enabledEnchants().size()); assertTrue(disabled.isEnabled("phoenix"));
        for (var effect : PveEnchant.values()) assertFalse(disabled.isEnabled(effect.id));
        yaml.set("pve-effects.enabled", true); yaml.set("enchants.crippling_edge.enabled", false);
        assertFalse(EnchantsConfig.parse(yaml, LOG).isEnabled("crippling_edge"));
        assertTrue(EnchantsConfig.parse(yaml, LOG).isEnabled("pinning_shot"));
    }
    @Test void noPlayersPetsNpcsNamedMobsOrBossesCanBeTargets() {
        assertFalse(PveEffectsListener.hostile(null));
        assertFalse(PveEffectsListener.hostile(entity(false, false, Player.class)));
        assertFalse(PveEffectsListener.hostile(entity(false, false, Wolf.class)));
        assertFalse(PveEffectsListener.hostile(entity(false, false, Monster.class, Tameable.class)));
        assertFalse(PveEffectsListener.hostile(entity(false, false, Monster.class, Boss.class)));
        assertFalse(PveEffectsListener.hostile(entity(false, false, Warden.class)));
        assertFalse(PveEffectsListener.hostile(entity(false, false, ElderGuardian.class)));
        assertFalse(PveEffectsListener.hostile(entity(true, false, Monster.class)));
        assertFalse(PveEffectsListener.hostile(entity(false, true, Monster.class)));
        assertTrue(PveEffectsListener.hostile(entity(false, false, Monster.class)));
        assertTrue(PveEffectsListener.hostile(entity(false, false, Slime.class)));
    }
    private LivingEntity entity(boolean npc, boolean named, Class<?>... types) {
        return (LivingEntity) Proxy.newProxyInstance(getClass().getClassLoader(), types,
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasMetadata" -> npc;
                    case "customName" -> named ? net.kyori.adventure.text.Component.text("Boss") : null;
                    default -> null;
                });
    }
    @Test void localizedNamesDescriptionsAndBookHintsAreAvailableThroughTheActualMessageLoader() throws Exception {
        try (var stream = getClass().getResourceAsStream("/messages.yml")) {
            assertNotNull(stream);
            Messages messages = Messages.from(stream, dir.resolve("messages.yml"), LOG);
            for (var effect : PveEnchant.values()) {
                assertTrue(messages.has("en." + effect.id + ".name"));
                assertTrue(messages.has("en." + effect.id + ".lore"));
                assertFalse(messages.plain("en." + effect.id + ".name").isBlank());
                assertTrue(messages.raw("en." + effect.id + ".lore").contains("перезарядка"));
                assertEquals(4, messages.getList("en." + effect.id + ".lore").size());
            }
        }
    }
    @Test void callbacksKeepFinalCancellationShotSessionAndEquipmentGuards() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/PveEffectsListener.java"));
        for (String guard : List.of("event.isCancelled() || event.getFinalDamage() <= 0", "generation != plugin.sourceGeneration()",
                "shot.source.isCancelled()", "shot.source.getProjectile().getUniqueId().equals(projectile)",
                "session.equals(sessions.get(owner.getUniqueId()))", "!hostile(target)", "target.hasPotionEffect(type)",
                "PveEquipment.same(weapon, owner.getInventory().getItemInMainHand())", "PveEquipment.same(armor, owner.getInventory().getArmorContents())",
                "pending >= MAX_PENDING", "shots.size() >= MAX_SHOTS", "shots.remove(arrow.getUniqueId())")) assertTrue(source.contains(guard), guard);
        assertFalse(source.contains("setDamage(")); assertFalse(source.contains("target.damage("));
        assertFalse(source.contains("addItem(")); assertFalse(source.contains("dropItem("));
        assertFalse(source.contains("removePotionEffect("));
    }
    @Test void ordinaryWearIsNormalizedOnACopyWithoutChangingOtherMetadata() {
        var original = wearMeta(27);
        var normalized = (org.bukkit.inventory.meta.Damageable) PveEquipment.normalize(original);
        assertEquals(0, normalized.getDamage());
        assertEquals(27, original.getDamage());
        assertEquals("CHRDK", normalized.getDisplayName());
        assertEquals(123, normalized.getCustomModelData());
        assertNotSame(original, normalized);
    }
    private org.bukkit.inventory.meta.Damageable wearMeta(int damage) {
        var wear = new java.util.concurrent.atomic.AtomicInteger(damage);
        return (org.bukkit.inventory.meta.Damageable) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{org.bukkit.inventory.meta.Damageable.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "clone" -> wearMeta(wear.get());
                    case "getDamage" -> wear.get();
                    case "setDamage" -> { wear.set((Integer) args[0]); yield null; }
                    case "getDisplayName" -> "CHRDK";
                    case "getCustomModelData" -> 123;
                    default -> null;
                });
    }
    @Test void persistentSharedCooldownIsOnlyPaidForAnAppliedEffectAndNeverRemovedOnQuitOrReload() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/PveEffectsListener.java"));
        assertTrue(source.contains("new NamespacedKey(plugin, \"pve_ready_at\")"));
        assertTrue(source.contains("getOrDefault(ready, PersistentDataType.LONG, 0L) > now"));
        assertTrue(source.contains("if (target.addPotionEffect(new PotionEffect"));
        assertTrue(source.contains("set(ready, PersistentDataType.LONG, now + choice.enchant.cooldownSeconds * 1000L)"));
        assertFalse(source.contains("remove(ready)"));
        assertTrue(source.contains("sessions.remove(player.getUniqueId())"));
    }
}
