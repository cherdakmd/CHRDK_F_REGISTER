package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** moderation.json: атомарная запись, полный round-trip и мягкий откат при повреждении. */
class ModerationStoreTest {

    @TempDir
    Path folder;

    @Test
    void stateSurvivesRestartAndMissingFields() throws Exception {
        ModerationStore store = new ModerationStore(folder, Logger.getAnonymousLogger());
        assertEquals(folder.resolve("moderation.json"), store.file());

        ModerationLedger first = store.load();
        assertEquals(0, first.warnTotal(), "отсутствующий файл — пустое состояние");
        first.issueWarn("Steve", "Mod", "грифинг", 1000L);
        first.addModerator("Mod", "Owner", 1001L);
        first.ban("Alex", "пять варнов", "Owner", 1002L);
        assertTrue(store.save(first));
        assertTrue(Files.isRegularFile(store.file()));
        assertFalse(Files.exists(folder.resolve("moderation.json.tmp")),
                "временный файл должен быть перенесён");

        ModerationLedger second = store.load();
        assertEquals(1, second.activeCount("Steve"));
        assertEquals("грифинг", second.activeWarns("Steve").get(0).reason);
        assertTrue(second.isModerator("mod"));
        assertTrue(second.isBanned("Alex"));
        assertEquals(3, second.journal("", 500).size());

        // Старый файл без новых полей: чтение не должно ронять панель.
        Files.writeString(store.file(), "{\"version\":1,\"warns\":[]}", StandardCharsets.UTF_8);
        ModerationLedger legacy = store.load();
        assertEquals(0, legacy.warnTotal());
        assertEquals(1L, legacy.issueWarn("Steve", "Mod", "после миграции", 1L).id);
    }

    @Test
    void corruptedFileStartsEmptyInsteadOfCrashing() throws Exception {
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("moderation.json"), "{ это не json", StandardCharsets.UTF_8);
        ModerationStore store = new ModerationStore(folder, Logger.getAnonymousLogger());
        ModerationLedger ledger = store.load();
        assertEquals(0, ledger.warnTotal());
        assertNotNull(ledger.snapshot());
        assertTrue(store.save(ledger), "после сбоя чтения запись продолжает работать");
    }
}
