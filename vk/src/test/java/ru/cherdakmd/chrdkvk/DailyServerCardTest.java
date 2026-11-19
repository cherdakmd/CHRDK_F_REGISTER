package ru.cherdakmd.chrdkvk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Серверная карточка: агрегаты суток, без имён и приватных данных. */
class DailyServerCardTest {

    private static final String DAY = "2026-11-03";

    @Test
    void renderProducesRealPngOfExpectedSize() throws Exception {
        var stats = new DailyServerCard.Stats(12, 3, 842L, 12482L);
        byte[] png = DailyServerCard.render(stats, DailyCardsConfig.DEFAULT, DAY);
        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image);
        assertEquals(1280, image.getWidth());
        assertEquals(720, image.getHeight());
    }

    @Test
    void messageContainsStatsContactAndMarker() {
        var stats = new DailyServerCard.Stats(12, 3, 842L, 12482L);
        String text = DailyServerCard.message(stats, DailyCardsConfig.DEFAULT,
                DAY, "#chrdk_abc123");
        assertTrue(text.contains("День сервера"));
        assertTrue(text.contains("Пик онлайна: 12 игроков"));
        assertTrue(text.contains("Новичков за сутки: 3 новичка"));
        assertTrue(text.contains("Активность: 842 победы над монстрами"));
        assertTrue(text.contains("Добыча: 12482 блока добыто"));
        assertTrue(text.contains("3 ноября 2026"));
        assertTrue(text.contains("4rdk.ru-mc.ru"));
        assertTrue(text.contains("1.21"));
        assertTrue(text.contains("#chrdk_abc123"));
        assertFalse(text.contains("UUID"));
        assertFalse(text.contains("uuid"));
    }

    @Test
    void valuesCanBeHiddenAndEmptyDayHasNoData() {
        var hidden = new DailyCardsConfig(true,
                java.time.ZoneId.of("Europe/Moscow"), false, 9, 12, 13, 15, 16, 18, 19, 5,
                "ТВОЙ МИР. ТВОЯ ИСТОРИЯ.", DailyCardsConfig.DEFAULT_CONTACT);
        var stats = new DailyServerCard.Stats(12, 3, 842L, 12482L);
        String text = DailyServerCard.message(stats, hidden, DAY, "");
        assertFalse(text.contains("12 игроков"));
        assertFalse(text.contains("842"));
        assertTrue(text.contains("Пик онлайна"));
        assertFalse(new DailyServerCard.Stats(0, 0, 0L, 0L).hasData());
        assertTrue(new DailyServerCard.Stats(0, 1, 0L, 0L).hasData());
    }
}
