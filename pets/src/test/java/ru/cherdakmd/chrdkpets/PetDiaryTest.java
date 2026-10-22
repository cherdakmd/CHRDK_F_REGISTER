package ru.cherdakmd.chrdkpets;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetDiaryTest {

    @Test
    void encodeDecodeRoundTrip() {
        PetDiary.Entry entry = new PetDiary.Entry(1_700_000_000_000L, "level", "7");
        PetDiary.Entry decoded = PetDiary.decode(PetDiary.encode(entry));
        assertNotNull(decoded);
        assertEquals(entry.at(), decoded.at());
        assertEquals("level", decoded.code());
        assertEquals("7", decoded.arg());
    }

    @Test
    void separatorInsideArgumentIsReplaced() {
        PetDiary.Entry decoded = PetDiary.decode(
                PetDiary.encode(new PetDiary.Entry(10L, "rename", "Бар|сик")));
        assertNotNull(decoded);
        assertEquals("Бар сик", decoded.arg());
    }

    @Test
    void brokenRecordsAreIgnored() {
        assertNull(PetDiary.decode(null));
        assertNull(PetDiary.decode(""));
        assertNull(PetDiary.decode("not-a-time|level|7"));
        assertNull(PetDiary.decode("1000||x"));
        assertNull(PetDiary.decode("1000"));
    }

    @Test
    void appendKeepsOnlyLatestEntries() {
        List<String> raw = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            raw = PetDiary.append(raw, new PetDiary.Entry(index + 1L, "kill",
                    String.valueOf(index + 1)), 3);
        }
        assertEquals(3, raw.size());
        assertEquals("3", PetDiary.decode(raw.get(0)).arg());
        assertEquals("5", PetDiary.decode(raw.get(2)).arg());
    }

    @Test
    void zeroLimitDisablesDiary() {
        List<String> raw = PetDiary.append(List.of("1|kill|"), new PetDiary.Entry(2L, "kill", ""), 0);
        assertTrue(raw.isEmpty());
    }
}
