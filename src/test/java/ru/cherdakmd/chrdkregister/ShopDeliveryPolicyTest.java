package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShopDeliveryPolicyTest {
    @org.junit.jupiter.api.io.TempDir Path temp;
    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = ShopEntitlements.DAY_MILLIS;

    @Test void fullPaidTermMustFitDownToTheMillisecond() {
        assertTrue(ShopDeliveryPolicy.fits(NOW, 0, 365, 365));
        assertTrue(ShopDeliveryPolicy.fits(NOW, NOW + 335 * DAY, 30, 365));
        assertFalse(ShopDeliveryPolicy.fits(NOW, NOW + 335 * DAY + 1, 30, 365));
        assertFalse(ShopDeliveryPolicy.fits(NOW, NOW + 360 * DAY, 30, 365));
        assertFalse(ShopDeliveryPolicy.fits(NOW, NOW + DAY, 365, 365));
        assertFalse(ShopDeliveryPolicy.fits(NOW, NOW + 400 * DAY, 1, 365));
        assertTrue(ShopDeliveryPolicy.fits(NOW, NOW - DAY, 365, 365));
        assertFalse(ShopDeliveryPolicy.fits(NOW, 0, 0, 365));
    }

    private RegistrationStore.PlayerAccount account(int permanent, int temporary, int days) {
        var account = new RegistrationStore.PlayerAccount();
        account.rankPermanent = permanent > 0;
        account.rankLevel = permanent > 0 ? permanent : temporary;
        account.tempRankLevel = permanent > 0 ? temporary : 0;
        account.passUntil = NOW + days * DAY;
        return account;
    }

    @Test void permanentBaseDoesNotEraseTemporaryRenewal() {
        var player = account(1, 5, 10);
        var plan = ShopDeliveryPolicy.rankPlan(player, 5, 30, 0, NOW, 365, 365, 365);
        assertEquals(ShopService.Status.GRANTED, plan.status());
        assertEquals(NOW + 40 * DAY, plan.until());
        assertEquals(NOW + 10 * DAY, player.passUntil, "Планирование не меняет аккаунт");
    }

    @Test void applyingRenewalPreservesPermanentFallback() {
        var store = new RegistrationStore(temp, java.util.logging.Logger.getAnonymousLogger());
        store.grantRankPermanent("Buyer", NOW, 1, "legacy-paid");
        store.grantRankUntil("Buyer", NOW, 5, NOW + 10 * DAY, "paid");
        var plan = ShopDeliveryPolicy.rankPlan(store.getAccount("Buyer").orElseThrow(), 5, 30, 0,
                NOW, 365, 365, 365);
        store.grantRankUntil("Buyer", NOW, 5, plan.until(), "shop:rank-founder");
        assertEquals(5, store.rankLevel("Buyer", NOW + 39 * DAY));
        assertEquals(1, store.rankLevel("Buyer", NOW + 40 * DAY));
        assertEquals(NOW + 40 * DAY, store.rankUntil("Buyer"));
    }

    @Test void permanentBaseCannotEnableDowngradeOfTemporaryRank() {
        var plan = ShopDeliveryPolicy.rankPlan(account(1, 5, 10), 3, 30, 0, NOW, 365, 229, 365);
        assertEquals(ShopService.Status.RANK_HIGHER, plan.status());
        assertEquals(0, plan.until());
        assertEquals(ShopService.Status.RANK_HIGHER,
                ShopDeliveryPolicy.rankPlan(account(3, 0, -1), 3, 365, 0, NOW, 229, 229, 365).status());
    }

    @Test void upgradePreservesPricedTemporaryRemainderOnPermanentBase() {
        var plan = ShopDeliveryPolicy.rankPlan(account(1, 2, 30), 3, 30, 0, NOW, 159, 229, 365);
        int carried = RankLadder.carryDays(30, 159, 229);
        assertEquals(ShopService.Status.GRANTED, plan.status());
        assertEquals(NOW + (30 + carried) * DAY, plan.until());
        assertEquals(ShopService.Status.TIME_LIMIT,
                ShopDeliveryPolicy.rankPlan(account(1, 2, 30), 3, 365, 0, NOW, 159, 229, 365).status());
    }

    @Test void annualNewRankAndExpiredTemporaryRankWorkButRenewalCannotLosePaidDays() {
        assertEquals(NOW + 365 * DAY,
                ShopDeliveryPolicy.rankPlan(null, 5, 365, 0, NOW, 0, 365, 365).until());
        assertEquals(NOW + 30 * DAY,
                ShopDeliveryPolicy.rankPlan(account(1, 5, -1), 3, 30, 0, NOW, 99, 229, 365).until());
        assertEquals(ShopService.Status.TIME_LIMIT,
                ShopDeliveryPolicy.rankPlan(account(0, 5, 350), 5, 30, 0, NOW, 365, 365, 365).status());
    }

    @Test void onlyConditionalBonusMayBeLimitedByHorizon() {
        var plan = ShopDeliveryPolicy.rankPlan(account(0, 3, 335), 3, 30, 5, NOW, 229, 229, 365);
        assertEquals(ShopService.Status.GRANTED, plan.status());
        assertEquals(NOW + 365 * DAY, plan.until(), "Все 30 оплаченных дней сохранены, бонус сверх 365 не обещан");
        assertEquals(0, ShopDeliveryPolicy.bonusDays(plan.until(), NOW + 365 * DAY));
        assertEquals(5, ShopDeliveryPolicy.bonusDays(NOW + 35 * DAY, NOW + 30 * DAY));
        assertEquals(0, ShopDeliveryPolicy.bonusDays(NOW + 30 * DAY + DAY / 2, NOW + 30 * DAY));
    }

    private Map<String, Object> item(String id) {
        return Map.of("id", id, "name", id, "price", 39, "days", 30);
    }

    private Map<String, Object> bundle(String id, String... includes) {
        return Map.of("id", id, "name", id, "price", 199, "days", 30, "includes", List.of(includes));
    }

    @Test void validNestedBundlePassesButMissingLeafCannotBePartiallySold() {
        var valid = ShopCatalog.parse(true, 365, List.of(item("flame"), item("hearts"),
                bundle("trails", "flame", "hearts"), bundle("look", "trails")));
        assertTrue(ShopDeliveryPolicy.complete(valid.find("look").orElseThrow(), valid));
        var broken = ShopCatalog.parse(true, 365, List.of(item("flame"), bundle("trails", "flame", "missing")));
        assertFalse(ShopDeliveryPolicy.complete(broken.find("trails").orElseThrow(), broken));
        var empty = ShopCatalog.parse(true, 365, List.of(bundle("trails", "missing")));
        assertFalse(ShopDeliveryPolicy.complete(empty.find("trails").orElseThrow(), empty));
    }

    @Test void cyclesAndRankLeavesAreNotFulfillableBundles() {
        var cycle = ShopCatalog.parse(true, 365, List.of(item("flame"),
                bundle("a", "flame", "b"), bundle("b", "a")));
        assertFalse(ShopDeliveryPolicy.complete(cycle.find("a").orElseThrow(), cycle));
        var rank = Map.<String, Object>of("id", "rank", "name", "rank", "price", 99, "days", 30, "rank", 1);
        var catalog = ShopCatalog.parse(true, 365, List.of(rank, bundle("mixed", "rank")));
        assertFalse(ShopDeliveryPolicy.complete(catalog.find("mixed").orElseThrow(), catalog));
    }

    @Test void allThreeCommandPathsUseOnePreflightAndOneReviewQueue() throws Exception {
        String service = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/ShopService.java"));
        assertTrue(service.indexOf("ShopDeliveryPolicy.complete") < service.indexOf("until = product.isBundle()"));
        assertTrue(service.indexOf("!fitsProduct(canonical, part") < service.indexOf("until = product.isBundle()"));
        assertTrue(service.contains("!AccessManager.isValidMinecraftName(playerName)"));
        assertFalse(AccessManager.isValidMinecraftName("%username%"));
        assertTrue(AccessManager.isValidMinecraftName("Player_123"));
        assertTrue(service.contains("queueRejected(result.playerName(), paidProduct, result.status())"));
        assertTrue(service.contains("\"homes-plus\".equals(product.id()) ? 0"), "Каждый пакет домов начинает собственный срок");
        String handler = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/RegisterCommandHandler.java"));
        assertFalse(handler.contains("rejectLowerRank("), "shoppay не должен создавать вторую запись pending");
        assertEquals(3, handler.split("case RANK_HIGHER, TIME_LIMIT, INVALID_BUNDLE, INVALID_RECIPIENT", -1).length - 1);
    }
}
