package ru.cherdakmd.chrdktierforge;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Книга коллекции одного игрока: какие редкости, свойства тиров и аффиксы он
 * уже встречал (1.4.150).
 *
 * <p>Класс без Bukkit-событий и без обращений к игроку — только множества,
 * проверки каталога и сериализация. Благодаря этому коллекция проверяется
 * юнит-тестами, а плагин лишь говорит, что выпало.</p>
 *
 * <p>Методы синхронизированы: запись идёт из игровых событий, а сейв
 * {@code stats.yml} — из асинхронной таски.</p>
 *
 * <p>Идентификаторы проверяются по реестрам ({@link TierProperties},
 * {@link TierAffixes}): испорченный файл или удалённое в будущем свойство не
 * раздуют счётчик и не сломают прогресс.</p>
 */
public final class TierCollectionBook {

    /** Сколько редкостей в каталоге (7). */
    public static final int TIER_TOTAL = TierDef.LADDER.size();

    /** Весь каталог: редкости + свойства + аффиксы (7 + 105 + 30 = 142). */
    public static final int CATALOG_TOTAL =
            TIER_TOTAL + TierCollectionConfig.PROPERTY_TOTAL + TierCollectionConfig.AFFIX_TOTAL;

    private final Set<String> tiers = new LinkedHashSet<>();
    private final Set<String> properties = new LinkedHashSet<>();
    private final Set<String> affixes = new LinkedHashSet<>();
    private final Set<String> claimed = new LinkedHashSet<>();

    /** Что нового открылось за одно событие. Пустые списки — дубль. */
    public record Discovery(String tier, List<String> properties, List<String> affixes) {

        public static final Discovery EMPTY = new Discovery(null, List.of(), List.of());

        public Discovery {
            properties = List.copyOf(properties == null ? List.of() : properties);
            affixes = List.copyOf(affixes == null ? List.of() : affixes);
        }

        public boolean isEmpty() {
            return tier == null && properties.isEmpty() && affixes.isEmpty();
        }

        /** Сколько всего новых позиций (для сообщения одной строкой). */
        public int total() {
            return (tier == null ? 0 : 1) + properties.size() + affixes.size();
        }
    }

    /**
     * Голые счётчики книги — без копирования множеств. Нужны там, где важна
     * только цифра: титул в чате, строка `/tiers stats`, рейтинг.
     */
    public record Progress(int tiers, int properties, int affixes) {

        public static final Progress EMPTY = new Progress(0, 0, 0);

        public int total() {
            return tiers + properties + affixes;
        }

        public int percent() {
            return percentOf(total());
        }

        /** Сколько собрано в разделе {@code tiers|affixes|properties|all}. */
        public int count(String section) {
            if (section == null) {
                return 0;
            }
            return switch (section) {
                case "tiers" -> tiers;
                case "properties" -> properties;
                case "affixes" -> affixes;
                case "all" -> total();
                default -> 0;
            };
        }

        /** Закрыт ли раздел целиком. */
        public boolean complete(String section) {
            int threshold = TierCollectionConfig.threshold(section);
            return threshold > 0 && count(section) >= threshold;
        }

        /** Процент закрытия раздела (0..100). */
        public int sectionPercent(String section) {
            int threshold = TierCollectionConfig.threshold(section);
            return threshold <= 0 ? 0 : (int) (Math.min(count(section), threshold) * 100L / threshold);
        }
    }

    /** Неизменяемый снимок для GUI и команд: можно читать из любого потока. */
    public record Snapshot(Set<String> tiers, Set<String> properties, Set<String> affixes) {

        public static final Snapshot EMPTY = new Snapshot(Set.of(), Set.of(), Set.of());

        public Snapshot {
            tiers = Set.copyOf(tiers == null ? Set.of() : tiers);
            properties = Set.copyOf(properties == null ? Set.of() : properties);
            affixes = Set.copyOf(affixes == null ? Set.of() : affixes);
        }

        public boolean hasTier(String id) {
            return id != null && tiers.contains(id);
        }

        public boolean hasProperty(String id) {
            return id != null && properties.contains(id);
        }

        public boolean hasAffix(String id) {
            return id != null && affixes.contains(id);
        }

        public int tierCount() {
            return tiers.size();
        }

        public int propertyCount() {
            return properties.size();
        }

        public int affixCount() {
            return affixes.size();
        }

        public int total() {
            return tiers.size() + properties.size() + affixes.size();
        }

        /** Счётчики без копий — для титулов и рейтинга. */
        public Progress progress() {
            return new Progress(tiers.size(), properties.size(), affixes.size());
        }

        public boolean complete(String section) {
            return progress().complete(section);
        }

        /** Процент собранного от всего каталога (округление вниз). */
        public int percent() {
            return percentOf(total());
        }
    }

    /** Сколько процентов каталога составляют {@code collected} позиций. */
    public static int percentOf(int collected) {
        if (collected <= 0) {
            return 0;
        }
        int clamped = Math.min(collected, CATALOG_TOTAL);
        return (int) (clamped * 100L / CATALOG_TOTAL);
    }

    /**
     * Отмечает добычу игрока.
     *
     * @param tierId      тир предмета (может быть {@code null}/неизвестным)
     * @param propertyIds свойства тира из PDC
     * @param affixIds    аффиксы, выведенные из {@code modifier_uid}
     * @return что открылось впервые
     */
    public synchronized Discovery discover(String tierId, Collection<String> propertyIds,
                                           Collection<String> affixIds) {
        String newTier = null;
        if (TierDef.isTier(tierId) && tiers.add(tierId)) {
            newTier = tierId;
        }
        List<String> newProperties = new ArrayList<>();
        if (propertyIds != null) {
            for (String id : propertyIds) {
                TierProperty property = TierProperties.byId(id);
                if (property != null && properties.add(property.id())) {
                    newProperties.add(property.id());
                }
            }
        }
        List<String> newAffixes = new ArrayList<>();
        if (affixIds != null) {
            for (String id : affixIds) {
                TierAffix affix = TierAffixes.byId(id);
                if (affix != null && affixes.add(affix.id())) {
                    newAffixes.add(affix.id());
                }
            }
        }
        return newTier == null && newProperties.isEmpty() && newAffixes.isEmpty()
                ? Discovery.EMPTY
                : new Discovery(newTier, newProperties, newAffixes);
    }

    public synchronized boolean hasTier(String id) {
        return id != null && tiers.contains(id);
    }

    public synchronized boolean hasProperty(String id) {
        return id != null && properties.contains(id);
    }

    public synchronized boolean hasAffix(String id) {
        return id != null && affixes.contains(id);
    }

    public synchronized int tierCount() {
        return tiers.size();
    }

    public synchronized int propertyCount() {
        return properties.size();
    }

    public synchronized int affixCount() {
        return affixes.size();
    }

    public synchronized int total() {
        return tiers.size() + properties.size() + affixes.size();
    }

    public synchronized int percent() {
        return percentOf(total());
    }

    /** Голые счётчики — дешёвая альтернатива {@link #snapshot()}. */
    public synchronized Progress progress() {
        return new Progress(tiers.size(), properties.size(), affixes.size());
    }

    /**
     * Отмечает веху как выплаченную. {@code true} — веха была новой, награду
     * нужно выдать; {@code false} — уже закрыта (повторной выплаты нет).
     */
    public synchronized boolean claim(String milestoneKey) {
        return milestoneKey != null && !milestoneKey.isBlank() && claimed.add(milestoneKey);
    }

    public synchronized boolean isClaimed(String milestoneKey) {
        return milestoneKey != null && claimed.contains(milestoneKey);
    }

    public synchronized Set<String> claimed() {
        return Set.copyOf(claimed);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(tiers, properties, affixes);
    }

    /**
     * Запись в секцию {@code stats.yml}. Порядок — по каталогу: файл не
     * «прыгает» между сейвами, а diff остаётся читаемым.
     */
    public synchronized void writeTo(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        section.set("tiers", ordered(tiers, TierDef.LADDER));
        List<String> propertyOrder = new ArrayList<>(TierCollectionConfig.PROPERTY_TOTAL);
        for (TierProperty property : TierProperties.all()) {
            propertyOrder.add(property.id());
        }
        section.set("properties", ordered(properties, propertyOrder));
        List<String> affixOrder = new ArrayList<>(TierCollectionConfig.AFFIX_TOTAL);
        for (TierAffix affix : TierAffixes.all()) {
            affixOrder.add(affix.id());
        }
        section.set("affixes", ordered(affixes, affixOrder));
        section.set("claimed", claimed.stream().sorted().toList());
    }

    /** Чтение из {@code stats.yml}; неизвестные иды отбрасываются. */
    public synchronized void read(ConfigurationSection section) {
        tiers.clear();
        properties.clear();
        affixes.clear();
        claimed.clear();
        if (section == null) {
            return;
        }
        for (String id : section.getStringList("tiers")) {
            if (TierDef.isTier(id)) {
                tiers.add(id);
            }
        }
        for (String id : section.getStringList("properties")) {
            TierProperty property = TierProperties.byId(id);
            if (property != null) {
                properties.add(property.id());
            }
        }
        for (String id : section.getStringList("affixes")) {
            TierAffix affix = TierAffixes.byId(id);
            if (affix != null) {
                affixes.add(affix.id());
            }
        }
        for (String key : section.getStringList("claimed")) {
            if (key != null && !key.isBlank()) {
                claimed.add(key.trim());
            }
        }
    }

    /** Иды в порядке каталога; незнакомые (из будущих версий) — в конце. */
    private static List<String> ordered(Set<String> found, List<String> catalog) {
        List<String> out = new ArrayList<>(found.size());
        for (String id : catalog) {
            if (found.contains(id)) {
                out.add(id);
            }
        }
        for (String id : found) {
            if (!catalog.contains(id)) {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }
}
