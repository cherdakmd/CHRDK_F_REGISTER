package ru.cherdakmd.chrdkfishing.bukkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkfishing.FishToken;
import ru.cherdakmd.chrdkfishing.FishingModel;
import static org.junit.jupiter.api.Assertions.*;

class FishingGenerationTest {
    @TempDir Path dir;
    Path fresh() throws IOException {
        Path folder = dir.resolve("new"); Files.createDirectory(folder);
        Files.writeString(folder.resolve(FishingGeneration.MARKER), "horizons-v1\n");
        Files.writeString(folder.resolve("album-rewards.db"), "fixture");
        Files.writeString(folder.resolve("album-rewards-lineage.txt"), "fixture");
        return folder;
    }
    @Test void explicitMarkerSelectsDifferentSourcesAndPreservesOldRewardHistory() throws Exception {
        Path folder = fresh();
        var generation = FishingGeneration.resolve(folder);
        assertSame(FishingGeneration.HORIZONS, generation);
        assertNotEquals(FishingGeneration.LEGACY.rodSource(), generation.rodSource());
        assertNotEquals(FishingGeneration.LEGACY.baitSource(), generation.baitSource());
        assertNotEquals(FishingGeneration.LEGACY.saleSource(), generation.saleSource());
        assertTrue(generation.bootstrapping(folder));
        byte[] key = generation.signingKey(folder);
        assertArrayEquals(key, generation.signingKey(folder));
        assertEquals("fixture", Files.readString(folder.resolve("album-rewards.db")));
        assertEquals("fixture", Files.readString(folder.resolve("album-rewards-lineage.txt")));
        generation.markInitialized(folder);
        assertFalse(generation.bootstrapping(folder));
        generation.markInitialized(folder); // idempotent
        assertArrayEquals(key, generation.signingKey(folder));
    }
    @Test void keysAreDistinctAndOldSignedFishAreNotAccepted() throws Exception {
        Path folder = fresh(); byte[] current = FishingGeneration.resolve(folder).signingKey(folder);
        byte[] previous = FishingPlugin.signingKey(dir.resolve("old"));
        var token = FishToken.issue(previous, UUID.randomUUID(), new FishingModel.Catch("pike", FishingModel.Size.NORMAL, 40), 100);
        assertTrue(FishToken.verify(previous, token)); assertFalse(FishToken.verify(current, token));
    }
    @Test void missingMarkerOrLostKeyNeverRotatesOrFallsBack() throws Exception {
        Path folder = fresh(); var g = FishingGeneration.resolve(folder); g.signingKey(folder);
        Files.delete(folder.resolve(FishingGeneration.MARKER));
        assertThrows(IOException.class, () -> FishingGeneration.resolve(folder));
        Files.writeString(folder.resolve(FishingGeneration.MARKER), "horizons-v1\n");
        Files.delete(folder.resolve("fish-signing.key"));
        assertThrows(IOException.class, () -> g.signingKey(folder));
        assertFalse(Files.exists(folder.resolve("fish-signing.key")));
    }
    @Test void preexistingDataOrMissingRewardHistoryNeverCreatesKey() throws Exception {
        Path folder = fresh(); Files.writeString(folder.resolve("old-journal.db"), "old data");
        var g = FishingGeneration.resolve(folder);
        assertThrows(IOException.class, () -> g.signingKey(folder));
        Files.delete(folder.resolve("old-journal.db")); Files.delete(folder.resolve("album-rewards.db"));
        assertThrows(IOException.class, () -> g.signingKey(folder));
        assertFalse(Files.exists(folder.resolve("fish-signing.key")));
        assertFalse(Files.exists(folder.resolve(FishingGeneration.KEY_LATCH)));
    }
    @Test void invalidOrSymlinkedMarkersCannotSelectOrRepairGeneration() throws Exception {
        Path folder = fresh(); var marker = folder.resolve(FishingGeneration.MARKER);
        Files.writeString(marker, "legacy\n"); assertThrows(IOException.class, () -> FishingGeneration.resolve(folder));
        Files.delete(marker); Files.createSymbolicLink(marker, folder.resolve("album-rewards.db"));
        assertThrows(IOException.class, () -> FishingGeneration.resolve(folder));
    }
    @Test void aLoneLatchFromInterruptedCreationIsNotRecoveredByMakingNewKey() throws Exception {
        Path folder = fresh(); Files.writeString(folder.resolve(FishingGeneration.KEY_LATCH), "started\n");
        assertThrows(IOException.class, () -> FishingGeneration.resolve(folder).signingKey(folder));
        assertFalse(Files.exists(folder.resolve("fish-signing.key")));
    }
    @Test void existingInstallationRemainsLegacyWithoutMarker() throws Exception {
        Path old = dir.resolve("legacy"); Files.createDirectory(old);
        assertSame(FishingGeneration.LEGACY, FishingGeneration.resolve(old));
        assertArrayEquals(FishingPlugin.signingKey(old), FishingGeneration.LEGACY.signingKey(old));
    }
}
