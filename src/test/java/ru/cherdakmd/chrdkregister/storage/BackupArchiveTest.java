package ru.cherdakmd.chrdkregister.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class BackupArchiveTest {
    @TempDir Path plugins;

    private BackupArchive service() {
        return new BackupArchive(plugins, plugins.resolve("CHRDK_Core/backups/snapshots"), ZoneId.of("UTC"));
    }

    private Path data(String plugin, String name, String content) throws IOException {
        Path file = plugins.resolve(plugin).resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void createsVerifiedManifestAndExcludesSecretsCachesLogsAndConfigs() throws Exception {
        data("CHRDK_Core", "data.json", "{\"accounts\":{}}");
        data("CHRDK_Bank", "state/bank.json", "{\"balance\":3}");
        data("CHRDK_Core", "config.yml", "api-token: do-not-copy");
        data("CHRDK_Core", "boosty-tokens.json", "{\"token\":\"do-not-copy\"}");
        data("CHRDK_Core", "cache/view.json", "not json");
        data("CHRDK_Core", "logs/event.jsonl", "not json");
        data("CHRDK_Core", "players/moderation.yml", "warns: []");
        Path unrelated = data("OtherPlugin", "state.json", "not json");

        Path created = service().create("manual", Instant.parse("2026-11-10T12:00:00Z"),
                new BackupArchive.Retention(6, 14, 8));

        BackupArchive.Verification result = service().verify(created);
        assertEquals(3, result.files());
        assertEquals(37, result.bytes());
        try (ZipFile zip = new ZipFile(created.toFile())) {
            assertTrue(zip.stream().allMatch(entry -> !entry.getName().contains("do-not-copy")));
            assertNull(zip.getEntry("OtherPlugin/state.json"));
            assertNotNull(zip.getEntry("CHRDK_Core/players/moderation.yml"));
        }
        assertTrue(Files.exists(unrelated));
    }

    @Test
    void rejectsInvalidJsonWithoutPublishingPartialArchive() throws Exception {
        data("CHRDK_Core", "data.json", "{broken");
        assertThrows(IOException.class, () -> service().create("manual",
                Instant.parse("2026-11-10T12:00:00Z"), new BackupArchive.Retention(6, 14, 8)));
        assertTrue(service().list().isEmpty());
        try (var files = Files.list(plugins.resolve("CHRDK_Core/backups/snapshots"))) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void rejectsHashMismatchAndTraversal() throws Exception {
        data("CHRDK_Core", "data.json", "{}");
        Path valid = service().create("manual", Instant.parse("2026-11-10T12:00:00Z"),
                new BackupArchive.Retention(6, 14, 8));
        Path changed = valid.resolveSibling("changed.chrdk.zip");
        try (ZipFile input = new ZipFile(valid.toFile()); ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(changed))) {
            for (var entries = input.entries(); entries.hasMoreElements();) {
                ZipEntry old = entries.nextElement();
                output.putNextEntry(new ZipEntry(old.getName()));
                byte[] bytes = input.getInputStream(old).readAllBytes();
                if (old.getName().equals("CHRDK_Core/data.json")) bytes = "{\"changed\":true}".getBytes(StandardCharsets.UTF_8);
                output.write(bytes);
                output.closeEntry();
            }
        }
        assertThrows(IOException.class, () -> service().verify(changed));

        Path traversal = valid.resolveSibling("traversal.chrdk.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(traversal))) {
            output.putNextEntry(new ZipEntry("CHRDK_Core/../../outside.json"));
            output.write("{}".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        assertThrows(IOException.class, () -> service().verify(traversal));
        assertThrows(IOException.class, () -> service().resolve("../outside.chrdk.zip"));
    }

    @Test
    void retentionKeepsLatestDailyWeeklyAndUnknownArchives() throws Exception {
        data("CHRDK_Core", "data.json", "{}");
        BackupArchive.Retention retention = new BackupArchive.Retention(1, 2, 1);
        service().create("first", Instant.parse("2026-11-01T10:00:00Z"), retention);
        Path second = service().create("second", Instant.parse("2026-11-02T10:00:00Z"), retention);
        Path third = service().create("third", Instant.parse("2026-11-10T10:00:00Z"), retention);
        Path unknown = third.resolveSibling("custom.chrdk.zip");
        Files.copy(third, unknown);
        Path latest = service().create("fourth", Instant.parse("2026-11-10T11:00:00Z"), retention);
        assertEquals(3, service().list().size(), service().list().toString());
        assertTrue(Files.exists(latest));
        assertTrue(Files.exists(second));
        assertTrue(Files.exists(unknown));
        assertFalse(Files.exists(third));
    }
}
