package ru.cherdakmd.chrdkregister.item;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Консервативное объединение {@code attribute_modifiers} из нескольких источников.
 *
 * <p>В 1.20.5+/1.21+ модификатор предмета идентифицируется парой
 * {@code (Attribute, NamespacedKey)}. Устаревший UUID-геттер вычисляется из
 * ключа, а не хранится отдельно; {@code CraftMetaItem#addAttributeModifier}
 * отвергает дубликат по атрибуту и {@code AttributeModifier#getKey()},
 * {@code removeAttributeModifier} тоже сопоставляет ключ. Поэтому «конфликт
 * UUID» на современном API — это почти всегда конфликт/переиспользование
 * {@code NamespacedKey} внутри одного атрибута, полная перезапись компонента
 * или потеря базы при очистке и пересборке.</p>
 *
 * <p>Утилита не удаляет «все свои» по широкому namespace/префиксу и не пишет
 * пустую карту: пустая карта перекрывает ванильные значения, а {@code null}
 * снимает компонент. Она собирает полную multimap заново из трёх групп:</p>
 * <ol>
 *   <li>не-owned модификаторы источника (база Mine Treasure, чужие плагины);</li>
 *   <li>текущие модификаторы результата (чары, тиры, чужие плагины);</li>
 *   <li>owned-модификаторы источника и ванильные defaults, которых ещё нет.</li>
 * </ol>
 *
 * <p>Если два разных источника всё же пришли с одной парой
 * {@code (атрибут, ключ)}, но разными amount/operation/slot, более поздний
 * (или owned) модификатор получает детерминированный суффикс
 * {@code _compat_<sha256>} в том же namespace и с тем же префиксом ключа.
 * Так данные не склеиваются и не теряются, а штатная очистка плагина по
 * namespace/префиксу продолжает находить свой модификатор. Базовый ключ
 * Mine Treasure/ванили сохраняется без переименования: foreign-модификаторы
 * источника обрабатываются первыми.</p>
 *
 * <p>Ванильные defaults добавляются только для атрибутов, которые источник
 * явно не переопределил своим базовым модификатором. Это исключает двойной
 * базовый урон/скорость атаки, когда {@link ItemAttributes#keep} вернул
 * ванильное значение поверх кастомной базы Mine Treasure. Чистая половина
 * класса проверяется юнит-тестами без серверного реестра.</p>
 */
public final class ItemAttributeMerge {

    /** Один модификатор с пометкой источника, принадлежности и происхождения. */
    public record Entry(Attribute attribute, AttributeModifier modifier, String source,
                        boolean owned, boolean fromExplicit) {
    }

    /** Снимок компонента одного предмета-источника. */
    public record Snapshot(Material type, List<Entry> entries, Set<Attribute> customBaseAttributes) {
        public boolean hasCustomBase() {
            return !customBaseAttributes.isEmpty();
        }
    }

    private enum AddMode { NORMAL, IF_ABSENT, BASE_DEFAULT }

    private ItemAttributeMerge() {
    }

    /**
     * Стабильный идентификатор модификатора внутри предмета. UUID здесь
     * намеренно не используется: он производный от ключа и одинаков для двух
     * разных атрибутов с одним {@code NamespacedKey}.
     */
    public static String stableId(Attribute attribute, AttributeModifier modifier) {
        return attributeKey(attribute) + "|"
                + (modifier == null || modifier.getKey() == null ? "?" : modifier.getKey().toString());
    }

    /** Полный отпечаток для проверки «нужно ли перезаписывать компонент». */
    private static String signature(Attribute attribute, AttributeModifier modifier) {
        return stableId(attribute, modifier) + "|" + modifier.getAmount() + "|"
                + modifier.getOperation() + "|" + modifier.getSlotGroup();
    }

    /** Снимок предмета в серверном рантайме. */
    public static Snapshot capture(ItemStack item, Set<String> ownedNamespaces, String source) {
        if (item == null) {
            return new Snapshot(null, List.of(), Set.of());
        }
        ItemMeta meta = item.getItemMeta();
        Multimap<Attribute, AttributeModifier> explicit = meta != null && meta.hasAttributeModifiers()
                ? meta.getAttributeModifiers() : null;
        return capture(item.getType(), explicit, defaults(item), ownedNamespaces, source);
    }

    /**
     * Чистая форма снимка: явный компонент, ванильные defaults и набор
     * namespace, которые считаются «своими». Не требует серверного реестра.
     */
    public static Snapshot capture(Material type,
                                   Multimap<Attribute, AttributeModifier> explicit,
                                   Multimap<Attribute, AttributeModifier> defaults,
                                   Set<String> ownedNamespaces,
                                   String source) {
        Set<String> owned = ownedNamespaces == null ? Set.of() : ownedNamespaces;
        List<Entry> entries = new ArrayList<>();
        Set<Attribute> defaultKeysPresent = new LinkedHashSet<>();
        if (explicit != null) {
            for (Map.Entry<Attribute, AttributeModifier> entry : explicit.entries()) {
                Attribute attribute = entry.getKey();
                AttributeModifier modifier = entry.getValue();
                if (attribute == null || modifier == null || modifier.getKey() == null) {
                    continue;
                }
                if (isDefaultKey(defaults, attribute, modifier.getKey())) {
                    defaultKeysPresent.add(attribute);
                }
            }
        }
        Set<Attribute> customBase = new LinkedHashSet<>();
        if (explicit != null) {
            for (Map.Entry<Attribute, AttributeModifier> entry : explicit.entries()) {
                Attribute attribute = entry.getKey();
                AttributeModifier modifier = entry.getValue();
                if (attribute == null || modifier == null || modifier.getKey() == null) {
                    continue;
                }
                NamespacedKey key = modifier.getKey();
                boolean isOwned = owned.contains(key.getNamespace());
                entries.add(new Entry(attribute, modifier, key.getNamespace() + ":" + source,
                        isOwned, true));
                // Кастомная база = не наш и не ванильный ключ, при этом ванильный
                // ключ того же атрибута в компоненте отсутствует. Рядом с ванильным
                // ключом такой модификатор считается аддитивным бонусом, а не заменой базы.
                if (!isOwned && !isDefaultKey(defaults, attribute, key)
                        && !defaultKeysPresent.contains(attribute)) {
                    customBase.add(attribute);
                }
            }
        }
        if (defaults != null) {
            for (Map.Entry<Attribute, AttributeModifier> entry : defaults.entries()) {
                Attribute attribute = entry.getKey();
                AttributeModifier modifier = entry.getValue();
                if (attribute == null || modifier == null || modifier.getKey() == null
                        || customBase.contains(attribute)
                        || containsIdentity(entries, attribute, modifier.getKey())) {
                    continue;
                }
                entries.add(new Entry(attribute, modifier, "vanilla:" + source, false, false));
            }
        }
        return new Snapshot(type, List.copyOf(entries), Set.copyOf(customBase));
    }

    /** Объединить снимки источников в готовый предмет. */
    public static boolean merge(ItemStack target, List<Snapshot> sources, Set<String> ownedNamespaces) {
        if (target == null) {
            return false;
        }
        ItemMeta meta = target.getItemMeta();
        if (meta == null) {
            return false;
        }
        boolean changed = merge(meta, defaults(target), sources, ownedNamespaces);
        if (changed) {
            target.setItemMeta(meta);
        }
        return changed;
    }

    /**
     * Чистая форма объединения. Пишет компонент через
     * {@link ItemMeta#setAttributeModifiers(Multimap)} только если итоговая
     * multimap отличается от текущей; пустой результат снимает компонент, а не
     * перекрывает ванильные defaults пустой картой.
     */
    public static boolean merge(ItemMeta meta,
                                Multimap<Attribute, AttributeModifier> defaults,
                                List<Snapshot> sources,
                                Set<String> ownedNamespaces) {
        if (meta == null) {
            return false;
        }
        Set<String> owned = ownedNamespaces == null ? Set.of() : ownedNamespaces;
        Multimap<Attribute, AttributeModifier> current = meta.hasAttributeModifiers()
                ? meta.getAttributeModifiers() : null;
        Snapshot target = capture(null, current, defaults, owned, "target");

        Set<Attribute> overridden = new LinkedHashSet<>();
        if (sources != null) {
            for (Snapshot source : sources) {
                if (source != null) {
                    overridden.addAll(source.customBaseAttributes());
                }
            }
        }

        // Явный, но пустой компонент перекрывает defaults: если источники не
        // принесли своих модификаторов, его нужно снять, а не дописывать.
        // getAttributeModifiers() возвращает null и для отсутствующего, и для
        // пустого компонента, поэтому ниже используется отдельная проверка
        // getAsComponentString() до/после setAttributeModifiers(null).
        if (current != null && current.isEmpty()
                && !hasNonDefaultEntries(sources, defaults)) {
            return clearEmptyComponent(meta);
        }

        Map<Attribute, LinkedHashMap<NamespacedKey, Entry>> merged = new LinkedHashMap<>();
        // 1. Foreign-база источника сохраняет исходные ключи.
        if (sources != null) {
            for (Snapshot source : sources) {
                if (source == null) {
                    continue;
                }
                for (Entry entry : source.entries()) {
                    if (!entry.owned() && !isVanillaDefault(entry, defaults)) {
                        add(merged, entry, AddMode.NORMAL, "source-base");
                    }
                }
            }
        }
        // 2. Текущий результат: снимаем только ванильный default, который
        //    источник явно переопределил своей базой.
        for (Entry entry : target.entries()) {
            if (isVanillaDefault(entry, defaults) && overridden.contains(entry.attribute())) {
                continue;
            }
            add(merged, entry, AddMode.NORMAL, "target");
        }
        // 3. Owned-модификаторы источника не перекрывают более свежий результат.
        //    Явный ванильный default источника, наоборот, восстанавливает базу,
        //    если плагин записал под тем же ключом другое значение (1.0 → 0).
        if (sources != null) {
            for (Snapshot source : sources) {
                if (source == null) {
                    continue;
                }
                for (Entry entry : source.entries()) {
                    boolean vanilla = isVanillaDefault(entry, defaults);
                    if (entry.owned()) {
                        add(merged, entry, AddMode.IF_ABSENT, "source-owned");
                    } else if (vanilla && !overridden.contains(entry.attribute())) {
                        AddMode mode = entry.fromExplicit()
                                ? AddMode.BASE_DEFAULT : AddMode.IF_ABSENT;
                        add(merged, entry, mode, "source-default");
                    }
                }
            }
        }

        ImmutableMultimap.Builder<Attribute, AttributeModifier> builder = ImmutableMultimap.builder();
        Set<String> mergedIds = new LinkedHashSet<>();
        boolean onlyDefaults = true;
        for (Map.Entry<Attribute, LinkedHashMap<NamespacedKey, Entry>> attributeEntry : merged.entrySet()) {
            for (Entry entry : attributeEntry.getValue().values()) {
                builder.put(entry.attribute(), entry.modifier());
                mergedIds.add(signature(entry.attribute(), entry.modifier()));
                if (!isVanillaDefault(entry, defaults)) {
                    onlyDefaults = false;
                }
            }
        }
        Set<String> currentIds = new LinkedHashSet<>();
        if (current != null) {
            for (Map.Entry<Attribute, AttributeModifier> entry : current.entries()) {
                if (entry.getKey() != null && entry.getValue() != null && entry.getValue().getKey() != null) {
                    currentIds.add(signature(entry.getKey(), entry.getValue()));
                }
            }
        }
        if (mergedIds.isEmpty()) {
            return clearEmptyComponent(meta);
        }
        if (current == null && onlyDefaults) {
            // Компонент с одними defaults не материализуем, но снимаем скрытый
            // пустой компонент, если он всё же был записан предыдущим плагином.
            return clearEmptyComponent(meta);
        }
        if (current != null && currentIds.equals(mergedIds)) {
            return false;
        }
        meta.setAttributeModifiers(builder.build());
        return true;
    }

    /**
     * Bukkit не различает «компонента нет» и «компонент пустой» через
     * {@code getAttributeModifiers()}. Сравниваем строку компонентов до и после
     * снятия: изменилась — пустой компонент реально был и его нужно записать
     * обратно в ItemStack; не изменилась — запись не нужна.
     */
    private static boolean clearEmptyComponent(ItemMeta meta) {
        String before = componentString(meta);
        try {
            meta.setAttributeModifiers(null);
        } catch (RuntimeException exception) {
            return false;
        }
        String after = componentString(meta);
        if (before == null || after == null) {
            return true; // старый API не дал диагностики — сохраняем снятие
        }
        return !before.equals(after);
    }

    private static String componentString(ItemMeta meta) {
        try {
            return meta.getAsComponentString();
        } catch (RuntimeException | LinkageError exception) {
            return null;
        }
    }

    /**
     * Детерминированный ключ для конфликтующего модификатора. Тот же namespace
     * и префикс исходного ключа: очистка по namespace/префиксу не теряет его.
     */
    static NamespacedKey uniqueKey(NamespacedKey original, String seed) {
        String value = sanitize(original.getKey()) + "_compat_" + hash(seed);
        return new NamespacedKey(original.getNamespace(), value);
    }

    private static void add(Map<Attribute, LinkedHashMap<NamespacedKey, Entry>> merged,
                            Entry entry, AddMode mode, String phase) {
        LinkedHashMap<NamespacedKey, Entry> byKey =
                merged.computeIfAbsent(entry.attribute(), ignored -> new LinkedHashMap<>());
        NamespacedKey key = entry.modifier().getKey();
        Entry existing = byKey.get(key);
        if (existing == null) {
            byKey.put(key, entry);
            return;
        }
        if (mode == AddMode.IF_ABSENT || existing.modifier().equals(entry.modifier())) {
            return;
        }
        if (mode == AddMode.BASE_DEFAULT) {
            // Источник сохранил исходную базу под ванильным ключом: именно она
            // авторитетна, даже если пересборка записала под этим ключом 0.
            byKey.put(key, entry);
            return;
        }
        if (preferExisting(existing, entry)) {
            Entry rekeyed = rekey(entry, byKey, phase);
            byKey.put(rekeyed.modifier().getKey(), rekeyed);
            return;
        }
        byKey.remove(key);
        Entry rekeyedExisting = rekey(existing, byKey, phase);
        byKey.put(rekeyedExisting.modifier().getKey(), rekeyedExisting);
        byKey.put(key, entry);
    }

    private static boolean preferExisting(Entry existing, Entry incoming) {
        if (existing.owned() && !incoming.owned()) {
            return false; // база источника важнее: переименовываем свой модификатор
        }
        return true; // target/newer или foreign-first: incoming получает новый ключ
    }

    private static Entry rekey(Entry entry, LinkedHashMap<NamespacedKey, Entry> byKey, String phase) {
        NamespacedKey original = entry.modifier().getKey();
        String seed = entry.source() + "|" + attributeKey(entry.attribute()) + "|" + original
                + "|" + entry.modifier().getAmount() + "|" + entry.modifier().getOperation()
                + "|" + entry.modifier().getSlotGroup() + "|" + phase;
        for (int attempt = 0; attempt < 1024; attempt++) {
            NamespacedKey candidate = uniqueKey(original, seed + "|" + attempt);
            if (!byKey.containsKey(candidate)) {
                return copyWithKey(entry, candidate);
            }
        }
        // Крайне маловероятный перебор: добиваемся уникальности явным счётчиком,
        // а не возвращаем исходный ключ и не теряем конфликтующий модификатор.
        int overflow = 0;
        NamespacedKey candidate;
        do {
            candidate = uniqueKey(original, seed + "|overflow|" + overflow++);
        } while (byKey.containsKey(candidate));
        return copyWithKey(entry, candidate);
    }

    private static Entry copyWithKey(Entry entry, NamespacedKey key) {
        AttributeModifier copy = new AttributeModifier(key,
                entry.modifier().getAmount(), entry.modifier().getOperation(),
                entry.modifier().getSlotGroup());
        return new Entry(entry.attribute(), copy, entry.source(), entry.owned(),
                entry.fromExplicit());
    }

    private static String hash(String value) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            digest = Integer.toString(value.hashCode()).getBytes(StandardCharsets.UTF_8);
        }
        StringBuilder result = new StringBuilder(12);
        for (int index = 0; index < Math.min(6, digest.length); index++) {
            int current = digest[index] & 0xff;
            result.append(Character.forDigit(current >>> 4, 16));
            result.append(Character.forDigit(current & 0x0f, 16));
        }
        while (result.length() < 12) {
            result.append('0');
        }
        return result.toString();
    }

    private static String sanitize(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < lower.length(); index++) {
            char symbol = lower.charAt(index);
            boolean valid = symbol >= 'a' && symbol <= 'z' || symbol >= '0' && symbol <= '9'
                    || symbol == '_' || symbol == '-' || symbol == '.' || symbol == '/';
            result.append(valid ? symbol : '_');
        }
        String cleaned = result.toString();
        return cleaned.length() > 180 ? cleaned.substring(0, 180) : cleaned;
    }

    private static boolean isVanillaDefault(Entry entry, Multimap<Attribute, AttributeModifier> defaults) {
        return isDefaultKey(defaults, entry.attribute(), entry.modifier().getKey());
    }

    private static boolean isDefaultKey(Multimap<Attribute, AttributeModifier> defaults,
                                        Attribute attribute, NamespacedKey key) {
        if (defaults == null || attribute == null || key == null) {
            return false;
        }
        Collection<AttributeModifier> collection = defaults.get(attribute);
        if (collection == null) {
            return false;
        }
        for (AttributeModifier modifier : collection) {
            if (modifier != null && modifier.getKey() != null && modifier.getKey().equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIdentity(List<Entry> entries, Attribute attribute, NamespacedKey key) {
        for (Entry entry : entries) {
            if (entry.attribute().equals(attribute) && entry.modifier().getKey().equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNonDefaultEntries(List<Snapshot> sources,
                                                Multimap<Attribute, AttributeModifier> defaults) {
        if (sources == null) {
            return false;
        }
        for (Snapshot source : sources) {
            if (source == null) {
                continue;
            }
            for (Entry entry : source.entries()) {
                if (!isVanillaDefault(entry, defaults)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String attributeKey(Attribute attribute) {
        if (attribute == null) {
            return "?";
        }
        try {
            NamespacedKey key = attribute.getKey();
            if (key != null) {
                return key.toString();
            }
        } catch (RuntimeException ignored) {
            // старые enum-реализации или прокси без реестра
        }
        return String.valueOf(attribute);
    }

    static Multimap<Attribute, AttributeModifier> defaults(ItemStack item) {
        if (item == null) {
            return null;
        }
        try {
            return item.getType().getDefaultAttributeModifiers();
        } catch (RuntimeException exception) {
            return null; // воздух/блок или серверный реестр ещё не готов
        }
    }
}
