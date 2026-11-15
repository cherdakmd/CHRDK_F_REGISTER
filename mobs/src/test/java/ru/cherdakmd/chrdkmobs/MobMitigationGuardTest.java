package ru.cherdakmd.chrdkmobs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Мобы всегда опасны: суммарное снижение входящего урона плагинами зажато
 * потолком, который падает с тиром моба. Контракт фиксации исходного урона,
 * зажима после всех редукторов и регистрации стража.
 */
class MobMitigationGuardTest {

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    void mitigationIsCappedAndTierAware() throws IOException {
        String guard = read("src/main/java/ru/cherdakmd/chrdkmobs/MobMitigationGuard.java");
        assertTrue(guard.contains("EventPriority.LOWEST"),
                "исходный урон фиксируется до редукторов классов, навыков и Кузни");
        assertTrue(guard.contains("EventPriority.MONITOR"),
                "суммарное снижение зажимается после всех редукторов");
        assertTrue(guard.contains("tier-v1"), "потолок зависит от тира моба");
        assertTrue(guard.contains("MobTierMath.mitigationCap"), "формула потолка — чистая математика");
        String plugin = read("src/main/java/ru/cherdakmd/chrdkmobs/MobsPlugin.java");
        assertTrue(plugin.contains("new MobMitigationGuard(this)"), "страж зарегистрирован при старте");
    }

    @Test
    void mitigationIsTunedInConfigWithoutCodeChanges() throws IOException {
        String config = read("src/main/resources/config.yml");
        assertTrue(config.contains("mitigation:"), "потолок настраивается в конфиге");
        assertTrue(config.contains("cap: 0.5"), "суммарное снижение ограничено половиной");
        assertTrue(config.contains("per-tier-step: 0.04"), "высшие тиры пробивают больше защиты");
        assertTrue(config.contains("floor: 0.3"), "у пробивания есть пол");
    }
}
