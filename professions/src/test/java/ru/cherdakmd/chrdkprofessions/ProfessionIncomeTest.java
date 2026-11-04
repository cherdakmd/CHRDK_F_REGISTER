package ru.cherdakmd.chrdkprofessions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.4.282: накопительный доход ремёсел не удваивается и не теряется. */
final class ProfessionIncomeTest {

    private static final Logger LOGGER = Logger.getLogger("ProfessionIncomeTest");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
    private static final String DAY = "2026-10-14";

    /** Повторяет семантику BankStore.creditEntitlement: только delta от total. */
    private static final class FakeBank implements ProfessionIncome.Bank {
        boolean available = true;
        boolean known = true;
        boolean registered = true;
        long credited;
        final List<Long> totals = new ArrayList<>();

        public boolean available() { return available; }
        public boolean registered(UUID lineage) { return registered; }
        public boolean register(UUID lineage) { registered = true; return true; }
        public boolean known(UUID owner) { return known; }
        public long credited(UUID lineage, UUID owner) { return credited; }
        public boolean credit(UUID lineage, UUID owner, long total) {
            totals.add(total);
            if (total <= credited) return true;
            credited = total;
            return true;
        }
    }

    @Test
    void repeatedDeliveryNeverPaysTwice(@TempDir Path dir) {
        ProfessionStore store = new ProfessionStore(dir, LOGGER);
        store.creditIncome(PLAYER, 3, DAY);
        assertEquals(3, store.incomeTotal(PLAYER));
        assertEquals(3, store.incomeToday(PLAYER, DAY));
        assertEquals(0, store.incomeToday(PLAYER, "2026-10-15"));

        FakeBank bank = new FakeBank();
        ProfessionIncome income = new ProfessionIncome(store, bank);
        assertTrue(income.deliver(PLAYER));
        assertEquals(3, bank.credited);
        assertTrue(income.deliver(PLAYER), "повтор с тем же total успешен");
        assertEquals(3, bank.credited, "но алмазы не удвоились");
        assertEquals(List.of(3L, 3L), bank.totals);

        store.creditIncome(PLAYER, 2, DAY);
        assertTrue(income.deliver(PLAYER));
        assertEquals(5, bank.credited);
        assertEquals(5, store.incomeTotal(PLAYER));
        assertEquals(5, store.incomeToday(PLAYER, DAY));
    }

    @Test
    void unavailableBankKeepsTheDebtForRetry(@TempDir Path dir) {
        ProfessionStore store = new ProfessionStore(dir, LOGGER);
        store.creditIncome(PLAYER, 7, DAY);
        FakeBank bank = new FakeBank();
        bank.available = false;
        ProfessionIncome income = new ProfessionIncome(store, bank);
        assertFalse(income.deliver(PLAYER));
        assertEquals(0, income.tick(16));
        assertEquals(7, store.incomeTotal(PLAYER), "долг перед игроком не сброшен");
        bank.available = true;
        assertEquals(1, income.tick(16));
        assertEquals(7, bank.credited);
    }

    @Test
    void lineageAndIncomeSurviveRoundTrip(@TempDir Path dir) {
        ProfessionStore store = new ProfessionStore(dir, LOGGER);
        UUID lineage = store.incomeLineage();
        store.setIncomeMicro(PLAYER, 750);
        store.creditIncome(PLAYER, 4, DAY);
        store.saveNow();

        ProfessionStore loaded = new ProfessionStore(dir, LOGGER);
        loaded.load();
        assertEquals(lineage, loaded.incomeLineage());
        assertEquals(750, loaded.incomeMicro(PLAYER));
        assertEquals(4, loaded.incomeTotal(PLAYER));
        assertEquals(4, loaded.incomeToday(PLAYER, DAY));
        assertEquals(List.of(PLAYER), loaded.incomeOwners());
    }
}
