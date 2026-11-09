package ru.cherdakmd.chrdkregister;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Разделённое хранилище модерации: YAML игроков, JSON с SHA-256 и миграция. */
class ModerationStoreTest {

    private static final Logger LOGGER = Logger.getAnonymousLogger();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    @TempDir
    Path folder;

    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(64);
        for (byte value : digest) out.append(String.format("%02x", value));
        return out.toString();
    }

    private static void writeLegacy(Path file, String json) throws Exception {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        root.addProperty("checksum", sha256(GSON.toJson(root)));
        Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
    }

    @Test
    void stateSurvivesRestartAndExistingYamlIsNotOverwrittenByLegacyJson() throws Exception {
        ModerationStore store = new ModerationStore(folder, LOGGER);
        assertEquals(folder.resolve("moderation.json"), store.file());

        ModerationLedger first = store.load();
        assertEquals(0, first.warnTotal(), "отсутствующий файл — пустое состояние");
        first.issueWarn("Steve", "Mod", "грифинг", 1000L);
        first.addModerator("Mod", "Owner", 1001L);
        first.ban("Alex", "пять варнов", "Owner", 1002L);
        assertTrue(store.save(first));
        assertFalse(Files.exists(folder.resolve("moderation.json.tmp")),
                "временный файл должен быть перенесён");
        assertTrue(Files.isRegularFile(store.warnsFile()));
        assertTrue(Files.isRegularFile(store.bansFile()));
        assertTrue(Files.isRegularFile(folder.resolve("players/README.md")));
        String saved = Files.readString(store.file(), StandardCharsets.UTF_8);
        assertTrue(saved.contains("\"checksum\""), "глобальный JSON обязан хранить контрольную сумму");
        assertFalse(saved.contains("\"checksum\":\"\""), "контрольная сумма не должна быть пустой");
        assertFalse(saved.contains("\"warns\""), "варны вынесены в YAML");
        assertFalse(saved.contains("\"bans\""), "баны вынесены в YAML");
        assertFalse(saved.contains("\"nextWarnId\""), "счётчик варнов вынесен в YAML");

        ModerationLedger second = store.load();
        assertEquals(1, second.activeCount("Steve"));
        assertEquals("грифинг", second.activeWarns("Steve").get(0).reason);
        assertTrue(second.isModerator("mod"));
        assertTrue(second.isBanned("Alex"));
        assertEquals(3, second.journal("", 500).size());

        // Старый global JSON без checksum и без части полей не должен затереть
        // уже существующий players/warns.yml: YAML авторитетнее legacy-копии.
        Files.writeString(store.file(), "{\"version\":1,\"warns\":[]}", StandardCharsets.UTF_8);
        ModerationLedger legacy = store.load();
        assertEquals(1, legacy.activeCount("Steve"));
        assertEquals(2L, legacy.issueWarn("Steve", "Mod", "после миграции", 1L).id);
        assertFalse(Files.readString(store.file(), StandardCharsets.UTF_8).contains("\"warns\""));
    }

    @Test
    void corruptedGlobalFileStartsEmptyInsteadOfCrashing() throws Exception {
        Files.writeString(folder.resolve("moderation.json"), "{ это не json", StandardCharsets.UTF_8);
        ModerationStore store = new ModerationStore(folder, LOGGER);
        ModerationLedger ledger = store.load();
        assertEquals(0, ledger.warnTotal());
        assertNotNull(ledger.snapshot());
        assertTrue(Files.exists(folder.resolve("moderation.json.broken")),
                "подозрительный файл сохраняем до перезаписи");
        assertTrue(Files.isRegularFile(store.warnsFile()));
        assertTrue(Files.isRegularFile(store.bansFile()));
        assertTrue(store.save(ledger), "после сбоя чтения запись продолжает работать");
    }

    @Test
    void manuallyEditedWarnsAndBansAreAccepted() throws Exception {
        Files.createDirectories(folder.resolve("players"));
        Files.writeString(folder.resolve("players/warns.yml"), """
                # Ручная правка администратора
                schema: 1
                next-warn-id: 8
                warns:
                  - id: 7
                    player: steve
                    issuer: owner
                    reason: "исправлено вручную"
                    at: 1000
                    removed-by: ""
                    removed-at: 0
                """, StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("players/bans.yml"), """
                schema: 1
                bans:
                  alex:
                    reason: "ручной бан"
                    by: owner
                    at: 1002
                """, StandardCharsets.UTF_8);

        ModerationStore store = new ModerationStore(folder, LOGGER);
        ModerationLedger ledger = store.load();
        assertFalse(store.playerLoadFailed());
        assertEquals(1, ledger.activeCount("Steve"));
        assertEquals("исправлено вручную", ledger.activeWarns("Steve").get(0).reason);
        assertTrue(ledger.isBanned("Alex"));
        assertEquals("ручной бан", ledger.banOf("Alex").reason);
        assertEquals(8L, ledger.issueWarn("Alex", "Owner", "новый", 2000L).id);
    }

    @Test
    void tamperedGlobalModeratorIsRejectedByChecksum() throws Exception {
        ModerationStore store = new ModerationStore(folder, LOGGER);
        ModerationLedger ledger = store.load();
        ledger.addModerator("Mod", "Owner", 1001L);
        assertTrue(store.save(ledger));

        JsonObject root = JsonParser.parseString(
                Files.readString(store.file(), StandardCharsets.UTF_8)).getAsJsonObject();
        root.getAsJsonArray("moderators").get(0).getAsJsonObject()
                .addProperty("name", "hacker");
        Files.writeString(store.file(), root.toString(), StandardCharsets.UTF_8);

        ModerationLedger rejected = store.load();
        assertFalse(rejected.isModerator("mod"), "подделанная роль не загружается");
        assertTrue(Files.exists(folder.resolve("moderation.json.broken")));
    }

    @Test
    void legacyJsonIsMigratedWithBackupAndGlobalChecksum() throws Exception {
        writeLegacy(folder.resolve("moderation.json"), """
                {
                  "version": 1,
                  "nextWarnId": 8,
                  "nextJournalId": 2,
                  "warns": [
                    {"id": 7, "target": "steve", "issuer": "mod",
                     "reason": "старый", "at": 1000, "removedBy": "", "removedAt": 0}
                  ],
                  "moderators": [],
                  "bans": [
                    {"target": "alex", "reason": "пять варнов", "by": "owner", "at": 1002}
                  ],
                  "journal": []
                }
                """);

        ModerationStore store = new ModerationStore(folder, LOGGER);
        ModerationLedger migrated = store.load();
        assertFalse(store.playerLoadFailed());
        assertTrue(Files.isRegularFile(store.warnsFile()));
        assertTrue(Files.isRegularFile(store.bansFile()));
        assertTrue(Files.isRegularFile(folder.resolve("players/README.md")));
        try (var files = Files.list(folder)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString()
                    .startsWith("moderation.json.before-players-split-")),
                    "перед перезаписью исходного JSON создаётся backup");
        }
        String global = Files.readString(store.file(), StandardCharsets.UTF_8);
        assertFalse(global.contains("\"warns\""));
        assertFalse(global.contains("\"bans\""));
        assertFalse(global.contains("\"nextWarnId\""));
        assertTrue(global.contains("\"playerDataSchema\""));
        assertTrue(global.contains("\"checksum\""));
        assertEquals(1, migrated.activeCount("Steve"));
        assertEquals("старый", migrated.activeWarns("Steve").get(0).reason);
        assertTrue(migrated.isBanned("Alex"));
        assertEquals(8L, migrated.issueWarn("Steve", "Mod", "новый", 2000L).id);

        ModerationLedger reloaded = new ModerationStore(folder, LOGGER).load();
        assertEquals(1, reloaded.activeCount("Steve"));
        assertTrue(reloaded.isBanned("Alex"));
    }

    @Test
    void brokenPlayerYamlIsQuarantinedAndNotOverwritten() throws Exception {
        ModerationStore store = new ModerationStore(folder, LOGGER);
        ModerationLedger first = store.load();
        first.issueWarn("Steve", "Mod", "грифинг", 1000L);
        assertTrue(store.save(first));
        Files.writeString(store.warnsFile(), "schema: oops\nwarns: [не карта]\n", StandardCharsets.UTF_8);

        ModerationLedger loaded = store.load();
        assertTrue(store.playerLoadFailed());
        assertTrue(Files.isRegularFile(folder.resolve("players/warns.yml.broken")));
        assertEquals(0, loaded.warnTotal());
        assertFalse(store.save(loaded),
                "пока players/warns.yml повреждён, сохранение не считается полным");
        assertTrue(Files.readString(store.warnsFile(), StandardCharsets.UTF_8).contains("schema: oops"),
                "подозрительный YAML не затирается следующим сохранением");
    }
}
