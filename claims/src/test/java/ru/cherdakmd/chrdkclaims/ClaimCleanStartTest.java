package ru.cherdakmd.chrdkclaims;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ClaimCleanStartTest {
    @TempDir Path dir;
    private final Logger logger=Logger.getAnonymousLogger();
    private static String read(Path path) {
        try { return Files.readString(path); }
        catch(java.io.IOException error) { throw new AssertionError(error); }
    }

    @Test void unverifiedLegacyBonusStopsInspectionWithoutChangingAnySourceFile() throws Exception {
        var old=new ClaimStore(dir,logger);
        old.add(new Claim("world",7,-3,"OldOwner",System.currentTimeMillis()+86_400_000L));
        old.addBonusChunks("Buyer",8);
        old.save();
        Path legacy=dir.resolve("claims-data.json");
        String oldJson=Files.readString(legacy);

        var error=assertThrows(java.io.IOException.class,()->ClaimStore.inspectCleanSeed(dir,logger));
        assertTrue(error.getMessage().contains("receipt provenance"));
        assertEquals(oldJson,Files.readString(legacy));
        assertFalse(Files.exists(dir.resolve("claims-data.json.bak")));
        try(var paths=Files.list(dir)) {
            assertFalse(paths.anyMatch(p->p.getFileName().toString().contains("pre-clean")),
                    "inspection must be read-only until every preserved bonus is verified");
        }
    }

    @Test void malformedLegacyBonusDoesNotDisappearAsAnEmptyMap() throws Exception {
        Path legacy=dir.resolve("claims-data.json");
        String malformed="{\"bonus-chunks\":{\"Buyer\":\"8\"}}";
        Files.writeString(legacy,malformed);
        assertThrows(java.io.IOException.class,()->ClaimStore.inspectCleanSeed(dir,logger));
        assertEquals(malformed,Files.readString(legacy));
    }

    @Test void explicitVerifiedSeedDropsOldDomainAndPreservesOnlyItsBonusAndCursor() throws Exception {
        var old=new ClaimStore(dir,logger);
        old.add(new Claim("world",7,-3,"OldOwner",System.currentTimeMillis()+86_400_000L));
        old.addBonusChunks("AdminGranted",24);
        old.save();
        var oldLineage=old.financialLineage();
        String oldJson=Files.readString(dir.resolve("claims-data.json"));
        String oldBackup="older operator backup";
        Files.writeString(dir.resolve("claims-data.json.bak"),oldBackup);

        // This seed represents a receipt-verified import; the legacy numeric bonus map is not used.
        var seed=new ClaimStore.CleanSeed(oldLineage,false,0,Map.of("Buyer",8));
        var fresh=new ClaimStore(dir,logger);
        assertTrue(fresh.initializeCleanState(seed));
        assertTrue(fresh.hasCleanState());

        try(var paths=Files.list(dir)) {
            var archived=paths.filter(p->p.getFileName().toString().contains("pre-clean-epoch-2")).toList();
            assertEquals(2,archived.size(),"both the active legacy state and its previous backup remain archived");
            assertTrue(archived.stream().anyMatch(p->read(p).equals(oldJson)));
            assertTrue(archived.stream().anyMatch(p->read(p).equals(oldBackup)));
        }

        var reloaded=new ClaimStore(dir,logger);
        reloaded.load();
        assertEquals(0,reloaded.count("OldOwner"),"old claims are not migrated");
        assertNull(reloaded.claimAt("world",7,-3));
        assertEquals(0,reloaded.bonusChunks("AdminGranted"),"legacy admin grants are not treated as purchases");
        assertEquals(8,reloaded.bonusChunks("Buyer"),"only explicitly verified purchase bonus remains");
        assertEquals(oldLineage,reloaded.financialLineage());
        assertEquals(0,reloaded.financialSequence());
        assertTrue(reloaded.hasCleanState());
    }

    @Test void corruptLegacyFileIsInspectedReadOnlyAndNeverOverwritten() throws Exception {
        Path legacy=dir.resolve("claims-data.json");
        String corrupted="{broken legacy claims json";
        Files.writeString(legacy,corrupted);
        assertThrows(java.io.IOException.class,()->ClaimStore.inspectCleanSeed(dir,logger));
        assertEquals(corrupted,Files.readString(legacy));
        try(var paths=Files.list(dir)) {
            assertFalse(paths.anyMatch(p->p.getFileName().toString().contains("corrupt")),
                    "bootstrap inspection must not quarantine or rewrite the old file");
        }
    }

    @Test void missingCurrentFileWithLegacyBackupCannotBeTreatedAsFreshInstall() throws Exception {
        Files.writeString(dir.resolve("claims-data.json.bak"),"older backup claims snapshot");
        assertThrows(java.io.IOException.class,()->ClaimStore.inspectCleanSeed(dir,logger));
        var store=new ClaimStore(dir,logger);
        store.load();
        assertFalse(store.moneyAvailable());
        assertNotNull(store.protectedClaimAt("world",0,0),"unknown backup may contain land, so protection fails closed");
    }

    @Test void interruptedTemporarySnapshotBlocksCleanResetWithoutOverwritingEitherFile() throws Exception {
        var old=new ClaimStore(dir,logger);
        old.add(new Claim("world",2,4,"Owner",System.currentTimeMillis()+86_400_000L));
        old.save();
        Path legacy=dir.resolve("claims-data.json");
        Path temporary=dir.resolve("claims-data.json.tmp");
        String oldJson=Files.readString(legacy), partial="partial interrupted snapshot";
        Files.writeString(temporary,partial);

        assertThrows(java.io.IOException.class,()->ClaimStore.inspectCleanSeed(dir,logger));
        var seed=new ClaimStore.CleanSeed(old.financialLineage(),old.financialRegistered(),old.financialSequence(),Map.of());
        assertFalse(new ClaimStore(dir,logger).initializeCleanState(seed));
        assertEquals(oldJson,Files.readString(legacy));
        assertEquals(partial,Files.readString(temporary));
        try(var paths=Files.list(dir)) {
            assertFalse(paths.anyMatch(p->p.getFileName().toString().contains("pre-clean")));
        }
    }

    @Test void cleanNewInstallStartsEmptyWithoutAnyLegacyFile() throws Exception {
        var fresh=new ClaimStore(dir,logger);
        assertNull(ClaimStore.inspectCleanSeed(dir,logger));
        assertTrue(fresh.initializeCleanState(null));
        var reloaded=new ClaimStore(dir,logger);
        reloaded.load();
        assertTrue(reloaded.all().isEmpty());
        assertTrue(reloaded.bonusChunks().isEmpty());
        assertTrue(reloaded.hasCleanState());
    }
}
