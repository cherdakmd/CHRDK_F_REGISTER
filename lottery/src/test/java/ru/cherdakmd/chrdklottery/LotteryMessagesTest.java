package ru.cherdakmd.chrdklottery;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

/** Literal message contract and the dynamic keys used by broadcasts/admin branching. */
class LotteryMessagesTest {
    @Test void allLiteralAndDynamicKeysExist() throws Exception {
        try (var stream = getClass().getResourceAsStream("/messages.yml")) {
            assertNotNull(stream);
            var yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            Pattern pattern = Pattern.compile("\"(lottery\\.[a-z0-9-]+)\"");
            try (var paths = Files.walk(Path.of("src/main/java"))) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                    Matcher matcher = pattern.matcher(Files.readString(path));
                    while (matcher.find()) {
                        // notifyEvent("lottery.draw", ...) is a Discord event identifier, not a message key.
                        if (matcher.group(1).equals("lottery.draw")) continue;
                        assertTrue(yaml.isString("messages." + matcher.group(1)), matcher.group(1));
                    }
                }
            }
            assertTrue(yaml.getString("messages.lottery.info-yours").contains("%chance%"));
            assertTrue(yaml.getString("messages.lottery.pending-paid").contains("%remaining%"));
        }
    }
}
