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
        return digest(prestige, donations, peak, newcomers, 0, 0, 0, leaders);
    }

    private static WallDigestCollector.Digest digest(int prestige, int donations, int peak,
                                                     int newcomers, long blocks, long diamonds,
                                                     long mobs, List<String> leaders) {
        long total = (long) prestige + donations + newcomers + blocks + diamonds + mobs;
        return new WallDigestCollector.Digest("2026-11-03", prestige, donations,
                peak, newcomers, blocks, diamonds, mobs, leaders, total);
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
    void fullSixRowDigestRendersWithoutOverlappingFooter() throws Exception {
        var data = digest(3, 2, 17, 1, 1842, 96, 37, List.of());
        byte[] png = WallDigestCard.render(data, WallDigestConfig.DEFAULT);
        assertNotNull(ImageIO.read(new ByteArrayInputStream(png)));
        String text = WallDigestCard.message(data, WallDigestConfig.DEFAULT, "#chrdk_abc123");
        assertTrue(text.contains("Добыто блоков: 1842"));
        assertTrue(text.contains("Получено алмазов: 96"));
        assertTrue(text.contains("Побед над монстрами: 37"));
        // На публичной стене по-прежнему только агрегаты.
        assertFalse(text.contains("Steve"));
        assertFalse(text.contains("руб"));
    }

    @Test
    void longLeaderNamesStillFitAboveFooter() throws Exception {
        var leaders = List.of("ОченьДлинноеИмяПервогоИгрокаКотороеПереносится",
                "ОченьДлинноеИмяВторогоИгрокаКотороеТожеПереносится",
                "ОченьДлинноеИмяТретьегоИгрокаКотороеОпятьПереносится");
        var cfg = WallDigestConfig.DEFAULT.withPlayerNames(true);
        var data = digest(3, 2, 17, 1, 1842, 96, 37, leaders);
        byte[] png = WallDigestCard.render(data, cfg);
        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image);
        assertEquals(1280, image.getWidth());
        assertEquals(720, image.getHeight());
    }

    @Test
    void activityBlocksCanBeDisabledIndependently() {
        var data = digest(3, 2, 17, 1, 1842, 96, 37, List.of());
        var cfg = new WallDigestConfig(true, 21, 0, java.time.ZoneId.of("Europe/Moscow"),
                true, true, true, false, false, false, false, 3, 1, 5,
                "Итоги дня", "ТВОЙ МИР. ТВОЯ ИСТОРИЯ.");
        String text = WallDigestCard.message(data, cfg, "");
        assertFalse(text.contains("Добыто блоков"));
        assertFalse(text.contains("Получено алмазов"));
        assertFalse(text.contains("Побед над монстрами"));
        assertTrue(text.contains("Престиж"));
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
