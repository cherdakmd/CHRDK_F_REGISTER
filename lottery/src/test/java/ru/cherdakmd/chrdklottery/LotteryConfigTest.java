package ru.cherdakmd.chrdklottery;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LotteryConfigTest {
    @Test void defaultsAndInvalidValues() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("tickets:\n  cost-diamonds: -1\n  max-per-player: -2\n  max-per-purchase: 0\ndraw:\n  day-of-week: nope\n  time: nope\n  timezone: nope\npot:\n  payout-percent: 999\n");
        var c = LotteryConfig.parse(yaml, null);
        assertEquals(1, c.costDiamonds()); assertEquals(0, c.maxPerPlayer()); assertEquals(1, c.maxPerPurchase());
        assertEquals(DayOfWeek.SUNDAY, c.drawDay()); assertEquals(LocalTime.of(20, 0), c.drawTime());
        assertEquals(ZoneId.of("Europe/Moscow"), c.zone()); assertEquals(100, c.payoutPercent());
        assertEquals(List.of(30, 5, 1), c.announceMinutes());
    }
    @Test void explicitEmptyRemindersAndBounds() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("tickets:\n  cost-diamonds: 2147483647\n  max-per-purchase: 2147483647\ndraw:\n  announce-minutes: []\n  catch-up-on-start: false\n");
        var c = LotteryConfig.parse(yaml, null);
        assertEquals(4096, c.costDiamonds()); assertEquals(1024, c.maxPerPurchase());
        assertTrue(c.announceMinutes().isEmpty()); assertFalse(c.catchUpOnStart());
    }
    @Test void reminderFiltering() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("draw:\n  announce-minutes: [5, 5, -1, 0, 3000, 1]\n");
        assertEquals(List.of(5, 1), LotteryConfig.parse(yaml, null).announceMinutes());
    }
}
