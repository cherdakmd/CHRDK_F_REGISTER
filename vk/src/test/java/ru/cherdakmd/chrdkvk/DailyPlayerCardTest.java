package ru.cherdakmd.chrdkvk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Карточка игрока: реальный PNG, ник в тексте и отсутствие приватных данных. */
class DailyPlayerCardTest {

    private static final String DAY = "2026-11-03";

    @Test
    void renderProducesRealPngOfExpectedSize() throws Exception {
        var nomination = new DailyNomination(DailyCardKind.RICHEST, "Steve", 12340L, 0L);
        byte[] png = DailyPlayerCard.render(nomination, DailyCardsConfig.DEFAULT, DAY);
        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image);
        assertEquals(1280, image.getWidth());
        assertEquals(720, image.getHeight());
    }

    @Test
    void messageContainsNickValueAndMarkerOnly() {
        var nomination = new DailyNomination(DailyCardKind.MINER, "Alex",
                1842L, 96L);
        String text = DailyPlayerCard.message(nomination, DailyCardsConfig.DEFAULT,
                DAY, "#chrdk_abc123");
        assertTrue(text.contains("Шахтёр дня"));
        assertTrue(text.contains("Alex"));
        assertTrue(text.contains("1842 блока"));
        assertTrue(text.contains("96 алмазов"));
        assertTrue(text.contains("3 ноября 2026"));
        assertTrue(text.contains("#chrdk_abc123"));
        assertFalse(text.contains("UUID"));
        assertFalse(text.contains("uuid"));
        assertFalse(text.contains("руб"));
    }

    @Test
    void valuesCanBeHiddenAndNickIsSanitized() {
        var nomination = new DailyNomination(DailyCardKind.HUNTER,
                "Мария<script>_12345678901234567890", 37L, 0L);
        assertEquals("Марияscript_123456789012", nomination.playerName());
        assertEquals(24, nomination.playerName().length());
        DailyCardsConfig hidden = new DailyCardsConfig(true,
                java.time.ZoneId.of("Europe/Moscow"), false, 9, 12, 15, 18, 5,
                "ТВОЙ МИР. ТВОЯ ИСТОРИЯ.");
        String text = DailyPlayerCard.message(nomination, hidden, DAY, "");
        assertTrue(text.contains("Марияscript"));
        assertFalse(text.contains("37"));
        assertFalse(text.contains("побед"));
    }

    @Test
    void pluralFormsAreCorrect() {
        assertEquals("1 блок · 1 алмаз",
                new DailyNomination(DailyCardKind.MINER, "A", 1L, 1L)
                        .valueLine(true));
        assertEquals("2 блока · 3 алмаза",
                new DailyNomination(DailyCardKind.MINER, "A", 2L, 3L)
                        .valueLine(true));
        assertEquals("12 блоков · 11 алмазов",
                new DailyNomination(DailyCardKind.MINER, "A", 12L, 11L)
                        .valueLine(true));
        assertEquals("7 ступеней престижа",
                new DailyNomination(DailyCardKind.PRESTIGE, "A", 7L, 0L)
                        .valueLine(true));
        assertEquals("1 победа над монстрами",
                new DailyNomination(DailyCardKind.HUNTER, "A", 1L, 0L)
                        .valueLine(true));
    }

    @Test
    void humanDateFallsBackToRawDay() {
        assertEquals("3 ноября 2026", DailyPlayerCard.humanDate(DAY));
        assertEquals("", DailyPlayerCard.humanDate(null));
        assertEquals("unknown", DailyPlayerCard.humanDate("unknown"));
    }
}
