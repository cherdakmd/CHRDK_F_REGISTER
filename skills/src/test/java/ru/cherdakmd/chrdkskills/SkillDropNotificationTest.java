package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Регрессия горячего обработчика: убираем чат, не вторую порцию дропа. */
class SkillDropNotificationTest {

    @Test
    void doubleDropStaysSilentAndStillDropsToTheWorld() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/ru/cherdakmd/chrdkskills/SkillListener.java"), StandardCharsets.UTF_8);
        int start = source.indexOf("private void doubleDrop(");
        int end = source.indexOf("\n    /**", start);
        assertTrue(start >= 0 && end > start, "Проверяется именно обработчик второй добычи");
        String method = source.substring(start, end);
        assertTrue(method.contains("SkillPerk.Kind.DOUBLE_DROP"));
        assertTrue(method.contains("random.nextDouble() >= chance"), "Шанс не должен стать гарантией");
        assertTrue(method.contains("block.getDrops("), "Вторая порция берётся из таблицы дропа блока");
        assertTrue(method.contains("dropItemNaturally(dropAt, drop.clone())"), "Добыча остаётся на земле");
        assertFalse(method.contains("messages()"));
        assertFalse(method.contains("sendMessage("));
        assertFalse(method.contains("sendActionBar("));
        assertFalse(method.contains("getInventory().addItem("));
    }

    @Test
    void removedMessageIsNotAdvertisedAsAnActiveSetting() throws IOException {
        String messages = Files.readString(Path.of("src/main/resources/messages.yml"), StandardCharsets.UTF_8);
        assertFalse(messages.contains("double-drop:"));
    }
}
