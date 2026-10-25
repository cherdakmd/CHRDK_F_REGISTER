package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Лестница тиров: порядок, поиск и поведение на {@code null}. Регрессия 1.4.126:
 * {@code List.of(...).contains(null)} бросает NPE, а {@code TierItems.tierOf}
 * возвращает {@code null} для обычного предмета — падал {@code PlayerItemHeldEvent}
 * и тик ауры.
 */
class TierDefTest {

    @Test
    void ladderIsStableAndOrdered() {
        assertEquals(7, TierDef.LADDER.size());
        assertEquals(0, TierDef.indexOf("cursed"));
        assertEquals(3, TierDef.indexOf("fine"));
        assertEquals(6, TierDef.indexOf("legendary"));
        assertEquals("cursed", TierDef.LADDER.get(0));
        assertEquals("legendary", TierDef.LADDER.get(TierDef.LADDER.size() - 1));
    }

    @Test
    void nullIsNotATierAndDoesNotThrow() {
        assertFalse(TierDef.isTier(null));
        assertEquals(-1, TierDef.indexOf(null));
        assertNull(TierDef.next(null));
    }

    @Test
    void unknownIdIsNotATier() {
        assertFalse(TierDef.isTier("no-such-tier"));
        assertFalse(TierDef.isTier(""));
        assertFalse(TierDef.isTier("LEGENDARY"), "id тира пишется в нижнем регистре");
        assertEquals(-1, TierDef.indexOf("unknown"));
    }

    @Test
    void nextWalksTheLadderAndStopsAtTheTop() {
        assertEquals("weak", TierDef.next("cursed"));
        assertEquals("legendary", TierDef.next("epic"));
        assertNull(TierDef.next("legendary"));
        assertTrue(TierDef.isTier(TierDef.next("rare")), "следующий тир существует");
    }
}
