package ru.cherdakmd.chrdktreasures;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Обменник осколков кладов (1.4.166): каталог предложений из {@code shards.shop}.
 *
 * <p>Осколки — не предмет, а счётчик в профиле кладоискателя: их нельзя
 * потерять при смерти и не нужно носить в рюкзаке. Копятся они за находки, а
 * тратятся здесь — на именную находку нужной редкости, книгу чар, реликвию или
 * обычные ресурсы. Правила разбора простые: цена обязательна и положительна,
 * неизвестный вид предложения или материал пропускается с предупреждением, а
 * иды не повторяются — иначе выгодное предложение можно было бы выбрать
 * дважды в одном меню.</p>
 *
 * <p>Класс не трогает ни мир, ни данные игроков: только чтение конфига и
 * нормализация. Поэтому каталог проверяется тестами без сервера.</p>
 */
public final class TreasureShardShop {

    /** Больше одного экрана предложений: меню на 54 слота, сетка — 21 ячейка. */
    public static final int MAX_OFFERS = 21;
    /** Потолок цены: защита от опечатки «250000» вместо «250». */
    public static final int MAX_COST = 100_000;

    /** Вид предложения. */
    public enum Kind {
        /** Именная находка случайной вещи указанной редкости. */
        NAMED("named"),
        /** Случайная светлая реликвия CHRDK_Artifacts. */
        ARTIFACT("artifact"),
        /** Книга случайной светлой чары указанного уровня. */
        ENCHANT("enchant"),
        /** Обычный предмет пачкой. */
        ITEM("item");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** Разбор вида без учёта регистра; {@code null} — неизвестный. */
        public static Kind byId(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (Kind kind : values()) {
                if (kind.id.equals(normalized)) {
                    return kind;
                }
            }
            return null;
        }
    }

    /**
     * Одно предложение обменника.
     *
     * @param id       ид предложения (уникальный в каталоге)
     * @param kind     вид награды
     * @param cost     цена в осколках (1..{@link #MAX_COST})
     * @param tier     редкость для {@link Kind#NAMED} и подпись остальным
     * @param level    уровень книги для {@link Kind#ENCHANT} (1..5)
     * @param material материал для {@link Kind#ITEM} (пусто — не используется)
     * @param amount   количество для {@link Kind#ITEM} (1..64)
     * @param name     особое имя предмета для {@link Kind#ITEM} (MiniMessage, пусто — без имени)
     */
    public record Offer(String id, Kind kind, int cost, TreasureTier tier, int level,
                        String material, int amount, String name) {

        public Offer {
            id = id == null || id.isBlank() ? "offer" : id.trim();
            kind = kind == null ? Kind.ITEM : kind;
            cost = Math.max(1, Math.min(MAX_COST, cost));
            tier = tier == null ? TreasureTier.COMMON : tier;
            level = Math.max(1, Math.min(5, level));
            material = material == null ? "" : material.trim().toUpperCase(Locale.ROOT);
            amount = Math.max(1, Math.min(64, amount));
            name = name == null ? "" : name.trim();
        }

        /** Материал для {@link Kind#ITEM} или {@code null}, если он битый. */
        public Material itemMaterial() {
            return kind == Kind.ITEM ? Material.matchMaterial(material) : null;
        }

        /** Короткая подпись для логов и тестов. */
        public String describe() {
            return switch (kind) {
                case NAMED -> "named/" + tier.id();
                case ARTIFACT -> "artifact";
                case ENCHANT -> "enchant/" + level;
                case ITEM -> material + "×" + amount;
            };
        }
    }

    private TreasureShardShop() {
    }

    /**
     * Разбирает секцию {@code shards.shop}.
     *
     * @param section секция списка или {@code null}
     * @param logger  журнал для предупреждений о битых записях
     * @return неизменяемый список предложений (может быть пустым)
     */
    public static List<Offer> parse(ConfigurationSection section, Logger logger) {
        if (section == null) {
            return List.of();
        }
        List<Map<?, ?>> rows = section.getMapList("shop");
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Offer> offers = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Map<?, ?> row : rows) {
            if (offers.size() >= MAX_OFFERS) {
                warn(logger, "shards.shop: больше " + MAX_OFFERS
                        + " предложений — лишние пропущены");
                break;
            }
            Offer offer = parseOffer(row, offers.size(), logger);
            if (offer == null) {
                continue;
            }
            String id = offer.id();
            int suffix = 2;
            while (!ids.add(id)) {
                id = offer.id() + '-' + suffix++;
            }
            offers.add(id.equals(offer.id()) ? offer : new Offer(id, offer.kind(),
                    offer.cost(), offer.tier(), offer.level(), offer.material(),
                    offer.amount(), offer.name()));
        }
        return List.copyOf(offers);
    }

    private static Offer parseOffer(Map<?, ?> row, int index, Logger logger) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        row.forEach((key, value) -> {
            if (key != null) {
                map.put(String.valueOf(key).toLowerCase(Locale.ROOT), value);
            }
        });
        Kind kind = Kind.byId(string(map.get("type")));
        if (kind == null) {
            warn(logger, "shards.shop[" + index + "]: неизвестный type «"
                    + string(map.get("type")) + "» — предложение пропущено");
            return null;
        }
        int cost = number(map.get("cost"), 0);
        if (cost <= 0) {
            warn(logger, "shards.shop[" + index + "]: цена должна быть больше нуля"
                    + " — предложение пропущено");
            return null;
        }
        String material = string(map.get("material"));
        int amount = number(map.get("amount"), 1);
        int level = number(map.get("level"), 1);
        TreasureTier tier = TreasureTier.byId(string(map.get("tier")));
        String id = string(map.get("id"));
        String name = string(map.get("name"));

        if (kind == Kind.ITEM) {
            Material parsed = Material.matchMaterial(material);
            if (parsed == null || material.isBlank()) {
                warn(logger, "shards.shop[" + index + "]: материал «" + material
                        + "» не найден — предложение пропущено");
                return null;
            }
            material = parsed.name();
        }
        return new Offer(id, kind, cost, tier, level, material, amount, name);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return (int) Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException broken) {
            return fallback;
        }
    }

    private static void warn(Logger logger, String message) {
        if (logger != null) {
            logger.warning("[CHRDK_Treasures] " + message);
        }
    }
}
