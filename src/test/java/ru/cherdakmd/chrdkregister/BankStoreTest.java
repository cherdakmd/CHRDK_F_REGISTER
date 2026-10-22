package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Алмазный банк (1.4.138): счета, депозит, снятие, переводы с комиссией,
 * топ, индекс ников и выживание данных после перезапуска.
 */
class BankStoreTest {

    private static final Logger LOGGER = Logger.getLogger("bank-test");

    private static BankStore store(Path dir) {
        BankStore store = new BankStore(dir, LOGGER);
        store.load();
        return store;
    }

    @Test
    void depositWithdrawAndBalance(@TempDir Path dir) {
        BankStore store = store(dir);
        UUID steve = UUID.randomUUID();
        assertEquals(0L, store.balance(steve));
        assertEquals(120L, store.deposit(steve, "Steve", 120L));
        assertEquals(120L, store.balance(steve));
        assertTrue(store.withdraw(steve, 70L));
        assertEquals(50L, store.balance(steve));
        assertFalse(store.withdraw(steve, 51L), "нельзя уйти в минус");
        assertEquals(50L, store.balance(steve));
        assertFalse(store.withdraw(steve, 0L));
        assertEquals(0L, store.deposit(steve, "Steve", 0L));
    }

    @Test
    void transferChargesTaxAndUpdatesCounters(@TempDir Path dir) {
        BankStore store = store(dir);
        UUID alex = UUID.randomUUID();
        UUID maria = UUID.randomUUID();
        store.deposit(alex, "Alex", 1_000L);
        store.open(maria, "Maria");
        BankStore.TransferResult result = store.transfer(alex, "Alex", maria, "Maria", 200L, 5);
        assertTrue(result.ok());
        assertEquals(200L, result.amount());
        assertEquals(10L, result.tax());
        assertEquals(190L, result.credited());
        assertEquals(800L, store.balance(alex));
        assertEquals(190L, store.balance(maria));
        assertEquals(200L, store.sentOf(alex));
        assertEquals(190L, store.receivedOf(maria));
    }

    @Test
    void transferRejectsBadInput(@TempDir Path dir) {
        BankStore store = store(dir);
        UUID alex = UUID.randomUUID();
        store.deposit(alex, "Alex", 100L);
        assertEquals(BankStore.Status.SELF_TRANSFER,
                store.transfer(alex, "Alex", alex, "Alex", 10L, 0).status());
        assertEquals(BankStore.Status.INVALID_AMOUNT,
                store.transfer(alex, "Alex", UUID.randomUUID(), "X", 0L, 0).status());
        assertEquals(BankStore.Status.NOT_ENOUGH,
                store.transfer(alex, "Alex", UUID.randomUUID(), "X", 1_000L, 0).status());
        assertEquals(100L, store.balance(alex), "неудачный перевод не трогает счёт");
    }

    @Test
    void topOrdersByBalanceAndSkipsEmptyAccounts(@TempDir Path dir) {
        BankStore store = store(dir);
        UUID low = UUID.randomUUID();
        UUID high = UUID.randomUUID();
        UUID empty = UUID.randomUUID();
        store.deposit(low, "Low", 10L);
        store.deposit(high, "High", 900L);
        store.open(empty, "Empty");
        List<BankStore.Entry> top = store.top(10);
        assertEquals(2, top.size());
        assertEquals("High", top.get(0).name());
        assertEquals("Low", top.get(1).name());
    }

    @Test
    void namesAreCaseInsensitiveAndSurviveRestart(@TempDir Path dir) {
        BankStore store = store(dir);
        UUID steve = UUID.randomUUID();
        store.deposit(steve, "Steve", 42L);
        store.flush();
        BankStore reloaded = store(dir);
        assertEquals(42L, reloaded.balanceOf("steve"));
        assertEquals(steve, reloaded.idByName(" STEVE "));
        assertNull(reloaded.idByName("unknown"));
    }

    @Test
    void nicknameChangeMovesIndexWithoutLosingBalance(@TempDir Path dir) {
        BankStore store = store(dir);
        UUID steve = UUID.randomUUID();
        store.deposit(steve, "Steve", 55L);
        store.open(steve, "SteveNew");
        assertNull(store.idByName("Steve"), "старый ник больше не указывает на счёт");
        assertEquals(steve, store.idByName("stevenew"));
        assertEquals(55L, store.balance(steve));
    }

    @Test
    void balanceIsCappedAndOverflowIsRejected(@TempDir Path dir) {
        BankStore store = store(dir);
        UUID rich = UUID.randomUUID();
        assertEquals(-1L, store.deposit(rich, "Rich", BankStore.MAX_BALANCE + 1L));
        assertEquals(0L, store.balance(rich));
        assertEquals(BankStore.MAX_BALANCE, store.deposit(rich, "Rich", BankStore.MAX_BALANCE));
        assertEquals(-1L, store.deposit(rich, "Rich", 1L), "переполнение счёта отклоняется");
    }

    @Test
    void corruptFileIsPreservedForDiagnostics(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("bank.json"), "{это не json", StandardCharsets.UTF_8);
        BankStore store = store(dir);
        assertEquals(0, store.accountCount());
        assertTrue(Files.exists(dir.resolve("bank.json.corrupt")), "битый файл сохранён рядом");
    }

    @Test
    void amountParsingAcceptsSpacesAndSuffixes() {
        assertEquals(1_000L, BankService.parseAmount("1 000"));
        assertEquals(1_000L, BankService.parseAmount("1k"));
        assertEquals(1_500L, BankService.parseAmount("1.5k"));
        assertEquals(2_000_000L, BankService.parseAmount("2M"));
        assertEquals(-1L, BankService.parseAmount("all"), "all обрабатывается командой отдельно");
        assertEquals(-1L, BankService.parseAmount("0"));
        assertEquals(-1L, BankService.parseAmount("-5"));
        assertEquals(-1L, BankService.parseAmount("abc"));
        assertEquals(-1L, BankService.parseAmount(null));
        assertNotNull(BankService.format(1_234L));
        assertTrue(BankService.format(1_234L).contains("234"));
    }
}
