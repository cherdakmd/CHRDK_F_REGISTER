package ru.cherdakmd.chrdkfishing.bukkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AlbumRewardsBootstrapTest {
    @TempDir Path dir;
    final UUID bankLineage = UUID.randomUUID();

    @Test void onlyAnExplicitNewGenerationCanStartOnceFromAnExistingBank() throws Exception {
        assertThrows(IOException.class, () -> AlbumRewardsSetup.prepareBootstrap(dir, true, true, null, false, false));
        assertThrows(IOException.class, () -> AlbumRewardsSetup.prepareBootstrap(dir, true, false, bankLineage, false, false));
        assertTrue(AlbumRewardsSetup.prepareBootstrap(dir, true, true, bankLineage, false, false));
        assertThrows(IOException.class, () -> AlbumRewardsSetup.prepareBootstrap(dir, true, true, bankLineage, false, false));
        assertThrows(IOException.class, () -> AlbumRewardsSetup.prepareBootstrap(dir, true, true, bankLineage, true, false));
        assertThrows(IOException.class, () -> AlbumRewardsSetup.prepareBootstrap(dir, true, true, bankLineage, false, true));
        assertFalse(AlbumRewardsSetup.prepareBootstrap(dir, true, true, bankLineage, true, true));
        assertFalse(AlbumRewardsSetup.prepareBootstrap(dir, true, false, bankLineage, true, true));
        Files.delete(dir.resolve("horizons-rewards-started"));
        assertThrows(IOException.class, () -> AlbumRewardsSetup.prepareBootstrap(dir, true, false, bankLineage, true, true));
    }
    @Test void legacyDoesNotRecreateLostJournalWithExistingBank() throws Exception {
        assertThrows(IOException.class, () -> AlbumRewardsSetup.prepareBootstrap(dir, false, false, bankLineage, false, false));
        assertFalse(AlbumRewardsSetup.prepareBootstrap(dir, false, false, null, false, false));
        assertFalse(AlbumRewardsSetup.prepareBootstrap(dir, false, false, bankLineage, true, true));
        assertThrows(IOException.class, () -> AlbumRewardsSetup.prepareBootstrap(dir, false, false, bankLineage, true, false));
    }
}
