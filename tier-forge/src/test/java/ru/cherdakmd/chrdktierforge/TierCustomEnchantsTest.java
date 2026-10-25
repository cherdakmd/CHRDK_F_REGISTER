package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Кастомные чары на тир-предметах (волна 18): детерминизм, пулы по полярности,
 * потолки и «мёртвая» настройка без аддона чар.
 */
class TierCustomEnchantsTest {

    private static final List<String> LIGHT = List.of("keen_edge", "bulwark", "scholar");
    private static final List<String> CURSES = List.of("dull_blade", "exposed", "cursed_blood");

    /** Конфиг: 100% шанс, чтобы проверять выбор, а не удачу. */
    private static TierEnchantConfig always(int maxPerItem, int curseChance, int curseMinTier) {
        return new TierEnchantConfig(true, 20, 2, 3, true, 100, maxPerItem,
                curseChance, curseMinTier);
    }

    private static List<TierCustomEnchants.Pick> roll(int tier, long seed, int maxPerItem,
                                                      List<String> light, List<String> curses,
                                                      int curseChance, int curseMinTier) {
        return TierCustomEnchants.roll(tier, new Random(seed), light, curses,
                always(maxPerItem, curseChance, curseMinTier), id -> 3);
    }

    @Test
    void deterministicForSameSeed() {
        List<TierCustomEnchants.Pick> first = roll(2, 42L, 2, LIGHT, CURSES, 50, 0);
        List<TierCustomEnchants.Pick> second = roll(2, 42L, 2, LIGHT, CURSES, 50, 0);
        assertEquals(first, second, "один uid и тир — одна выдача");
        assertFalse(first.isEmpty());
    }

    @Test
    void lowTierNeverRollsCurses() {
        for (long seed = 0; seed < 200; seed++) {
            for (TierCustomEnchants.Pick pick : roll(1, seed, 2, LIGHT, CURSES, 100, 5)) {
                assertTrue(LIGHT.contains(pick.id()), "проклятие на низком тире: " + pick.id());
            }
        }
    }

    @Test
    void highTierCanRollCurses() {
        boolean sawCurse = false;
        for (long seed = 0; seed < 300 && !sawCurse; seed++) {
            for (TierCustomEnchants.Pick pick : roll(6, seed, 1, LIGHT, CURSES, 100, 5)) {
                if (CURSES.contains(pick.id())) {
                    sawCurse = true;
                }
            }
        }
        assertTrue(sawCurse, "на легендарном тире проклятие должно встречаться");
    }

    @Test
    void cursesFallBackToLightWhenAddonDisablesThem() {
        for (long seed = 0; seed < 200; seed++) {
            List<TierCustomEnchants.Pick> picks = roll(6, seed, 2, LIGHT, List.of(), 100, 5);
            for (TierCustomEnchants.Pick pick : picks) {
                assertTrue(LIGHT.contains(pick.id()), "пул проклятий пуст — только светлые");
            }
        }
    }

    @Test
    void respectsItemLimitAndLevelCap() {
        for (long seed = 0; seed < 100; seed++) {
            List<TierCustomEnchants.Pick> one = roll(0, seed, 1, LIGHT, CURSES, 0, 5);
            assertTrue(one.size() <= 1);
            for (TierCustomEnchants.Pick pick : one) {
                assertEquals(1, pick.level(), "проклятый тир — только I уровень");
            }
            List<TierCustomEnchants.Pick> two = roll(6, seed, 2, LIGHT, CURSES, 0, 5);
            assertTrue(two.size() <= 2);
            for (TierCustomEnchants.Pick pick : two) {
                assertTrue(pick.level() >= 1 && pick.level() <= 3);
            }
        }
    }

    @Test
    void zeroChanceAndUnknownIdsGiveNothing() {
        TierEnchantConfig zero = new TierEnchantConfig(true, 20, 2, 3, true, 0, 2, 100, 0);
        assertTrue(TierCustomEnchants.roll(6, new Random(1L), LIGHT, CURSES, zero, id -> 3)
                .isEmpty(), "chance-percent: 0 выключает кастомные чары");
        TierEnchantConfig disabled = new TierEnchantConfig(true, 20, 2, 3, false, 100, 2, 100, 0);
        assertTrue(TierCustomEnchants.roll(6, new Random(1L), LIGHT, CURSES, disabled, id -> 3)
                .isEmpty(), "custom.enabled: false выключает кастомные чары");
        assertTrue(TierCustomEnchants.roll(6, new Random(1L), LIGHT, CURSES, always(2, 0, 5),
                id -> 0).isEmpty(), "неизвестные аддону id пропускаются");
    }
}
