package ru.cherdakmd.chrdktreasures;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Модель бочки: владелец, срок жизни и подпись. Логика чистая, поэтому
 * проверяется без сервера — именно она решает, когда бочку пора убирать.
 */
class TreasureBarrelTest {

    private static final long MINUTE = 60_000L;

    @Test
    void ownerIsRecognisedById() {
        UUID owner = UUID.randomUUID();
        TreasureBarrel barrel = new TreasureBarrel(owner, "Cherdak", TreasureTier.RARE, 0L);
        assertTrue(barrel.ownedBy(owner));
        assertFalse(barrel.ownedBy(UUID.randomUUID()));
        assertFalse(barrel.ownedBy(null));
    }

    @Test
    void expiryRespectsTtlAndZeroMeansForever() {
        TreasureBarrel barrel = new TreasureBarrel(UUID.randomUUID(), "Cherdak",
                TreasureTier.COMMON, 1_000L);
        assertFalse(barrel.expired(1_000L + 19 * MINUTE, 20 * MINUTE));
        assertTrue(barrel.expired(1_000L + 20 * MINUTE, 20 * MINUTE));
        // ttl = 0 — администратор отключил срок жизни, бочка не исчезает.
        assertFalse(barrel.expired(Long.MAX_VALUE - 1, 0L));
    }

    @Test
    void emptyModelGetsSafeDefaults() {
        TreasureBarrel barrel = new TreasureBarrel(null, null, null, -10L);
        assertEquals("", barrel.ownerName());
        assertEquals("?", barrel.ownerLabel());
        assertEquals(TreasureTier.COMMON, barrel.tier());
        assertEquals(0L, barrel.createdAt());
        assertFalse(barrel.ownedBy(null), "бочка без владельца ничья");
    }
}
