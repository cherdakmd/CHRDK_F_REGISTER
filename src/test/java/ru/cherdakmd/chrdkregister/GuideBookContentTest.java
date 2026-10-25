package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Содержимое книги-путеводителя из messages.yml: заголовок помещается в лимит Minecraft,
 * страницы не пустые, каждая укладывается в 1024 символа, команды из книги существуют.
 */
class GuideBookContentTest {

    private static final Logger LOGGER = Logger.getLogger("GuideBookContentTest");

    private static GuideBookContent content() throws Exception {
        try (InputStream stream = Files.newInputStream(Path.of("src/main/resources/messages.yml"))) {
            return GuideBookContent.from(Messages.from(stream, null, LOGGER));
        }
    }

    @Test
    void bookIsUsableAndFitsMinecraftLimits() throws Exception {
        GuideBookContent book = content();
        assertTrue(book.isUsable(), "в messages.yml должны быть guide.title и guide.pages");
        assertTrue(book.title().length() <= GuideBookContent.MAX_TITLE_LENGTH,
                "заголовок книги длиннее 32 символов: " + book.title());
        assertFalse(book.author().isBlank());
        assertTrue(book.pages().size() >= 5, "слишком короткий путеводитель: " + book.pages().size());
        assertTrue(book.pages().size() <= GuideBookContent.MAX_PAGES);
        for (int index = 0; index < book.pages().size(); index++) {
            String page = book.plainPage(index);
            assertFalse(page.isBlank(), "пустая страница " + (index + 1));
            assertTrue(page.length() <= GuideBookContent.MAX_PAGE_LENGTH,
                    "страница " + (index + 1) + " длиннее 1024 символов");
            assertTrue(page.length() <= 320,
                    "страница " + (index + 1) + " не влезет на экран книги: " + page.length());
        }
    }

    @Test
    void everyPageMentionsCommandsWithSlash() throws Exception {
        GuideBookContent book = content();
        for (int index = 1; index < book.pages().size(); index++) {
            assertTrue(book.plainPage(index).contains("/"),
                    "страница " + (index + 1) + " без команд: " + book.plainPage(index));
        }
    }

    @Test
    void emptyMessagesProduceUnusableBook() {
        Messages empty = Messages.from(new java.io.ByteArrayInputStream(new byte[0]), null, LOGGER);
        GuideBookContent book = GuideBookContent.from(empty);
        assertFalse(book.isUsable());
        assertEquals(0, book.pages().size());
    }
}
