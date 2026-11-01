package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Сверка витрины с аддонами (1.4.188): каталог {@code shop.products} обязан
 * оставаться целым, а каждый товар, который где-то открывает возможность, —
 * существовать.
 *
 * <p>Главное правило — <b>уникальные цены</b>. Панель TradeMC присылает только
 * {@code pass shoppay <ник> <сумма>}, и ядро ищет карточку по цене: два товара
 * с одинаковой ценой означают, что один из них не купить никогда, а покупатель
 * получит соседний. До 1.4.188 в конфиге было три цвета по 149 ₽ и три шлейфа
 * по 59 ₽ — эта проверка такое не пропустит.</p>
 *
 * <p>Второе правило — ссылки: {@code labels.colors.list[].product},
 * {@code cosmetics.trail.defs[].product}, {@code cosmetics.shop.pack-products},
 * {@code cosmetics.join-effect.product} и {@code battlepass.config.yml →
 * premium.product} должны указывать на реальные товары, иначе игрок заплатит
 * и не получит ничего.</p>
 */
class ShopConfigConsistencyTest {

    private static YamlConfiguration yaml(String path) {
        try {
            return YamlConfiguration.loadConfiguration(
                    new java.io.StringReader(Files.readString(Path.of(path))));
        } catch (Exception exception) {
            throw new AssertionError("не прочитать " + path, exception);
        }
    }

    private static ShopCatalog catalog() {
        return ShopCatalog.from(yaml("src/main/resources/config.yml"));
    }

    private static List<String> cosmeticsProducts() {
        YamlConfiguration cosmetics = yaml("cosmetics/src/main/resources/config.yml");
        List<String> ids = new ArrayList<>();
        for (Object entry : cosmetics.getList("cosmetics.trail.defs", List.of())) {
            if (entry instanceof java.util.Map<?, ?> map) {
                String id = map.get("id") == null ? "" : String.valueOf(map.get("id")).trim();
                String product = map.get("product") == null ? "" : String.valueOf(map.get("product")).trim();
                // Поле есть, но пустое — шлейф только за ранг (эксклюзив Мецената и
                // Покровителя) и в продаже его нет. Поля нет вовсе — аддон берёт
                // имя товара по умолчанию, trail-<id>, и такой товар обязан быть.
                if (map.containsKey("product")) {
                    if (!product.isEmpty()) {
                        ids.add(product.toLowerCase(java.util.Locale.ROOT));
                    }
                } else if (!id.isEmpty()) {
                    ids.add(("trail-" + id).toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        ids.addAll(cosmetics.getStringList("cosmetics.shop.pack-products"));
        ConfigurationSection join = cosmetics.getConfigurationSection("cosmetics.join-effect");
        if (join != null) {
            String product = join.getString("product", "").trim();
            if (!product.isEmpty()) {
                ids.add(product.toLowerCase(java.util.Locale.ROOT));
            }
        }
        return ids;
    }

    @Test
    void catalogParsesWithoutProblems() {
        ShopCatalog catalog = catalog();
        assertTrue(catalog.usable(), "витрина должна быть включена");
        assertTrue(catalog.problems().isEmpty(), "каталог разбирается с ошибками: " + catalog.problems());
        assertEquals(30, catalog.products().size(), "три личные лицензии и клановый комплект с бонусом");
    }

    @Test
    void idsAndPricesAreUnique() {
        ShopCatalog catalog = catalog();
        Set<String> ids = new HashSet<>();
        Set<Integer> prices = new HashSet<>();
        List<String> repeatedPrices = new ArrayList<>();
        for (ShopProduct product : catalog.products()) {
            assertTrue(ids.add(product.id()), "повтор id: " + product.id());
            if (!prices.add(product.priceRubles())) {
                repeatedPrices.add(product.id() + " = " + product.priceRubles());
            }
        }
        assertTrue(repeatedPrices.isEmpty(),
                "цены обязаны быть уникальными, иначе витрина выдаст соседний товар: " + repeatedPrices);
    }

    @Test
    void everyProductIsSellable() {
        ShopCatalog catalog = catalog();
        for (ShopProduct product : catalog.products()) {
            assertFalse(product.name().isBlank(), "нет названия: " + product.id());
            assertFalse(product.description().isBlank(), "нет описания: " + product.id());
            assertTrue(product.priceRubles() > 0, "нулевая цена: " + product.id());
            assertTrue(product.days() > 0 && product.days() <= ShopCatalog.MAX_PRODUCT_DAYS,
                    "срок вне диапазона: " + product.id());
            assertTrue(product.priceRubles() <= ShopCatalog.MAX_PRICE_RUBLES,
                    "цена похожа на опечатку: " + product.id());
        }
    }

    @Test
    void bundlesResolveEveryPart() {
        ShopCatalog catalog = catalog();
        List<String> bundles = new ArrayList<>();
        for (ShopProduct product : catalog.products()) {
            if (!product.isBundle()) {
                continue;
            }
            bundles.add(product.id());
            assertFalse(product.resolvedIncludes().isEmpty() || product.hasPassDays(),
                    "набор ничего не выдаёт: " + product.id());
            for (String partId : product.includes()) {
                assertTrue(catalog.find(partId).isPresent(),
                        "набор " + product.id() + " ссылается на несуществующий товар " + partId);
            }
            for (String partId : product.resolvedIncludes()) {
                assertTrue(catalog.find(partId).isPresent(),
                        "набор " + product.id() + " раскрылся в несуществующий товар " + partId);
            }
        }
        assertTrue(bundles.contains("trails-pack"));
        assertTrue(bundles.contains("look-pack"), "набор «Весь вид» должен существовать");
    }

    @Test
    void paletteColorsPointToRealProducts() {
        ShopCatalog catalog = catalog();
        LabelPalette palette = LabelPalette.fromConfig(yaml("src/main/resources/config.yml"));
        assertTrue(palette.enabled());
        assertFalse(palette.colors().isEmpty(), "палитра пуста");

        Set<String> premium = new LinkedHashSet<>();
        for (LabelPalette.Color color : palette.colors()) {
            if (!color.inCatalog()) {
                continue;
            }
            premium.add(color.id());
            ShopProduct product = catalog.find(color.product()).orElse(null);
            assertNotNull(product, "цвет " + color.id() + " продаётся товаром " + color.product()
                    + ", которого нет в каталоге");
            assertTrue(product.days() > 0, "товар цвета живёт 0 дней: " + product.id());
            assertFalse(palette.allowed(color, 5, Set.of()),
                    "премиум-цвет " + color.id() + " не должен открываться рангом: покупка — "
                            + "единственный способ");
            assertTrue(palette.allowed(color, 0, Set.of(color.product())),
                    "покупка открывает цвет " + color.id() + " даже без ранга");
        }
        assertTrue(premium.contains("aurora") && premium.contains("lagoon")
                        && premium.contains("crimson"),
                "ожидаются премиум-цвета каталога: " + premium);
    }

    @Test
    void cosmeticsAndBattlePassPointToRealProducts() {
        ShopCatalog catalog = catalog();
        List<String> referenced = cosmeticsProducts();
        assertFalse(referenced.isEmpty(), "косметика не ссылается ни на один товар");
        for (String id : referenced) {
            assertTrue(catalog.find(id).isPresent(),
                    "косметика ссылается на товар " + id + ", которого нет в каталоге");
        }
        assertEquals(1, referenced.stream().filter("join-effect"::equals).count(),
                "торжественный вход продаётся товаром join-effect");
        for (String sold : List.of("trail-flame", "trail-hearts", "trail-notes",
                "trail-sparkles", "trail-magic")) {
            assertTrue(referenced.contains(sold), "шлейф " + sold + " должен продаваться");
        }
        assertFalse(referenced.contains("trail-gold") || referenced.contains("trail-aurora"),
                "«Золотая пыль» и «Сияние» — эксклюзив ранга IV–V, поштучно не продаются");

        YamlConfiguration battlepass = yaml("battlepass/src/main/resources/config.yml");
        String premium = battlepass.getString("battlepass.premium.product", "").trim();
        assertFalse(premium.isEmpty(), "премиум-трек батлпасса без товара");
        assertTrue(catalog.find(premium).isPresent(),
                "батлпасс ссылается на товар " + premium + ", которого нет в каталоге");
    }

    @Test
    void battlePassNeverRewardsPaidCatalogProducts() {
        YamlConfiguration battlepass = yaml("battlepass/src/main/resources/config.yml");
        int missions = 0;
        for (java.util.Map<?, ?> week : battlepass.getMapList("battlepass.weeks")) {
            assertTrue(week.get("missions") instanceof List<?>);
            for (Object entry : (List<?>) week.get("missions")) {
                assertTrue(entry instanceof java.util.Map<?, ?>);
                java.util.Map<?, ?> mission = (java.util.Map<?, ?>) entry;
                assertFalse(mission.containsKey("premium-products"), "paid product reward: " + mission.get("id"));
                assertTrue(mission.containsKey("free-boost-hours"), "explicit independent boost reward required");
                missions++;
            }
        }
        assertEquals(23, missions, "audit must cover every shipped season mission");
    }

    @Test
    void homesProductIsWiredToTheTeleportsPermission() {
        ShopProduct homes = catalog().find("homes-plus").orElse(null);
        assertNotNull(homes, "товар домов должен быть в каталоге");
        assertTrue(homes.hasGroup(), "право на дома выдаётся группой LuckPerms");
        assertTrue(homes.permissions().contains("chrdktele.homes.plus"),
                "группа товара должна нести право дополнительных домов: " + homes.permissions());
    }
}
