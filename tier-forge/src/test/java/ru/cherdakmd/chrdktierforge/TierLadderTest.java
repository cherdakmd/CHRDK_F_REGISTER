package ru.cherdakmd.chrdktierforge;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Лестница: порядок, навигация, парсинг с клампами. */
class TierLadderTest {

    @Test
    void ladderOrderAndNext() {
        assertEquals(7, TierDef.LADDER.size());
        assertEquals(0, TierDef.indexOf("cursed"));
        assertEquals(6, TierDef.indexOf("legendary"));
        assertEquals("weak", TierDef.next("cursed"));
        assertEquals("legendary", TierDef.next("epic"));
        assertNull(TierDef.next("legendary"), "потолок");
        assertNull(TierDef.next("nosoth"));
        assertEquals(-1, TierDef.indexOf("nosoth"));
    }

    @Test
    void parseDefaultsWhenConfigEmpty() {
        List<TierDef.Snapshot> tiers = TierDef.parse(new YamlConfiguration(),
                Logger.getAnonymousLogger());
        assertEquals(7, tiers.size());
        assertEquals(30, tiers.get(2).weight(), "common по умолчанию 30");
        assertEquals(-12, tiers.get(0).percent(), "cursed -12%");
        assertEquals(30, tiers.get(6).percent(), "legendary +30%");
    }

    @Test
    void parseClampsExtremes() {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString("""
                    tiers:
                      cursed: {weight: 4000, percent: -999}
                      legendary: {weight: -5, percent: 999}
                    """);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        List<TierDef.Snapshot> tiers = TierDef.parse(config, Logger.getAnonymousLogger());
        assertEquals(1000, tiers.get(0).weight());
        assertEquals(-50, tiers.get(0).percent());
        assertEquals(0, tiers.get(6).weight());
        assertEquals(60, tiers.get(6).percent());
        assertEquals(30, tiers.get(2).weight(), "дефолты защищены");
    }
}
