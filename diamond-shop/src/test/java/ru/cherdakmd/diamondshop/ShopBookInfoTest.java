package ru.cherdakmd.diamondshop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.ShopBookKey;

/**
 * Офлайн-справочник русских названий чар: покрытие каталога, человекочитаемый
 * вывод и поиск. Тесты не требуют загруженного CHRDK_Enchants и Bukkit Registry.
 * Книги сняты с продажи (2.0.5), справочник остаётся для выданных ранее книг;
 * полнота сверяется с реестром чар {@code ../enchants/src/main/resources/config.yml}.
 */
class ShopBookInfoTest {

    /** Реестр кастомных чар CHRDK_Enchants (id → enabled). */
    private static Set<String> registryIds() throws Exception {
        var yaml = YamlConfiguration.loadConfiguration(
                new InputStreamReader(Files.newInputStream(
                        Path.of("../enchants/src/main/resources/config.yml")), StandardCharsets.UTF_8));
        return yaml.getConfigurationSection("enchants").getKeys(false);
    }

    /** Товары-книги без парсера конфига: ключ строится из id реестра. */
    private static List<ShopConfig.ShopItem> customBooks() throws Exception {
        List<ShopConfig.ShopItem> books = new ArrayList<>();
        for (String id : registryIds()) {
            ShopBookKey.Spec spec = new ShopBookKey.Spec(id, 3, true);
            books.add(new ShopConfig.ShopItem(spec.key(), spec.category(), 100, 200, 1, 1, true, 1, spec.key()));
        }
        return books;
    }

    /** Без дополнительных зависимостей: имена сборки — одиночные TextComponent. */
    private static String plain(Component component) {
        if (component == null) {
            return null;
        }
        return component instanceof TextComponent text ? text.content() : "";
    }

    @Test
    void everyCustomEnchantHasRussianNameAndEffect() throws Exception {
        List<ShopConfig.ShopItem> books = customBooks();
        assertEquals(76, books.size(), "Реестр чар изменился — нужен повтор генерации справочника");
        int curses = 0;
        for (ShopConfig.ShopItem item : books) {
            ShopBookKey.Spec spec = item.spec();
            ShopBookInfo.Label label = ShopBookInfo.label(spec.id());
            assertNotNull(label, "Нет перевода для " + spec.id());
            assertTrue(label.name().matches(".*[А-Яа-яЁё].*"),
                    "Название " + spec.id() + " не русское: " + label.name());
            assertTrue(label.description().length() <= 100);
            assertTrue(label.name().length() <= 40);
            assertNotNull(ShopBookInfo.displayName(spec));
            assertNotNull(ShopBookInfo.description(spec));
            if (label.curse()) {
                curses++;
            }
        }
        assertEquals(15, curses, "Список проклятий должен совпадать с EnchantRegistry");
    }

    @Test
    void displayNameIsHumanReadableAndCurseIsMarked() {
        var spec = new ShopBookKey.Spec("veinminer", 3, true);
        String name = plain(ShopBookInfo.displayName(spec));
        assertNotNull(name);
        assertTrue(name.contains("Жилодробитель"));
        assertTrue(name.contains("III"));
        assertFalse(name.toLowerCase().contains("veinminer"),
                "Технический id не должен оставаться единственным названием");
        assertTrue(plain(ShopBookInfo.description(spec)).startsWith("✶ "));

        var curse = new ShopBookKey.Spec("dull_blade", 1, true);
        assertTrue(plain(ShopBookInfo.displayName(curse)).contains("проклятия"));
        assertTrue(plain(ShopBookInfo.description(curse)).contains("Проклятие"));
    }

    @Test
    void vanillaAndUnknownSpecsFallBackToTechnicalName() {
        var vanilla = new ShopBookKey.Spec("protection", 4, false);
        assertNotNull(ShopBookInfo.displayName(vanilla),
                "Ванильная книга не должна терять имя в витрине");
        assertNull(ShopBookInfo.description(vanilla));
        assertNull(ShopBookInfo.searchName(vanilla));

        var unknown = new ShopBookKey.Spec("unknown_ench", 2, true);
        assertNull(ShopBookInfo.label("unknown_ench"));
        assertNotNull(ShopBookInfo.displayName(unknown));
        assertNull(ShopBookInfo.description(unknown));
        assertNull(ShopBookInfo.label(null));
    }

    @Test
    void findSearchesRussianNameAndNormalizesYo() throws Exception {
        List<ShopConfig.ShopItem> books = customBooks();
        assertEquals(1, ShopCatalog.search(books, "жилодробитель").size());
        assertEquals(1, ShopCatalog.search(books, "ЖИЛОДРОБИТЕЛЬ").size());
        assertEquals(1, ShopCatalog.search(books, "  громобой  ").size());
        assertEquals(1, ShopCatalog.search(books, "veinminer").size(),
                "Латинский ключ товара продолжает работать");
        assertTrue(ShopCatalog.search(books, "несуществующая чара").isEmpty());
        assertTrue(ShopCatalog.search(books, "   ").isEmpty());
        assertTrue(ShopCatalog.search(books, null).isEmpty());

        // Ё/е в названии чары: запрос в любой раскладке находит один товар.
        String yoName = books.stream()
                .map(item -> ShopBookInfo.label(item.spec().id()).name())
                .filter(name -> name.indexOf('ё') >= 0 || name.indexOf('Ё') >= 0)
                .findFirst().orElse(null);
        if (yoName != null) {
            assertEquals(1, ShopCatalog.search(books, yoName.replace('ё', 'е')
                    .replace('Ё', 'Е')).size());
        }
    }
}
