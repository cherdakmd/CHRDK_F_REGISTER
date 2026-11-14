package ru.cherdakmd.chrdkmobs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Контракт ресурсов модуля: конфиг тиров, тексты и plugin.yml.
 */
class MobTierConfigTest {

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    void tierSettingsAreNamedAndDocumented() throws IOException {
        String config = read("src/main/resources/config.yml");
        assertTrue(config.contains("distances: [1000, 3000, 7000, 15000]"),
                "границы тиров по умолчанию");
        assertTrue(config.contains("health-per-tier: 0.5"));
        assertTrue(config.contains("damage-per-tier: 0.25"));
        assertTrue(config.contains("name-prefix: true"));
        assertTrue(config.contains("reward-per-tier: 1"));
        assertTrue(config.contains("NATURAL/PATROL"),
                "фермы спавнеров исключены документированно");
    }

    @Test
    void textsAndPluginContract() throws IOException {
        String messages = read("src/main/resources/messages.yml");
        assertTrue(messages.contains("mob-name-prefix"));
        assertTrue(messages.contains("mob-reward"));
        String plugin = read("src/main/resources/plugin.yml");
        assertTrue(plugin.contains("name: CHRDK_Mobs"));
        assertTrue(plugin.contains("main: ru.cherdakmd.chrdkmobs.MobsPlugin"));
        assertTrue(plugin.contains("CHRDK_Core"));
    }
}
