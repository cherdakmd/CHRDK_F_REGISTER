package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** CSV-выгрузка статистики находок (1.4.99). */
class StatsCsvTest {

    private static UUID id(int value) {
        return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", value));
    }

    @Test
    void headerFollowsTheLadder() {
        assertEquals("uuid,name,cursed,weak,common,fine,rare,epic,legendary,total",
                StatsCsv.header());
    }

    @Test
    void rowsAreSortedByTotalAndFallBackToUuid() {
        Map<UUID, Map<String, Integer>> snapshot = new LinkedHashMap<>();
        snapshot.put(id(1), Map.of("rare", 1));
        snapshot.put(id(2), Map.of("rare", 3, "common", 1));
        Map<UUID, String> names = Map.of(id(1), "Alpha", id(2), "Beta");

        String csv = StatsCsv.render(snapshot, names);
        String[] lines = csv.split("\n");
        assertEquals(3, lines.length);
        assertTrue(lines[1].startsWith(id(2) + ",Beta,0,0,1,0,3,0,0,4"), lines[1]);
        assertTrue(lines[2].startsWith(id(1) + ",Alpha,0,0,0,0,1,0,0,1"), lines[2]);
    }

    @Test
    void missingNameFallsBackToUuidAndUnknownTiersIgnored() {
        Map<UUID, Map<String, Integer>> snapshot = Map.of(id(7), Map.of("legendary", 2, "mythic", 5));
        String[] lines = StatsCsv.render(snapshot, Map.of()).split("\n");
        assertEquals(id(7) + "," + id(7) + ",0,0,0,0,0,0,2,2", lines[1],
                "неизвестный тир не попадает в сумму");
    }

    @Test
    void fieldsAreEscapedPerRfc4180() {
        assertEquals("plain", StatsCsv.escape("plain"));
        assertEquals("\"a,b\"", StatsCsv.escape("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", StatsCsv.escape("say \"hi\""));
        assertEquals("\"two\nlines\"", StatsCsv.escape("two\nlines"));
        assertEquals("", StatsCsv.escape(null));

        Map<UUID, Map<String, Integer>> snapshot = Map.of(id(3), Map.of("rare", 1));
        String csv = StatsCsv.render(snapshot, Map.of(id(3), "Nick,With Comma"));
        assertTrue(csv.contains("\"Nick,With Comma\""), "ник экранируется в строке");
    }
}
