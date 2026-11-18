package ru.cherdakmd.chrdkanticheat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlagIngestTest {

    @Test
    void parsesFullBridgeCommand() {
        FlagIngest.Parsed parsed = FlagIngest.parse(
                new String[]{"flag", "Nick", "KillauraB", "3", "aim", "pattern"});
        assertEquals("Nick", parsed.player());
        assertEquals("KillauraB", parsed.check());
        assertEquals(3, parsed.vl());
        assertEquals("aim pattern", parsed.verbose());
    }

    @Test
    void verboseIsEmptyWhenOmitted() {
        FlagIngest.Parsed parsed = FlagIngest.parse(new String[]{"flag", "Nick", "Speed", "1"});
        assertEquals("", parsed.verbose());
    }

    @Test
    void rejectsBrokenForms() {
        assertThrows(IllegalArgumentException.class, () -> FlagIngest.parse(null));
        assertThrows(IllegalArgumentException.class,
                () -> FlagIngest.parse(new String[]{"flag", "Nick", "Speed"}));
        assertThrows(IllegalArgumentException.class,
                () -> FlagIngest.parse(new String[]{"other", "Nick", "Speed", "1"}));
        assertThrows(IllegalArgumentException.class,
                () -> FlagIngest.parse(new String[]{"flag", "Nick", "Speed", "abc"}));
        assertThrows(IllegalArgumentException.class,
                () -> FlagIngest.parse(new String[]{"flag", "Nick", "Speed", "-2"}));
        assertThrows(IllegalArgumentException.class,
                () -> FlagIngest.parse(new String[]{"flag", "", "Speed", "1"}));
    }
}
