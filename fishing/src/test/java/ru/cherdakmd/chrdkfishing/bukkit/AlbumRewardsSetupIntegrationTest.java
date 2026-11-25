package ru.cherdakmd.chrdkfishing.bukkit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkfishing.FishAlbum;
import ru.cherdakmd.chrdkfishing.FishToken;
import ru.cherdakmd.chrdkregister.api.IncomeSourceLineageProvider;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises Fishing's real SQLite setup against a fake Core API, not a live server. */
class AlbumRewardsSetupIntegrationTest {
    @TempDir Path dir;
    public interface Api extends IncomeSourceLineageProvider {
        boolean personalBankAvailable();
        boolean incomeSourceAvailable(String source, UUID lineage);
        Map<UUID, Map<String, Long>> incomeSourceReceipts(String source, UUID lineage);
        boolean creditEntitlementMinor(String source, UUID lineage, String key, UUID owner, long total);
    }
    @Test void newDatabaseUsesBankHistoryAndRestartsWithoutCopyingOldFishingFiles() throws Exception {
        var owner = UUID.randomUUID(); var lineage = UUID.randomUUID();
        var services = mock(ServicesManager.class); var bank = mock(Api.class);
        when(services.load(Api.class)).thenReturn(bank);
        when(bank.personalBankAvailable()).thenReturn(true);
        when(bank.incomeSourceLineage("fishing-album")).thenReturn(lineage);
        when(bank.incomeSourceAvailable("fishing-album", lineage)).thenReturn(true);
        when(bank.incomeSourceReceipts("fishing-album", lineage)).thenReturn(Map.of(owner, Map.of(owner.toString(), 350L)));
        var folder = dir.resolve("fishing"); Files.createDirectory(folder);
        Files.writeString(folder.resolve(FishingGeneration.MARKER), "horizons-v1\n");
        var g = FishingGeneration.resolve(folder); byte[] key = g.signingKey(folder);
        assertEquals(FishToken.KEY_BYTES, key.length);
        try (var album = new FishAlbum(folder.resolve("fishing-album.db"), true, key)) {
            try (var rewards = AlbumRewardsSetup.open(folder, services, Api.class, getClass().getClassLoader(), album, p -> true, true, true)) {
                assertNotNull(rewards);
            }
            try (var journal = new ru.cherdakmd.chrdkfishing.AlbumRewardJournal(folder.resolve("album-rewards.db"), lineage, false)) {
                assertEquals(3, journal.progress(owner).paidStage());
                assertEquals(350, journal.progress(owner).confirmed());
            }
            try (var rewards = AlbumRewardsSetup.open(folder, services, Api.class, getClass().getClassLoader(), album, p -> true, false, true)) {
                assertNotNull(rewards);
            }
            verify(bank, never()).creditEntitlementMinor(anyString(), any(), anyString(), any(), anyLong());
        }
        Files.delete(folder.resolve("album-rewards.db")); Files.delete(folder.resolve("album-rewards-lineage.txt"));
        try (var album = new FishAlbum(folder.resolve("fishing-album.db"), false, key)) {
            assertThrows(Exception.class, () -> AlbumRewardsSetup.open(folder, services, Api.class, getClass().getClassLoader(), album, p -> true, true, true));
        }
    }
}
