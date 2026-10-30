package ru.cherdakmd.chrdkclasses;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Совместимость раннего отчёта схемы 3 и строгая проверка новой доказательной основы. */
class LegacyClassBasisStorageTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final long NOW = 1_792_000_000_000L;

    private static String saveReport(LegacyClassMigration report) {
        return new Gson().toJson(Map.of("version", 3, "players", Map.of(PLAYER.toString(),
                Map.of("class", "warrior", "development", ClassDevelopment.migrate(report)))));
    }

    @Test
    void earlyVersionThreeReportWithoutBasisRemainsReadable(@TempDir Path dir) throws Exception {
        String original = saveReport(new LegacyClassMigration("warrior", 10, 12, Set.of("warrior-stoyka-1"), NOW));
        Files.writeString(dir.resolve("classes.json"), original);
        ClassStore store = new ClassStore(dir, null);
        store.load();
        assertEquals(12, store.development(PLAYER).earnedPoints());
        assertNull(store.development(PLAYER).migration().basis());
        store.flush();
        ClassStore again = new ClassStore(dir, null);
        again.load();
        assertEquals(store.development(PLAYER), again.development(PLAYER));
    }

    @Test
    void wrongPlayerBasisCannotBeLoadedUnderAnotherUuid(@TempDir Path dir) throws Exception {
        LegacyClassBasis basis = new LegacyClassBasis(OTHER, "warrior", Set.of("warrior-stoyka-1"),
                0, 0, LegacyClassRules.capture(ClassConfig.defaults()), 300, 0, 30, NOW);
        assertBlocked(dir, saveReport(LegacyClassMigration.capture(basis)));
    }

    @Test
    void malformedProofCannotBeSilentlyCoercedOrDropped(@TempDir Path dir) throws Exception {
        LegacyClassBasis basis = new LegacyClassBasis(PLAYER, "warrior", Set.of("warrior-stoyka-1"),
                0, 0, LegacyClassRules.capture(ClassConfig.defaults()), 300, 0, 30, NOW);
        String valid = saveReport(LegacyClassMigration.capture(basis));
        for (String broken : new String[]{
                valid.replace("\"skillTotal\":300", "\"skillTotal\":\"300\""),
                valid.replace("\"skillTotal\":300", "\"skillTotal\":1000"),
                valid.replace("\"levelsPerLevel\":30", "\"levelsPerLevel\":\"30\""),
                valid.replace("\"ranksEnabled\":true", "\"ranksEnabled\":\"true\""),
                valid.replace("\"skillTotal\":300", "\"future-field\":100,\"skillTotal\":300"),
                valid.replace("\"skillTotal\":300", "\"skillTotal\":300.5")}) {
            assertNotEquals(valid, broken);
            assertBlocked(dir, broken);
        }
    }

    private static void assertBlocked(Path dir, String json) throws Exception {
        Files.writeString(dir.resolve("classes.json"), json);
        ClassStore store = new ClassStore(dir, null);
        assertThrows(IllegalStateException.class, store::load);
        store.flush();
        store.saveNow();
        assertEquals(json, Files.readString(store.file()));
    }
}
