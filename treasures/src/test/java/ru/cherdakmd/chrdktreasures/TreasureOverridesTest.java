package ru.cherdakmd.chrdktreasures;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code chances.yml}: правки шансов переживают перезапуск, обрезаются до
 * разумных границ и не тянут за собой перезапись основного config.yml.
 */
class TreasureOverridesTest {

    private static final Logger LOGGER = Logger.getLogger("TreasureOverridesTest");

    @TempDir
    Path folder;

    private TreasureOverrides overrides() {
        TreasureOverrides overrides = new TreasureOverrides(folder, LOGGER);
        overrides.load();
        return overrides;
    }

    @Test
    void missingFileMeansNoOverrides() {
        TreasureOverrides overrides = overrides();
        assertEquals(0, overrides.size());
        assertTrue(overrides.get(TreasureTier.LEGENDARY).isEmpty());
        assertTrue(overrides.asMap().isEmpty());
        assertFalse(Files.exists(folder.resolve(TreasureOverrides.FILE_NAME)));
    }

    @Test
    void setWritesFileAndSurvivesReload() {
        TreasureOverrides overrides = overrides();
        assertEquals(0.5, overrides.set(TreasureTier.LEGENDARY, 0.5), 1e-9);
        Path file = folder.resolve(TreasureOverrides.FILE_NAME);
        assertTrue(Files.isRegularFile(file));
        assertTrue(Files.readString(file).contains("legendary"));
        assertFalse(Files.exists(folder.resolve(TreasureOverrides.FILE_NAME + ".tmp")));

        TreasureOverrides reloaded = overrides();
        assertEquals(1, reloaded.size());
        assertTrue(reloaded.get(TreasureTier.LEGENDARY).isPresent());
        assertEquals(0.5, reloaded.get(TreasureTier.LEGENDARY).getAsDouble(), 1e-9);
    }

    @Test
    void valuesAreClampedToZeroHundred() {
        TreasureOverrides overrides = overrides();
        assertEquals(100.0, overrides.set(TreasureTier.COMMON, 500.0), 1e-9);
        assertEquals(0.0, overrides.set(TreasureTier.RARE, -3.0), 1e-9);
        assertEquals(100.0, overrides.get(TreasureTier.COMMON).getAsDouble(), 1e-9);
        assertEquals(0.0, overrides.get(TreasureTier.RARE).getAsDouble(), 1e-9);
    }

    @Test
    void clearRemovesOverrideAndFileEntry() {
        TreasureOverrides overrides = overrides();
        overrides.set(TreasureTier.EPIC, 0.25);
        assertTrue(overrides.clear(TreasureTier.EPIC));
        assertFalse(overrides.clear(TreasureTier.EPIC));
        assertTrue(overrides.get(TreasureTier.EPIC).isEmpty());

        TreasureOverrides reloaded = overrides();
        assertEquals(0, reloaded.size());
    }

    @Test
    void unknownAndBrokenEntriesAreIgnored() throws Exception {
        Files.writeString(folder.resolve(TreasureOverrides.FILE_NAME),
                "chances:\n"
                        + "  nope: 1.0\n"
                        + "  rare: 2.5\n"
                        + "  epic: -1\n"
                        + "  legendary: очень много\n");
        TreasureOverrides overrides = overrides();
        assertEquals(1, overrides.size());
        assertEquals(2.5, overrides.get(TreasureTier.RARE).getAsDouble(), 1e-9);
        assertTrue(overrides.get(TreasureTier.EPIC).isEmpty());
        assertTrue(overrides.get(TreasureTier.LEGENDARY).isEmpty());
    }

    @Test
    void mapIsImmutableSnapshot() {
        TreasureOverrides overrides = overrides();
        overrides.set(TreasureTier.MYTHICAL, 0.01);
        var snapshot = overrides.asMap();
        assertEquals(1, snapshot.size());
        try {
            snapshot.put(TreasureTier.MYTHICAL, 1.0);
            throw new AssertionError("карта переопределений должна быть неизменяемой");
        } catch (UnsupportedOperationException expected) {
            // так и должно быть
        }
        assertEquals(0.01, overrides.get(TreasureTier.MYTHICAL).getAsDouble(), 1e-9);
    }
}
