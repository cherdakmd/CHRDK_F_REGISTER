package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Значения moderation.* не должны выводить автобан за пределы разумного. */
class ModerationConfigTest {

    @Test
    void defaultsMatchFiveWarnsAndPublicTabCounter() {
        ModerationConfig config = ModerationConfig.DEFAULT;
        assertTrue(config.enabled());
        assertEquals(5, config.warnLimit());
        assertEquals(ModerationLedger.DEFAULT_WARN_LIMIT, config.warnLimit());
        assertTrue(config.warnsInTab());
        assertEquals(500, config.journalLimit());
        assertTrue(config.banReason().contains("%count%"));
        assertSame(ModerationConfig.DEFAULT, ModerationConfig.fromConfig(null));
    }

    @Test
    void mutePresetsComeFromConfigAndNeverLeaveThePanelEmpty() {
        assertEquals(java.util.List.of(10L, 60L, 1_440L, 10_080L),
                ModerationConfig.DEFAULT.mutePresets());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("moderation.mute-presets-minutes", java.util.List.of(5, 30));
        assertEquals(java.util.List.of(5L, 30L),
                ModerationConfig.fromConfig(yaml).mutePresets());
        yaml.set("moderation.mute-presets-minutes", java.util.List.of("15", "120"));
        assertEquals(java.util.List.of(15L, 120L),
                ModerationConfig.fromConfig(yaml).mutePresets(), "строки тоже разбираются");
        yaml.set("moderation.mute-presets-minutes", java.util.List.of(0, -5, 99_999_999));
        assertEquals(ModerationConfig.DEFAULT_MUTE_PRESETS,
                ModerationConfig.fromConfig(yaml).mutePresets(), "мусор откатывается к дефолту");
    }

    @Test
    void clampsExtremeValuesAndRestoresEmptyReason() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("moderation.enabled", false);
        yaml.set("moderation.warn-limit", 999);
        yaml.set("moderation.ban-reason", "   ");
        yaml.set("moderation.warns-in-tab", false);
        yaml.set("moderation.journal-limit", 1);
        ModerationConfig config = ModerationConfig.fromConfig(yaml);
        assertFalse(config.enabled());
        assertEquals(50, config.warnLimit());
        assertEquals(ModerationConfig.DEFAULT_BAN_REASON, config.banReason());
        assertFalse(config.warnsInTab());
        assertEquals(10, config.journalLimit());

        yaml.set("moderation.warn-limit", -4);
        yaml.set("moderation.journal-limit", 99999);
        assertEquals(1, ModerationConfig.fromConfig(yaml).warnLimit());
        assertEquals(5000, ModerationConfig.fromConfig(yaml).journalLimit());
    }
}
