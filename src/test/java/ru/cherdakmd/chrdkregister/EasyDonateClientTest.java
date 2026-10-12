package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор ответа EasyDonate API v3 и поведение источнико-зависимых курсоров/ключей
 * в хранилище (коллизии id между провайдерами исключаются префиксом сервиса).
 */
class EasyDonateClientTest {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    private static final String SAMPLE_RESPONSE = """
            {
              "success": true,
              "response": [
                {
                  "id": 7359,
                  "customer": "Ivan_{shulz24}",
                  "email": "ivan@example.com",
                  "shop_id": 1936,
                  "server_id": 2384,
                  "status": 2,
                  "enrolled": 491,
                  "payment_system": "Card",
                  "payment_type": "test",
                  "created_at": "2021-02-23 10:35:38",
                  "updated_at": "2021-02-23 10:35:38"
                },
                {
                  "id": 7360,
                  "customer": "PlayerTwo",
                  "email": "p2@example.com",
                  "shop_id": 1936,
                  "server_id": 2384,
                  "status": 1,
                  "enrolled": 100,
                  "payment_system": "Card",
                  "payment_type": "payment",
                  "created_at": "2021-02-23 11:00:00",
                  "updated_at": "2021-02-23 11:00:00"
                },
                {
                  "id": 7361,
                  "customer": "PlayerThree",
                  "status": 2,
                  "enrolled": "250.50",
                  "created_at": "2021-02-23 12:00:00",
                  "updated_at": "2021-02-23 12:00:00"
                }
              ]
            }
            """;

    @Test
    void parseKeepsOnlyPaidPaymentsSortedById() throws IOException {
        List<DonatePayClient.Transaction> parsed = EasyDonateClient.parsePaymentsResponse(SAMPLE_RESPONSE, MSK);
        assertEquals(2, parsed.size(), "status != 2 должен отсекаться");
        assertEquals(7359L, parsed.get(0).id());
        assertEquals(7361L, parsed.get(1).id());
        assertEquals("Ivan_{shulz24}", parsed.get(0).donorName(), "ник берём из customer");
        assertEquals(new BigDecimal("491"), parsed.get(0).amount(), "сумма — enrolled");
        assertEquals(new BigDecimal("250.50"), parsed.get(1).amount(), "enrolled может быть строкой");
        assertTrue(parsed.get(0).createdAt() > 0, "created_at распаршен в миллисекунды");
        assertTrue(parsed.get(0).testPayment(), "payment_type=test помечается флагом (уйдёт в журнал с 🧪)");
        assertFalse(parsed.get(1).testPayment(), "обычный платёж — без флага");
    }

    @Test
    void compactConstructorIsNotTestPayment() {
        // шесть аргументов (DonatePay и прочие): тестовым платёж не считается
        DonatePayClient.Transaction tx = new DonatePayClient.Transaction(
                1L, "Player", java.math.BigDecimal.TEN, "success", "donation", 1L);
        assertFalse(tx.testPayment());
    }

    @Test
    void unsuccessfulResponseIsAnError() {
        assertThrows(IOException.class, () -> EasyDonateClient.parsePaymentsResponse(
                "{\"success\": false, \"response\": \"Магазин не найден.\", \"error_code\": 1}", MSK));
    }

    @Test
    void sourceKeyedCursorsAndProcessedNeverCollide(@TempDir Path tempDir) {
        RegistrationStore store = new RegistrationStore(tempDir, Logger.getAnonymousLogger());
        // donatepay #123 и easydonate #123 — разные платежи, конфликта быть не должно
        assertFalse(store.isDonationProcessed(123L));
        assertFalse(store.isDonationProcessed(EasyDonateClient.SOURCE, 123L));

        store.markDonationProcessed(EasyDonateClient.SOURCE, 123L);
        assertTrue(store.isDonationProcessed(EasyDonateClient.SOURCE, 123L));
        assertFalse(store.isDonationProcessed(123L), "donatepay-запись НЕ должна помечаться из easydonate");
        store.markDonationProcessed(123L);
        assertTrue(store.isDonationProcessed(123L), "donatepay #123 имеет своё пространство имён");

        // mark уже инициализировал курсор ED на 123; повторный initialize — no-op (одноразовость)
        store.initializeDonationCursor(RegistrationStore.DONATION_SOURCE_DONATEPAY, 900L);
        store.initializeDonationCursor(EasyDonateClient.SOURCE, 42L);
        assertEquals(900L, store.getDonationCursor());
        assertEquals(123L, store.getDonationCursor(EasyDonateClient.SOURCE),
                "initialize после mark ничего не меняет (курсор уже инициализирован)");
        assertTrue(store.isDonationCursorInitialized("easydonate"));
        assertEquals(0L, store.getDonationCursor("unknown-provider"), "незнакомый источник — курсор 0");

        // markDonationProcessed поднимает курсор источника (вниз — никогда)
        store.markDonationProcessed(EasyDonateClient.SOURCE, 200L);
        assertEquals(200L, store.getDonationCursor(EasyDonateClient.SOURCE));
        assertEquals(900L, store.getDonationCursor(), "cursor donatepay не тронут");
    }
}
