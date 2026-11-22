package ru.cherdakmd.chrdkregister.pve;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Общий реестр: дубликаты, статистика по всем получателям, смена дня. */
class PveAwardLedgerTest {

    private static final UUID PLAYER = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID OTHER = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final String DAY = "2026-11-05";
    private static final String NEXT = "2026-11-06";

    private static PveReceipt receipt(UUID death, PveRecipient recipient, String day) {
        return PveAwardContract.receipt(death, recipient, day);
    }

    @Test
    void sharedInstanceExistsAndTestsUseIsolatedOnes() {
        assertNotNull(PveAwardLedger.shared());
        PveAwardLedger first = new PveAwardLedger();
        PveAwardLedger second = new PveAwardLedger();
        assertTrue(first.register(PLAYER, receipt(UUID.randomUUID(), PveRecipient.CLASS_XP, DAY)));
        assertEquals(0, second.receipts(PLAYER));
    }

    @Test
    void duplicateReceiptForSameRecipientIsRejected() {
        PveAwardLedger ledger = new PveAwardLedger();
        UUID death = UUID.randomUUID();
        PveReceipt receipt = receipt(death, PveRecipient.COMMON_GOALS, DAY);
        assertTrue(ledger.register(PLAYER, receipt));
        assertFalse(ledger.register(PLAYER, receipt));
        assertEquals(1, ledger.receipts(PLAYER));
        assertEquals(1, ledger.spent(PLAYER));
    }

    @Test
    void differentRecipientsForOneDeathAreDistinct() {
        PveAwardLedger ledger = new PveAwardLedger();
        UUID death = UUID.randomUUID();
        for (PveRecipient recipient : PveRecipient.values()) {
            assertTrue(ledger.register(PLAYER, receipt(death, recipient, DAY)), recipient.name());
        }
        assertEquals(5, ledger.receipts(PLAYER));
        assertEquals(5, ledger.spent(PLAYER));
    }

    @Test
    void historicalBudgetDoesNotBlockDistinctReceipts() {
        PveAwardLedger ledger = new PveAwardLedger();
        for (int i = 0; i < PveAwardContract.DAILY_AWARD_CAP; i++) {
            assertTrue(ledger.register(PLAYER,
                    receipt(UUID.randomUUID(), PveRecipient.BATTLEPASS, DAY)));
        }
        UUID death = UUID.randomUUID();
        var next = receipt(death, PveRecipient.PERK_POWDER, DAY);
        assertTrue(ledger.register(PLAYER, next));
        assertFalse(ledger.register(PLAYER, next), "защита от повтора сохраняется");
        assertEquals(PveAwardContract.DAILY_AWARD_CAP + 1, ledger.spent(PLAYER));
        assertTrue(ledger.register(OTHER, next));
    }

    @Test
    void diagnosticCounterSaturatesWithoutBlockingRewards() {
        var ledger = new PveAwardLedger();
        var first = new PveReceipt(UUID.randomUUID(), PveRecipient.BATTLEPASS, DAY, Integer.MAX_VALUE);
        var second = receipt(UUID.randomUUID(), PveRecipient.PERK_POWDER, DAY);
        assertTrue(ledger.register(PLAYER, first));
        assertTrue(ledger.register(PLAYER, second));
        assertEquals(Integer.MAX_VALUE, ledger.spent(PLAYER));
        assertFalse(ledger.register(PLAYER, first));
    }

    @Test
    void sameDeathOnAnotherDayOrAnotherPlayerDoesNotCollide() {
        PveAwardLedger ledger = new PveAwardLedger();
        UUID death = UUID.randomUUID();
        assertTrue(ledger.register(PLAYER, receipt(death, PveRecipient.CLASS_XP, DAY)));
        assertTrue(ledger.register(OTHER, receipt(death, PveRecipient.CLASS_XP, DAY)));
        assertEquals(1, ledger.receipts(PLAYER));
        assertEquals(1, ledger.receipts(OTHER));
        // Новый день сбрасывает квитанции и счётчики у всех игроков.
        assertTrue(ledger.register(PLAYER,
                receipt(UUID.randomUUID(), PveRecipient.CLASS_XP, NEXT)));
        assertEquals(NEXT, ledger.currentDay());
        assertEquals(1, ledger.receipts(PLAYER));
        assertEquals(0, ledger.receipts(OTHER));
        assertEquals(1, ledger.spent(PLAYER));
        // Та же смерть в новый день уже не считается дубликатом.
        assertTrue(ledger.register(PLAYER, receipt(death, PveRecipient.CLASS_XP, NEXT)));
        assertEquals(2, ledger.spent(PLAYER));
    }

    @Test
    void trimEvictsLeastRecentlyUsedInsteadOfAnArbitraryKey() {
        PveAwardLedger ledger = new PveAwardLedger();
        UUID keep = UUID.randomUUID();
        // Заполняем реестр ровно до потолка; keep попадает первым.
        for (int i = 0; i < 8_192; i++) {
            UUID who = i == 0 ? keep : UUID.randomUUID();
            assertTrue(ledger.register(who,
                    receipt(UUID.randomUUID(), PveRecipient.CLASS_XP, DAY)));
        }
        assertEquals(8_192, ledger.players());
        // Освежаем keep: теперь он самый недавно использованный.
        assertTrue(ledger.register(keep,
                receipt(UUID.randomUUID(), PveRecipient.COMMON_GOALS, DAY)));
        // 8193-й игрок вытесняет давно не активного, а не случайный ключ HashMap.
        assertTrue(ledger.register(UUID.randomUUID(),
                receipt(UUID.randomUUID(), PveRecipient.CLASS_XP, DAY)));
        assertEquals(8_192, ledger.players());
        assertEquals(2, ledger.receipts(keep),
                "недавно активный игрок не должен терять квитанции при вытеснении");
    }

    @Test
    void nullArgumentsAndClearAreSafe() {
        PveAwardLedger ledger = new PveAwardLedger();
        assertFalse(ledger.register(null, receipt(UUID.randomUUID(), PveRecipient.CLASS_XP, DAY)));
        assertFalse(ledger.register(PLAYER, null));
        assertTrue(ledger.register(PLAYER, receipt(UUID.randomUUID(), PveRecipient.CLASS_XP, DAY)));
        ledger.clear();
        assertEquals(0, ledger.receipts(PLAYER));
        assertEquals(0, ledger.spent(PLAYER));
        assertNull(ledger.currentDay());
    }
}
