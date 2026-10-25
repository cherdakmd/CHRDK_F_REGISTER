package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Поиск карточки витрины по сумме платежа (1.4.167).
 *
 * <p>Панель TradeMC присылает только {@code pass shoppay <ник> <цена>}, поэтому
 * единственный ключ выдачи ранга — цена. Тест держит три свойства: точное
 * совпадение, пустой ответ на чужую/нулевую сумму и сохранение порядка каталога
 * при дубле цен (ядро выдаёт первый товар и пишет предупреждение).</p>
 */
class ShopPriceLookupTest {

    private static ShopProduct product(String id, int price, int days, int rank) {
        return new ShopProduct(id, id, "", price, days, "", "", List.of(), List.of(),
                List.of(), List.of(), 0, rank);
    }

    @Test
    void findsRankCardByItsPrice() {
        List<ShopProduct> products = List.of(
                product("rank-wanderer", 99, 30, 1),
                product("rank-wanderer-year", 990, 365, 1),
                product("rank-patron", 365, 30, 5),
                product("rank-patron-year", 3650, 365, 5));

        assertEquals("rank-wanderer", ShopCatalog.matchingPrice(products, 99).get(0).id());
        assertEquals("rank-patron", ShopCatalog.matchingPrice(products, 365).get(0).id());
        assertEquals(5, ShopCatalog.matchingPrice(products, 3650).get(0).rank(),
                "год Покровителя — ступень V");
        assertEquals(365, ShopCatalog.matchingPrice(products, 3650).get(0).days(),
                "год — 365 дней, а не 30");
    }

    @Test
    void missingAndNonPositivePricesReturnNothing() {
        List<ShopProduct> products = List.of(product("rank-wanderer", 99, 30, 1));
        assertTrue(ShopCatalog.matchingPrice(products, 100).isEmpty(), "чужой цены нет");
        assertTrue(ShopCatalog.matchingPrice(products, 0).isEmpty(), "нулевой суммы не бывает");
        assertTrue(ShopCatalog.matchingPrice(products, -365).isEmpty(), "минус — не платёж");
        assertTrue(ShopCatalog.matchingPrice(List.of(), 99).isEmpty(), "пустой каталог");
        assertTrue(ShopCatalog.matchingPrice(null, 99).isEmpty(), "null каталог");
    }

    @Test
    void duplicatePricesKeepCatalogOrderForTheWarning() {
        List<ShopProduct> products = List.of(
                product("rank-keeper", 229, 30, 3),
                product("trail-flame", 229, 30, 0));

        List<ShopProduct> matches = ShopCatalog.matchingPrice(products, 229);
        assertEquals(2, matches.size(), "дубль цен должен быть виден сервису");
        assertEquals("rank-keeper", matches.get(0).id(), "первым идёт товар из каталога");
        assertEquals("trail-flame", matches.get(1).id());
    }
}
