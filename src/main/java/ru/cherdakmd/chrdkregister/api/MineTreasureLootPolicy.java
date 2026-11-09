package ru.cherdakmd.chrdkregister.api;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.loot.LootTable;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Общая политика слияния лута CHRDK и Mine Treasure v1.6.5 RU (q10=merge).
 *
 * <p>Патченый датапак ставит PDC-метку {@code chrdk:mine_treasure_item} в
 * {@code minecraft:custom_data.PublicBukkitValues} каждого
 * {@code minecraft:set_components}. Все 43 переопределённые таблицы блоков
 * содержат ванильный дроп <b>и</b> вложенный {@code mt:} пул; награды MT всегда
 * приходят предметом с компонентами/PDC, а ванильные стеки — без ItemMeta.
 * Поэтому двойной дроп и бонусы руды/урожая CHRDK применяются только к
 * {@code vanillaOnly(...)}: чужие награды не размножаются и не перетираются.</p>
 *
 * <p>В Paper 26.3 {@code LootGenerateEvent} вызывается из
 * {@code LootTable#fill(Container...)}, а дроп блоков идёт через
 * {@code LootTable#getRandomItems(...)} и {@code BlockDropItemEvent}. Поэтому
 * {@link #isMineTreasureTable(LootTable)} нужен сундукам/бочкам {@code mt:},
 * а {@link #isForeignDrop(ItemStack)} — путям ломания блоков.</p>
 *
 * <p>Метка читается Bukkit-PDC; уже выданные предметы без метки не
 * мигрируются (q6=migration). Для них работает консервативный fallback
 * {@code hasItemMeta()}: такие стеки тоже не дублируются. Компиляции против
 * Purpur 26.3 и живого smoke в песочнице не было — см. чеклист в docs.</p>
 */
public final class MineTreasureLootPolicy {

    /** PDC-ключ, который build_mine_treasure.py пишет в custom_data. */
    public static final NamespacedKey OWNED_ITEM =
            new NamespacedKey("chrdk", "mine_treasure_item");

    private static final String MT_NAMESPACE = "mt:";
    private static final String LEGACY_NAMESPACE = "mine_treasure:";

    private MineTreasureLootPolicy() {
    }

    /** Сундуки/бочки/структуры самого Mine Treasure. */
    public static boolean isMineTreasureTable(LootTable table) {
        return table != null && isMineTreasureTable(table.getKey());
    }

    public static boolean isMineTreasureTable(NamespacedKey key) {
        return key != null && isMineTreasureTable(key.toString());
    }

    public static boolean isMineTreasureTable(String key) {
        if (key == null) {
            return false;
        }
        return key.startsWith(MT_NAMESPACE) || key.startsWith(LEGACY_NAMESPACE);
    }

    /** Точная метка патченого ZIP. */
    public static boolean isMineTreasureItem(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(OWNED_ITEM, PersistentDataType.STRING);
    }

    /**
     * Чужой именованный/компонентный предмет: MT-награда или старый предмет
     * без PDC-метки. Ванильный дроп блоков такую проверку не проходит.
     */
    public static boolean isForeignDrop(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        return isMineTreasureItem(stack) || stack.hasItemMeta();
    }

    /** Только ванильные стеки для DOUBLE_DROP и бонусов дропа. */
    public static List<ItemStack> vanillaOnly(Collection<ItemStack> drops) {
        List<ItemStack> plain = new ArrayList<>();
        if (drops == null) {
            return plain;
        }
        for (ItemStack drop : drops) {
            if (drop != null && !drop.getType().isAir() && drop.getAmount() > 0
                    && !isForeignDrop(drop)) {
                plain.add(drop.clone());
            }
        }
        return plain;
    }
}
