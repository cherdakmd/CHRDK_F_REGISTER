package ru.cherdakmd.chrdkregister.api;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;

/**
 * Книги чар алмазного магазина: единый формат предмета и метки происхождения.
 * Используется магазином и {@link ShopInventory} при проверке замороженной
 * сделки — расхождение в формате сделало бы сделку невосстановимой.
 *
 * <p>Кастомные чары хранятся в PDC {@code chrdkenchants:custom_enchants} в формате
 * {@code id:level} (совместимо с {@code EnchantCodec} аддона enchants). Ванильные
 * чары записаны в {@link EnchantmentStorageMeta}. Оба вида помечаются
 * {@code book_source_v1 = v1:shop} и собственным ключом товара, поэтому скупка
 * принимает только книги, выданные магазином: книги стола, жителей, мобов,
 * мастерской и админской выдачи в магазин не возвращаются.</p>
 */
public final class ShopBooks {

    private static final NamespacedKey CUSTOM_ENCHANTS = new NamespacedKey("chrdkenchants", "custom_enchants");
    private static final NamespacedKey BOOK_SOURCE = new NamespacedKey("chrdkenchants", "book_source_v1");
    private static final NamespacedKey BOOK_KEY = new NamespacedKey("chrdk_diamond_shop", "book_key");
    private static final String SHOP_MARK = EnchantBookSource.SHOP.encoded();

    private ShopBooks() {
    }

    /** Детерминированное имя магазинной книги без обращения к messages аддона. */
    public static Component displayName(ShopBookKey.Spec spec) {
        if (spec == null) {
            return null;
        }
        String roman = switch (spec.level()) {
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> "I";
        };
        return Component.text("Книга чар: " + spec.id() + " " + roman);
    }

    /** Книга со стандартным именем. */
    public static ItemStack create(ShopBookKey.Spec spec) {
        return create(spec, displayName(spec));
    }

    /**
     * Создаёт книгу магазина. {@code null} — ванильное зачарование отсутствует
     * в реестре текущей версии сервера: такой товар нельзя выдать игроку.
     */
    public static ItemStack create(ShopBookKey.Spec spec, Component displayName) {
        if (spec == null) {
            return null;
        }
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = book.getItemMeta();
        if (meta == null) {
            return null;
        }
        var data = meta.getPersistentDataContainer();
        data.set(BOOK_KEY, PersistentDataType.STRING, spec.key());
        data.set(BOOK_SOURCE, PersistentDataType.STRING, SHOP_MARK);
        if (spec.custom()) {
            data.set(CUSTOM_ENCHANTS, PersistentDataType.STRING, spec.id() + ":" + spec.level());
        } else {
            Enchantment enchant = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(spec.id()));
            if (enchant == null || !(meta instanceof EnchantmentStorageMeta storage)) {
                return null;
            }
            storage.addStoredEnchant(enchant, spec.level(), true);
        }
        if (displayName != null) {
            meta.displayName(displayName);
        }
        book.setItemMeta(meta);
        return book;
    }

    /** Книга выдана магазином? Метка источника обязательна для обратной скупки. */
    public static boolean shopMarked(ItemStack item) {
        if (item == null || item.getType() != Material.ENCHANTED_BOOK || !item.hasItemMeta()) {
            return false;
        }
        var data = item.getItemMeta().getPersistentDataContainer();
        return SHOP_MARK.equals(data.get(BOOK_SOURCE, PersistentDataType.STRING))
                && data.has(BOOK_KEY, PersistentDataType.STRING);
    }

    /** Точное совпадение предмета с товаром каталога. */
    public static boolean matches(ItemStack item, ShopBookKey.Spec spec) {
        if (spec == null || !shopMarked(item)) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        var data = meta.getPersistentDataContainer();
        if (!spec.key().equals(data.get(BOOK_KEY, PersistentDataType.STRING))) {
            return false;
        }
        if (!spec.custom()) {
            if (!(meta instanceof EnchantmentStorageMeta storage)) {
                return false;
            }
            Enchantment enchant = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(spec.id()));
            Map<Enchantment, Integer> stored = storage.getStoredEnchants();
            return enchant != null && stored.size() == 1
                    && stored.getOrDefault(enchant, 0) == spec.level();
        }
        return (spec.id() + ":" + spec.level()).equals(data.get(CUSTOM_ENCHANTS, PersistentDataType.STRING));
    }

    /** Точное совпадение предмета с ключом товара (для проверки сделки в ядре). */
    public static boolean matchesKey(ItemStack item, String key) {
        return matches(item, ShopBookKey.fromKey(key));
    }
}
