package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Регрессия на «мёртвые» настройки: ключ может читаться из config.yml и даже
 * проверяться тестом, но при этом ни разу не влиять на поведение. Каждый ключ
 * блока {@code honors.*} обязан иметь аксессор, используемый вне парсера.
 */
class HonorConfigWiringTest {

    @Test
    void everyHonorsKeyIsParsedAndReadByRuntime() throws Exception {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/config.yml").toFile());
        var section = yaml.getConfigurationSection("honors");
        assertNotNull(section, "блок honors отсутствует в config.yml");

        Path dir = Path.of("src/main/java/ru/cherdakmd/chrdkregister");
        String parser = Files.readString(dir.resolve("HonorConfig.java"));
        StringBuilder runtime = new StringBuilder();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")
                    && !p.getFileName().toString().equals("HonorConfig.java")).toList()) {
                runtime.append(Files.readString(file));
            }
        }
        String code = runtime.toString();
        for (String key : section.getKeys(false)) {
            assertTrue(parser.contains("\"honors." + key + "\""),
                    "ключ honors." + key + " не читается из конфига");
            String accessor = camel(key);
            assertTrue(code.contains("." + accessor + "()"),
                    "ключ honors." + key + " парсится, но нигде не используется: "
                            + accessor + "()");
        }
    }

    private static String camel(String key) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : key.toCharArray()) {
            if (c == '-') {
                upper = true;
                continue;
            }
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return out.toString();
    }
}
