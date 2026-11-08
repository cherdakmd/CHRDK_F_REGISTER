package ru.cherdakmd.diamondshop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Три типа акций, потолки и анти-арбитражный контракт. */
class ShopPromoServiceTest {

    @TempDir
    Path dir;

    private static final List<String> POOL = List.of("DIRT", "STONE", "SAND", "COAL", "WHEAT");
    private static final UUID STEVE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private ShopPromoConfig.Settings settings() {
        return new ShopPromoConfig.Settings(true, POOL, 3, 25, 64, 12, 3, 16, 32, 128);
    }

    private ShopPromoService service(ShopPromoConfig.Settings settings) {
        ShopPromoStore store = new ShopPromoStore(dir.resolve("promos-" + System.nanoTime()),
                Logger.getAnonymousLogger());
        store.load();
        return new ShopPromoService(settings, store);
    }

    private long nowFor(ShopPromoMath.Type type) {
        for (long day = 1; day < 500; day++) {
            if (ShopPromoMath.typeFor(day) == type) {
                return day * ShopPromoMath.DAY_MILLIS + 1000L;
            }
        }
        throw new IllegalStateException("type not found");
    }

    private ShopConfig.ShopItem item(String material, boolean sellEnabled) {
        return new ShopConfig.ShopItem(material, "blocks", 0.05, 20000, 0.5, 2.0, sellEnabled);
    }

    @Test
    void hotBuybackPaysPercentButNeverAboveThePurchasePrice() {
        ShopPromoService promos = service(settings());
        long now = nowFor(ShopPromoMath.Type.HOT_BUYBACK);
        assertEquals(ShopPromoMath.Type.HOT_BUYBACK, promos.plan(now).type());
        ShopConfig.ShopItem featured = item(promos.plan(now).featured().get(0), true);

        assertEquals(25, promos.quote(STEVE, featured, 100, 200, 64, 1000, now).amount());
        assertEquals(0, promos.quote(STEVE, featured, 100, 100, 64, 1000, now).amount(),
                "выплата не может сравняться с ценой покупки и дать арбитраж");
        assertEquals(0, promos.quote(STEVE, item("NOT_IN_POOL", true), 100, 200, 64, 1000, now).amount());
        assertEquals(0, promos.quote(STEVE, featured, 100, 200, 64, 0, now).amount(),
                "нет места в дневном обороте — бонуса нет");
        assertEquals(0, promos.quote(STEVE, item(featured.material(), false), 100, 200, 64, 1000, now).amount());
    }

    @Test
    void dailyPlayerCapIsEnforcedAcrossTrades() {
        ShopPromoService promos = service(settings());
        long now = nowFor(ShopPromoMath.Type.HOT_BUYBACK);
        ShopConfig.ShopItem featured = item(promos.plan(now).featured().get(0), true);
        assertEquals(128, promos.remaining(STEVE, now));

        // 25 % от 200 = 50, но потолок на сделку режет до 32.
        var first = promos.quote(STEVE, featured, 200, 1000, 64, 1000, now);
        assertEquals(32, first.amount(), "срабатывает потолок на сделку");
        promos.record(STEVE, featured.material(), 64, first, now);
        assertEquals(96, promos.remaining(STEVE, now));

        // Исчерпываем суточный лимит бонуса и убеждаемся, что дальше акция молчит.
        promos.record(STEVE, featured.material(), 1, new ShopPromoService.Bonus(96, false, false), now);
        assertEquals(0, promos.remaining(STEVE, now));
        assertEquals(0, promos.quote(STEVE, featured, 100, 1000, 64, 1000, now).amount());
    }

    @Test
    void contractPaysOnceOnlyForTargetAndRequiredVolume() {
        ShopPromoService promos = service(settings());
        long now = nowFor(ShopPromoMath.Type.CONTRACT);
        List<String> featured = promos.plan(now).featured();
        ShopConfig.ShopItem target = item(featured.get(0), true);

        assertEquals(0, promos.quote(STEVE, target, 100, 1000, 1, 1000, now).amount());
        assertEquals(0, promos.quote(STEVE, item(featured.get(1), true), 100, 1000, 64, 1000, now).amount());
        var bonus = promos.quote(STEVE, target, 100, 1000, 64, 1000, now);
        assertEquals(12, bonus.amount());
        assertTrue(bonus.contractPaid());
        promos.record(STEVE, target.material(), 64, bonus, now);
        assertEquals(0, promos.quote(STEVE, target, 100, 1000, 64, 1000, now).amount(),
                "разовая награда за заказ не повторяется");
    }

    @Test
    void seriesNeedsSeveralDifferentFeaturedGoods() {
        ShopPromoService promos = service(settings());
        long now = nowFor(ShopPromoMath.Type.SERIES);
        List<String> featured = promos.plan(now).featured();
        assertEquals(0, promos.quote(STEVE, item(featured.get(0), true), 100, 1000, 16, 1000, now).amount());

        // Две разные сдачи того же дня фиксируются без бонуса.
        promos.record(STEVE, featured.get(0), 16, ShopPromoService.Bonus.none(), now);
        promos.record(STEVE, featured.get(1), 16, ShopPromoService.Bonus.none(), now);
        var bonus = promos.quote(STEVE, item(featured.get(2), true), 100, 1000, 16, 1000, now);
        assertEquals(16, bonus.amount());
        assertTrue(bonus.seriesPaid());
        promos.record(STEVE, featured.get(2), 16, bonus, now);
        assertEquals(0, promos.quote(STEVE, item(featured.get(0), true), 100, 1000, 16, 1000, now).amount());
    }

    @Test
    void disabledSettingsNeverPay() {
        ShopPromoService promos = service(ShopPromoConfig.Settings.disabled());
        long now = nowFor(ShopPromoMath.Type.HOT_BUYBACK);
        assertFalse(promos.enabled());
        assertEquals(0, promos.remaining(STEVE, now));
        assertEquals(0, promos.quote(STEVE, item("DIRT", true), 100, 1000, 64, 1000, now).amount());
    }
}
