package ru.cherdakmd.chrdkbosses;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор config.yml боссов (1.4.162): умолчания, клампы и деградация опечаток.
 *
 * <p>Опечатка в имени моба не должна молча превращать босса в зомби и не должна
 * ронять плагин: запись пропускается с предупреждением. Реальный файл из
 * ресурсов тоже проверяется — иначе конфиг «для вида» расходится с кодом.</p>
 */
class BossConfigTest {

    private static final Logger LOGGER = Logger.getLogger("BossConfigTest");

    private static BossConfig parse(String text) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(text);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return BossConfig.parse(config, LOGGER);
    }

    private static BossConfig bundled() throws Exception {
        try (InputStream stream = BossConfigTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(stream, "config.yml должен лежать в ресурсах модуля");
            YamlConfiguration config = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            return BossConfig.parse(config, LOGGER);
        }
    }

    @Test
    void emptyConfigUsesDefaults() {
        BossConfig config = parse("");
        assertTrue(config.enabled());
        assertEquals("ru", config.locale());
        assertFalse(config.autoSpawn(), "авто-спавн по умолчанию выключен");
        assertTrue(config.bossbar());
        assertTrue(config.actionbar());
        assertEquals(60, config.checkSeconds());
        assertTrue(config.bossList().isEmpty());
        assertTrue(config.zoneList().isEmpty());
        assertNull(config.boss("stone_warden"));
        assertNull(config.zone("ash_fields"));
        assertTrue(BossConfig.defaults().bossIds().isEmpty());
    }

    @Test
    void generalValuesAreClamped() {
        BossConfig config = parse("""
                general:
                  check-seconds: 5
                """);
        assertEquals(10, config.checkSeconds());
        BossConfig slow = parse("""
                general:
                  check-seconds: 9999
                """);
        assertEquals(BossConfig.MAX_CHECK_SECONDS, slow.checkSeconds());
    }

    @Test
    void bundledConfigLoadsEveryBossAndZone() throws Exception {
        BossConfig config = bundled();
        assertEquals(4, config.bossList().size());
        assertEquals(3, config.zoneList().size());
        assertEquals(0, config.activeZones().size(),
                "зоны из примеров выключены до настройки координат");

        BossDefinition warden = config.boss("stone_warden");
        assertNotNull(warden);
        assertEquals(EntityType.IRON_GOLEM, warden.entity());
        assertEquals(240.0, warden.health(), 1.0e-9);
        assertEquals(3, warden.phases().size());
        assertTrue(warden.arena().autoHeight());
        assertEquals("world", warden.arena().world());
        assertTrue(warden.reward().xp() > 0);
        assertTrue(warden.reward().hasTrophy());
        assertTrue(warden.usable());

        for (BossDefinition boss : config.bossList()) {
            assertTrue(boss.usable(), "каждый босс из конфига должен быть пригоден: " + boss.id());
            assertTrue(boss.phases().size() >= 2, boss.id());
        }

        DangerZone ash = config.zone("ash_fields");
        assertNotNull(ash);
        assertEquals(2, ash.level());
        assertEquals(2, ash.effects().size());
        assertTrue(ash.harmful());
        for (DangerZone zone : config.zoneList()) {
            assertFalse(zone.enabled(), "пример зоны выключен: " + zone.id());
            assertFalse(zone.world().isBlank(), zone.id());
        }
    }

    @Test
    void unknownEntitySkipsBoss() {
        BossConfig config = parse("""
                bosses:
                  phantom:
                    entity: DRAGON_OF_DOOM
                    arena:
                      world: "world"
                    reward:
                      xp: 100
                """);
        assertTrue(config.bossList().isEmpty(), "опечатка в мобе — босс не появляется");
    }

    @Test
    void bossWithoutArenaIsSkipped() {
        BossConfig config = parse("""
                bosses:
                  ghost:
                    entity: ZOMBIE
                    reward:
                      xp: 100
                """);
        assertTrue(config.bossList().isEmpty());
    }

    @Test
    void bossWithoutRewardIsSkipped() {
        BossConfig config = parse("""
                bosses:
                  poor:
                    entity: ZOMBIE
                    arena:
                      world: "world"
                      radius: 10
                """);
        assertTrue(config.bossList().isEmpty());
    }

    @Test
    void unknownIconFallsBackToSword() {
        BossConfig config = parse("""
                bosses:
                  odd:
                    entity: ZOMBIE
                    icon: MAGIC_STICK_OF_DOOM
                    health: 100
                    arena:
                      world: "world"
                      radius: 10
                    phases:
                      - at: 100
                      - at: 40
                    reward:
                      xp: 50
                """);
        BossDefinition boss = config.boss("odd");
        assertNotNull(boss);
        assertEquals(Material.NETHERITE_SWORD, boss.icon());
    }

    @Test
    void badAbilityIsDroppedWithoutLosingPhase() {
        BossConfig config = parse("""
                bosses:
                  odd:
                    entity: SKELETON
                    arena:
                      world: "world"
                      radius: 10
                    phases:
                      - at: 100
                        name: "Первая"
                        abilities:
                          - { type: NONSENSE, amount: 5 }
                          - { type: STOMP, amount: 5, radius: 6 }
                      - at: 50
                        name: "Вторая"
                    reward:
                      xp: 50
                """);
        BossDefinition boss = config.boss("odd");
        assertNotNull(boss);
        assertEquals(2, boss.phases().size());
        assertEquals(1, boss.phases().get(0).abilities().size());
        assertEquals(BossAbility.Type.STOMP, boss.phases().get(0).abilities().get(0).type());
    }

    @Test
    void phasesAreNormalizedWhenOrderIsWrong() {
        BossConfig config = parse("""
                bosses:
                  odd:
                    entity: ZOMBIE
                    arena:
                      world: "world"
                      radius: 10
                    phases:
                      - { at: 20, name: "Низ" }
                      - { at: 100, name: "Верх" }
                      - { at: 60, name: "Середина" }
                    reward:
                      xp: 50
                """);
        BossDefinition boss = config.boss("odd");
        assertNotNull(boss);
        assertEquals("Верх", boss.phases().get(0).name());
        assertEquals("Середина", boss.phases().get(1).name());
        assertEquals("Низ", boss.phases().get(2).name());
    }

    @Test
    void rewardReadsItemsAndIgnoresUnknownMaterials() {
        BossConfig config = parse("""
                bosses:
                  rich:
                    entity: ZOMBIE
                    arena:
                      world: "world"
                      radius: 10
                    reward:
                      xp: 120
                      items:
                        GOLDEN_APPLE: 4
                        UNOBTAINIUM: 2
                      trophy: DIAMOND
                      trophy-name: "Сердце"
                """);
        BossDefinition boss = config.boss("rich");
        assertNotNull(boss);
        assertEquals(120, boss.reward().xp());
        assertEquals(1, boss.reward().items().size());
        assertEquals(Material.GOLDEN_APPLE, boss.reward().items().get(0).material());
        assertEquals(4, boss.reward().items().get(0).amount());
        assertEquals(Material.DIAMOND, boss.reward().trophy());
        assertEquals("Сердце", boss.reward().trophyName());
    }

    @Test
    void zoneWithoutWorldIsSkipped() {
        BossConfig config = parse("""
                zones:
                  nowhere:
                    name: "Нигде"
                    radius: 10
                """);
        assertTrue(config.zoneList().isEmpty());
    }

    @Test
    void zoneReadsEffectsAndSkipsEmptyOnes() {
        BossConfig config = parse("""
                zones:
                  ash:
                    name: "Пепел"
                    world: "world"
                    x: 10
                    y: 64
                    z: -10
                    radius: 20
                    level: 9
                    damage-per-second: 9
                    xp-bonus-percent: 99
                    effect-interval-seconds: 1
                    effects:
                      - { type: slowness, amplifier: 1, duration-seconds: 5 }
                      - { type: "", amplifier: 0, duration-seconds: 5 }
                """);
        DangerZone zone = config.zone("ash");
        assertNotNull(zone);
        assertEquals(5, zone.level(), "уровень зажат в 1..5");
        assertEquals(DangerZone.MAX_DAMAGE_PER_SECOND, zone.damagePerSecond(), 1.0e-9);
        assertEquals(DangerZone.MAX_XP_BONUS_PERCENT, zone.xpBonusPercent());
        assertEquals(1, zone.effectIntervalSeconds());
        assertEquals(1, zone.effects().size(), "пустой эффект отброшен");
    }

    @Test
    void disabledZoneStaysInListButDoesNotAct() {
        BossConfig config = parse("""
                zones:
                  off:
                    name: "Выключена"
                    enabled: false
                    world: "world"
                    radius: 10
                """);
        assertEquals(1, config.zoneList().size());
        assertTrue(config.activeZones().isEmpty());
    }

    @Test
    void moreThanLimitIsTruncated() {
        StringBuilder text = new StringBuilder("bosses:\n");
        for (int index = 0; index < BossConfig.MAX_BOSSES + 3; index++) {
            text.append("  b").append(index).append(":\n")
                    .append("    entity: ZOMBIE\n")
                    .append("    arena:\n      world: \"world\"\n      radius: 10\n")
                    .append("    reward:\n      xp: 10\n");
        }
        BossConfig config = parse(text.toString());
        assertEquals(BossConfig.MAX_BOSSES, config.bossList().size());
    }
}
