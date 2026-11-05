package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Публичный кадр TAB: формат, топ-N, приватность и выключенный флаг. */
class PublicWarnSummaryTest {

    @Test
    void emptyListIsReportedAsNoOffenders() {
        assertEquals("✅ Нарушителей нет", PublicWarnSummary.format(List.of(), 3, 5));
        assertEquals("✅ Нарушителей нет", PublicWarnSummary.format(null, 3, 5));
        assertEquals("✅ Нарушителей нет",
                PublicWarnSummary.format(List.of(new PublicWarnSummary.Row("Alex", 0)), 3, 5));
    }

    @Test
    void disabledFrameProducesEmptyString() {
        assertEquals("", PublicWarnSummary.format(
                List.of(new PublicWarnSummary.Row("Alex", 2)), 0, 5));
        assertEquals("", PublicWarnSummary.format(
                List.of(new PublicWarnSummary.Row("Alex", 2)), 3, 0));
    }

    @Test
    void sortsByActiveCountAndShowsLimit() {
        String text = PublicWarnSummary.format(List.of(
                new PublicWarnSummary.Row("Petr", 2),
                new PublicWarnSummary.Row("Ivan", 4),
                new PublicWarnSummary.Row("Oleg", 1)), 3, 5);
        assertEquals("⚠ Ivan 4/5, Petr 2/5, Oleg 1/5", text);
        assertTrue(text.startsWith("⚠ "));
    }

    @Test
    void extraOffendersAreCollapsed() {
        String text = PublicWarnSummary.format(List.of(
                new PublicWarnSummary.Row("Ivan", 4),
                new PublicWarnSummary.Row("Petr", 3),
                new PublicWarnSummary.Row("Oleg", 2),
                new PublicWarnSummary.Row("Anna", 1)), 2, 5);
        assertEquals("⚠ Ivan 4/5, Petr 3/5 (+2 ещё)", text);
        assertFalse(text.contains("Oleg"), "сверх лимита ники не уходят в TAB");
    }

    @Test
    void namesAreSanitizedAndNeverCarryReasons() {
        String text = PublicWarnSummary.format(List.of(
                new PublicWarnSummary.Row("<red>Ivan</red>", 3),
                new PublicWarnSummary.Row("%tab_warns%", 2),
                new PublicWarnSummary.Row("", 2),
                new PublicWarnSummary.Row(null, 1)), 5, 5);
        assertEquals("⚠ RedIvanred 3/5, Tab_warns 2/5", text);
        assertFalse(text.contains("<"), "разметка из ника не должна попасть в MiniMessage");
        assertFalse(text.contains("%"), "сырой токен из ника не должен попасть в TAB");
    }

    @Test
    void longNamesAreTruncatedToBukkitLimit() {
        String text = PublicWarnSummary.format(List.of(
                new PublicWarnSummary.Row("ABCDEFGHIJKLMNOPQRSTUVWXYZ", 2)), 3, 5);
        assertEquals("⚠ ABCDEFGHIJKLMNOP 2/5", text);
    }
}
