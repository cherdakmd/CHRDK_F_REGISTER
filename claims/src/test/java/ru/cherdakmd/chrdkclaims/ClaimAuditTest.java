package ru.cherdakmd.chrdkclaims;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimAuditTest {

    @TempDir
    Path dir;

    private ClaimAudit audit(long maxBytes) {
        return new ClaimAudit(dir, Logger.getAnonymousLogger(), maxBytes);
    }

    @Test
    void writesReadableLinesAndReturnsTail() {
        ClaimAudit audit = audit(ClaimAudit.MAX_BYTES);
        audit.write("buy", "Steve world [1, 2] 16 ⛁");
        audit.write("unclaim", "Steve world [1, 2]");

        List<String> tail = audit.tail(10);
        assertEquals(2, tail.size());
        assertTrue(tail.get(0).contains("| buy |"), tail.get(0));
        assertTrue(tail.get(1).contains("| unclaim |"), tail.get(1));
        assertTrue(tail.get(0).startsWith("20"), "строка начинается с даты");
        assertEquals(1, audit.tail(1).size(), "хвост ограничивается запрошенным числом строк");
    }

    @Test
    void newlinesAndPipesInValuesDoNotBreakTheFormat() {
        ClaimAudit audit = audit(ClaimAudit.MAX_BYTES);
        audit.write("say", "строка1\nстрока2 | с чертой");
        List<String> tail = audit.tail(5);
        assertEquals(1, tail.size(), "одно событие — одна строка");
        assertTrue(tail.get(0).contains("строка1 строка2 / с чертой"), tail.get(0));
    }

    @Test
    void logRotatesWhenSizeIsExceeded() throws IOException {
        ClaimAudit audit = audit(1024);
        for (int index = 0; index < 60; index++) {
            audit.write("test", "запись номер " + index + " с небольшим хвостом для объёма");
        }
        assertTrue(Files.isRegularFile(dir.resolve("claims-audit.log.1")),
                "старый журнал должен откладываться в .1");
        assertTrue(Files.size(dir.resolve("claims-audit.log")) < 2048,
                "после ротации новый файл начинается заново");
        assertTrue(audit.tail(5).get(4).contains("запись номер 59"), "хвост — из нового файла");
    }

    @Test
    void missingFileGivesEmptyTail() {
        assertTrue(audit(ClaimAudit.MAX_BYTES).tail(5).isEmpty());
    }

    @Test
    void emptyValuesAreReplacedWithDash() throws IOException {
        ClaimAudit audit = audit(ClaimAudit.MAX_BYTES);
        audit.write(null, "  ");
        String line = Files.readString(dir.resolve("claims-audit.log"), StandardCharsets.UTF_8);
        assertTrue(line.contains("| - | -"), line);
    }
}
