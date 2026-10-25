package ru.cherdakmd.chrdkprofessions;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ответ действия с профессией (1.4.165).
 *
 * <p>Результат — это ключ текста и пары подстановок, а не готовая строка:
 * команда и меню показывают один и тот же ответ. Пустой ключ не должен
 * приводить к пустому сообщению — тогда игрок не поймёт, что произошло.</p>
 */
class ProfessionResultTest {

    @Test
    void okAndFailKeepPairs() {
        ProfessionResult ok = ProfessionResult.ok("profession-chosen", "profession", "smith");
        assertTrue(ok.ok());
        assertEquals("profession-chosen", ok.key());
        assertEquals(List.of("profession", "smith"), ok.args());

        ProfessionResult fail = ProfessionResult.fail("profession-cooldown", "time", "1 ч");
        assertFalse(fail.ok());
        assertEquals("profession-cooldown", fail.key());
        assertEquals(2, fail.array().length);
    }

    @Test
    void blankKeyFallsBackToUnknown() {
        assertEquals("unknown", new ProfessionResult(true, "", List.of()).key());
        assertEquals("unknown", new ProfessionResult(false, null, null).key());
        assertEquals(List.of(), new ProfessionResult(false, null, null).args());
    }

    @Test
    void emptyPairsAreAllowed() {
        assertEquals(List.of(), ProfessionResult.ok("admin-reset").args());
        assertEquals(List.of(), ProfessionResult.fail("admin-reset-none").args());
        assertEquals(0, ProfessionResult.fail("admin-reset-none").array().length);
    }

    @Test
    void argumentsAreImmutable() {
        ProfessionResult result = ProfessionResult.ok("profession-chosen",
                "profession", "smith");
        assertThrows(UnsupportedOperationException.class,
                () -> result.args().add("profession"));
        assertThrows(UnsupportedOperationException.class,
                () -> result.args().set(1, "mason"));
    }
}
