package ru.cherdakmd.chrdkclaims;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Перенос стоковых значений уведомлений о границах на новые. Без Bukkit-сервера. */
class ClaimNoticeMigrationTest {

    private static final List<String> STOCK = List.of(
            "# Уведомления о границах приватов",
            "general:",
            "  # Канал: actionbar — строка над хотбаром; chat — в чат; both — и туда, и туда.",
            "  notice-mode: \"actionbar\"",
            "  enter-notice: true",
            "  notice-own: false   # свои приваты молчат",
            "  notice-members: true",
            "  notice-cooldown-seconds: 2",
            "menu:",
            "  notice-mode: \"actionbar\"",
            "  title: \"Приваты\"");

    @Test
    void stockValuesAreRewrittenWithComments() {
        ClaimNoticeMigration.Result result = ClaimNoticeMigration.rewrite(STOCK);
        assertEquals(List.of("general.notice-mode", "general.notice-own"), result.changed());
        assertTrue(result.dirty());

        List<String> lines = result.lines();
        assertEquals(STOCK.size(), lines.size(), "число строк не меняется");
        assertEquals("  notice-mode: \"both\"", lines.get(3));
        assertEquals("  notice-own: true   # свои приваты молчат", lines.get(5),
                "инлайн-комментарий остаётся");
        assertEquals(STOCK.get(2), lines.get(2), "комментарии и остальные ключи не тронуты");
    }

    @Test
    void chosenValuesAndOtherSectionsStayUntouched() {
        List<String> chosen = List.of(
                "general:",
                "  notice-mode: \"chat\"",
                "  notice-own: true");
        assertEquals(List.of(), ClaimNoticeMigration.rewrite(chosen).changed());

        // Ключи вне секции general не считаются настройкой уведомлений.
        List<String> lines = ClaimNoticeMigration.rewrite(STOCK).lines();
        assertEquals("  notice-mode: \"actionbar\"", lines.get(9));
    }

    @Test
    void alreadyNewOrMinimalConfigDoesNothing() {
        assertEquals(List.of(), ClaimNoticeMigration.rewrite(List.of(
                "general:",
                "  notice-mode: \"both\"",
                "  notice-own: true")).changed());
        assertEquals(List.of(), ClaimNoticeMigration.rewrite(List.of("general: {}")).changed());
        assertEquals(List.of(), ClaimNoticeMigration.rewrite(List.of()).changed());
        assertEquals(List.of(), ClaimNoticeMigration.rewrite(null).changed());
    }

    @Test
    void quotesAndCaseAreRecognized() {
        List<String> lines = ClaimNoticeMigration.rewrite(List.of(
                "general:",
                "  notice-mode: ActionBar",
                "  notice-own: FALSE")).lines();
        assertEquals("  notice-mode: \"both\"", lines.get(1));
        assertEquals("  notice-own: true", lines.get(2));
    }

    @Test
    void applyRewritesFileOnce(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("config.yml");
        Files.write(config, STOCK, StandardCharsets.UTF_8);

        assertTrue(ClaimNoticeMigration.apply(config, dir, null), "файл изменён");
        List<String> after = Files.readAllLines(config, StandardCharsets.UTF_8);
        assertEquals("  notice-mode: \"both\"", after.get(3));
        assertEquals("  notice-own: true   # свои приваты молчат", after.get(5));
        assertTrue(Files.exists(dir.resolve(ClaimNoticeMigration.MARKER)));

        // Владелец вернул тишину на своих приватах — повторный запуск не спорит.
        List<String> restored = List.of("general:", "  notice-own: false");
        Files.write(config, restored, StandardCharsets.UTF_8);
        assertFalse(ClaimNoticeMigration.apply(config, dir, null));
        assertEquals(restored, Files.readAllLines(config, StandardCharsets.UTF_8));
    }

    @Test
    void applyIsSafeWithoutConfig(@TempDir Path dir) {
        assertFalse(ClaimNoticeMigration.apply(dir.resolve("config.yml"), dir, null),
                "файла нет — переписывать нечего");
        assertTrue(Files.exists(dir.resolve(ClaimNoticeMigration.MARKER)),
                "метка ставится, чтобы не проверять файл каждый запуск");
        assertFalse(ClaimNoticeMigration.apply(null, dir, null));
        assertFalse(ClaimNoticeMigration.apply(dir.resolve("config.yml"), null, null));
    }
}
