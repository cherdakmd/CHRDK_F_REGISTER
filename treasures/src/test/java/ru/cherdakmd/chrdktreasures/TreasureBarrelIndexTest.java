package ru.cherdakmd.chrdktreasures;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Индекс бочек: строка файла ↔ запись. Формат должен переживать ники с
 * необычными символами и не падать на битой строке — иначе один испорченный
 * файл стирал бы учёт всех бочек сервера.
 */
class TreasureBarrelIndexTest {

    @Test
    void roundTripKeepsEveryField() {
        UUID world = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        TreasureBarrel barrel = new TreasureBarrel(owner, "Cherdak", TreasureTier.LEGENDARY,
                1_700_000_000_000L);
        String line = TreasureBarrelIndex.encode(
                new TreasureBarrelIndex.Entry(world, 120, 64, -330, barrel));

        TreasureBarrelIndex.Entry decoded = TreasureBarrelIndex.decode(line);
        assertNotNull(decoded);
        assertEquals(world, decoded.world());
        assertEquals(120, decoded.x());
        assertEquals(64, decoded.y());
        assertEquals(-330, decoded.z());
        assertEquals(owner, decoded.barrel().owner());
        assertEquals("Cherdak", decoded.barrel().ownerName());
        assertEquals(TreasureTier.LEGENDARY, decoded.barrel().tier());
        assertEquals(1_700_000_000_000L, decoded.barrel().createdAt());
    }

    @Test
    void headerCommentsAndBrokenLinesAreSkipped() {
        assertNull(TreasureBarrelIndex.decode(TreasureBarrelIndex.HEADER));
        assertNull(TreasureBarrelIndex.decode(""));
        assertNull(TreasureBarrelIndex.decode("   "));
        assertNull(TreasureBarrelIndex.decode(null));
        assertNull(TreasureBarrelIndex.decode("не\tформат"), "мало полей");
        assertNull(TreasureBarrelIndex.decode(
                "not-a-uuid\t1\t2\t3\t" + UUID.randomUUID() + "\tNick\tcommon\t5"),
                "битый мир");
        assertNull(TreasureBarrelIndex.decode(
                UUID.randomUUID() + "\t1\t2\t3\tnot-a-uuid\tNick\tcommon\t5"),
                "битый владелец");
        assertNull(TreasureBarrelIndex.decode(
                UUID.randomUUID() + "\t1\t2\t3\t" + UUID.randomUUID() + "\tNick\tcommon\t"
                        + "не-число"), "битое время");
    }

    @Test
    void unknownTierFallsBackToCommonAndTabsAreCleaned() {
        TreasureBarrel barrel = new TreasureBarrel(UUID.randomUUID(), "Bad\tName", null, 42L);
        String encoded = TreasureBarrelIndex.encode(
                new TreasureBarrelIndex.Entry(UUID.randomUUID(), 1, 2, 3, barrel));
        assertTrue(encoded.contains("Bad_Name"), "табуляция должна быть вычищена");
        assertEquals(8, encoded.split("\t", -1).length, "полей ровно восемь");

        TreasureBarrelIndex.Entry decoded = TreasureBarrelIndex.decode(encoded);
        assertNotNull(decoded);
        assertEquals(TreasureTier.COMMON, decoded.barrel().tier(),
                "null-редкость становится обычной");

        TreasureBarrelIndex.Entry unknown = TreasureBarrelIndex.decode(
                UUID.randomUUID() + "\t1\t2\t3\t" + UUID.randomUUID()
                        + "\tNick\tunknown-tier\t42");
        assertNotNull(unknown);
        assertEquals(TreasureTier.COMMON, unknown.barrel().tier(),
                "неизвестная редкость не должна ломать разбор");
    }
}
