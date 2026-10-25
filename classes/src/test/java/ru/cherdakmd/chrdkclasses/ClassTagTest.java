package ru.cherdakmd.chrdkclasses;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;

import java.io.InputStream;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Метка класса в нике (1.4.174).
 *
 * <p>Склейка строки — чистая функция, поэтому проверяется без сервера: пустая
 * метка ничего не ломает, повторная склейка не даёт «[Воин] [Воин] Ник», а
 * метки всех четырёх классов есть в настоящем {@code messages.yml}.</p>
 */
class ClassTagTest {

    private static final Logger LOGGER = Logger.getLogger("ClassTagTest");

    @Test
    void tagGoesBeforeName() {
        assertEquals("<red>[Воин]</red> Чердак",
                ClassTag.prepend("<red>[Воин]</red>", "Чердак"));
    }

    @Test
    void blankTagChangesNothing() {
        assertEquals("Чердак", ClassTag.prepend("", "Чердак"));
        assertEquals("Чердак", ClassTag.prepend(null, "Чердак"));
        assertEquals("Чердак", ClassTag.prepend("   ", "Чердак"));
        assertEquals("", ClassTag.prepend(null, null));
        assertEquals("<red>[Воин]</red>", ClassTag.prepend(" <red>[Воин]</red> ", ""));
    }

    @Test
    void repeatedDecorationDoesNotStack() {
        String once = ClassTag.prepend("<red>[Воин]</red>", "Чердак");
        assertEquals(once, ClassTag.prepend("<red>[Воин]</red>", once),
                "ядро зовёт оформление и для чата, и для TAB");
        assertFalse(once.contains("[Воин] [Воин]"));
    }

    @Test
    void bundledMessagesHaveTagForEveryClass() throws Exception {
        try (InputStream stream = ClassTagTest.class.getResourceAsStream("/messages.yml")) {
            assertNotNull(stream, "messages.yml должен лежать в ресурсах модуля");
            Messages messages = Messages.from(stream, null, LOGGER);
            for (PlayerClass playerClass : ClassCatalog.classes()) {
                String key = "tag." + playerClass.id();
                assertTrue(messages.has(key), "нет метки класса: " + key);
                String name = messages.plain("class." + playerClass.id() + ".name");
                assertFalse(name.isBlank(), "нет имени класса: " + playerClass.id());
                assertTrue(messages.raw(key).contains(name),
                        "метка " + key + " должна содержать имя класса");
            }
        }
    }
}
