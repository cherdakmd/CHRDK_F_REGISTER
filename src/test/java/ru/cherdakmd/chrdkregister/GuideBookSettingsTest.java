package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Секция guide: без неё книга работает по умолчанию, ключи читаются, выключение работает. */
class GuideBookSettingsTest {

    private static GuideBookSettings parse(String yaml) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(yaml);
        return GuideBookSettings.parse(configuration);
    }

    @Test
    void missingSectionKeepsFeatureOn() throws Exception {
        GuideBookSettings settings = parse("general:\n  locale: ru\n");
        assertTrue(settings.enabled());
        assertTrue(settings.giveOnFirstJoin());
    }

    @Test
    void readsBothKeys() throws Exception {
        GuideBookSettings settings = parse("""
                guide:
                  enabled: false
                  give-on-first-join: false
                """);
        assertFalse(settings.enabled());
        assertFalse(settings.giveOnFirstJoin());
    }

    @Test
    void missingKeysFallBackToDefaults() throws Exception {
        GuideBookSettings settings = parse("guide:\n  give-on-first-join: false\n");
        assertTrue(settings.enabled());
        assertFalse(settings.giveOnFirstJoin());
    }
}
