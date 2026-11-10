package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Обновляет ТОЛЬКО точные стоковые значения презентационных секций: пункты меню,
 * автосообщения, таб-лист и описания стандартного каталога. Владельческие правки, расписания и закреплённые ники
 * не трогаются: если значение в конфиге отличается от любого известного стокового
 * снимка, оно остаётся как есть.
 *
 * <p>Снимков несколько — по одному на прошлые релизы ({@link #LEGACY_RESOURCES}).
 * Так сервер, который обновился не сразу, всё равно получает новый стоковый набор:
 * сравнение идёт с каждым известным вариантом, а не только с самым старым.</p>
 *
 * <p>Пункты меню и его новая структура (категории, заголовки, кнопка «Назад»)
 * обновляются одним пакетом ({@link #MENU_COMPANIONS}): новые пункты никогда не
 * окажутся без категорий.</p>
 */
final class PresentationMigration {

    static final List<String> PATHS = List.of("server-menu.items", "server-menu.categories",
            "auto-messages.messages", "auto-messages.prefix", "tablist.header", "tablist.footer",
            "tablist.player-name-format", "shop.products");

    /**
     * Новые стоковые SKU после четырёх клановых лицензий. Миграция может
     * добавить их к прежнему каталогу, но неизвестный товар владельца — нет.
     * social-access-day — дневной тариф 15 ₽ / 24 часа (1.5.x).
     */
    static final Set<String> LATE_STOCK_PRODUCTS = Set.of(
            "social-access", "social-access-day", "claims-chunks-plus");

    /** Настройки меню, которые должны обновиться вместе с его пунктами. */
    static final List<String> MENU_COMPANIONS = List.of("server-menu.title",
            "server-menu.category-title", "server-menu.back-slot", "server-menu.back-icon",
            "server-menu.back-name", "server-menu.close-slot", "server-menu.close-icon",
            "server-menu.close-name", "server-menu.categories");

    /**
     * Стоковые снимки прошлых версий: от нового к старому. Снимок 1.4.185 —
     * меню из 52 пунктов со старой подсказкой «Оформление ника»; снимок 1.4.182 —
     * меню с «Вики сервера», но без кнопки «Оформление ника» (51 пункт);
     * снимок 1.4.170 — без обеих (50 пунктов); снимок 1.4.169 — последний со
     * старой плоской структурой (6 разделов, 42 пункта).
     */
    static final List<String> LEGACY_RESOURCES = List.of("presentation-legacy-1.4.282-rc3.yml", "presentation-legacy-1.4.275.yml", "presentation-legacy-1.4.270.yml", "presentation-legacy-1.4.267.yml", "presentation-legacy-1.4.261.yml", "presentation-legacy-1.4.254.yml", "presentation-legacy-1.4.253.yml", "presentation-legacy-1.4.247.yml", "presentation-legacy-1.4.246.yml", "presentation-legacy-1.4.244.yml", "presentation-legacy-1.4.241.yml", "presentation-legacy-1.4.240.yml", "presentation-legacy-1.4.239.yml", "presentation-legacy-1.4.238.yml", "presentation-legacy-1.4.192.yml", "presentation-legacy-1.4.185.yml",
            "presentation-legacy-1.4.182.yml", "presentation-legacy-1.4.170.yml",
            "presentation-legacy-1.4.169.yml", "presentation-legacy-pre-1.4.138.yml",
            "presentation-legacy.yml");

    private PresentationMigration() {
    }

    /** Совместимость с одним снимком (используется тестами). */
    static List<String> replacements(YamlConfiguration live, YamlConfiguration legacy, YamlConfiguration fresh) {
        return replacements(live, List.of(legacy), fresh);
    }

    static List<String> replacements(YamlConfiguration live, List<YamlConfiguration> legacies,
                                     YamlConfiguration fresh) {
        List<String> out = new ArrayList<>();
        if (live == null || fresh == null) {
            return out;
        }
        for (String key : PATHS) {
            if (!live.contains(key, true)) {
                continue;
            }
            Object value = live.get(key);
            // Пустой TAB — явный выбор владельца, даже если когда-то это был старый stock.
            // Иначе сравнение со всеми историческими снимками отменяет выключение имени/шапки.
            if (key.equals("tablist.player-name-format") && value instanceof String text && text.isBlank()) continue;
            if ((key.equals("tablist.header") || key.equals("tablist.footer")) && value instanceof List<?> lines
                    && lines.stream().allMatch(line -> line == null || line instanceof String text && text.isBlank())) continue;
            // Снятие старой рекламы не должно обновлять цены/сроки из более ранних снимков.
            // Исключение: если в каталоге сервера стоят ровно тарифы предыдущей
            // поставки, владелец цены не правил — отличие от fresh объясняется
            // сменой тарифов в новом config.yml, и каталог можно обновить.
            if (key.equals("shop.products") && !sameCatalogTerms(value, fresh.get(key))
                    && !isPreviousStockCatalog(value)) continue;
            boolean stock = false;
            for (YamlConfiguration legacy : legacies) {
                if (legacy != null && legacy.contains(key, true) && Objects.equals(value, legacy.get(key))) {
                    stock = true;
                    break;
                }
            }
            if (stock && !Objects.equals(value, fresh.get(key))) {
                out.add(key);
            }
        }
        // Меню обновляется целиком: пункты + категории + заголовки и «Назад».
        if (out.contains("server-menu.items")) {
            for (String companion : MENU_COMPANIONS) {
                if (!out.contains(companion) && fresh.contains(companion, true)) {
                    out.add(companion);
                }
            }
        }
        return out;
    }

    /** Для каталога допустимы только отличия описаний при неизменных условиях каждого SKU. */
    private static boolean sameCatalogTerms(Object before, Object after) {
        if (!(before instanceof List<?> previous) || !(after instanceof List<?> current)) return false;
        // Допустимы только известные стоковые добавления (четыре клановые лицензии
        // и товар social-access). Если владелец завёл свой SKU или правил цены,
        // каталог целиком остаётся его редакцией и миграция его не трогает.
        Set<Object> previousIds = new HashSet<>();
        for (Object value : previous) {
            if (!(value instanceof Map<?, ?> item)) return false;
            previousIds.add(item.get("id"));
        }
        List<Object> retained = new ArrayList<>();
        for (Object value : current) {
            if (!(value instanceof Map<?, ?> item)) return false;
            Object id = item.get("id");
            if (previousIds.contains(id)) {
                retained.add(value);
            } else if (!PremiumPresentationMigration.PRODUCTS.contains(String.valueOf(id))
                    && !LATE_STOCK_PRODUCTS.contains(String.valueOf(id))) {
                return false;
            }
        }
        if (previous.size() != retained.size()) return false;
        for (int i = 0; i < previous.size(); i++) {
            if (!(previous.get(i) instanceof Map<?, ?> old) || !(retained.get(i) instanceof Map<?, ?> fresh))
                return false;
            var left = new HashMap<Object, Object>(old);
            var right = new HashMap<Object, Object>(fresh);
            left.remove("description"); right.remove("description");
            if (!left.equals(right)) return false;
        }
        return true;
    }

    /**
     * Тарифы предыдущей поставки. Служат только для отличия «владелец правил
     * цены» от «поставка сменила тарифы»: любое другое значение цены считается
     * владельческой правкой и каталог миграция не трогает.
     */
    private static final Map<String, Integer> PREVIOUS_STOCK_PRICES = Map.ofEntries(
            Map.entry("rank-wanderer", 99), Map.entry("rank-wanderer-year", 990),
            Map.entry("rank-pathfinder", 159), Map.entry("rank-pathfinder-year", 1590),
            Map.entry("rank-keeper", 229), Map.entry("rank-keeper-year", 2290),
            Map.entry("rank-patron", 299), Map.entry("rank-patron-year", 2990),
            Map.entry("rank-founder", 365), Map.entry("rank-founder-year", 3650),
            Map.entry("all-inclusive", 899));

    private static boolean isPreviousStockCatalog(Object value) {
        if (!(value instanceof List<?> catalog)) return false;
        Map<Object, Object> prices = new HashMap<>();
        for (Object entry : catalog) {
            if (!(entry instanceof Map<?, ?> product)) return false;
            prices.put(product.get("id"), product.get("price"));
        }
        for (var expected : PREVIOUS_STOCK_PRICES.entrySet()) {
            if (!(prices.get(expected.getKey()) instanceof Number actual)
                    || actual.intValue() != expected.getValue()) return false;
        }
        return true;
    }

    static void apply(JavaPlugin plugin) {
        Path config = ru.cherdakmd.chrdkregister.storage.ChrdkStoragePaths.dataFolder(plugin).resolve("config.yml");
        try (InputStream latest = plugin.getResource("config.yml")) {
            if (latest == null || !Files.exists(config)) {
                return;
            }
            List<YamlConfiguration> legacies = new ArrayList<>();
            for (String resource : LEGACY_RESOURCES) {
                try (InputStream old = plugin.getResource(resource)) {
                    if (old != null) {
                        legacies.add(YamlConfiguration.loadConfiguration(
                                new InputStreamReader(old, java.nio.charset.StandardCharsets.UTF_8)));
                    }
                }
            }
            if (legacies.isEmpty()) {
                return;
            }
            YamlConfiguration live = YamlConfiguration.loadConfiguration(config.toFile());
            YamlConfiguration fresh = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(latest, java.nio.charset.StandardCharsets.UTF_8));
            List<String> paths = replacements(live, legacies, fresh);
            if (paths.isEmpty()) {
                return;
            }
            Path backup = config.resolveSibling("config-before-presentation-1.4.138-"
                    + System.currentTimeMillis() + ".yml");
            Files.copy(config, backup); // fail BEFORE changing anything if backup cannot be written
            for (String key : paths) {
                live.set(key, fresh.get(key));
            }
            Path temp = config.resolveSibling("config-presentation.tmp");
            live.save(temp.toFile());
            try {
                Files.move(temp, config, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, config, StandardCopyOption.REPLACE_EXISTING);
            }
            plugin.reloadConfig();
            plugin.getLogger().info("Updated stock presentation: " + paths + ". Backup: " + backup.getFileName());
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Presentation migration skipped; existing configuration retained", e);
        }
    }
}
