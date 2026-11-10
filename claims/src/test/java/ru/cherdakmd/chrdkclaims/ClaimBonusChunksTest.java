package ru.cherdakmd.chrdkclaims;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Вечный купленный бонус чанков: накопление, потолок, JSON и перенос ника. */
class ClaimBonusChunksTest {

    @TempDir
    Path dir;

    private ClaimStore store() {
        return new ClaimStore(dir, Logger.getAnonymousLogger());
    }

    @Test
    void purchasesStackCaseInsensitivelyAndNeverGoBelowZero() {
        ClaimStore store = store();
        assertEquals(0, store.bonusChunks("Steve"));
        assertEquals(8, store.addBonusChunks("Steve", 8));
        assertEquals(16, store.addBonusChunks("steve", 8), "ник без учёта регистра");
        assertEquals(16, store.bonusChunks("STEVE"));
        assertEquals(8, store.addBonusChunks("Steve", -8));
        assertEquals(0, store.addBonusChunks("Steve", -100));
        assertTrue(store.bonusChunks().isEmpty(), "нулевой бонус не хранится");
    }

    @Test
    void bonusIsClampedAndAdminCanSetOrResetIt() {
        ClaimStore store = store();
        assertEquals(100_000, store.addBonusChunks("Steve", Integer.MAX_VALUE));
        assertEquals(100_000, store.addBonusChunks("Steve", 8), "переполнение не обнуляет купленное");
        assertEquals(24, store.setBonusChunks("Steve", 24));
        assertEquals(0, store.setBonusChunks("Steve", 0));
        assertEquals(0, store.bonusChunks("Steve"));
        assertEquals(0, store.addBonusChunks(null, 8));
    }

    @Test
    void roundTripWritesOptionalBlockAndKeepsOldFilesReadable() throws IOException {
        ClaimStore store = store();
        store.add(new Claim("world", 1, 1, "Steve", 1_700_000_000_000L));
        store.addBonusChunks("Steve", 8);
        store.setLimitOverride("Alex", 30);
        store.save();
        String json = Files.readString(dir.resolve("claims-data.json"), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"bonus-chunks\""), "блок дописывается только при непустом бонусе");

        ClaimStore loaded = store();
        loaded.load();
        assertEquals(8, loaded.bonusChunks("Steve"));
        assertEquals(Integer.valueOf(30), loaded.limitOverride("Alex"));

        // Файл эпохи до появления бонуса (без ключа bonus-chunks) читается как есть.
        ClaimStore before = store();
        before.setLimitOverride("Alex", 30);
        before.save();
        assertFalse(Files.readString(dir.resolve("claims-data.json"), StandardCharsets.UTF_8)
                .contains("bonus-chunks"));
        ClaimStore old = store();
        old.load();
        assertEquals(0, old.bonusChunks("Steve"));
        assertEquals(Integer.valueOf(30), old.limitOverride("Alex"));
    }

    @Test
    void transferAllCarriesBonusAndSumsWithRecipient() {
        ClaimStore store = store();
        store.addBonusChunks("OldName", 16);
        store.addBonusChunks("NewName", 8);
        store.add(new Claim("world", 0, 0, "OldName", 1_700_000_000_000L));

        ClaimStore.TransferSummary summary = store.transferAll("OldName", "NewName");
        assertEquals(16, summary.bonusChunks());
        assertEquals(1, summary.claims());
        assertEquals(24, store.bonusChunks("NewName"), "бонус получателя сохраняется");
        assertEquals(0, store.bonusChunks("OldName"));
    }

    @Test
    void transferAllWithoutBonusStillReportsZero() {
        ClaimStore store = store();
        store.add(new Claim("world", 0, 0, "OldName", 1_700_000_000_000L));
        ClaimStore.TransferSummary summary = store.transferAll("OldName", "NewName");
        assertEquals(0, summary.bonusChunks());
    }
}
