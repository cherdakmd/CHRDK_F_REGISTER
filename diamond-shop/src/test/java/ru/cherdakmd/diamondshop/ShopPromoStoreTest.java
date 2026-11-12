package ru.cherdakmd.diamondshop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Отдельный файл прогресса: переживает рестарт, сбрасывается в новые сутки. */
class ShopPromoStoreTest {

    @TempDir
    Path dir;

    private static final long DAY = ShopPromoMath.DAY_MILLIS;
    private static final long NOW = 20_000L * DAY + 12_345L;
    private static final UUID STEVE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private ShopPromoStore store() {
        return new ShopPromoStore(dir, Logger.getAnonymousLogger());
    }

    @Test
    void commitsSurviveReloadAndRespectDailyCap() {
        ShopPromoStore first = store();
        first.load();
        assertEquals(0, first.bonusPaid(STEVE, NOW));
        first.commit(STEVE, NOW, "DIRT", 64, 12, true, false);
        first.commit(STEVE, NOW, "STONE", 32, 8, false, false);
        var snapshot = first.snapshot(STEVE, NOW);
        assertEquals(20, snapshot.bonus());
        assertEquals(64L, snapshot.soldOf("DIRT"));
        assertEquals(2, snapshot.distinct());
        assertTrue(snapshot.contractPaid());
        assertFalse(snapshot.seriesPaid());
        assertTrue(Files.isRegularFile(dir.resolve(ShopPromoStore.FILE_NAME)));

        ShopPromoStore reloaded = store();
        reloaded.load();
        assertEquals(20, reloaded.bonusPaid(STEVE, NOW));
        assertEquals(32L, reloaded.snapshot(STEVE, NOW).soldOf("STONE"));
        assertTrue(reloaded.snapshot(STEVE, NOW).contractPaid());
    }

    @Test
    void reservationSurvivesRestartAndReleaseIsDurable() {
        ShopPromoStore first = store();
        first.load();
        assertTrue(first.reserve(STEVE, NOW, 500000));
        assertEquals(500000, first.bonusPaid(STEVE, NOW));

        ShopPromoStore reloaded = store();
        reloaded.load();
        assertEquals(500000, reloaded.bonusPaid(STEVE, NOW));
        reloaded.release(STEVE, NOW, 500000);

        ShopPromoStore afterRelease = store();
        afterRelease.load();
        assertEquals(0, afterRelease.bonusPaid(STEVE, NOW));
    }

    @Test
    void newUtcDayStartsFromScratch() {
        ShopPromoStore store = store();
        store.load();
        store.commit(STEVE, NOW, "DIRT", 64, 12, false, false);
        assertEquals(12, store.bonusPaid(STEVE, NOW));
        long tomorrow = NOW + DAY;
        assertEquals(0, store.bonusPaid(STEVE, tomorrow));
        assertTrue(store.snapshot(STEVE, tomorrow).kinds().isEmpty());
        // Возврат к старым суткам не воскрешает вчерашний прогресс.
        assertEquals(0, store.bonusPaid(STEVE, NOW));
    }

    @Test
    void corruptOrForeignFileIsQuarantinedByEmptyState() throws IOException {
        Path file = dir.resolve(ShopPromoStore.FILE_NAME);
        Files.writeString(file, "{ это не json", StandardCharsets.UTF_8);
        ShopPromoStore broken = store();
        broken.load();
        assertEquals(0, broken.bonusPaid(STEVE, NOW));

        Files.writeString(file, "{\"schema\":99,\"day\":1,\"players\":{}}", StandardCharsets.UTF_8);
        ShopPromoStore foreign = store();
        foreign.load();
        assertEquals(0, foreign.bonusPaid(STEVE, NOW));

        Files.writeString(file, "{\"schema\":1,\"day\":" + ShopPromoMath.dayIndex(NOW)
                + ",\"players\":{\"bad\":{\"bonus\":\"много\"}}}", StandardCharsets.UTF_8);
        ShopPromoStore partial = store();
        partial.load();
        assertEquals(0, partial.bonusPaid(STEVE, NOW), "битая строка игрока пропускается");
    }

    @Test
    void negativeAndZeroBonusesNeverGrowCounters() {
        ShopPromoStore store = store();
        store.load();
        store.commit(STEVE, NOW, "DIRT", 0, -50, false, false);
        assertEquals(0, store.bonusPaid(STEVE, NOW));
        assertTrue(store.snapshot(STEVE, NOW).kinds().isEmpty());
    }
}
