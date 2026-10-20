package ru.cherdakmd.chrdkteleports;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Парсинг и санитизация конфигурации телепортов (чистый YAML — без сервера). */
class TpConfigTest {

    private TpConfig parse(String yaml) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return TpConfig.parse(config, Logger.getAnonymousLogger());
    }

    @Test
    void defaultsFromRealisticFile() {
        TpConfig config = parse("""
                costs:
                  vip-discount-percent: 50
                  rtp-diamonds: 5
                  tpa-diamonds: 2
                  home-diamonds: 1
                rtp:
                  radius-blocks: 10000
                  min-radius-blocks: 250
                  max-attempts: 24
                  cooldown-seconds: 300
                  world: world
                homes:
                  max-normal: 3
                  max-vip: 8
                """);
        assertEquals(10000, config.rtpRadius());
        assertEquals(250, config.rtpMinRadius());
        assertEquals(24, config.rtpMaxAttempts());
        assertEquals(300, config.rtpCooldownSeconds());
        assertEquals(50, config.vipDiscountPercent());
        assertEquals(5, config.costRtp());
        assertEquals(2, config.costTpa());
        assertEquals(1, config.costHome());
        assertEquals(0, config.costSethome());
        assertEquals(3, config.homeLimit(false));
        assertEquals(8, config.homeLimit(true));
        assertEquals("world", config.rtpWorld());
    }

    @Test
    void extraHomesFromShopRespectHardCap() {
        TpConfig config = parse("""
                homes:
                  max-normal: 3
                  max-vip: 8
                  extra-permission: "chrdktele.homes.plus"
                  extra-limit: 10
                  hard-cap: 9
                """);
        assertEquals(3, config.homeLimit(false, false));
        assertEquals(8, config.homeLimit(true, false));
        assertEquals(9, config.homeLimit(false, true), "extra-limit 10 урезан потолком 9");
        assertEquals(9, config.homeLimit(true, true));
        assertEquals("chrdktele.homes.plus", config.homesExtraPermission());
        assertTrue(config.grantsExtraHomes(true));
        assertFalse(config.grantsExtraHomes(false), "без права товар не даёт домов");
    }

    @Test
    void emptyPermissionTurnsExtraHomesOff() {
        TpConfig config = parse("""
                homes:
                  max-normal: 3
                  max-vip: 8
                  extra-permission: ""
                """);
        assertFalse(config.grantsExtraHomes(true));
        assertEquals(3, config.homeLimit(false, true), "право без настроенного товара ничего не даёт");
    }

    @Test
    void emptyConfigYieldsSafeDefaults() {
        TpConfig config = parse("");
        assertEquals(10000, config.rtpRadius());
        assertEquals("world", config.rtpWorld());
        assertTrue(config.worldAllowed("любой_мир"), "пустой список = все миры");
    }

    @Test
    void outOfRangeClamped() {
        TpConfig config = parse("""
                costs:
                  vip-discount-percent: 500
                rtp:
                  radius-blocks: -5
                  min-radius-blocks: 999999
                  cooldown-seconds: -100
                homes:
                  max-normal: 10
                  max-vip: 2
                """);
        assertEquals(100, config.vipDiscountPercent());
        assertEquals(1, config.rtpRadius());
        assertEquals(1, config.rtpMinRadius(), "мин.радиус не больше радиуса");
        assertEquals(0, config.rtpCooldownSeconds());
        assertEquals(10, config.homesMaxVip(), "vip-лимит не ниже обычного");
    }

    @Test
    void allowedWorldsRespected() {
        TpConfig config = parse("""
                allowed-worlds:
                  - World
                  - world_nether
                """);
        assertTrue(config.worldAllowed("WORLD"));
        assertTrue(config.worldAllowed("world_nether"));
        assertFalse(config.worldAllowed("world_the_end"));
    }
}
