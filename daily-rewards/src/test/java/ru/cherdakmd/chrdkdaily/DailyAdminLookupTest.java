package ru.cherdakmd.chrdkdaily;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class DailyAdminLookupTest {
    @Test void unknownPlayerIsNotReportedAsUnclaimedAndPartialNamesAreNotUsed() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkdaily/DailyCommandHandler.java"));
        assertEquals(2, source.split("\"admin.player-not-found\"", -1).length - 1);
        assertFalse(source.contains("Bukkit.getPlayer(name)"));
        assertEquals(2, source.split("Bukkit.getPlayerExact\\(name\\)", -1).length - 1);
        String lookup = source.substring(source.indexOf("if (args.length >= 2 && \"lookup\""), source.indexOf("if (args.length >= 3 && \"set\""));
        int missing = lookup.indexOf("if (uuid == null)");
        int end = lookup.indexOf("return true;", missing);
        assertTrue(missing >= 0 && end > missing);
        assertFalse(lookup.substring(missing, end).contains("daily.claimed-no"));
    }
}
