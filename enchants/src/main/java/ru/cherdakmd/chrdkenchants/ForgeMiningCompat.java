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
 * <p>Колонну {@code ZONE_BREAK} дают две вещи CHRDK_TierForge: легендарное
 * свойство тира {@code quarry} («Карьер») и кузнечное улучшение {@code hewer}
 * («Забойщик»). Кастомные чары группы {@code megamine} ({@code veinminer}
 * «Жилодробитель», {@code excavator} «Дробитель») дают своё массовое
 * разрушение за тот же удар. Раньше {@link EnchantConflicts} разводил только
 * чары между собой, а тир-предмет мог получить и колонну, и «Дробитель» —
 * как раз такой кадр и стал причиной запрета. Теперь пара проверяется в обе
 * стороны: аддон чар не наложит {@code megamine} на предмет с колонной, а
 * тир-аддон не выдаст ни «Карьер», ни «Забойщик» предмету с уже стоявшей
 * чарой {@code megamine}.</p>
 *
 * <p>Правило мягкое: CHRDK_Enchants не зависит от CHRDK_TierForge. Чужой PDC
 * читается по <em>именам ключей</em> {@code tier_properties} и
 * {@code up_hewer} среди {@link PersistentDataContainer#getKeys()} — namespace
 * аддона тиров не хардкодится, а предмет без тира просто считается обычным.
 * Формат свойств — id через запятую ({@code TierProperties.serialize}),
 * уровень улучшения — целое в {@code up_<id>}. Неизвестный тип или отсутствие
 * плагина не ломают чтение: старые и чужие предметы остаются валидными.</p>
 *
 * <p>Уже выданный предмет с двумя режимами не переписывается молча: PDC игрока
 * не трогается, но при добыче приоритет остаётся у колонны, а ветки
 * {@code veinminer}/{@code excavator} не запускаются.</p>
 */
public final class ForgeMiningCompat {

    /** Id легендарного свойства тира «Карьер». */
    public static final String QUARRY = "quarry";
    /** Id кузнечного улучшения «Забойщик» (тот же эффект колонны). */
    public static final String HEWER = "hewer";
    /** Группа чар массовой добычи в {@link EnchantRegistry}. */
    public static final String MEGA_MINING_GROUP = "megamine";
    /** Имя PDC-ключа CHRDK_TierForge со списком свойств тира. */
    private static final String TIER_PROPERTIES_KEY = "tier_properties";
    /** Имя PDC-ключа уровня улучшения: {@code up_} + id из {@code ForgeUpgrade}. */
    private static final String UPGRADE_PREFIX = "up_";

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
     * Есть ли колонна {@code ZONE_BREAK}: «Карьер» ({@code tier_properties})
     * или «Забойщик» ({@code up_hewer} уровня 1+). Один проход по чужим
     * PDC-ключам, без привязки к namespace CHRDK_TierForge.
     */
    public static boolean hasColumnMining(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        String hewerKey = UPGRADE_PREFIX + HEWER;
        for (NamespacedKey key : data.getKeys()) {
            String name = key.getKey();
            if (TIER_PROPERTIES_KEY.equals(name)) {
                try {
                    if (quarryPresent(parseProperties(data.get(key, PersistentDataType.STRING)))) {
                        return true;
                    }
                } catch (IllegalArgumentException | ClassCastException malformed) {
                    // Чужой ключ с другим типом: это не наши данные.
                }
            } else if (hewerKey.equals(name)) {
                try {
                    Integer level = data.get(key, PersistentDataType.INTEGER);
                    if (level != null && level > 0) {
                        return true;
                    }
                } catch (IllegalArgumentException | ClassCastException malformed) {
                    // Уровень улучшения записан другим типом — колонны нет.
                }
            }
        }
        return false;
    }

    /** Чистое правило: колонна + конкретная чара {@code megamine}. */
    public static boolean blocked(boolean columnMining, String enchantId) {
        return columnMining && isMegaMining(enchantId);
    }

    /** Нельзя ли добавить конкретную чару на такой предмет. */
    public static boolean blocked(ItemStack item, String enchantId) {
        return blocked(hasColumnMining(item), enchantId);
    }

    /** Чистое правило: колонна + хотя бы одна чара {@code megamine} в наборе. */
    public static boolean blocked(boolean columnMining, Map<String, Integer> enchants) {
        if (!columnMining || enchants == null || enchants.isEmpty()) {
            return false;
        }
        for (String id : enchants.keySet()) {
            if (isMegaMining(id)) {
                return true;
            }
        }
        return false;
    }

    /** Есть ли в наборе чара {@code megamine}, когда на предмете колонна. */
    public static boolean blocked(ItemStack item, Map<String, Integer> enchants) {
        return blocked(hasColumnMining(item), enchants);
    }

    /**
     * Убирает чары {@code megamine} из переносимого набора при колонне.
     * Остальные чары и порядок обхода сохраняются. Чистая перегрузка нужна
     * юнит-тестам: чтение Bukkit-PDC вынесено в вариант с {@link ItemStack}.
     */
    public static Map<String, Integer> stripMegaMining(Map<String, Integer> enchants, boolean columnMining) {
        if (enchants == null) {
            return Map.of();
        }
        if (!columnMining || enchants.isEmpty()) {
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
        return stripMegaMining(enchants, hasColumnMining(item));
    }
}
