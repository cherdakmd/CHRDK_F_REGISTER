package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Разбор каталога товаров: валидные записи, терпимость к опечаткам и диагностика. */
class ShopCatalogTest {

    private static Map<String, Object> product(String id, Object price, Object days) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("name", "<gold>" + id + "</gold>");
        entry.put("price", price);
        entry.put("days", days);
        entry.put("description", "описание " + id);
        return entry;
    }

    @Test
    void parsesValidCatalog() {
        ShopCatalog catalog = ShopCatalog.parse(true, 365, List.of(
                product("vip-queue", 149, 30),
                product("homes-plus", 99, 30)));

        assertTrue(catalog.usable());
        assertEquals(2, catalog.products().size());
        assertEquals(365, catalog.maxTotalDays());
        assertTrue(catalog.problems().isEmpty(), () -> catalog.problems().toString());

        ShopProduct vip = catalog.find("VIP-QUEUE").orElseThrow();
        assertEquals(149, vip.priceRubles());
        assertEquals(30, vip.days());
        assertEquals("<gold>vip-queue</gold>", vip.name());
        assertFalse(vip.hasGroup());
        assertFalse(vip.hasTag());
    }

    @Test
    void readsGroupTagPermissionsAndCommands() {
        Map<String, Object> entry = product("homes-plus", 99, 30);
        entry.put("group", "homes");
        entry.put("tag", "<aqua>");
        entry.put("permissions", List.of("chrdktele.homes.plus"));
        entry.put("commands", List.of("broadcast %player% купил дома", "say ok"));

        ShopCatalog catalog = ShopCatalog.parse(true, 365, List.of(entry));
        ShopProduct homes = catalog.find("homes-plus").orElseThrow();

        assertTrue(homes.hasGroup());
        assertEquals("homes", homes.group());
        assertTrue(homes.hasTag());
        assertEquals(List.of("chrdktele.homes.plus"), homes.permissions());
        assertEquals(2, homes.commands().size());
    }

    @Test
    void skipsBrokenEntriesButKeepsGoodOnes() {
        Map<String, Object> noId = product("", 100, 30);
        Map<String, Object> badId = product("Vip Queue!", 100, 30);
        Map<String, Object> zeroPrice = product("free", 0, 30);
        Map<String, Object> zeroDays = product("forever", 100, 0);
        Map<String, Object> duplicate = product("vip-queue", 100, 30);

        ShopCatalog catalog = ShopCatalog.parse(true, 365, List.of(
                noId, badId, zeroPrice, zeroDays,
                product("vip-queue", 149, 30),
                duplicate));

        assertEquals(1, catalog.products().size(), "должен остаться только корректный товар");
        assertNotNull(catalog.find("vip-queue").orElse(null));
        assertNull(catalog.find("free").orElse(null));
        assertFalse(catalog.problems().isEmpty());
        assertTrue(catalog.problems().stream().anyMatch(problem -> problem.contains("повторный id")),
                () -> catalog.problems().toString());
    }

    @Test
    void trimsDaysToGlobalCap() {
        ShopCatalog catalog = ShopCatalog.parse(true, 30, List.of(product("year", 1000, 365)));
        ShopProduct year = catalog.find("year").orElseThrow();

        assertEquals(30, year.days(), "срок не может быть больше общего потолка");
        assertTrue(catalog.problems().stream().anyMatch(problem -> problem.contains("урезан")),
                () -> catalog.problems().toString());
    }

    @Test
    void dropsUnbalancedTagAndBadGroup() {
        Map<String, Object> entry = product("nick", 149, 30);
        entry.put("tag", "<gold");
        entry.put("group", "не группа!");

        ShopCatalog catalog = ShopCatalog.parse(true, 365, List.of(entry));
        ShopProduct nick = catalog.find("nick").orElseThrow();

        assertFalse(nick.hasTag());
        assertFalse(nick.hasGroup());
        assertEquals(2, catalog.problems().size(), () -> catalog.problems().toString());
    }

    @Test
    void emptyCatalogIsNotUsable() {
        ShopCatalog catalog = ShopCatalog.parse(true, 365, List.of());
        assertFalse(catalog.usable());
        assertTrue(catalog.isEmpty());
        assertTrue(catalog.problems().stream().anyMatch(problem -> problem.contains("каталог пуст")));
    }

    @Test
    void disabledCatalogKeepsProductsButIsNotUsable() {
        ShopCatalog catalog = ShopCatalog.parse(false, 365, List.of(product("vip-queue", 149, 30)));

        assertFalse(catalog.usable());
        assertEquals(1, catalog.products().size(), "выключенный каталог всё равно показывает витрину как данные");
        assertTrue(catalog.problems().isEmpty());
    }

    @Test
    void idAndGroupValidationIsCaseInsensitive() {
        Map<String, Object> entry = product("VIP-Queue", 149, 30);
        entry.put("group", "VIP");

        ShopCatalog catalog = ShopCatalog.parse(true, 365, List.of(entry));
        ShopProduct vip = catalog.find("vip-queue").orElseThrow();

        assertEquals("vip-queue", vip.id());
        assertEquals("VIP", vip.group(), "группа хранится как написана — LuckPerms регистронезависим");
    }

    @Test
    void balancedTagCheckHandlesGradient() {
        assertTrue(ShopCatalog.balanced("<gradient:#ffd700:#ff8800>"), "префикс закрывается автоматически");
        assertTrue(ShopCatalog.balanced("<bold><gold>Ник</gold></bold>"));
        assertTrue(ShopCatalog.balanced("<bold><gold>"), "незакрытые открывающие допустимы");
        assertFalse(ShopCatalog.balanced("<gold"), "обрыв тега");
        assertFalse(ShopCatalog.balanced("</gold>"), "закрывающий без открывающего");
        assertFalse(ShopCatalog.balanced(">"), "мусор вне тега");
    }

    /* ------------------------------ наборы ------------------------------ */

    private static Map<String, Object> bundle(String id, Object price, List<String> includes) {
        Map<String, Object> entry = product(id, price, 30);
        entry.put("includes", includes);
        return entry;
    }

    @Test
    void bundleKeepsDeclaredListAndResolvesLeaves() {
        ShopCatalog catalog = ShopCatalog.parse(true, 365, List.of(
                product("trail-flame", 59, 30),
                product("trail-hearts", 59, 30),
                bundle("trails-pack", 99, List.of("trail-flame", "trail-hearts"))));

        ShopProduct pack = catalog.find("trails-pack").orElseThrow();
        assertTrue(pack.isBundle());
        assertEquals(List.of("trail-flame", "trail-hearts"), pack.includes(), "витрина показывает заявленный состав");
        assertEquals(List.of("trail-flame", "trail-hearts"), pack.resolvedIncludes());
    }

    @Test
    void nestedBundleFlattensToLeaves() {
        ShopCatalog catalog = ShopCatalog.parse(true, 365, List.of(
                product("trail-flame", 59, 30),
                bundle("trails-pack", 49, List.of("trail-flame")),
                bundle("start-kit", 39, List.of("trails-pack"))));

        ShopProduct kit = catalog.find("start-kit").orElseThrow();
        assertEquals(List.of("trails-pack"), kit.includes(), "заявлено вложение набора");
        assertEquals(List.of("trail-flame"), kit.resolvedIncludes(), "выдаётся лист, а не набор");
    }

    @Test
    void bundleIgnoresUnknownSelfAndCyclicLinks() {
        ShopCatalog catalog = ShopCatalog.parse(true, 365, List.of(
                product("trail-flame", 59, 30),
                bundle("a", 10, List.of("ghost", "a", "b", "trail-flame")),
                bundle("b", 10, List.of("a"))));

        ShopProduct a = catalog.find("a").orElseThrow();
        assertEquals(List.of("trail-flame"), a.resolvedIncludes(),
                "неизвестный товар, ссылка на себя и цикл отброшены");
        assertTrue(catalog.problems().stream().anyMatch(line -> line.contains("неизвестный")),
                () -> catalog.problems().toString());
        assertTrue(catalog.problems().stream().anyMatch(line -> line.contains("сам себя")),
                () -> catalog.problems().toString());
    }

    @Test
    void bundleWithoutDiscountIsReported() {
        ShopCatalog catalog = ShopCatalog.parse(true, 365, List.of(
                product("trail-flame", 59, 30),
                product("trail-hearts", 59, 30),
                bundle("bad-pack", 200, List.of("trail-flame", "trail-hearts"))));

        assertTrue(catalog.problems().stream().anyMatch(line -> line.contains("Скидки нет")),
                () -> catalog.problems().toString());
    }

    @Test
    void bundleWithPassDaysIsABundle() {
        Map<String, Object> entry = bundle("start-kit", 299, List.of("trail-flame"));
        entry.put("pass-days", 30);
        ShopCatalog catalog = ShopCatalog.parse(true, 365, List.of(
                product("trail-flame", 59, 30), entry));

        ShopProduct kit = catalog.find("start-kit").orElseThrow();
        assertTrue(kit.isBundle());
        assertTrue(kit.hasPassDays());
        assertEquals(30, kit.passDays());
    }

    @Test
    void passDaysOutOfRangeAreDropped() {
        Map<String, Object> entry = bundle("kit", 99, List.of("trail-flame"));
        entry.put("pass-days", 10_000);
        ShopCatalog catalog = ShopCatalog.parse(true, 365, List.of(
                product("trail-flame", 59, 30), entry));

        assertFalse(catalog.find("kit").orElseThrow().hasPassDays());
        assertTrue(catalog.problems().stream().anyMatch(line -> line.contains("pass-days")),
                () -> catalog.problems().toString());
    }
}
