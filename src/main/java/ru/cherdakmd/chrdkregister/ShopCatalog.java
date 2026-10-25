package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Каталог товаров из {@code shop} секции {@code config.yml}: разбор, проверка и поиск.
 *
 * <p>Разбор намеренно терпимый: одна кривая запись не должна ронять плагин и не должна
 * утаскивать за собой остальные товары. Всё, что не удалось прочитать, попадает в
 * {@link #problems()} и печатается в лог при включении и {@code /pass reload}.</p>
 *
 * <p>Класс чистый: ни Bukkit-сервера, ни LuckPerms — покрывается юнит-тестами.</p>
 */
public final class ShopCatalog {

    /** Максимальный срок одного товара: дальше — уже не «сезон», а вечность. */
    public static final int MAX_PRODUCT_DAYS = 3650;
    /** Потолок суммарного срока по умолчанию (365 дней). */
    public static final int DEFAULT_MAX_TOTAL_DAYS = 365;
    /** Разумный потолок цены: защита от опечатки «149000» вместо «149». */
    public static final int MAX_PRICE_RUBLES = 100_000;
    /** Насколько глубоко набор может включать другие наборы. */
    public static final int MAX_BUNDLE_DEPTH = 3;
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

    private final boolean enabled;
    private final int maxTotalDays;
    private final List<ShopProduct> products;
    private final List<String> problems;

    private ShopCatalog(boolean enabled, int maxTotalDays, List<ShopProduct> products, List<String> problems) {
        this.enabled = enabled;
        this.maxTotalDays = maxTotalDays;
        this.products = List.copyOf(products);
        this.problems = List.copyOf(problems);
    }

    /** Пустой каталог: функция выключена или товары ещё не настроены. */
    public static ShopCatalog empty(boolean enabled) {
        return new ShopCatalog(enabled, DEFAULT_MAX_TOTAL_DAYS, List.of(), List.of());
    }

    public static ShopCatalog from(FileConfiguration config) {
        if (config == null) {
            return empty(false);
        }
        return parse(
                config.getBoolean("shop.enabled", false),
                config.getInt("shop.max-total-days", DEFAULT_MAX_TOTAL_DAYS),
                config.getMapList("shop.products"));
    }

    /**
     * Разбор списка товаров. Кривые записи пропускаются с пояснением в {@link #problems()}.
     *
     * @param enabled      включён ли каталог ({@code shop.enabled})
     * @param maxTotalDays потолок суммарного срока одного товара у игрока
     * @param raw          список карт из {@code shop.products}
     */
    public static ShopCatalog parse(boolean enabled, int maxTotalDays, List<Map<?, ?>> raw) {
        List<String> problems = new ArrayList<>();
        List<ShopProduct> products = new ArrayList<>();
        Map<String, ShopProduct> seen = new LinkedHashMap<>();
        int cap = Math.clamp(maxTotalDays, 1, MAX_PRODUCT_DAYS);
        if (maxTotalDays != cap) {
            problems.add("shop.max-total-days: " + maxTotalDays + " вне диапазона 1.."
                    + MAX_PRODUCT_DAYS + " — использую " + cap);
        }
        if (raw == null) {
            return new ShopCatalog(enabled, cap, List.of(), problems);
        }
        int index = 0;
        for (Map<?, ?> entry : raw) {
            index++;
            if (entry == null) {
                problems.add("shop.products[" + index + "]: пустая запись — пропущена");
                continue;
            }
            String id = text(entry.get("id")).toLowerCase(Locale.ROOT);
            if (id.isEmpty()) {
                problems.add("shop.products[" + index + "]: не задан id — пропущен");
                continue;
            }
            if (!ID_PATTERN.matcher(id).matches()) {
                problems.add("shop.products[" + index + "]: id '" + id
                        + "' должен состоять из a-z, 0-9, '-' и '_' (до 32 символов) — пропущен");
                continue;
            }
            if (seen.containsKey(id)) {
                problems.add("shop.products[" + index + "]: повторный id '" + id + "' — пропущен");
                continue;
            }

            String name = text(entry.get("name"));
            if (name.isEmpty()) {
                name = id;
                problems.add("shop.products[" + index + "] (" + id + "): пустое name — показываю id");
            }

            int price = intOf(entry.get("price"), -1);
            if (price <= 0 || price > MAX_PRICE_RUBLES) {
                problems.add("shop.products[" + index + "] (" + id + "): цена " + price
                        + " вне диапазона 1.." + MAX_PRICE_RUBLES + " — товар пропущен");
                continue;
            }

            int days = intOf(entry.get("days"), -1);
            if (days <= 0 || days > MAX_PRODUCT_DAYS) {
                problems.add("shop.products[" + index + "] (" + id + "): срок " + days
                        + " вне диапазона 1.." + MAX_PRODUCT_DAYS + " — товар пропущен");
                continue;
            }
            if (days > cap) {
                problems.add("shop.products[" + index + "] (" + id + "): срок " + days
                        + " больше shop.max-total-days " + cap + " — срок урезан");
                days = cap;
            }

            String group = text(entry.get("group"));
            if (!group.isEmpty() && !ID_PATTERN.matcher(group.toLowerCase(Locale.ROOT)).matches()) {
                problems.add("shop.products[" + index + "] (" + id + "): группа '" + group
                        + "' содержит недопустимые символы — товар выдан без группы");
                group = "";
            }

            String tag = text(entry.get("tag"));
            if (!tag.isEmpty() && !balanced(tag)) {
                problems.add("shop.products[" + index + "] (" + id + "): в tag не закрыта "
                        + "MiniMessage-разметка — ник показан без оформления");
                tag = "";
            }

            int rank = intOf(entry.get("rank"), 0);
            if (rank < 0 || rank > RankLadder.MAX_LEVEL) {
                problems.add("shop.products[" + index + "] (" + id + "): rank " + rank
                        + " вне диапазона 1.." + RankLadder.MAX_LEVEL + " — товар без ранга");
                rank = 0;
            }
            if (rank > 0 && !strings(entry.get("includes")).isEmpty()) {
                problems.add("shop.products[" + index + "] (" + id + "): у товара ранга не бывает "
                        + "includes — набор проигнорирован");
            }

            int passDays = intOf(entry.get("pass-days"), 0);
            if (passDays < 0 || passDays > MAX_PRODUCT_DAYS) {
                problems.add("shop.products[" + index + "] (" + id + "): pass-days " + passDays
                        + " вне диапазона 1.." + MAX_PRODUCT_DAYS + " — дни проходки не выданы");
                passDays = 0;
            }

            List<String> includes = ids(strings(entry.get("includes")));
            List<String> permissions = strings(entry.get("permissions"));
            List<String> commands = strings(entry.get("commands"));

            List<String> safeIncludes = rank > 0 ? List.of() : includes;
            ShopProduct product = new ShopProduct(id, name, text(entry.get("description")),
                    price, days, group, tag, permissions, commands, safeIncludes, List.of(), passDays, rank);
            seen.put(id, product);
            products.add(product);
        }
        if (enabled && products.isEmpty()) {
            problems.add("shop.enabled: true, но ни один товар не настроен — каталог пуст");
        }
        List<ShopProduct> resolved = resolveBundles(products, seen, problems);
        return new ShopCatalog(enabled, cap, resolved, problems);
    }

    /**
     * Раскрывает вложенные наборы в плоский список товаров и проверяет ссылки.
     *
     * <p>Результат детерминирован: {@code includes} остаётся тем, что написал админ
     * (для витрины), {@code resolvedIncludes} — листья, которые реально выдаются.
     * Циклы, ссылки на себя и на несуществующие товары отбрасываются с пояснением,
     * а не роняют каталог.</p>
     */
    private static List<ShopProduct> resolveBundles(List<ShopProduct> products,
                                                    Map<String, ShopProduct> byId,
                                                    List<String> problems) {
        List<ShopProduct> result = new ArrayList<>(products.size());
        for (ShopProduct product : products) {
            if (product.includes().isEmpty()) {
                result.add(product);
                continue;
            }
            List<String> leaves = new ArrayList<>();
            List<String> visited = new ArrayList<>();
            collectLeaves(product, byId, leaves, visited, problems, 0);
            if (leaves.isEmpty()) {
                problems.add("shop.products (" + product.id() + "): набор не содержит ни одного "
                        + "существующего товара — покупка выдаст только сам набор");
            }
            result.add(new ShopProduct(product.id(), product.name(), product.description(),
                    product.priceRubles(), product.days(), product.group(), product.tag(),
                    product.permissions(), product.commands(), product.includes(), leaves,
                    product.passDays(), product.rank()));
        }
        for (ShopProduct bundle : result) {
            if (bundle.resolvedIncludes().isEmpty() || bundle.hasPassDays()) {
                // Набор с днями проходки не проверяем: у проходки своя цена по тарифам,
                // и любая оценка здесь была бы выдумкой.
                continue;
            }
            int partsPrice = 0;
            boolean complete = true;
            for (String id : bundle.includes()) {
                ShopProduct part = byId.get(id);
                if (part == null) {
                    complete = false;
                    break;
                }
                partsPrice += part.priceRubles();
            }
            if (complete && bundle.priceRubles() >= partsPrice) {
                problems.add("shop.products (" + bundle.id() + "): набор стоит "
                        + bundle.priceRubles() + " ₽, а те же товары по отдельности — " + partsPrice
                        + " ₽. Скидки нет, покупатель выберет поштучно");
            }
        }
        return result;
    }

    /** Рекурсивно собирает листья набора; глубина ограничена, циклы отсекаются. */
    private static void collectLeaves(ShopProduct bundle, Map<String, ShopProduct> byId,
                                      List<String> leaves, List<String> visited,
                                      List<String> problems, int depth) {
        if (depth > MAX_BUNDLE_DEPTH) {
            problems.add("shop.products (" + bundle.id() + "): вложенность наборов больше "
                    + MAX_BUNDLE_DEPTH + " — лишние уровни пропущены");
            return;
        }
        for (String include : bundle.includes()) {
            if (include.equals(bundle.id())) {
                problems.add("shop.products (" + bundle.id() + "): набор включает сам себя — ссылка пропущена");
                continue;
            }
            ShopProduct part = byId.get(include);
            if (part == null) {
                problems.add("shop.products (" + bundle.id() + "): в includes указан неизвестный "
                        + "товар '" + include + "' — пропущен");
                continue;
            }
            if (part.includes().isEmpty()) {
                if (!leaves.contains(part.id())) {
                    leaves.add(part.id());
                }
                continue;
            }
            if (visited.contains(part.id())) {
                problems.add("shop.products (" + bundle.id() + "): циклическая ссылка на набор '"
                        + part.id() + "' — пропущена");
                continue;
            }
            visited.add(part.id());
            collectLeaves(part, byId, leaves, visited, problems, depth + 1);
            visited.remove(visited.size() - 1);
        }
    }

    /** Нормализует список id: нижний регистр, без пустых и повторов. */
    private static List<String> ids(List<String> raw) {
        List<String> result = new ArrayList<>(raw.size());
        for (String id : raw) {
            String normalized = id.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && !result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return List.copyOf(result);
    }

    public boolean enabled() {
        return enabled;
    }

    /** Каталог включён и в нём есть хотя бы один корректный товар. */
    public boolean usable() {
        return enabled && !products.isEmpty();
    }

    public boolean isEmpty() {
        return products.isEmpty();
    }

    public int maxTotalDays() {
        return maxTotalDays;
    }

    public List<ShopProduct> products() {
        return products;
    }

    /** Проблемы разбора — для лога и {@code /pass doctor}. */
    public List<String> problems() {
        return problems;
    }

    public Optional<ShopProduct> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String key = id.trim().toLowerCase(Locale.ROOT);
        for (ShopProduct product : products) {
            if (product.id().equals(key)) {
                return Optional.of(product);
            }
        }
        return Optional.empty();
    }

    /** Подсказка для tab-дополнения и сообщений об ошибке. */
    public List<String> ids() {
        List<String> result = new ArrayList<>(products.size());
        for (ShopProduct product : products) {
            result.add(product.id());
        }
        return result;
    }

    /* ---------------------------- разбор значений ---------------------------- */

    private static String text(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private static int intOf(Object raw, int fallback) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            String value = text(item);
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Проверка разметки ника: незакрытые <b>открывающие</b> теги допустимы — их закроет
     * {@link ShopEntitlements#decorate(String, String)}, а вот лишний закрывающий тег или
     * обрыв {@code <gold} без {@code >} сломали бы разбор всего сообщения.
     */
    static boolean balanced(String tag) {
        java.util.ArrayDeque<String> open = new java.util.ArrayDeque<>();
        int index = 0;
        while (index < tag.length()) {
            int start = tag.indexOf('<', index);
            if (start < 0) {
                break;
            }
            int end = tag.indexOf('>', start);
            if (end < 0) {
                return false; // обрыв тега
            }
            String inside = tag.substring(start + 1, end).trim();
            if (inside.isEmpty() || inside.indexOf('<') >= 0) {
                return false;
            }
            if (inside.startsWith("/")) {
                if (open.isEmpty()) {
                    return false; // закрывающий тег без открывающего
                }
                open.pop();
            } else {
                open.push(inside);
            }
            index = end + 1;
        }
        int stray = tag.indexOf('>', index);
        return stray < 0; // '>' вне тега — мусор
    }
}
