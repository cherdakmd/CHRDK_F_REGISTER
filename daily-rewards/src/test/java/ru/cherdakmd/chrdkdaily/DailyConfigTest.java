package ru.cherdakmd.chrdkdaily;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Каталог наград: парсинг, зацикливание по стрику, VIP-ветки и описания.
 * Material не валидируем (validateMaterials=false — в юнит-JVM нет серверных классов).
 */
class DailyConfigTest {

    private DailyConfig parse(String yaml) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return DailyConfig.parse(config, Logger.getAnonymousLogger(), false);
    }

    @Test
    void parsesDaysAndVipBranches() {
        DailyConfig config = parse("""
                rewards:
                  - day: 1
                    items: ["BREAD 8"]
                    vip-items: ["BREAD 16"]
                  - day: 2
                    items: ["COAL 4"]
                    vip-pass-days: 1
                join-reminder: false
                """);
        assertEquals(2, config.rewards().size());
        assertFalse(config.joinReminderEnabled());
        DailyConfig.DayReward day1 = config.rewardForStreakDay(1);
        assertEquals(8, day1.itemsFor(false).get(0).amount());
        assertEquals("BREAD", day1.itemsFor(false).get(0).material());
        assertEquals(16, day1.itemsFor(true).get(0).amount());
        DailyConfig.DayReward day2 = config.rewardForStreakDay(2);
        assertEquals(1, day2.vipPassDays());
    }

    @Test
    void cycleWrapsAfterLastDay() {
        DailyConfig config = parse("""
                rewards:
                  - day: 1
                    items: ["BREAD 8"]
                  - day: 2
                    items: ["COAL 4"]
                """);
        assertEquals(1, config.rewardForStreakDay(3).day(), "день 3 стрика = день 1 цикла");
        assertEquals(2, config.rewardForStreakDay(4).day());
        assertEquals(1, config.rewardForStreakDay(101).day(), "100 дней стрика — цикл жив");
    }

    @Test
    void vipFallsBackToRegularItemsWhenVipListEmpty() {
        DailyConfig config = parse("""
                rewards:
                  - day: 1
                    items: ["BREAD 8"]
                """);
        DailyConfig.DayReward day = config.rewardForStreakDay(1);
        assertEquals(day.items(), day.itemsFor(true), "пустая VIP-ветка = обычная награда");
    }

    @Test
    void invalidEntriesAreSkipped() {
        DailyConfig config = parse("""
                rewards:
                  - day: 1
                    items: ["", "BREAD 0", "BREAD -2"]
                  - day: 2
                    items: ["COAL 4 extra words ok"]
                """);
        assertEquals(1, config.rewards().size(), "день с пустой наградой отброшен");
        assertEquals(4, config.rewards().get(0).items().get(0).amount());
    }

    @Test
    void rewardsCapAt2304ToAvoidAbsurdStacks() {
        DailyConfig config = parse("""
                rewards:
                  - day: 1
                    items: ["STONE 999999"]
                """);
        assertEquals(2304, config.rewards().get(0).items().get(0).amount());
    }

    @Test
    void describeBuildsHumanText() {
        DailyConfig.DayReward day = new DailyConfig.DayReward(1,
                List.of(new DailyConfig.ItemReward("DIAMOND", 2)), List.of(), 3);
        assertEquals("diamond ×2", DailyConfig.describe(day, false, Optional.empty()));
        assertEquals("diamond ×2, +3 дн. ранга", DailyConfig.describe(day, true, Optional.empty()));
        assertEquals("—", DailyConfig.describe(new DailyConfig.DayReward(1, List.of(), List.of(), 0),
                false, Optional.empty()));
    }

    @Test
    void emptyCatalogYieldsEmptyRewardButNoCrash() {
        DailyConfig config = parse("");
        assertTrue(config.rewards().isEmpty());
        assertTrue(config.rewardForStreakDay(5).items().isEmpty());
    }
}
