package ru.cherdakmd.chrdkvk;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.Messages;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class VkMessagesTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Path STOCK_MESSAGES = Path.of("src/main/resources/messages.yml");
    private static final Path LEGACY_1_4_268 = Path.of("src/main/resources/vk-messages-legacy-1.4.268.yml");

    @Test
    void stock275MigratesAndCustomTextsStayUntouched(@TempDir Path tempDir) throws Exception {
        Path legacy = Path.of("src/main/resources/vk-messages-legacy-1.4.275.yml");
        Path live = tempDir.resolve("messages.yml");
        Files.copy(legacy, live);
        assertTrue(VkPlugin.migrateLegacyMessagesFile(live, () -> uncheckedStream(STOCK_MESSAGES),
                () -> uncheckedStream(legacy), Logger.getAnonymousLogger()));
        assertTrue(Files.readString(live).contains("bot:"));
        assertFalse(VkPlugin.migrateLegacyMessagesFile(live, () -> uncheckedStream(STOCK_MESSAGES),
                () -> uncheckedStream(legacy), Logger.getAnonymousLogger()));
        Files.writeString(live, Files.readString(legacy).replace("Мост беседы ВК и анонсы стены", "Мой заголовок"));
        assertTrue(VkPlugin.migrateLegacyMessagesFile(live, () -> uncheckedStream(STOCK_MESSAGES),
                () -> uncheckedStream(legacy), Logger.getAnonymousLogger()));
        assertTrue(Files.readString(live).contains("Мой заголовок"));
        try (InputStream in = Files.newInputStream(STOCK_MESSAGES)) {
            Messages messages = Messages.from(in, null, Logger.getAnonymousLogger());
            String text = messages.plain("bot.login-alert", "player", "Steve", "method", "пароль", "ip", "скрыт");
            assertTrue(text.contains("Steve") && text.contains("/заморозить"));
            assertTrue(text.contains("\n") && !text.contains("%"));
            assertTrue(messages.plain("bot.news-links", "url", "https://vk.ru/chrdk_reborn").contains("не означает"));
        }
    }

    @Test
    void linkCodeAndCommunityClicksRenderValidClickEventsWithoutRawMarkup() throws Exception {
        try (InputStream defaults = Files.newInputStream(STOCK_MESSAGES)) {
            Messages msg = Messages.fromChecked(defaults, STOCK_MESSAGES, Logger.getAnonymousLogger());
            assertTrue(msg.validate().isEmpty(), () -> "Проблемы messages.yml: " + msg.validate());

            List<Component> codeLines = msg.getList("vk.link-code-issued",
                    "code", "AB34CD",
                    "minutes", "5",
                    "url", "https://vk.ru/chrdk_reborn");
            assertEquals(2, codeLines.size());

            String line1 = PLAIN.serialize(codeLines.get(0));
            assertTrue(line1.contains("AB34CD"), line1);
            assertTrue(line1.contains("5 мин."), line1);
            assertFalse(line1.contains("<click:"), line1);
            assertFalse(line1.contains("<code>"), line1);
            assertFalse(line1.contains("<minutes>"), line1);
            assertTrue(collectClicks(codeLines.get(0)).contains(
                    ClickEvent.copyToClipboard("AB34CD")), "Должен быть ClickEvent копирования кода");

            String line2 = PLAIN.serialize(codeLines.get(1));
            assertTrue(line2.contains("https://vk.ru/chrdk_reborn"), line2);
            assertFalse(line2.contains("<click:"), line2);
            assertFalse(line2.contains("<url>"), line2);
            assertTrue(collectClicks(codeLines.get(1)).contains(
                    ClickEvent.openUrl("https://vk.ru/chrdk_reborn")), "Должен быть ClickEvent открытия группы ВК");

            Component wall = msg.get("vk.wall-broadcast",
                    "preview", "Открытие нового сезона!",
                    "post_url", "https://vk.ru/wall-12345_99");
            String wallPlain = PLAIN.serialize(wall);
            assertTrue(wallPlain.contains("Открытие нового сезона!"), wallPlain);
            assertFalse(wallPlain.contains("<click:"), wallPlain);
            assertTrue(collectClicks(wall).contains(
                    ClickEvent.openUrl("https://vk.ru/wall-12345_99")));

            Component unlinkPrompt = msg.get("vk.unlink-confirm-prompt", "vk_id", "777888");
            assertTrue(PLAIN.serialize(unlinkPrompt).contains("id777888"));
            assertTrue(collectClicks(unlinkPrompt).contains(
                    ClickEvent.runCommand("/vk unlink confirm")));

            Component adminStatus = msg.get("admin.status",
                    "configured", msg.mini("vk.state-yes"),
                    "worker", msg.mini("vk.state-on"),
                    "chat_peer", "2000000001",
                    "links", "12",
                    "donuts", "3",
                    "chatbans", "0",
                    "last_post", "99");
            String adminPlain = PLAIN.serialize(adminStatus);
            assertTrue(adminPlain.contains("2000000001") && adminPlain.contains("12") && adminPlain.contains("#99"), adminPlain);
            assertFalse(adminPlain.contains("%"), adminPlain);
        }
    }

    @Test
    void legacy14268StockFileOnDiskMigratesAutomatically(@TempDir Path tempDir) throws Exception {
        Path userFile = tempDir.resolve("messages.yml");
        Files.copy(LEGACY_1_4_268, userFile);

        boolean migrated = VkPlugin.migrateLegacyMessagesFile(
                userFile,
                () -> uncheckedStream(STOCK_MESSAGES),
                () -> uncheckedStream(LEGACY_1_4_268),
                Logger.getAnonymousLogger()
        );
        assertTrue(migrated, "Стоковый messages.yml из 1.4.268 должен обновиться на диске");

        String updatedContent = Files.readString(userFile, StandardCharsets.UTF_8);
        assertTrue(updatedContent.contains("%code%"));
        assertTrue(updatedContent.contains("%url%"));
        assertFalse(updatedContent.contains("'<url>'"));

        // Повторный запуск идемпотентен и не перезаписывает уже актуальный файл.
        assertFalse(VkPlugin.migrateLegacyMessagesFile(
                userFile,
                () -> uncheckedStream(STOCK_MESSAGES),
                () -> uncheckedStream(LEGACY_1_4_268),
                Logger.getAnonymousLogger()
        ));
    }

    @Test
    void partialCustomEditsIn14268FileArePreservedDuringMigration(@TempDir Path tempDir) throws Exception {
        Path userFile = tempDir.resolve("messages.yml");
        String legacyWithCustomHeader = Files.readString(LEGACY_1_4_268, StandardCharsets.UTF_8)
                .replace("━━━ Сообщество ВКонтакте CHRDK ━━━", "=== КАСТОМНЫЙ ЗАГОЛОВОК СЕРВЕРА ===");
        Files.writeString(userFile, legacyWithCustomHeader, StandardCharsets.UTF_8);

        boolean migrated = VkPlugin.migrateLegacyMessagesFile(
                userFile,
                () -> uncheckedStream(STOCK_MESSAGES),
                () -> uncheckedStream(LEGACY_1_4_268),
                Logger.getAnonymousLogger()
        );
        assertTrue(migrated);

        try (InputStream defaults = Files.newInputStream(STOCK_MESSAGES)) {
            Messages msg = Messages.fromChecked(defaults, userFile, Logger.getAnonymousLogger());
            assertTrue(PLAIN.serialize(msg.get("vk.status-header")).contains("КАСТОМНЫЙ ЗАГОЛОВОК СЕРВЕРА"));
            List<Component> codeLines = msg.getList("vk.link-code-issued",
                    "code", "ZX90QP", "minutes", "5", "url", "https://vk.ru/chrdk_reborn");
            assertTrue(PLAIN.serialize(codeLines.get(0)).contains("ZX90QP"));
            assertTrue(collectClicks(codeLines.get(1)).contains(
                    ClickEvent.openUrl("https://vk.ru/chrdk_reborn")));
        }
    }

    private static InputStream uncheckedStream(Path path) {
        try {
            return Files.newInputStream(path);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<ClickEvent> collectClicks(Component root) {
        List<ClickEvent> events = new ArrayList<>();
        if (root.clickEvent() != null) {
            events.add(root.clickEvent());
        }
        for (Component child : root.children()) {
            events.addAll(collectClicks(child));
        }
        return events;
    }
}
