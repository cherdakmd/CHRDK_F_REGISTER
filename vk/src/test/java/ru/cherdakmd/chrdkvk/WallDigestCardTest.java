package ru.cherdakmd.chrdkvk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Рендер проверяется офлайн: без Bukkit, сети и PIL. */
class WallDigestCardTest {

    private static WallDigestCollector.Digest digest(int prestige, int donations, int peak,
                                                     int newcomers, List<String> leaders) {
        return new WallDigestCollector.Digest("2026-11-03", prestige, donations,
                peak, newcomers, leaders, prestige + donations + newcomers);
    }

    @Test
    void renderProducesRealPngOfExpectedSize() throws Exception {
        byte[] png = WallDigestCard.render(digest(3, 2, 17, 1, List.of()), WallDigestConfig.DEFAULT);
        assertTrue(png.length > 10_000, "Карточка не должна быть пустышкой");
        assertEquals((byte) 0x89, png[0]);
        assertEquals((byte) 'P', png[1]);
        assertEquals((byte) 'N', png[2]);
        assertEquals((byte) 'G', png[3]);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image);
        assertEquals(WallDigestCard.WIDTH, image.getWidth());
        assertEquals(WallDigestCard.HEIGHT, image.getHeight());
    }

    @Test
    void quietDayStillRendersAndUsesFallbackLine() throws Exception {
        byte[] png = WallDigestCard.render(digest(0, 0, 0, 0, List.of()), WallDigestConfig.DEFAULT);
        assertTrue(png.length > 10_000);
        String text = WallDigestCard.message(digest(0, 0, 0, 0, List.of()),
                WallDigestConfig.DEFAULT, "#chrdk_test");
        assertTrue(text.contains("Тихий день"));
        assertTrue(text.contains("#chrdk_test"));
    }

    @Test
    void publicTextHasOnlyAggregatesAndHiddenMarker() {
        var data = digest(3, 2, 17, 1, List.of());
        String text = WallDigestCard.message(data, WallDigestConfig.DEFAULT, "#chrdk_abc123");

        assertTrue(text.contains("3 ноября 2026"));
        assertTrue(text.contains("Престиж: 3 ступени"));
        assertTrue(text.contains("Поддержка сервера: 2 покупки"));
        assertTrue(text.contains("пик 17"));
        assertTrue(text.contains("#chrdk_abc123"));
        assertFalse(text.contains("Hidden"), "Opt-out имя не уходит на публичную стену");
        assertFalse(text.contains("руб"), "Суммы не публикуются");
        assertFalse(text.contains("uuid"), "UUID не публикуются");
        assertTrue(text.contains("ТВОЙ МИР. ТВОЯ ИСТОРИЯ."));
    }

    @Test
    void namedLeadersAppearOnlyWhenExplicitlyEnabled() throws Exception {
        var data = digest(3, 0, 0, 1, List.of("Steve", "Alex"));
        WallDigestConfig named = WallDigestConfig.DEFAULT.withPlayerNames(true);
        assertTrue(WallDigestCard.message(data, named, "").contains("лидеры: Steve, Alex"));
        // С именами постер всё ещё рендерится в корректный PNG.
        assertNotNull(ImageIO.read(new ByteArrayInputStream(WallDigestCard.render(data, named))));
    }
}
