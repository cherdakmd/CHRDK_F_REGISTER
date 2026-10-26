package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class RewardBoostTest {
    @TempDir Path dir;
    private static final long NOW = 1_800_000_000_000L;
    private RegistrationStore store() { return new RegistrationStore(dir, Logger.getAnonymousLogger()); }

    @Test void gameplayNeverTouchesAnyPaidStateIncludingPermanentRanks() {
        var store = store();
        store.grantRankPermanent("Paid", NOW, 5, "shop:founder");
        store.grantRankUntil("Paid", NOW, 5, NOW + 86_400_000L, "shop:founder");
        store.grantEntitlement("Paid", "nick-aurora", NOW, 30, 365);
        store.addPayment("Paid", 365_00L, NOW);
        var before = store.getAccount("Paid").orElseThrow();
        store.grantPassHours("Paid", NOW, 8, "achievements:test");
        var after = store.getAccount("Paid").orElseThrow();
        assertEquals(8 * RewardBoostMath.HOUR, after.rewardBoostMillis);
        assertEquals(before.passUntil, after.passUntil);
        assertEquals(before.rankLevel, after.rankLevel);
        assertEquals(5, after.rankLevel, "snapshot must not erase purchased rank");
        assertEquals(before.rankPermanent, after.rankPermanent);
        assertEquals(before.tempRankLevel, after.tempRankLevel);
        assertEquals(before.lastGrantSource, after.lastGrantSource);
        assertEquals(before.lastGrantAt, after.lastGrantAt);
        assertEquals(before.paymentsCount, after.paymentsCount);
        assertEquals(before.totalPaidKopecks, after.totalPaidKopecks);
        assertEquals(before.entitlements, after.entitlements);
    }

    @Test void newPlayerGetsOnlySeparateBoostAndBankIsBounded() {
        var store = store();
        assertEquals(3 * RewardBoostMath.HOUR, store.grantRewardBoost("Free", 3, 24));
        assertEquals(24 * RewardBoostMath.HOUR, store.grantRewardBoost("Free", Integer.MAX_VALUE, 24));
        assertEquals(24 * RewardBoostMath.HOUR, store.grantRewardBoost("Free", 1, 24));
        assertFalse(store.hasActivePass("Free", NOW));
        assertEquals(0, store.rankLevel("Free", NOW));
        assertEquals(0, store.getAccount("Free").orElseThrow().paymentsCount);
        assertTrue(store.getAccount("Free").orElseThrow().entitlements.isEmpty());
    }

    @Test void remainingAndFractionsSurviveOfflineAndRestart() {
        var store = store();
        store.grantRewardBoost("Free", 1, 24);
        store.consumeRewardBoost("Free", 1234);
        for (int i = 0; i < 9; i++) assertEquals(1, store.boostExperience("Free", "skills", 1, 1));
        store.flushBlocking();
        var reloaded = store();
        assertEquals(RewardBoostMath.HOUR - 1234, reloaded.rewardBoostRemaining("Free"));
        assertEquals(2, reloaded.boostExperience("Free", "skills", 1, 1), "small XP must not lose the 10 percent bonus");
        assertEquals(1, reloaded.boostExperience("Free", "professions", 1, 1), "channels have separate fractions");
        assertEquals(10, reloaded.boostExperience("Free", "unknown", 10, 10));
        assertEquals(RewardBoostMath.HOUR - 1234, reloaded.rewardBoostRemaining("Free"), "XP award does not spend offline wall time");
    }

    @Test void exhaustedOrAbsentBoostDoesNotScaleExperience() {
        var store = store();
        assertEquals(100, store.boostExperience("Free", "skills", 100, 100));
        store.grantRewardBoost("Free", 1, 24);
        store.consumeRewardBoost("Free", Long.MAX_VALUE);
        assertEquals(0, store.rewardBoostRemaining("Free"));
        assertEquals(100, store.boostExperience("Free", "skills", 100, 100));
        assertEquals(0, store.boostExperience("Free", "skills", 100, 0));
    }

    @Test void additiveBonusAndCeilingsDoNotBecomeMultiplicative() {
        assertEquals(110, RewardBoostMath.experience(100, 100, 0).amount());
        assertEquals(135, RewardBoostMath.experience(100, 125, 0).amount());
        assertEquals(150, RewardBoostMath.experience(100, 145, 0).amount());
        assertEquals(150, RewardBoostMath.experience(100, 150, 0).amount());
        assertEquals(150, RewardBoostMath.experience(100, 190, 0).amount());
        assertEquals(150, RewardBoostMath.limit(100, 190));
        assertEquals(0, RewardBoostMath.experience(100, 0, .9).amount());
        assertTrue(RewardBoostMath.experience(Long.MAX_VALUE, Long.MAX_VALUE - 1, 0).amount() > 0);
    }

    @Test void repeatedRewardAddsTimeNotStrengthAndHandlesInvalidInput() {
        assertEquals(24 * RewardBoostMath.HOUR, RewardBoostMath.grant(Long.MAX_VALUE, Integer.MAX_VALUE, 24));
        assertEquals(RewardBoostMath.HOUR, RewardBoostMath.grant(-100, 1, 24));
        assertEquals(0, RewardBoostMath.grant(0, -1, 24));
        var store = store();
        store.grantRewardBoost("Free", 2, 24);
        store.grantRewardBoost("Free", 2, 24);
        assertEquals(110, store.boostExperience("Free", "vanilla", 100, 100));
        assertEquals(4 * RewardBoostMath.HOUR, store.rewardBoostRemaining("Free"));
        assertEquals(0, store.grantRewardBoost("../bad", 10, 24));
    }

    @Test void paymentReferenceIsPersistentAndCannotBeReplayedAcrossCommands() {
        var store = store();
        assertTrue(store.reservePayment("trademc:123", "RCON", "shopbuy Free nick-aurora", NOW));
        assertFalse(store.reservePayment("trademc:123", "Console", "shoppay Free 99", NOW));
        assertFalse(store().reservePayment("trademc:123", "RCON", "shopgift Other", NOW));
        store.finishPayment("trademc:123", "GRANTED");
        assertFalse(store().reservePayment("trademc:123", "Console", "repeat", NOW));
        assertFalse(store.reservePayment("", "RCON", "x", NOW));
        assertFalse(store.reservePayment("../../bad", "RCON", "x", NOW));
        assertTrue(store().paymentReceipts().get("trademc:123").contains("GRANTED"));
    }

    @Test void failedDurableReservationNeverAuthorizesPaidFulfillment() throws Exception {
        Path blocked = dir.resolve("file-not-folder");
        Files.writeString(blocked, "not a directory");
        var store = new RegistrationStore(blocked, Logger.getAnonymousLogger());
        assertFalse(store.reservePayment("trademc:123", "Console", "shopbuy Free product", NOW));
    }

    @Test void publicPaidGrantApisRejectEvenForgedPaymentSource() {
        var api = new ChrdkRegisterApiImpl(null, null, null, null, null, null, null);
        assertDoesNotThrow(() -> api.grantRank("Free", 5, 365, "shop:paid"));
        assertDoesNotThrow(() -> api.grantRankPermanent("Free", 5, "donatepay:123"));
        assertDoesNotThrow(() -> api.grantPass("Free", 365, "admin"));
        assertEquals(0, api.grantEntitlement("Free", "nick-aurora", 365, "trademc:123"));
        var shop = new ShopService(null, null, null, Logger.getAnonymousLogger());
        assertEquals(0, shop.grantInternal("Free", "nick-aurora", 365, "shop:fake"));
        assertFalse(shop.grant("Free", "nick-aurora", 30, (PaymentConfirmation) null).granted());
    }

    @Test void auditIsReadOnlyAndDoesNotLeakAuthenticationData() {
        var store = store();
        store.registerPassword("Paid", UUID.randomUUID(), "StrongPassword123", "192.0.2.42", NOW);
        store.grantPassManual("Paid", NOW, RewardBoostMath.HOUR, "achievements:legacy");
        String report = PaidAuditReport.render(store.getAllAccounts(), Map.of(), NOW);
        assertTrue(report.contains("READ_ONLY_NO_REVOCATION"));
        assertTrue(report.contains("achievements:legacy"));
        for (String secret : List.of("passwordHash", "StrongPassword123", "totpSecret", "192.0.2.42", "lastAuthIp")) assertFalse(report.contains(secret));
        assertEquals(NOW + RewardBoostMath.HOUR, store.getAccount("Paid").orElseThrow().passUntil);
    }

    @Test void newApiHasBackwardCompatibleDefaults() throws Exception {
        var type = ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi.class;
        assertEquals(12, ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi.API_VERSION);
        assertTrue(type.getMethod("grantRewardBoostHours", String.class, int.class, String.class).isDefault());
        assertTrue(type.getMethod("rewardBoostRemainingMillis", String.class).isDefault());
        assertTrue(type.getMethod("boostExperience", String.class, String.class, long.class, long.class).isDefault());
    }
}
