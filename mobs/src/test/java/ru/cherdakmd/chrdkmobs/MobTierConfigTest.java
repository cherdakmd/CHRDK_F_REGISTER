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
    void progressionAndEliteSettingsAreNamed() throws IOException {
        String config = read("src/main/resources/config.yml");
        assertTrue(config.contains("progression:"));
        assertTrue(config.contains("health-per-100: 0.10"));
        assertTrue(config.contains("damage-per-100: 0.05"));
        assertTrue(config.contains("prestige-weight: 5"));
        assertTrue(config.contains("max-bonus: 1.5"));
        assertTrue(config.contains("elite:"));
        assertTrue(config.contains("chance: 0.03"));
        assertTrue(config.contains("health-multiplier: 2.0"));
        assertTrue(config.contains("damage-multiplier: 1.5"));
        assertTrue(config.contains("abilities-min: 2"));
        assertTrue(config.contains("abilities-max: 3"));
        assertTrue(config.contains("reward-tiers: 2"));
        assertTrue(config.contains("epithets:"), "имена элит настраиваются");
    }

    @Test
    void textsAndPluginContract() throws IOException {
        String messages = read("src/main/resources/messages.yml");
        assertTrue(messages.contains("mob-name-prefix"));
        assertTrue(messages.contains("mob-reward"));
        assertTrue(messages.contains("mobtiers-header"));
        assertTrue(messages.contains("elite-name"), "имя элиты");
        assertTrue(messages.contains("elite-reward"), "награда за элиту");
        assertTrue(messages.contains("mobtiers-progression"));
        assertTrue(messages.contains("mobtiers-elite"));
        assertTrue(messages.contains("mob-names:"), "русские названия мобов");
        assertTrue(messages.contains("PILLAGER: Разбойник"), "имена мобов — на русском");
        assertTrue(messages.contains("ZOMBIE: Зомби"));
        String plugin = read("src/main/resources/plugin.yml");
        assertTrue(plugin.contains("name: CHRDK_Mobs"));
        assertTrue(plugin.contains("main: ru.cherdakmd.chrdkmobs.MobsPlugin"));
        assertTrue(plugin.contains("CHRDK_Core"));
        assertTrue(plugin.contains("mobtiers"), "команда для меню и рассылки");
        assertTrue(plugin.contains("chrdkmobs.use"), "право пункта меню");
    }
}
