package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Повторная оплата при активной проходке складывается с остатком; платёжный код ловит владельца. */
class DonationStackingTest {

    @Test
    void repeatedDonationStacksOnActivePass(@TempDir Path tempDir) {
        RegistrationStore store = new RegistrationStore(tempDir, Logger.getAnonymousLogger());
        long now = 1_700_000_000_000L;
        long thirtyDays = Duration.ofDays(30).toMillis();

        var first = store.grantPassFromDonation("CherdakPlayer", now, now, thirtyDays, false);
        assertTrue(first.isPresent());
        assertEquals(now + thirtyDays, first.get().passUntil, "первая выдача — 30 дней");

        // вторая оплата через 5 дней активной проходки — должна ПРИБАВИТЬ 30 дней сверху
        long fiveDaysLater = now + Duration.ofDays(5).toMillis();
        var second = store.grantPassFromDonation("CherdakPlayer", fiveDaysLater, fiveDaysLater,
                thirtyDays, false);
        assertTrue(second.isPresent());
        assertEquals(now + thirtyDays + thirtyDays, second.get().passUntil,
                "стак: дни второй оплаты плюсуются к остатку, а не перезаписывают его");

        // и ручное продление из pending-очереди тоже складывается
        var manual = store.grantPassManual("CherdakPlayer", fiveDaysLater, thirtyDays,
                "donatepay:#12345");
        assertEquals(now + thirtyDays * 3, manual.passUntil, "approve из журнала продлевает, а не сбрасывает");

        assertTrue(store.hasActivePass("CherdakPlayer", now + thirtyDays + thirtyDays - 1));
    }

    @Test
    void paymentCodeFindsOwner(@TempDir Path tempDir) {
        RegistrationStore store = new RegistrationStore(tempDir, Logger.getAnonymousLogger());
        long now = 1_700_000_000_000L;
        store.grantPassFromDonation("CherdakPlayer", now, now, Duration.ofDays(30).toMillis(), false);

        String code = store.getOrCreatePaymentCode("CherdakPlayer", () -> "4821");
        assertEquals("4821", code);

        assertEquals(Optional.of("CherdakPlayer"), store.findPlayerByPaymentCode("CherdakPlayer 4821"),
                "«Ник код» находит владельца");
        assertEquals(Optional.of("CherdakPlayer"), store.findPlayerByPaymentCode("cherdakplayer4821"),
                "слитное написание тоже принимается");
        assertTrue(store.findPlayerByPaymentCode("CherdakPlayer").isEmpty(), "без кода — нет сверки");
        assertTrue(store.findPlayerByPaymentCode("Stranger 4822").isEmpty(), "чужой код — нет сверки");
        assertTrue(store.findPlayerByPaymentCode("").isEmpty());

        assertEquals(Optional.of("CherdakPlayer"),
                DonationService.matchByPaymentCode(true, store, "CherdakPlayer 4821"),
                "хелпер сервиса работает при включённой функции");
        assertTrue(DonationService.matchByPaymentCode(false, store, "CherdakPlayer 4821").isEmpty(),
                "при выключенных кодах хелпер молчит");
    }
}
