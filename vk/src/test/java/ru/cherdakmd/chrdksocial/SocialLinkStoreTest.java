package ru.cherdakmd.chrdksocial;

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

class SocialLinkStoreTest {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static SocialLinkStore store(Path dir) {
        SocialLinkStore store = new SocialLinkStore(dir, Logger.getLogger("social-test"));
        store.load();
        return store;
    }

    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(64);
        for (byte value : digest) out.append(String.format("%02x", value));
        return out.toString();
    }

    private static void writeLegacy(Path dir, String json) throws Exception {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        root.addProperty("checksum", sha256(GSON.toJson(root)));
        Files.writeString(dir.resolve("social-links.json"), GSON.toJson(root), StandardCharsets.UTF_8);
    }

    @Test
    void linkIsUniquePerPlayerAndPerProfile(@TempDir Path dir) {
        SocialLinkStore store = store(dir);
        assertEquals(SocialLinkStore.LinkStatus.LINKED,
                store.link("StaveLime", "max", 555L, "555", 1, 100L));
        assertEquals(SocialLinkStore.LinkStatus.PLAYER_ALREADY_LINKED,
                store.link("stavelime", "vk", 999L, "999", 1, 200L));
        assertEquals(SocialLinkStore.LinkStatus.PROFILE_LIMIT_REACHED,
                store.link("Other", "max", 555L, "555", 1, 300L));
        assertEquals(SocialLinkStore.LinkStatus.INVALID_ARGUMENT,
                store.link("Other", "telegram", 555L, "", 1, 300L));
        assertTrue(store.isLinked("STAVELIME"));
        assertEquals("max", store.binding("StaveLime").orElseThrow().provider());
        assertEquals(1, store.linkCount());
    }

    @Test
    void firstSeenIsRecordedOnceAndDonorAccessSurvivesReload(@TempDir Path dir) {
        SocialLinkStore first = store(dir);
        assertEquals(0L, first.firstSeenAt("StaveLime"));
        first.recordFirstSeen("StaveLime", 1_000L);
        first.recordFirstSeen("stavelime", 9_000L);
        first.grantDonor("StaveLime", "manual:оплата на сайте", 2_000L);
        assertTrue(first.donorGranted("STAVELIME"));
        assertTrue(first.donorSource("StaveLime").contains("manual"));
        assertEquals(1, first.donorCount());

        SocialLinkStore second = store(dir);
        assertFalse(second.loadFailed());
        assertEquals(1_000L, second.firstSeenAt("StaveLime"),
                "повторный вход не продлевает личный переходный срок");
        assertTrue(second.donorGranted("StaveLime"));
        assertEquals(java.util.List.of("stavelime"), second.donors());
        assertTrue(second.revokeDonor("STAVELIME"));
        assertFalse(second.revokeDonor("StaveLime"));
        assertEquals(0, second.donorCount());
    }

    @Test
    void survivesReloadWithChecksum(@TempDir Path dir) {
        SocialLinkStore first = store(dir);
        first.link("Player", "vk", 123L, "id123", 1, 100L);
        first.setLastNewsTag("v1.4.287");
        first.setChatId("bridge", 72000001L);
        first.markRewarded("Player", 200L);

        SocialLinkStore second = store(dir);
        assertFalse(second.loadFailed());
        assertEquals("vk", second.binding("player").orElseThrow().provider());
        assertEquals("v1.4.287", second.lastNewsTag());
        assertEquals(72000001L, second.chatId("bridge"));
        assertTrue(second.rewardClaimed("Player"));
    }

    @Test
    void tamperedGlobalIsPreservedAndFlagged(@TempDir Path dir) throws Exception {
        SocialLinkStore first = store(dir);
        first.link("Player", "max", 1L, "1", 1, 100L);
        first.setLastNewsTag("v1.4.287");
        Path file = dir.resolve("social-links.json");
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        Files.writeString(file, raw.replace("v1.4.287", "v9.9.9"), StandardCharsets.UTF_8);

        SocialLinkStore second = store(dir);
        assertTrue(second.loadFailed());
        assertTrue(Files.isRegularFile(dir.resolve("social-links.json.broken")));
        assertEquals(1, second.linkCount(),
                "валидный players/social.yml читается отдельно от подделанного global JSON");

        // До подтверждения администратором ни один файл не перезаписывается.
        second.link("Other", "vk", 2L, "2", 1, 200L);
        String untouched = Files.readString(second.playersFile(), StandardCharsets.UTF_8);
        assertTrue(untouched.contains("player:"), "валидные привязки прочитаны даже при сбое global JSON");
        assertFalse(untouched.contains("other:"));
        second.acknowledgeCorruption(999_999L);
        assertFalse(second.loadFailed());
        assertEquals(999_999L, second.emergencyUntil());

        SocialLinkStore recovered = store(dir);
        assertFalse(recovered.loadFailed());
        assertTrue(recovered.isLinked("Player"),
                "автоматический fail-open не стирает валидный players/social.yml");
    }

    @Test
    void brokenPlayerFileIsQuarantined(@TempDir Path dir) throws Exception {
        store(dir).link("Player", "vk", 5L, "id5", 1, 100L);
        Files.writeString(dir.resolve("players/social.yml"),
                "schema: nope\nplayers: [не карта]\n", StandardCharsets.UTF_8);

        SocialLinkStore second = store(dir);
        assertTrue(second.loadFailed());
        assertTrue(second.playersFrozen());
        assertTrue(Files.isRegularFile(dir.resolve("players/social.yml.broken")));
        assertEquals(0, second.linkCount());

        second.acknowledgeCorruption(999_999L);
        assertFalse(second.loadFailed());
        assertEquals(999_999L, second.emergencyUntil());
        second.link("Other", "vk", 6L, "6", 1, 200L);
        String quarantined = Files.readString(second.playersFile(), StandardCharsets.UTF_8);
        assertTrue(quarantined.contains("schema: nope"),
                "битый YAML не затирается автоматическим аварийным режимом");
        assertFalse(quarantined.contains("other:"));
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString()
                    .startsWith("social-links.json.before-corruption-")),
                    "перед перезаписью global JSON создаётся аварийный backup");
        }
    }

    @Test
    void legacyJsonIsMigratedWithBackupAndGlobalChecksum(@TempDir Path dir) throws Exception {
        writeLegacy(dir, """
                {
                  "schema": 2,
                  "rolloutStartedAt": 1700000000000,
                  "emergencyUntil": 0,
                  "lastNewsTag": "v1.4.287",
                  "links": {
                    "stavelime": {
                      "provider": "vk",
                      "externalId": 123,
                      "externalName": "Иван",
                      "linkedAt": 100
                    }
                  },
                  "owners": {"vk:123": "stavelime"},
                  "rewarded": {"stavelime": "200"},
                  "discoveredChats": {},
                  "firstSeen": {"stavelime": 1000},
                  "donors": {"stavelime": "300|manual:оплата на сайте"},
                  "maxChatId": "0",
                  "maxNewsChatId": "0",
                  "maxBridgeChatId": "72000001"
                }
                """);

        SocialLinkStore migrated = store(dir);
        assertFalse(migrated.loadFailed());
        assertTrue(Files.isRegularFile(migrated.playersFile()));
        assertTrue(Files.isRegularFile(dir.resolve("players/README.md")));
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString()
                    .startsWith("social-links.json.before-players-move-")),
                    "перед перезаписью исходного JSON создаётся backup");
        }
        String global = Files.readString(dir.resolve("social-links.json"), StandardCharsets.UTF_8);
        assertFalse(global.contains("\"links\""));
        assertFalse(global.contains("\"owners\""));
        assertFalse(global.contains("\"firstSeen\""));
        assertFalse(global.contains("\"donors\""));
        assertFalse(global.contains("\"rewarded\""));
        assertTrue(global.contains("\"playerDataSchema\""));
        assertTrue(global.contains("\"checksum\""));

        var binding = migrated.binding("StaveLime").orElseThrow();
        assertEquals("vk", binding.provider());
        assertEquals(123L, binding.externalId());
        assertEquals("Иван", binding.externalName());
        assertEquals(100L, binding.linkedAt());
        assertEquals(1000L, migrated.firstSeenAt("stavelime"));
        assertTrue(migrated.donorGranted("StaveLime"));
        assertTrue(migrated.donorSource("StaveLime").contains("manual"));
        assertTrue(migrated.rewardClaimed("StaveLime"));
        assertTrue(migrated.ownerOf("vk", 123L).isPresent());
        assertEquals("v1.4.287", migrated.lastNewsTag());
        assertEquals(1700000000000L, migrated.rolloutStartedAt());
        assertEquals(72000001L, migrated.chatId("bridge"));

        SocialLinkStore reloaded = store(dir);
        assertFalse(reloaded.loadFailed());
        assertTrue(reloaded.isLinked("StaveLime"));
        assertEquals(1000L, reloaded.firstSeenAt("StaveLime"));
    }

    @Test
    void manuallyEditedPlayerYamlIsAccepted(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("players"));
        Files.writeString(dir.resolve("players/social.yml"), """
                # Ручная правка администратора
                schema: 1
                players:
                  alex:
                    link:
                      provider: vk
                      external-id: 777
                      external-name: "Вручную"
                      linked-at: 100
                    first-seen-at: 50
                profiles:
                  vk-777: alex
                """, StandardCharsets.UTF_8);

        SocialLinkStore store = store(dir);
        assertFalse(store.loadFailed());
        assertEquals("vk", store.binding("Alex").orElseThrow().provider());
        assertEquals(777L, store.binding("Alex").orElseThrow().externalId());
        assertEquals(50L, store.firstSeenAt("ALEX"));
        assertEquals("alex", store.ownerOf("vk", 777L).orElseThrow());
        assertTrue(Files.isRegularFile(dir.resolve("social-links.json")));
        assertTrue(Files.isRegularFile(dir.resolve("players/README.md")));
    }

    @Test
    void unlinkReleasesProfileAndEmergencyIsPersisted(@TempDir Path dir) {
        SocialLinkStore store = store(dir);
        store.link("Player", "max", 42L, "42", 1, 100L);
        store.setEmergencyUntil(5_000L);
        assertTrue(store.emergencyUntil() > 0L);
        assertTrue(store.unlink("player").isPresent());
        assertFalse(store.unlink("player").isPresent());
        assertFalse(store.ownerOf("max", 42L).isPresent());
        assertEquals(SocialLinkStore.LinkStatus.LINKED,
                store.link("New", "max", 42L, "42", 1, 200L));
        assertTrue(store(dir).discoveredChats().isEmpty());
    }
}
