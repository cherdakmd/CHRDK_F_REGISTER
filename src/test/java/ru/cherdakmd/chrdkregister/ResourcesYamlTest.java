package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Валидность всех YAML-ресурсов всех модулей (config.yml / messages.yml / plugin.yml).
 * Страховка от инцидента 1.4.40–1.4.50: ручная вставка секций дважды «откусывала»
 * ключи в config.yml, файл переставал парситься, сервер молча работал на дефолтах.
 * Теперь ломается тест, а не прод.
 */
class ResourcesYamlTest {

    private static final List<String> MODULES = List.of(
            "", "achievements", "artifacts", "auction", "battlepass", "claims", "common-goals",
            "cosmetics", "daily-rewards", "diamond-shop", "enchants", "events", "hunt", "lottery",
            "orders", "party", "pets",
            "queue",
            "ranks", "referrals", "skills", "streams", "teleports", "tier-forge", "treasures");

    @Test
    void allResourceYamlsParse() throws Exception {
        StringBuilder problems = new StringBuilder();
        for (String module : MODULES) {
            Path resources = module.isEmpty()
                    ? Path.of("src/main/resources")
                    : Path.of(module, "src/main/resources");
            if (!Files.isDirectory(resources)) {
                continue;
            }
            try (var stream = Files.list(resources)) {
                for (Path file : stream.filter(p -> p.toString().endsWith(".yml")).toList()) {
                    try {
                        YamlConfiguration.loadConfiguration(
                                new StringReader(Files.readString(file)));
                    } catch (Exception exception) {
                        problems.append('\n').append(file).append(": ")
                                .append(exception.getMessage());
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), "YAML-ресурсы не парсятся:" + problems);
    }

    @Test
    void donatepayBlockContainsMatchingKeys() throws Exception {
        // Контракт PluginConfig.fromBukkitConfig: эти ключи должны лежать внутри donatepay:
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                new StringReader(Files.readString(Path.of("src/main/resources/config.yml"))));
        for (String key : List.of("api-token", "payment-url", "minimum-rubles", "poll-seconds",
                "strict-case-match", "review-unmatched", "fuzzy-match", "payment-code",
                "payment-code-digits", "journal-keep-days", "health-seconds", "alert-fail-streak",
                "alert-admins", "alert-repeat-minutes", "pending-notify-hours", "show-balance",
                "enabled")) {
            if (!config.isSet("donatepay." + key)) {
                fail("В config.yml секции donatepay нет ключа " + key);
            }
        }
        if (config.isSet("payment-code")) {
            fail("Обрывок payment-code на корневом уровне config.yml — ключ должен жить внутри donatepay");
        }
    }

    @Test
    void spawnSectionContainsEveryKey() throws Exception {
        // Контракт SpawnSettings.parse: ключи секции spawn; отсутствие секции включает дефолты.
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                new StringReader(Files.readString(Path.of("src/main/resources/config.yml"))));
        for (String key : List.of("enabled", "on-first-join", "on-death", "world",
                "use-world-spawn-center", "center-x", "center-z", "radius-blocks",
                "min-radius-blocks", "max-attempts", "avoid-claims", "respect-claims",
                "respect-homes", "other-dimensions")) {
            if (!config.isSet("spawn." + key)) {
                fail("В config.yml секции spawn нет ключа " + key);
            }
        }
    }

    @Test
    void guideSectionContainsBothKeys() throws Exception {
        // Контракт GuideBookSettings.parse: книга работает и без секции, но в ресурсе она должна быть.
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                new StringReader(Files.readString(Path.of("src/main/resources/config.yml"))));
        for (String key : List.of("enabled", "give-on-first-join")) {
            if (!config.isSet("guide." + key)) {
                fail("В config.yml секции guide нет ключа " + key);
            }
        }
    }
}
