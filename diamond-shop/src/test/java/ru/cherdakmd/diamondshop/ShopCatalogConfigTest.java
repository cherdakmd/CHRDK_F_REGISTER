package ru.cherdakmd.diamondshop;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверка поставляемого каталога {@code config.yml}: размер, уникальность,
 * разделы, цены и границы множителей. Читает ресурс как текст, чтобы не тянуть
 * Bukkit в юнит-тесты.
 */
class ShopCatalogConfigTest {

    private record RawItem(String material, String category, double price, long ref,
                           Double min, Double max) {
    }

    @Test
    void shippedCatalogIsLargeAndConsistent() throws IOException {
        List<RawItem> items = parse();
        assertEquals(249,items.size(),"размер поставляемого каталога");

        Set<String> materials = new HashSet<>();
        Set<String> currency = Set.of("DIAMOND", "DIAMOND_BLOCK");
        Map<String, Integer> perCategory = new HashMap<>();
        for (RawItem item : items) {
            assertTrue(item.material().matches("[A-Z][A-Z0-9_]*"),
                    "имя материала в верхнем регистре: " + item.material());
            assertTrue(materials.add(item.material()), "дубликат товара: " + item.material());
            assertFalse(currency.contains(item.material()),
                    "валюта не торгуется как товар: " + item.material());
            assertTrue(item.price() > 0, item.material() + ": base-price > 0");
            assertTrue(item.ref() > 0, item.material() + ": ref-stock > 0");

            ShopCategory category = ShopCategory.byId(item.category()).orElse(null);
            assertNotNull(category, item.material() + ": неизвестный раздел " + item.category());
            assertFalse(category == ShopCategory.ALL,
                    item.material() + ": раздел all — служебный");
            perCategory.merge(category.id(), 1, Integer::sum);

            double min = item.min() == null ? 0.25 : item.min();
            double max = item.max() == null ? 4.0 : item.max();
            assertTrue(min >= 0.01 && min <= 1.0, item.material() + ": min-multiplier");
            assertTrue(max >= 1.0 && max <= 100.0, item.material() + ": max-multiplier");
            assertTrue(min <= max, item.material() + ": min <= max");
        }

        for (ShopCategory category : List.of(ShopCategory.BLOCKS, ShopCategory.WOOD,
                ShopCategory.MINERALS, ShopCategory.MOBS, ShopCategory.FOOD,
                ShopCategory.UTILITY)) {
            assertTrue(perCategory.getOrDefault(category.id(), 0) >= 5,
                    "раздел " + category.id() + " должен быть наполнен");
        }
        assertEquals(items.size(), materials.size());
    }

    private List<RawItem> parse() throws IOException {
        List<String> lines = Files.readAllLines(resolveConfig(), StandardCharsets.UTF_8);
        List<RawItem> result = new ArrayList<>();
        Map<String, String> current = null;
        boolean inItems = false;
        for (String line : lines) {
            if (line.startsWith("items:")) {
                inItems = true;
                continue;
            }
            if (!inItems) {
                continue;
            }
            if (line.startsWith("  - material:")) {
                flush(result, current);
                current = new HashMap<>();
                current.put("material", value(line));
                continue;
            }
            if (current != null && line.startsWith("    ") && line.contains(":")) {
                int colon = line.indexOf(':');
                current.put(line.substring(4, colon).trim(),
                        line.substring(colon + 1).trim());
                continue;
            }
            if (!line.isBlank() && !line.startsWith(" ") && !line.startsWith("#")) {
                break; // defaults:/general: — каталог закончился
            }
        }
        flush(result, current);
        return result;
    }

    private static void flush(List<RawItem> result, Map<String, String> raw) {
        if (raw == null || !raw.containsKey("material")) {
            return;
        }
        Double min = raw.containsKey("min-multiplier")
                ? Double.parseDouble(raw.get("min-multiplier")) : null;
        Double max = raw.containsKey("max-multiplier")
                ? Double.parseDouble(raw.get("max-multiplier")) : null;
        result.add(new RawItem(raw.get("material"), raw.get("category"),
                Double.parseDouble(raw.get("base-price")),
                Long.parseLong(raw.get("ref-stock")), min, max));
    }

    private static String value(String line) {
        return line.substring(line.indexOf(':') + 1).trim();
    }

    private static Path resolveConfig() throws IOException {
        Path module = Path.of("src", "main", "resources", "config.yml");
        if (Files.isRegularFile(module)) {
            return module;
        }
        Path root = Path.of("diamond-shop", "src", "main", "resources", "config.yml")
                .toAbsolutePath().normalize();
        if (Files.isRegularFile(root)) {
            return root;
        }
        throw new IOException("config.yml не найден: " + module.toAbsolutePath()
                + " / " + root + " (cwd=" + Path.of("").toAbsolutePath() + ")");
    }
}
