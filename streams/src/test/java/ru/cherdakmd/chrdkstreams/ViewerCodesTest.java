package ru.cherdakmd.chrdkstreams;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Коды просмотра: формат, сверка «как ввёл игрок» и границы окна. */
class ViewerCodesTest {

    @Test
    void codeHasWatchPrefix() {
        String code = ViewerCodes.generate();
        assertTrue(code.matches("WATCH-[A-Z2-9]{4}"), "получено: " + code);
    }

    @Test
    void matchesIgnoresCaseSpacesAndDashes() {
        assertTrue(ViewerCodes.matches("WATCH-7K2M", "WATCH-7K2M"));
        assertTrue(ViewerCodes.matches("watch 7k2m", "WATCH-7K2M"));
        assertTrue(ViewerCodes.matches("  WATCH - 7K2M  ", "WATCH-7K2M"));
        assertTrue(ViewerCodes.matches("watch7k2m", "WATCH-7K2M"));
    }

    @Test
    void rejectsWrongOrEmptyCode() {
        assertFalse(ViewerCodes.matches("WATCH-7K2N", "WATCH-7K2M"));
        assertFalse(ViewerCodes.matches("", "WATCH-7K2M"));
        assertFalse(ViewerCodes.matches(null, "WATCH-7K2M"));
        assertFalse(ViewerCodes.matches("WATCH-7K2M", ""));
        assertFalse(ViewerCodes.matches("CHRDK-7K2M", "WATCH-7K2M"));
    }

    @Test
    void windowExpiresWhenTimeIsUp() {
        assertTrue(ViewerCodes.expired(1_000L, 1_000L));
        assertTrue(ViewerCodes.expired(1_001L, 1_000L));
        assertFalse(ViewerCodes.expired(999L, 1_000L));
    }

    @Test
    void windowMinutesAreClamped() {
        assertEquals(ViewerCodes.MIN_WINDOW_MINUTES * 60_000L, ViewerCodes.windowMillis(0));
        assertEquals(ViewerCodes.MAX_WINDOW_MINUTES * 60_000L, ViewerCodes.windowMillis(9_999));
        assertEquals(10 * 60_000L, ViewerCodes.windowMillis(10));
    }

    @Test
    void creditIsCappedByCodeWindow() {
        long window = 10 * 60_000L;
        assertEquals(0L, ViewerCodes.creditMillis(0L, window),
                "первый чек-ин ничего не даёт — он только запускает счёт");
        assertEquals(5 * 60_000L, ViewerCodes.creditMillis(5 * 60_000L, window));
        assertEquals(window, ViewerCodes.creditMillis(60 * 60_000L, window),
                "час отсутствия не превращается в час просмотра");
        assertEquals(0L, ViewerCodes.creditMillis(-1L, window));
        assertEquals(0L, ViewerCodes.creditMillis(5 * 60_000L, 0L));
    }

    @Test
    void normalizeCompactsCode() {
        assertEquals("WATCH7K2M", ViewerCodes.normalize(" watch - 7k2m "));
        assertEquals("", ViewerCodes.normalize(null));
    }
}
