package ru.cherdakmd.chrdkclasses;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Append-only журнал: формат, экранирование и потолок между рестартами. */
class PerkTreeAuditTest {

    private static final UUID PLAYER = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final Path FILE = Path.of("audit", "perk-trees.jsonl");

    @TempDir
    Path dir;

    @Test
    void writesParseableJsonLinesAndEscapesReason() throws Exception {
        PerkTreeAudit audit = new PerkTreeAudit(dir, null);
        audit.record("unlock", PLAYER, "combat", 0L, 0, "combat-threat-hint");
        audit.record("reset", PLAYER, "combat", 120L, 3, "tok\"en\n");

        List<String> lines = Files.readAllLines(dir.resolve(FILE));
        assertEquals(2, lines.size());
        for (String line : lines) {
            assertTrue(line.startsWith("{\"at\":"));
            assertTrue(line.endsWith("}"));
        }
        assertTrue(lines.get(0).contains("\"action\":\"unlock\""));
        assertTrue(lines.get(0).contains("\"domain\":\"combat\""));
        assertTrue(lines.get(1).contains("\"refundPowder\":120"));
        assertTrue(lines.get(1).contains("\"refundTokens\":3"));
        assertTrue(lines.get(1).contains("tok\\\"en\\n"),
                "кавычки и перевод строки не должны ломать JSONL");
    }

    @Test
    void lineLimitCountsExistingFileAfterRestart() throws Exception {
        PerkTreeAudit first = new PerkTreeAudit(dir, null);
        first.record("unlock", PLAYER, "combat", 0L, 0, "a");
        first.record("select", PLAYER, "combat", 0L, 0, "b");
        assertEquals(2, first.lines());

        PerkTreeAudit restarted = new PerkTreeAudit(dir, null);
        assertEquals(2, restarted.lines(),
                "после рестарта счётчик должен продолжиться, а не начаться с нуля");
        restarted.record("reset", PLAYER, "combat", 5L, 1, "c");
        assertEquals(3, restarted.lines());
        assertEquals(3, Files.readAllLines(dir.resolve(FILE)).size());
    }

    @Test
    void missingFileStartsEmptyAndDirectoryIsCreatedLazily() {
        PerkTreeAudit audit = new PerkTreeAudit(dir, null);
        assertEquals(0, audit.lines());
        assertTrue(Files.notExists(dir.resolve(FILE)));
        audit.record("unlock", PLAYER, "combat", 0L, 0, "a");
        assertTrue(Files.isRegularFile(dir.resolve(FILE)));
    }
}
