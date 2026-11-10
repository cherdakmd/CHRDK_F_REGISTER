package ru.cherdakmd.chrdkvk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Дедуп, повторы, новый день и защита от дубля после рестарта. */
class DailyCardsLedgerTest {

    private static final Instant SLOT_RICHEST = Instant.parse("2026-11-03T06:00:00Z"); // 09:00 МСК
    private static final Instant RETRY_RICHEST = Instant.parse("2026-11-03T06:02:00Z");
    private static final Instant NEXT_DAY = Instant.parse("2026-11-04T06:00:00Z");

    @TempDir
    Path folder;

    private DailyCardsStore store() {
        return new DailyCardsStore(folder, Logger.getLogger("daily-cards-test"));
    }

    private DailyCardsLedger fresh() {
        DailyCardsStore store = store();
        return new DailyCardsLedger(store, store.load());
    }

    @Test
    void eachSlotPublishesOncePerDay() {
        DailyCardsLedger ledger = fresh();
        assertEquals(List.of(DailyCardKind.RICHEST), ledger.due(DailyCardsConfig.DEFAULT, SLOT_RICHEST));
        ledger.markAttempt(DailyCardKind.RICHEST);
        ledger.markPosting(DailyCardKind.RICHEST);
        // Незавершённая публикация не должна породить вторую карточку.
        assertEquals(List.of(), ledger.due(DailyCardsConfig.DEFAULT, RETRY_RICHEST));
        ledger.markPosted(DailyCardKind.RICHEST, 101L);
        assertEquals(List.of(), ledger.due(DailyCardsConfig.DEFAULT, RETRY_RICHEST));
        assertTrue(ledger.isPosted(DailyCardKind.RICHEST));
        assertEquals(101L, ledger.postedId(DailyCardKind.RICHEST));

        assertEquals(List.of(DailyCardKind.PRESTIGE),
                ledger.due(DailyCardsConfig.DEFAULT, Instant.parse("2026-11-03T09:00:00Z")));
        assertEquals(List.of(DailyCardKind.MINER),
                ledger.due(DailyCardsConfig.DEFAULT, Instant.parse("2026-11-03T12:00:00Z")));
        assertEquals(List.of(DailyCardKind.HUNTER),
                ledger.due(DailyCardsConfig.DEFAULT, Instant.parse("2026-11-03T15:00:00Z")));
    }

    @Test
    void retryWindowIsBoundedByThreeAttempts() {
        DailyCardsLedger ledger = fresh();
        ledger.due(DailyCardsConfig.DEFAULT, SLOT_RICHEST); // фиксируем сутки
        ledger.markAttempt(DailyCardKind.RICHEST);
        assertEquals(List.of(DailyCardKind.RICHEST),
                ledger.due(DailyCardsConfig.DEFAULT, RETRY_RICHEST));
        ledger.markAttempt(DailyCardKind.RICHEST);
        assertEquals(List.of(DailyCardKind.RICHEST),
                ledger.due(DailyCardsConfig.DEFAULT, RETRY_RICHEST));
        ledger.markAttempt(DailyCardKind.RICHEST);
        assertEquals(List.of(), ledger.due(DailyCardsConfig.DEFAULT, RETRY_RICHEST));
        assertEquals(3, ledger.attempts(DailyCardKind.RICHEST));
    }

    @Test
    void disabledConfigSuppressesEverything() {
        DailyCardsLedger ledger = fresh();
        DailyCardsConfig disabled = new DailyCardsConfig(false,
                java.time.ZoneId.of("Europe/Moscow"), true, 9, 12, 15, 18, 5,
                "ТВОЙ МИР. ТВОЯ ИСТОРИЯ.");
        assertEquals(List.of(), ledger.due(disabled, SLOT_RICHEST));
    }

    @Test
    void newDayClearsDedupState() {
        DailyCardsLedger ledger = fresh();
        ledger.due(DailyCardsConfig.DEFAULT, SLOT_RICHEST); // фиксируем сутки
        ledger.markAttempt(DailyCardKind.RICHEST);
        ledger.markPosted(DailyCardKind.RICHEST, 7L);
        assertEquals("2026-11-03", ledger.day());
        assertEquals(List.of(DailyCardKind.RICHEST),
                ledger.due(DailyCardsConfig.DEFAULT, NEXT_DAY));
        assertEquals("2026-11-04", ledger.day());
        assertEquals(0, ledger.attempts(DailyCardKind.RICHEST));
        assertFalse(ledger.isPosted(DailyCardKind.RICHEST));
    }

    @Test
    void stateSurvivesReloadAndUnresolvedPostIsSkipped() {
        DailyCardsStore store = store();
        DailyCardsLedger first = new DailyCardsLedger(store, store.load());
        first.due(DailyCardsConfig.DEFAULT, SLOT_RICHEST); // фиксируем сутки
        first.markAttempt(DailyCardKind.MINER);
        first.markPosted(DailyCardKind.MINER, 55L);
        first.markAttempt(DailyCardKind.HUNTER);
        first.markPosting(DailyCardKind.HUNTER);
        first.save();

        DailyCardsLedger second = new DailyCardsLedger(store, store.load());
        assertEquals(List.of(DailyCardKind.HUNTER), second.takeUnresolvedPosting());
        assertTrue(second.isPosted(DailyCardKind.HUNTER),
                "Прерванная публикация пропускается, а не повторяется");
        assertTrue(second.isPosted(DailyCardKind.MINER));
        assertEquals(55L, second.postedId(DailyCardKind.MINER));
        assertEquals(List.of(), second.due(DailyCardsConfig.DEFAULT,
                Instant.parse("2026-11-03T15:00:00Z")));
    }
}
