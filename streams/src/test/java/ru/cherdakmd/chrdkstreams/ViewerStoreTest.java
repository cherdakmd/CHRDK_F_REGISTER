package ru.cherdakmd.chrdkstreams;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** streams-viewers.json: настройки стримера, заявки и прогресс зрителей переживают перезапуск. */
class ViewerStoreTest {

    private static final Logger LOGGER = Logger.getLogger("ViewerStoreTest");
    private static final UUID STREAMER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VIEWER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir
    Path folder;

    private ViewerStore store() {
        return new ViewerStore(folder, LOGGER);
    }

    @Test
    void roundTripKeepsSettingsViewerAndProgress() {
        ViewerStore store = store();
        store.putSettings(new ViewerSettings(STREAMER, "Streamer", true,
                "WATCH-7K2M", 1_800_000_600_000L));
        store.putViewer(new ViewerBinding(VIEWER, "Steve", STREAMER, "Streamer",
                true, 1_800_000_000_000L, 1_800_000_300_000L));
        store.putProgress(VIEWER, new StreamRewards.State(20_833, 1, 30 * 60_000L, 4));
        store.save();

        ViewerStore loaded = store();
        loaded.load();

        ViewerSettings settings = loaded.settings(STREAMER).orElseThrow();
        assertTrue(settings.enabled());
        assertEquals("WATCH-7K2M", settings.code());
        assertEquals(1_800_000_600_000L, settings.codeUntil());

        ViewerBinding binding = loaded.viewer(VIEWER).orElseThrow();
        assertTrue(binding.accepted());
        assertEquals("Streamer", binding.streamerName());
        assertEquals(1_800_000_300_000L, binding.lastCheckIn());

        assertEquals(4, loaded.progress(VIEWER).totalHours());
        assertEquals(1, loaded.progress(VIEWER).awardedToday());
    }

    @Test
    void disablingStreamerKeepsViewersAndTheirProgress() {
        ViewerStore store = store();
        store.putSettings(new ViewerSettings(STREAMER, "Streamer", true, "WATCH-ABCD", 0L));
        store.putViewer(new ViewerBinding(VIEWER, "Steve", STREAMER, "Streamer", true, 1L, 2L));
        store.putProgress(VIEWER, new StreamRewards.State(1, 1, 0L, 3));

        assertTrue(store.removeSettings(STREAMER));

        assertTrue(store.settings(STREAMER).isEmpty());
        assertTrue(store.viewer(VIEWER).isPresent(),
                "прогресс и привязка зрителя не должны сгорать из-за выключения");
        assertEquals(3, store.progress(VIEWER).totalHours(),
                "дневной потолок нельзя сбросить переподключением");
        assertEquals(0, store.streamerCount());
    }

    @Test
    void pendingLookupIsCaseInsensitiveAndIgnoresAccepted() {
        ViewerStore store = store();
        store.putViewer(new ViewerBinding(VIEWER, "Steve", STREAMER, "Streamer", false, 1L, 0L));

        assertTrue(store.pendingByName(STREAMER, "steve").isPresent());
        assertTrue(store.pendingByName(STREAMER, "STEVE").isPresent());
        assertTrue(store.pendingByName(STREAMER, "Alex").isEmpty());

        store.putViewer(store.viewer(VIEWER).orElseThrow().accept());
        assertTrue(store.pendingByName(STREAMER, "Steve").isEmpty(),
                "подтверждённая заявка уже не «ждёт»");
    }

    @Test
    void viewersOfOneStreamerDoNotLeakToAnother() {
        ViewerStore store = store();
        UUID other = UUID.randomUUID();
        store.putViewer(new ViewerBinding(VIEWER, "Steve", STREAMER, "Streamer", true, 1L, 1L));
        store.putViewer(new ViewerBinding(UUID.randomUUID(), "Alex", other, "Other", false, 1L, 0L));

        assertEquals(1, store.viewersOf(STREAMER).size());
        assertEquals(1, store.pendingOf(other).size());
        assertEquals(0, store.pendingOf(STREAMER).size());
        assertEquals(2, store.viewerCount());
    }

    @Test
    void emptyProgressIsNotStored() {
        ViewerStore store = store();
        store.putProgress(VIEWER, StreamRewards.State.empty());

        assertEquals(StreamRewards.State.empty(), store.progress(VIEWER));
    }

    @Test
    void brokenEntriesAreSkipped() throws Exception {
        Path file = folder.resolve("streams-viewers.json");
        Files.writeString(file, """
                {"streamers":[
                   {"uuid":"not-a-uuid","name":"bad","enabled":true}
                 ],
                 "viewers":[
                   {"uuid":"33333333-3333-3333-3333-333333333333","name":"X","streamer":"bad"}
                 ],
                 "progress":[
                   {"uuid":"44444444-4444-4444-4444-444444444444","totalHours":7}
                 ]}
                """, StandardCharsets.UTF_8);

        ViewerStore store = store();
        store.load();

        assertEquals(0, store.viewerCount());
        assertEquals(7, store.progress(
                UUID.fromString("44444444-4444-4444-4444-444444444444")).totalHours());
        assertTrue(store.allStreamers().isEmpty());
        assertFalse(Files.exists(folder.resolve("streams-viewers.json.tmp")));
    }
}
