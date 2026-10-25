package ru.cherdakmd.chrdkregister;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Покупка ступени ниже текущей (1.4.167).
 *
 * <p>Витрина TradeMC присылает только сумму, поэтому игрок с Покровителем может
 * случайно купить Мецената. Ранг не понижается — и без записи в журнал человек
 * заплатил бы и не получил ничего. Тест держит два свойства: платёж попадает
 * в журнал со статусом ожидания и остаётся видимым для {@code /pass pending}.</p>
 */
class LowerRankPaymentTest {

    @TempDir
    Path dir;

    private static final long NOW = 1_800_000_000_000L;
    private static final UUID UUID1 = UUID.fromString("00000000-0000-0000-0000-000000000042");

    private RegistrationStore store() {
        return new RegistrationStore(dir, Logger.getAnonymousLogger());
    }

    @Test
    void lowerRankPaymentIsParkedForManualReview() {
        RegistrationStore store = store();
        store.registerPassword("Patron", UUID1, "StrongPassword123", "127.0.0.1", NOW);
        store.grantRankUntil("Patron", NOW, 5, NOW + 30L * 86_400_000L, "shop:rank-founder");

        long id = store.nextShopSaleId();
        assertTrue(id < 0, "id продажи каталога отрицательный — положительные у DonatePay");

        RegistrationStore.DonationRecord record = new RegistrationStore.DonationRecord();
        record.id = id;
        record.source = RegistrationStore.DONATION_SOURCE_TRADEMC;
        record.donorName = "Patron";
        record.amountKopecks = 299_00L;
        record.amountRaw = "299";
        record.createdAt = NOW;
        record.detectedAt = NOW;
        record.status = RegistrationStore.DonationStatus.PENDING_REVIEW.name();
        record.note = "shop:rank-patron:ниже текущей ступени 5";
        store.recordDonation(record);

        var pending = store.listDonations(null, 0).stream().filter(r -> r.donationStatus().isPending()).toList();
        assertEquals(1, pending.size(), "платёж должен ждать разбора, а не исчезнуть");
        RegistrationStore.DonationRecord saved = pending.get(0);
        assertEquals("Patron", saved.donorName);
        assertEquals(299_00L, saved.amountKopecks);
        assertTrue(saved.donationStatus().isPending(), "статус именно «ожидает разбора»");
        assertEquals(5, store.rankLevel("Patron", NOW), "ступень не понизилась");
    }

    @Test
    void shopSaleIdsDoNotCollideWithDonatePayIds() {
        RegistrationStore store = store();
        long first = store.nextShopSaleId();
        long second = store.nextShopSaleId();
        assertEquals(-1L, first, "первая продажа идёт от минус единицы");
        assertEquals(-2L, second, "идентификаторы продаж уникальны");
        assertTrue(store.nextShopSaleId() < second);
    }

    @Test
    void journalSurvivesRestart() {
        RegistrationStore store = store();
        long id = store.nextShopSaleId();
        RegistrationStore.DonationRecord record = new RegistrationStore.DonationRecord();
        record.id = id;
        record.source = RegistrationStore.DONATION_SOURCE_TRADEMC;
        record.donorName = "Patron";
        record.amountKopecks = 365_00L;
        record.status = RegistrationStore.DonationStatus.PENDING_REVIEW.name();
        record.note = "shop:rank-founder:ниже текущей ступени 5";
        record.createdAt = NOW;
        record.detectedAt = NOW;
        store.recordDonation(record);

        RegistrationStore reloaded = store();
        List<RegistrationStore.DonationRecord> pending = reloaded.listDonations(null, 0).stream()
                .filter(r -> r.donationStatus().isPending()).toList();
        assertEquals(1, pending.size(), "после перезапуска платёж на месте");
        assertEquals("Patron", pending.get(0).donorName);
    }
}
