package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Прокачка и отклики кристалла показываются в акшнбаре, чат не спамится. */
class SkillActionbarFeedbackTest {

    @Test
    void crystalFeedbackGoesToActionbarAndRefusalsStayInChat() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/ru/cherdakmd/chrdkskills/SkillXpItems.java"), StandardCharsets.UTF_8);
        assertTrue(source.contains("private void feedback(Player player, String key, Object... args)"),
                "отклик кристалла собирается в одном маршруте");
        assertTrue(source.contains("player.sendActionBar(plugin.messages().get(key, args))"),
                "обычный отклик идёт в акшнбар");
        assertTrue(source.contains("plugin.config().actionbar()"),
                "маршрут подчиняется feedback.actionbar");
        for (String key : List.of("xp-potion-used", "xp-potion-used-max", "xp-potion-money",
                "xp-potion-vanilla", "xp-potion-cooldown")) {
            assertTrue(source.contains("feedback(player, \"" + key + "\""),
                    key + " уходит в акшнбар");
        }
        assertTrue(source.contains("plugin.messages().send(player, key)"),
                "отказы кристалла (fail) остаются в чате — они важны и содержат подсказки");
    }

    @Test
    void levelUpGoesToActionbarAndChatIsOnlyFallback() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/ru/cherdakmd/chrdkskills/SkillService.java"), StandardCharsets.UTF_8);
        assertTrue(source.contains("player.sendActionBar(plugin.messages().get(\"actionbar-level\","),
                "поздравление с новым уровнем — в акшнбар");
        assertTrue(source.contains("} else if (plugin.config().announce()) {"),
                "чат — только запасной путь при выключенном акшнбаре");
        assertTrue(source.indexOf("sendActionBar") < source.indexOf("\"level-up\""),
                "акшнбар — основной канал, чат — фолбэк");
    }
}
