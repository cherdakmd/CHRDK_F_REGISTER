package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Лестница донатных рангов (1.4.167): миграция с проходки, пересчёт остатка
 * при повышении и сроки. Тесты чистые — сервер не поднимается.
 */
class RankLadderTest {

    private static final long DAY = 86_400_000L;

    private static RegistrationStore store(Path dir) {
        return new RegistrationStore(dir, Logger.getAnonymousLogger());
    }

    @Test
    void lifetimePaymentsMapToPermanentRank() {
        assertEquals(0, RankLadder.levelForLifetime(0L), "не платил — без ранга");
        assertEquals(1, RankLadder.levelForLifetime(150_00L), "150 ₽ — Странник");
        assertEquals(2, RankLadder.levelForLifetime(400_00L), "400 ₽ — Следопыт");
        assertEquals(2, RankLadder.levelForLifetime(1_399_00L), "до 1400 ₽ — Следопыт");
        assertEquals(3, RankLadder.levelForLifetime(1_400_00L), "1400 ₽ — Хранитель");
        assertEquals(3, RankLadder.levelForLifetime(9_999_00L), "выше порога — всё равно Хранитель");
    }

    @Test
    void upgradeCarriesRemainingDaysByPrice() {
        // 200 дней Следопыта (199 ₽) при повышении до Мецената (599 ₽): 200*199/599 = 66.
        assertEquals(66, RankLadder.carryDays(200, 199, 599));
        // Остаток дороже нового ранга не даёт «лишних» дней: не больше, чем осталось.
        assertEquals(0, RankLadder.carryDays(10, 0, 599), "без цены — без переноса");
        assertEquals(0, RankLadder.carryDays(0, 199, 599));
        assertEquals(0, RankLadder.carryDays(30, 999, 599), "понижения не бывает");
    }

    @Test
    void upgradeDaysRespectTotalCap() {
        assertEquals(365, RankLadder.upgradeDays(300, 365, 199, 599, 365));
        assertEquals(96, RankLadder.upgradeDays(30, 200, 199, 599, 365));
        assertEquals(1, RankLadder.clampLevel(0));
        assertEquals(5, RankLadder.clampLevel(99));
    }

    @Test
    void migrationGivesPermanentRankToPayersAndKeepsTrialDays(@TempDir Path dir) {
        long now = 1_700_000_000_000L;
        RegistrationStore store = store(dir);

        store.grantPassManual("Paid", now, 30 * DAY, "donatepay");
        store.addPayment("Paid", 1_400_00L, now);

        store.grantPassManual("Small", now, 30 * DAY, "donatepay");
        store.addPayment("Small", 150_00L, now);

        store.grantPassManual("Trial", now, 1 * DAY, "trial");

        int migrated = store.migrateRanks(now);
        assertEquals(3, migrated, "трое получили ранг");
        assertEquals(3, store.rankLevel("Paid", now), "1400 ₽ — Хранитель");
        assertEquals(0L, store.rankUntil("Paid"), "постоянный ранг без срока");
        assertEquals(1, store.rankLevel("Small", now), "150 ₽ — Странник");
        assertEquals(1, store.rankLevel("Trial", now), "триал — первая ступень");
        assertTrue(store.rankUntil("Trial") > now, "срок триала сохранён");

        assertEquals(0, store.migrateRanks(now), "миграция одноразовая");
    }

    @Test
    void permanentRankIgnoresDaysAndRevokeClearsIt(@TempDir Path dir) {
        long now = 1_700_000_000_000L;
        RegistrationStore store = store(dir);

        store.grantRankPermanent("Founder", now, 5, "manual");
        assertEquals(5, store.rankLevel("Founder", now + 3650 * DAY));
        assertEquals(0L, store.extendRankDays("Founder", now, 10, "quests"),
                "постоянный ранг не продлевается днями");
        assertEquals(0L, store.rankUntil("Founder"));

        assertTrue(store.revokeRank("Founder"));
        assertEquals(0, store.rankLevel("Founder", now));
        assertFalse(store.revokeRank("Founder"), "повторный отзыв — false");
    }

    @Test
    void timedRankExpiresAndDaysExtendIt(@TempDir Path dir) {
        long now = 1_700_000_000_000L;
        RegistrationStore store = store(dir);

        store.grantRankUntil("Keeper", now, 3, now + 10 * DAY, "test");
        assertEquals(3, store.rankLevel("Keeper", now + 9 * DAY));
        assertEquals(0, store.rankLevel("Keeper", now + 11 * DAY), "срок вышел");

        long until = store.extendRankDays("Keeper", now, 5, "quests");
        assertEquals(now + 15 * DAY, until, "дни добавились к текущему сроку");
        assertEquals(3, store.rankLevel("Keeper", now + 14 * DAY));

        // Дни без ранга открывают первую ступень.
        long fresh = store.extendRankDays("Newbie", now, 2, "daily");
        assertEquals(now + 2 * DAY, fresh);
        assertEquals(1, store.rankLevel("Newbie", now + DAY));
    }
}
