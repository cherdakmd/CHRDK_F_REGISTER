package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Безопасная точка выкладки вещей «привязки души» (1.4.88). */
class SoulboundDropTest {

    @Test
    void clampsToWorldBounds() {
        assertEquals(-63, SoulboundDrop.clampY(-500, -64, 320));
        assertEquals(319, SoulboundDrop.clampY(999, -64, 320));
        assertEquals(64, SoulboundDrop.clampY(64, -64, 320));
        assertEquals(1, SoulboundDrop.clampY(0, 0, 1), "вырожденный мир не уходит ниже min+1");
    }

    @Test
    void preferredSafeYIsUsedAsIs() {
        assertEquals(64, SoulboundDrop.safeY(64, -64, 320, 4, y -> true));
    }

    @Test
    void searchesUpThenDown() {
        Set<Integer> safe = Set.of(66);
        assertEquals(66, SoulboundDrop.safeY(64, -64, 320, 4, safe::contains),
                "лава на 64..65 — поднимаемся на 66");
        Set<Integer> below = Set.of(62);
        assertEquals(62, SoulboundDrop.safeY(64, -64, 320, 4, below::contains),
                "если сверху пусто, ищем ниже");
    }

    @Test
    void fallsBackToClampedPreferred() {
        assertEquals(-63, SoulboundDrop.safeY(-1000, -64, 320, 3, y -> false));
        assertEquals(64, SoulboundDrop.safeY(64, -64, 320, 0, y -> false));
    }
}
