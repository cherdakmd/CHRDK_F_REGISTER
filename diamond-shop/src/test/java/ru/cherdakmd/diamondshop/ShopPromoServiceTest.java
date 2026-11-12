package ru.cherdakmd.diamondshop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Ежедневная горячая скупка, окно 6 часов, лимит 5000 и анти-арбитраж. */
class ShopPromoServiceTest {

    @TempDir
    Path dir;

    private static final List<String> POOL = List.of(
            "DIRT", "STONE", "SAND", "COAL", "WHEAT", "GRAVEL", "REDSTONE",
            "OAK_LOG", "IRON_INGOT", "GOLD_INGOT", "SPRUCE_LOG", "RAW_IRON", "COBBLESTONE");
    private static final UUID STEVE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private ShopPromoConfig.Settings settings() {
        return new ShopPromoConfig.Settings(true, POOL, 5, 50, 64, 0, 3, 0, 1000, 5000);
    }

    private ShopPromoService service(ShopPromoConfig.Settings settings) {
        ShopPromoStore store = new ShopPromoStore(dir.resolve("promos-" + System.nanoTime()),
                Logger.getAnonymousLogger());
        store.load();
        return new ShopPromoService(settings, store);
    }

    private ShopConfig.ShopItem item(String material, boolean sellEnabled) {
        return new ShopConfig.ShopItem(material, "blocks", 0.05, 20000, 0.5, 2.0, sellEnabled);
    }

    @Test
    void hotBuybackPaysFiftyPercentButNeverAbovePurchasePrice() {
        ShopPromoService promos = service(settings());
        long now = ShopPromoMath.DAY_MILLIS + 1000;
        assertEquals(ShopPromoMath.Type.HOT_BUYBACK, promos.plan(now).type());
        assertEquals(5, promos.plan(now).featured().size());
        ShopConfig.ShopItem featured = item(promos.plan(now).featured().get(0), true);

        assertEquals(50, promos.quote(STEVE, featured, 100, 200, 64, 1000, now).amount());
        assertEquals(0, promos.quote(STEVE, featured, 100, 100, 64, 1000, now).amount());
        assertEquals(0, promos.quote(STEVE, item("NOT_IN_POOL", true), 100, 200, 64, 1000, now).amount());
        assertEquals(0, promos.quote(STEVE, featured, 100, 200, 64, 0, now).amount());
        assertEquals(0, promos.quote(STEVE, item(featured.material(), false), 100, 200, 64, 1000, now).amount());
    }

    @Test
    void fiveNewResourcesAppearAfterSixHours() {
        ShopPromoService promos = service(settings());
        long now = ShopPromoMath.DAY_MILLIS + 1000;
        var current = promos.plan(now).featured();
        var next = promos.plan(now + ShopPromoMath.WINDOW_MILLIS).featured();
        assertEquals(5, next.size());
        assertTrue(current.stream().noneMatch(next::contains));
        assertEquals(0, promos.quote(STEVE, item(current.get(0), true), 100, 200,
                64, 1000, now + ShopPromoMath.WINDOW_MILLIS).amount());
        assertEquals(50, promos.quote(STEVE, item(next.get(0), true), 100, 200,
                64, 1000, now + ShopPromoMath.WINDOW_MILLIS).amount());
    }

    @Test
    void dailyPlayerCapSurvivesWindowChangesAndResetsOnlyNextUtcDay() {
        ShopPromoService promos = service(settings());
        long now = ShopPromoMath.DAY_MILLIS + 1000;
        ShopConfig.ShopItem featured = item(promos.plan(now).featured().get(0), true);
        assertEquals(500000, promos.remaining(STEVE, now));

        var first = promos.quote(STEVE, featured, 2_000_000, 10_000_000, 64, 100_000_000, now);
        assertEquals(100000, first.amount(), "потолок тестовой сделки 1000 ⛁");
        assertTrue(promos.reserve(STEVE, first, now));
        assertEquals(400000, promos.remaining(STEVE, now), "лимит расходуется до WAL, а не после него");
        promos.record(STEVE, featured.material(), 64, first, now);
        assertEquals(400000, promos.remaining(STEVE, now), "commit не должен списать бонус повторно");
        var rest = new ShopPromoService.Bonus(400000, false, false);
        assertTrue(promos.reserve(STEVE, rest, now));
        promos.record(STEVE, featured.material(), 1, rest, now);

        long nextWindow = now + ShopPromoMath.WINDOW_MILLIS;
        assertEquals(0, promos.remaining(STEVE, nextWindow));
        assertEquals(0, promos.quote(STEVE, item(promos.plan(nextWindow).featured().get(0), true),
                100, 1000, 64, 1000, nextWindow).amount());
        assertEquals(500000, promos.remaining(STEVE, now + ShopPromoMath.DAY_MILLIS));
    }

    @Test
    void rejectedTradeReleasesOnlyItsReservation() {
        ShopPromoService promos = service(settings());
        long now = ShopPromoMath.DAY_MILLIS + 1000;
        var bonus = new ShopPromoService.Bonus(100000, false, false);
        assertTrue(promos.reserve(STEVE, bonus, now));
        assertEquals(400000, promos.remaining(STEVE, now));
        promos.release(STEVE, bonus, now);
        assertEquals(500000, promos.remaining(STEVE, now));
    }

    @Test
    void unwritableProgressFailsBonusClosedWithoutChargingPlayer() throws Exception {
        Path notAFolder = Files.writeString(dir.resolve("not-a-folder"), "x");
        ShopPromoStore store = new ShopPromoStore(notAFolder, Logger.getAnonymousLogger());
        store.load();
        ShopPromoService promos = new ShopPromoService(settings(), store);
        long now = ShopPromoMath.DAY_MILLIS + 1000;
        var bonus = new ShopPromoService.Bonus(100000, false, false);
        assertFalse(promos.reserve(STEVE, bonus, now));
        assertEquals(500000, promos.remaining(STEVE, now));
    }

    @Test
    void disabledSettingsNeverPay() {
        ShopPromoService promos = service(ShopPromoConfig.Settings.disabled());
        long now = ShopPromoMath.DAY_MILLIS + 1000;
        assertFalse(promos.enabled());
        assertEquals(0, promos.remaining(STEVE, now));
        assertEquals(0, promos.quote(STEVE, item("DIRT", true), 100, 1000, 64, 1000, now).amount());
    }
}
