package ru.cherdakmd.chrdkskills;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SkillAbilityTest {
    @TempDir Path folder;
    private static final UUID PLAYER = UUID.fromString("313a19b4-4539-4a8b-a503-1bf9ca534e80");
    private static final long NOW = 1_800_000_000_000L;
    private static final Logger LOG = Logger.getLogger("SkillAbilityTest");
    private SkillAbilityStore store() { return new SkillAbilityStore(folder, LOG); }

    @Test void catalogHasSixNonPaidAbilitiesAndStrictToolMatching() {
        assertEquals(6, SkillAbility.values().length);
        assertEquals(10, SkillAbility.UNLOCK_LEVEL);
        assertNull(SkillAbility.of(SkillType.ALCHEMY));
        assertNull(SkillAbility.of(null));
        assertTrue(SkillAbility.WOODCUTTING.acceptsTool("DIAMOND_AXE"));
        assertFalse(SkillAbility.WOODCUTTING.acceptsTool("DIAMOND_PICKAXE"));
        assertFalse(SkillAbility.COMBAT.acceptsTool("IRON_PICKAXE"));
        assertTrue(SkillAbility.COMBAT.acceptsTool("IRON_SWORD"));
        assertTrue(SkillAbility.ARCHERY.acceptsTool("CROSSBOW"));
        for (var ability : SkillAbility.values()) {
            assertTrue(ability.cost() >= 25 && ability.cost() <= 30);
            assertTrue(ability.seconds() <= 10);
            assertTrue(ability.cooldown() >= 35);
        }
    }

    @Test void costAndCooldownAreAtomicAndPerAbilityButEnergyIsShared() {
        var store = store();
        assertEquals(100, store.energy(PLAYER));
        assertEquals("", store.activate(PLAYER, SkillAbility.MINING, NOW));
        assertEquals(75, store.energy(PLAYER));
        assertEquals(45, store.cooldownLeft(PLAYER, SkillAbility.MINING, NOW));
        assertEquals("ability-cooldown", store.activate(PLAYER, SkillAbility.MINING, NOW + 1));
        assertEquals(75, store.energy(PLAYER));
        assertEquals("", store.activate(PLAYER, SkillAbility.COMBAT, NOW));
        assertEquals(45, store.energy(PLAYER));
        assertEquals("", store.activate(PLAYER, SkillAbility.DEFENSE, NOW));
        assertEquals(15, store.energy(PLAYER));
        assertEquals("ability-energy-low", store.activate(PLAYER, SkillAbility.DIGGING, NOW));
        assertEquals(0, store.cooldownLeft(PLAYER, SkillAbility.DIGGING, NOW));
        assertEquals(15, store.energy(PLAYER));
    }

    @Test void relogAndTimePassingDoNotRegenerateEnergyButExplicitOnlineTicksDo() {
        var store = store();
        store.activate(PLAYER, SkillAbility.MINING, NOW);
        store.flush();
        var loaded = store(); loaded.load();
        assertEquals(75, loaded.energy(PLAYER));
        assertEquals(0, loaded.cooldownLeft(PLAYER, SkillAbility.MINING, NOW + 60_000));
        assertEquals(75, loaded.energy(PLAYER));
        loaded.regenerate(PLAYER);
        assertEquals(77, loaded.energy(PLAYER));
        for (int i = 0; i < 200; i++) loaded.regenerate(PLAYER);
        assertEquals(100, loaded.energy(PLAYER));
        loaded.flush();
        var again = store(); again.load();
        assertEquals(100, again.energy(PLAYER));
        assertEquals(45, again.cooldownLeft(PLAYER, SkillAbility.MINING, NOW));
    }

    @Test void malformedStateFailsClosedAndIsNotOverwritten() throws Exception {
        String corrupt = "{not valid json}";
        Files.writeString(folder.resolve("abilities.json"), corrupt);
        var store = store(); store.load();
        assertFalse(store.available());
        assertEquals("ability-unavailable", store.activate(PLAYER, SkillAbility.MINING, NOW));
        store.regenerate(PLAYER); store.flush();
        assertEquals(corrupt, Files.readString(folder.resolve("abilities.json")));
    }

    @Test void invalidNumbersAndUnknownSchemaCannotResetEnergyOrTimers() throws Exception {
        for (String row : java.util.List.of(
                "{\"energy\":101,\"cooldowns\":{}}", "{\"energy\":-1,\"cooldowns\":{}}",
                "{\"energy\":30.5,\"cooldowns\":{}}", "{\"energy\":30}",
                "{\"energy\":30,\"cooldowns\":{\"mining\":-1}}")) {
            Files.writeString(folder.resolve("abilities.json"), "{\"version\":1,\"players\":{\"" + PLAYER + "\":" + row + "}}");
            var store = store(); store.load(); assertFalse(store.available(), row);
        }
        Files.writeString(folder.resolve("abilities.json"), "{\"version\":2,\"players\":{}}");
        var store = store(); store.load(); assertFalse(store.available());
    }

    @Test void configurationCanDisableAbilitiesWithoutDisablingSkills() {
        var yaml = new YamlConfiguration();
        var defaults = SkillsConfig.parse(yaml, LOG);
        assertTrue(defaults.abilitiesEnabled()); assertTrue(defaults.abilitiesHud());
        yaml.set("abilities.enabled", false); yaml.set("abilities.hud", false);
        var disabled = SkillsConfig.parse(yaml, LOG);
        assertTrue(disabled.enabled()); assertFalse(disabled.abilitiesEnabled()); assertFalse(disabled.abilitiesHud());
    }

    @Test void playersPetsArmorStandsAndNpcsAreNotPveTargets() {
        assertFalse(SkillAbilities.hostile(null));
        assertFalse(SkillAbilities.hostile(entity(false, Player.class)));
        assertFalse(SkillAbilities.hostile(entity(false, Wolf.class)));
        assertFalse(SkillAbilities.hostile(entity(false, ArmorStand.class)));
        assertFalse(SkillAbilities.hostile(entity(false, Monster.class, Tameable.class)));
        assertFalse(SkillAbilities.hostile(entity(true, Monster.class)));
        assertTrue(SkillAbilities.hostile(entity(false, Monster.class)));
        assertTrue(SkillAbilities.hostile(entity(false, Slime.class)));
    }

    private LivingEntity entity(boolean npc, Class<?>... types) {
        return (LivingEntity) Proxy.newProxyInstance(getClass().getClassLoader(), types,
                (proxy, method, args) -> method.getName().equals("hasMetadata") ? npc : null);
    }

    @Test void callbacksDoNotCreateDamageOrDropsAndRespectCancellationAndArrowTiming() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkskills/SkillAbilities.java"));
        assertFalse(source.contains("setDamage(event.getDamage() *"));
        assertFalse(source.contains("target.damage("));
        assertFalse(source.contains("dropItem"));
        assertFalse(source.contains("award("));
        assertTrue(source.contains("event.isCancelled() || event.getFinalDamage() <= 0"));
        assertTrue(source.contains("target.hasPotionEffect(type)"));
        assertTrue(source.contains("shot.token.equals(value.token)"));
        assertTrue(source.contains("context.event.isCancelled()"));
        assertTrue(source.contains("context.tick != Bukkit.getCurrentTick()"));
        assertTrue(source.contains("PlayerChangedWorldEvent"));
        assertTrue(source.contains("PlayerDeathEvent"));
        assertTrue(source.contains("MAX_ARROW_MARKS = 4096"));
    }

    @Test void localizedCardsExistForEachAbility() throws Exception {
        var yaml = new YamlConfiguration();
        try (var stream = getClass().getResourceAsStream("/messages.yml")) {
            assertNotNull(stream);
            yaml.load(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
        }
        var root = yaml.getConfigurationSection("messages");
        assertNotNull(root);
        for (var ability : SkillAbility.values()) {
            for (String key : java.util.List.of(ability.nameKey(), ability.descKey())) {
                String value = root.getString(key); assertNotNull(value, key);
                assertDoesNotThrow(() -> net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(value));
            }
        }
    }
}
