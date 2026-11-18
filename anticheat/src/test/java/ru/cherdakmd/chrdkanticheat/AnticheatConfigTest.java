package ru.cherdakmd.chrdkanticheat;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnticheatConfigTest {

    @Test
    void readsValues() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("general.enabled", false);
        config.set("alerts.throttle-seconds", 25);
        config.set("journal.keep-days", 10);
        AnticheatConfig settings = AnticheatConfig.from(config);
        assertFalse(settings.enabled());
        assertEquals(25, settings.throttleSeconds());
        assertEquals(10, settings.keepDays());
    }

    @Test
    void clampsLimitsAndDefaultsMissingValues() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("alerts.throttle-seconds", 1_000_000);
        config.set("journal.keep-days", -3);
        AnticheatConfig settings = AnticheatConfig.from(config);
        assertTrue(settings.enabled());
        assertEquals(3600, settings.throttleSeconds(), "потолок троттлинга — час");
        assertEquals(1, settings.keepDays(), "чистка никогда не съедает текущие сутки");
    }
}
