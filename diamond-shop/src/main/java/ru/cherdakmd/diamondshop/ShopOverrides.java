package ru.cherdakmd.diamondshop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Правки каталога «вне конфига» (7 ноября 2026).
 *
 * <p>Администратор меняет и удаляет товары через GUI {@code /shopadmin edit};
 * результат живёт в {@code shop-overrides.json} в папке плагина, а config.yml
 * остаётся эталоном и не перезаписывается. При загрузке эталон читается как
 * обычно, после чего {@link #apply} убирает удалённые товары и накладывает
 * изменённые поля. Битый или неизвестный JSON не валит магазин: он логируется,
 * и работает чистый каталог из config.yml.</p>
 *
 * <p>Формат: {@code {"schema":1,"removed":["..."],"changes":{"MATERIAL":{...}}}}.
 * Поля change необязательны: отсутствующее поле берётся из config.yml. Все
 * значения валидируются здесь же, чтобы GUI и команда не могли записать
 * некорректную цену, запас или тариф.</p>
 */
public final class ShopOverrides {

    public static final int SCHEMA = 1;
    private static final String FILE_NAME = "shop-overrides.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Set<String> FIELDS = Set.of(
            "base-price", "ref-stock", "min-multiplier", "max-multiplier",
            "sell-enabled", "purchase-multiplier", "category");

    /** Допустимые разделы (кроме «Все»): смена категории не может создать вкладку-призрак. */
    private static final List<String> CATEGORIES = Arrays.stream(ShopCategory.values())
            .map(ShopCategory::id).filter(id -> !"all".equals(id)).toList();

    /** Одна правка товара: {@code null} — поле не тронуто, берётся из config.yml. */
    public record Change(
            Double basePrice,
            Long refStock,
            Double minMultiplier,
            Double maxMultiplier,
            Boolean sellEnabled,
            Double purchaseMultiplier,
            String category
    ) {
        public static final Change EMPTY = new Change(null, null, null, null, null, null, null);

        /** Накладывает правку на эталонный товар, сохраняя ключ книги чар. */
        public ShopConfig.ShopItem applyTo(ShopConfig.ShopItem item) {
            return new ShopConfig.ShopItem(
                    item.material(),
                    category == null ? item.category() : category,
                    basePrice == null ? item.basePrice() : basePrice,
                    refStock == null ? item.refStock() : refStock,
                    minMultiplier == null ? item.minMultiplier() : minMultiplier,
                    maxMultiplier == null ? item.maxMultiplier() : maxMultiplier,
                    sellEnabled == null ? item.sellEnabled() : sellEnabled,
                    purchaseMultiplier == null ? item.purchaseMultiplier() : purchaseMultiplier,
                    item.book());
        }

        public JsonObject toJson() {
            JsonObject object = new JsonObject();
            if (basePrice != null) object.addProperty("base-price", basePrice);
            if (refStock != null) object.addProperty("ref-stock", refStock);
            if (minMultiplier != null) object.addProperty("min-multiplier", minMultiplier);
            if (maxMultiplier != null) object.addProperty("max-multiplier", maxMultiplier);
            if (sellEnabled != null) object.addProperty("sell-enabled", sellEnabled);
            if (purchaseMultiplier != null) object.addProperty("purchase-multiplier", purchaseMultiplier);
            if (category != null) object.addProperty("category", category);
            return object;
        }
    }

    /** Пустые правки для тестов и для плагина до появления файла. */
    public static final ShopOverrides EMPTY = new ShopOverrides(null, null, Map.of(), Set.of());

    private final Path file;
    private final Logger logger;
    private final Map<String, Change> changes = new LinkedHashMap<>();
    private final Set<String> removed = new LinkedHashSet<>();

    private ShopOverrides(Path file, Logger logger, Map<String, Change> changes, Set<String> removed) {
        this.file = file;
        this.logger = logger;
        this.changes.putAll(changes);
        this.removed.addAll(removed);
    }

    /** Загружает правки из папки плагина; отсутствие файла — норма, не ошибка. */
    public static ShopOverrides load(Path dataFolder, Logger logger) {
        ShopOverrides result = new ShopOverrides(dataFolder.resolve(FILE_NAME), logger, Map.of(), Set.of());
        result.read();
        return result;
    }

    /** Полная независимая копия — GUI редактирует её до нажатия «Сохранить». */
    public synchronized ShopOverrides copy() {
        return new ShopOverrides(file, logger, changes, removed);
    }

    public synchronized Map<String, Change> changes() {
        return Map.copyOf(changes);
    }

    public synchronized Set<String> removed() {
        return Set.copyOf(removed);
    }

    public synchronized Change change(String material) {
        return changes.getOrDefault(normalize(material), Change.EMPTY);
    }

    public synchronized boolean isRemoved(String material) {
        return removed.contains(normalize(material));
    }

    /** Удаляет товар из витрины, даже если строка осталась в config.yml владельца. */
    public synchronized void remove(String material) {
        String key = normalize(material);
        removed.add(key);
        changes.remove(key);
    }

    /** Откатывает и удаление, и все правки товара — возвращает эталон из config.yml. */
    public synchronized void restore(String material) {
        String key = normalize(material);
        removed.remove(key);
        changes.remove(key);
    }

    /**
     * Меняет одно поле. Возвращает {@code false}, если значение не прошло
     * валидацию (тогда правка не записывается).
     */
    public synchronized boolean set(String material, String field, String value) {
        String key = normalize(material);
        if (key.isEmpty() || !FIELDS.contains(field)) {
            return false;
        }
        Change current = changes.getOrDefault(key, Change.EMPTY);
        Object parsed = parseField(field, value);
        if (parsed == null) {
            return false;
        }
        Change next = switch (field) {
            case "base-price" -> new Change((Double) parsed, current.refStock(),
                    current.minMultiplier(), current.maxMultiplier(), current.sellEnabled(),
                    current.purchaseMultiplier(), current.category());
            case "ref-stock" -> new Change(current.basePrice(), (Long) parsed,
                    current.minMultiplier(), current.maxMultiplier(), current.sellEnabled(),
                    current.purchaseMultiplier(), current.category());
            case "min-multiplier" -> new Change(current.basePrice(), current.refStock(),
                    (Double) parsed, current.maxMultiplier(), current.sellEnabled(),
                    current.purchaseMultiplier(), current.category());
            case "max-multiplier" -> new Change(current.basePrice(), current.refStock(),
                    current.minMultiplier(), (Double) parsed, current.sellEnabled(),
                    current.purchaseMultiplier(), current.category());
            case "sell-enabled" -> new Change(current.basePrice(), current.refStock(),
                    current.minMultiplier(), current.maxMultiplier(), (Boolean) parsed,
                    current.purchaseMultiplier(), current.category());
            case "purchase-multiplier" -> new Change(current.basePrice(), current.refStock(),
                    current.minMultiplier(), current.maxMultiplier(), current.sellEnabled(),
                    (Double) parsed, current.category());
            default -> new Change(current.basePrice(), current.refStock(),
                    current.minMultiplier(), current.maxMultiplier(), current.sellEnabled(),
                    current.purchaseMultiplier(), (String) parsed);
        };
        if (next.equals(Change.EMPTY)) {
            changes.remove(key);
        } else {
            changes.put(key, next);
        }
        removed.remove(key);
        return true;
    }

    /** Допустимые значения поля для подсказки в GUI и таб-комплита. */
    public static Set<String> fields() {
        return FIELDS;
    }

    public static List<String> categories() {
        return CATEGORIES;
    }

    private static Object parseField(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            return switch (field) {
                case "base-price" -> bounded(Double.parseDouble(value), 0.01d, 10_000d);
                case "ref-stock" -> {
                    Double parsedStock = bounded(Double.parseDouble(value), 1d, 1_000_000d);
                    yield parsedStock == null ? null : parsedStock.longValue();
                }
                case "min-multiplier" -> bounded(Double.parseDouble(value), 0.01d, 1d);
                case "max-multiplier" -> bounded(Double.parseDouble(value), 1d, 100d);
                case "purchase-multiplier" -> bounded(Double.parseDouble(value), 1d, 100d);
                case "sell-enabled" -> switch (value.toLowerCase(Locale.ROOT)) {
                    case "true", "on", "да", "1" -> Boolean.TRUE;
                    case "false", "off", "нет", "0" -> Boolean.FALSE;
                    default -> null;
                };
                case "category" -> {
                    String id = value.toLowerCase(Locale.ROOT);
                    yield CATEGORIES.contains(id) ? id : null;
                }
                default -> null;
            };
        } catch (NumberFormatException error) {
            return null;
        }
    }

    /**
     * Строгая граница: {@code null} для NaN, бесконечности и значений вне
     * диапазона. Молча клампить нельзя — администратор должен увидеть отказ,
     * а не обнаружить в витрине цену 0.01 вместо введённой −4.
     */
    private static Double bounded(double value, double min, double max) {
        return Double.isFinite(value) && value >= min && value <= max ? value : null;
    }

    /**
     * Накладывает правки на эталонный каталог. {@code retired} — список из
     * config.yml ({@code catalog.retired-materials}); он не даёт удалённому
     * товару вернуться ни через миграцию, ни через пользовательский каталог.
     */
    public synchronized List<ShopConfig.ShopItem> apply(List<ShopConfig.ShopItem> source,
                                                       Set<String> retired) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        Set<String> gone = new HashSet<>(retired == null ? Set.of() : retired);
        for (String key : removed) {
            gone.add(key.toUpperCase(Locale.ROOT));
        }
        Map<String, Change> snapshot = Map.copyOf(changes);
        List<ShopConfig.ShopItem> result = new ArrayList<>(source.size());
        Set<String> seen = new HashSet<>();
        for (ShopConfig.ShopItem item : source) {
            String key = item.material().toUpperCase(Locale.ROOT);
            if (gone.contains(key)) {
                continue;
            }
            Change change = snapshot.get(key);
            ShopConfig.ShopItem effective = change == null ? item : change.applyTo(item);
            if (!seen.add(effective.material().toUpperCase(Locale.ROOT))) {
                continue;
            }
            result.add(effective);
        }
        return List.copyOf(result);
    }

    /** Атомарно записывает правки. Пустой объект всё равно сохраняется как файл. */
    public synchronized void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = toJson();
            Path temp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(temp, GSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException error) {
            if (logger != null) {
                logger.log(Level.WARNING, "Правки каталога не сохранены: " + error.getMessage(), error);
            }
        }
    }

    /** Заменяет содержимое этого объекта данными черновика GUI. */
    public synchronized void replaceWith(ShopOverrides draft) {
        Map<String, Change> nextChanges = draft.changes();
        Set<String> nextRemoved = draft.removed();
        changes.clear();
        changes.putAll(nextChanges);
        removed.clear();
        removed.addAll(nextRemoved);
    }

    public synchronized JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        JsonArray removedArray = new JsonArray();
        removed.stream().sorted().forEach(removedArray::add);
        root.add("removed", removedArray);
        JsonObject changesObject = new JsonObject();
        changes.forEach((material, change) -> changesObject.add(material, change.toJson()));
        root.add("changes", changesObject);
        return root;
    }

    private void read() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject root = ShopJson.read(file);
            for (String key : root.keySet()) {
                if (!Set.of("schema", "removed", "changes").contains(key)) {
                    throw new IOException("неизвестное поле правок: " + key);
                }
            }
            int schema = integer(root, "schema");
            if (schema != SCHEMA) {
                throw new IOException("неподдерживаемая schema правок: " + schema);
            }
            Map<String, Change> nextChanges = new LinkedHashMap<>();
            Set<String> nextRemoved = new LinkedHashSet<>();
            if (root.has("removed")) {
                if (!root.get("removed").isJsonArray()) {
                    throw new IOException("removed должен быть списком");
                }
                for (JsonElement element : root.getAsJsonArray("removed")) {
                    if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                        throw new IOException("removed: ожидалась строка");
                    }
                    nextRemoved.add(normalize(element.getAsString()));
                }
            }
            if (root.has("changes")) {
                JsonObject table = root.getAsJsonObject("changes");
                for (Map.Entry<String, JsonElement> entry : table.entrySet()) {
                    String material = normalize(entry.getKey());
                    if (material.isEmpty() || !entry.getValue().isJsonObject()) {
                        throw new IOException("changes: неверный товар " + entry.getKey());
                    }
                    JsonObject object = entry.getValue().getAsJsonObject();
                    // Один probe на товар: все поля проходят через единый валидатор set()
                    // и накапливаются, а не перезаписывают друг друга.
                    ShopOverrides probe = new ShopOverrides(null, null, Map.of(), Set.of());
                    for (Map.Entry<String, JsonElement> field : object.entrySet()) {
                        if (!FIELDS.contains(field.getKey()) || !field.getValue().isJsonPrimitive()) {
                            throw new IOException("changes: неверное поле " + field.getKey());
                        }
                        String raw = field.getValue().getAsJsonPrimitive().getAsString();
                        if (!probe.set(material, field.getKey(), raw)) {
                            throw new IOException("changes: неверное значение " + field.getKey());
                        }
                    }
                    Change change = probe.changes().get(material);
                    if (change != null && !change.equals(Change.EMPTY)) {
                        nextChanges.put(material, change);
                    }
                }
            }
            changes.clear();
            changes.putAll(nextChanges);
            removed.clear();
            removed.addAll(nextRemoved);
        } catch (IOException | RuntimeException error) {
            if (logger != null) {
                logger.log(Level.WARNING, "[DiamondShop] shop-overrides.json не прочитан, работает каталог из config.yml: "
                        + error.getMessage(), error);
            }
        }
    }

    private static int integer(JsonObject root, String key) throws IOException {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IOException("отсутствует " + key);
        }
        return element.getAsInt();
    }

    private static String normalize(String material) {
        return material == null ? "" : material.trim().toUpperCase(Locale.ROOT);
    }
}
