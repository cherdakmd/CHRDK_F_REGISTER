package ru.cherdakmd.chrdktreasures;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Броски особых находок: границы шансов, потолки уровня и «мягкие» источники.
 * Логика чистая, поэтому проверяется без сервера и без аддонов.
 */
class TreasureSpecialRollsTest {

    private static final Logger LOGGER = Logger.getLogger("TreasureSpecialRollsTest");
    private static final Random RANDOM = new Random(42L);

    private static TreasuresConfig.Specials settings(boolean enabled, boolean artifacts,
                                                     boolean enchantments) {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("specials.enabled", enabled);
        cfg.set("specials.artifacts.enabled", artifacts);
        cfg.set("specials.enchantments.enabled", enchantments);
        return TreasuresConfig.parse(cfg, LOGGER).specials();
    }

    @Test
    void hitRespectsBoundsAndExtremes() {
        assertFalse(TreasureSpecialRolls.hit(0.0, RANDOM), "0 % — никогда");
        assertFalse(TreasureSpecialRolls.hit(-5.0, RANDOM), "отрицательный шанс — выключено");
        assertTrue(TreasureSpecialRolls.hit(100.0, RANDOM), "100 % — всегда");
        assertTrue(TreasureSpecialRolls.hit(250.0, RANDOM), "запредельный шанс зажат к 100");

        int hits = 0;
        for (int i = 0; i < 10_000; i++) {
            if (TreasureSpecialRolls.hit(25.0, RANDOM)) {
                hits++;
            }
        }
        assertTrue(hits > 2_200 && hits < 2_800, "25 % на 10 000 бросках: " + hits);
    }

    @Test
    void levelStaysInsideCap() {
        for (int i = 0; i < 500; i++) {
            int level = TreasureSpecialRolls.level(3, RANDOM);
            assertTrue(level >= 1 && level <= 3, "уровень вне потолка: " + level);
        }
        assertEquals(1, TreasureSpecialRolls.level(0, RANDOM), "нулевой потолок — I");
        assertEquals(1, TreasureSpecialRolls.level(1, RANDOM));
        for (int i = 0; i < 200; i++) {
            int high = TreasureSpecialRolls.level(99, RANDOM);
            assertTrue(high >= 1 && high <= 5, "потолок зажат пятью уровнями: " + high);
        }
    }

    @Test
    void planFollowsTierChances() {
        TreasuresConfig.Specials defaults = TreasuresConfig.defaultSpecials();

        // Мифический клад: реликвия гарантирована, книга — до V.
        TreasureSpecialRolls.Plan mythical = TreasureSpecialRolls.plan(
                defaults, TreasureTier.MYTHICAL, RANDOM);
        assertTrue(mythical.artifact(), "у мифического клада реликвия всегда");
        assertEquals(5, mythical.levelCap(), "мифическая книга — до V");

        // Обычный клад: шанс реликвии 1 % — редко, и книга не выше I.
        int artifacts = 0;
        for (int i = 0; i < 1_000; i++) {
            TreasureSpecialRolls.Plan plan = TreasureSpecialRolls.plan(
                    defaults, TreasureTier.COMMON, RANDOM);
            if (plan.artifact()) {
                artifacts++;
            }
            if (plan.enchant()) {
                assertEquals(1, plan.levelCap(), "обычная книга — только I");
            }
        }
        assertTrue(artifacts < 60, "1 % не должен превращаться в поток: " + artifacts);
    }

    @Test
    void disabledSourcesProduceNothing() {
        TreasureSpecialRolls.Plan off = TreasureSpecialRolls.plan(
                settings(false, true, true), TreasureTier.MYTHICAL, RANDOM);
        assertFalse(off.any(), "выключенные особые находки не выпадают");

        TreasuresConfig.Specials artifactsOnly = settings(true, true, false);
        for (int i = 0; i < 200; i++) {
            assertFalse(TreasureSpecialRolls.plan(artifactsOnly, TreasureTier.MYTHICAL, RANDOM)
                    .enchant(), "выключенные книги не выпадают");
        }

        TreasuresConfig.Specials enchantOnly = settings(true, false, true);
        for (int i = 0; i < 200; i++) {
            assertFalse(TreasureSpecialRolls.plan(enchantOnly, TreasureTier.MYTHICAL, RANDOM)
                    .artifact(), "выключенные реликвии не выпадают");
        }
    }
}
