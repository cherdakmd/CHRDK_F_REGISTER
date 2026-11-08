package ru.cherdakmd.chrdkenchants;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Несовместимость «Карьера» CHRDK_TierForge и режимов массовой добычи чар.
 *
 * <p>Легендарное свойство тира {@code quarry} («Карьер», эффект
 * {@code ZONE_BREAK}: колонна +1 блок сверху и +1 снизу) и кастомные чары
 * группы {@code megamine} ({@code veinminer} «Жилодробитель»,
 * {@code excavator} «Дробитель») дают два разных массовых разрушения за один
 * удар. Раньше {@link EnchantConflicts} разводил только чары между собой, а
 * тир-предмет мог получить и «Карьер», и «Дробитель» — как раз такой кадр и
 * стал причиной запрета. Теперь пара проверяется в обе стороны: аддон чар не
 * наложит {@code megamine} на предмет с {@code quarry}, а тир-аддон не выдаст
 * {@code quarry} предмету с уже стоявшей чарой {@code megamine}.</p>
 *
 * <p>Правило мягкое: CHRDK_Enchants не зависит от CHRDK_TierForge. Чужой PDC
 * читается по <em>имени ключа</em> {@code tier_properties} среди
 * {@link PersistentDataContainer#getKeys()} — namespace аддона тиров не
 * хардкодится, а предмет без тира (или без этого ключа) просто считается
 * обычным. Формат значения — id свойств через запятую, как в
 * {@code TierProperties.serialize}. Неизвестный тип или отсутствие плагина не
 * ломают чтение: старые и чужие предметы остаются валидными.</p>
 *
 * <p>Уже выданный предмет с двумя режимами не переписывается молча: PDC игрока
 * не трогается, но при добыче приоритет остаётся у «Карьера», а ветки
 * {@code veinminer}/{@code excavator} не запускаются.</p>
 */
public final class ForgeMiningCompat {

    /** Id легендарного свойства тира «Карьер». */
    public static final String QUARRY = "quarry";
    /** Группа чар массовой добычи в {@link EnchantRegistry}. */
    public static final String MEGA_MINING_GROUP = "megamine";
    /** Имя PDC-ключа CHRDK_TierForge со списком свойств тира. */
    private static final String TIER_PROPERTIES_KEY = "tier_properties";

    private ForgeMiningCompat() {
    }

    /** Чара режима массовой добычи ({@code veinminer} / {@code excavator}). */
    public static boolean isMegaMining(String id) {
        return MEGA_MINING_GROUP.equals(EnchantConflicts.group(id));
    }

    /** Есть ли «Карьер» в разобранном списке свойств тира. */
    public static boolean quarryPresent(Collection<String> tierPropertyIds) {
        if (tierPropertyIds == null || tierPropertyIds.isEmpty()) {
            return false;
        }
        for (String id : tierPropertyIds) {
            if (QUARRY.equalsIgnoreCase(id == null ? null : id.trim())) {
                return true;
            }
        }
        return false;
    }

    /** Разбор PDC-строки {@code "world_breaker,quarry"}; регистр и пробелы не важны. */
    public static List<String> parseProperties(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String part : raw.split("[,;\\s]+")) {
            if (!part.isBlank()) {
                ids.add(part.trim().toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(ids);
    }

    /**
     * Свойства тира предмета без жёсткой зависимости от CHRDK_TierForge.
     * Пустой список — предмета нет, тира нет или плагин тиров не установлен.
     */
    public static List<String> tierProperties(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return List.of();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return List.of();
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        NamespacedKey found = null;
        for (NamespacedKey key : data.getKeys()) {
            if (TIER_PROPERTIES_KEY.equals(key.getKey())) {
                found = key;
                break;
            }
        }
        if (found == null) {
            return List.of();
        }
        try {
            return parseProperties(data.get(found, PersistentDataType.STRING));
        } catch (IllegalArgumentException | ClassCastException malformed) {
            // Чужой ключ с другим типом: это не наши данные, предмет не трогаем.
            return List.of();
        }
    }

    /** На предмете стоит легендарное свойство «Карьер». */
    public static boolean hasQuarry(ItemStack item) {
        return quarryPresent(tierProperties(item));
    }

    /** Чистое правило: «Карьер» + конкретная чара {@code megamine}. */
    public static boolean blocked(boolean quarry, String enchantId) {
        return quarry && isMegaMining(enchantId);
    }

    /** Нельзя ли добавить конкретную чару на такой предмет. */
    public static boolean blocked(ItemStack item, String enchantId) {
        return blocked(hasQuarry(item), enchantId);
    }

    /** Чистое правило: «Карьер» + хотя бы одна чара {@code megamine} в наборе. */
    public static boolean blocked(boolean quarry, Map<String, Integer> enchants) {
        if (!quarry || enchants == null || enchants.isEmpty()) {
            return false;
        }
        for (String id : enchants.keySet()) {
            if (isMegaMining(id)) {
                return true;
            }
        }
        return false;
    }

    /** Есть ли в наборе чара {@code megamine}, когда на предмете «Карьер». */
    public static boolean blocked(ItemStack item, Map<String, Integer> enchants) {
        return blocked(hasQuarry(item), enchants);
    }

    /**
     * Убирает чары {@code megamine} из переносимого набора при «Карьере».
     * Остальные чары и порядок обхода сохраняются. Чистая перегрузка нужна
     * юнит-тестам: чтение Bukkit-PDC вынесено в вариант с {@link ItemStack}.
     */
    public static Map<String, Integer> stripMegaMining(Map<String, Integer> enchants, boolean quarry) {
        if (enchants == null) {
            return Map.of();
        }
        if (!quarry || enchants.isEmpty()) {
            return enchants;
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        enchants.forEach((id, level) -> {
            if (!isMegaMining(id)) {
                result.put(id, level);
            }
        });
        return result;
    }

    /** То же по фактическому предмету (последняя граница перед записью PDC). */
    public static Map<String, Integer> stripMegaMining(Map<String, Integer> enchants, ItemStack item) {
        // Пустую карту нечего чистить — не читаем чужой PDC в горячем пути.
        if (enchants == null || enchants.isEmpty()) {
            return stripMegaMining(enchants, false);
        }
        return stripMegaMining(enchants, hasQuarry(item));
    }
}
