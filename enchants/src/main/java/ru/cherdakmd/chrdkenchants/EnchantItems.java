package ru.cherdakmd.chrdkenchants;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Работа с предметами: PDC-метка чар ("id:level;..."), книга чары как предмет и
 * аккуратная перерисовка лора (наши строки помечаются маркером «✦ »).
 * Только серверный рантайм — в юнит-тестах не использовать.
 */
public final class EnchantItems {

    public static final String PDC_KEY = "custom_enchants";
    /** Маркер строк лора, созданных плагином (по нему узнаём свои строки при перерисовке). */
    public static final String LORE_MARKER = "✦ ";

    private final EnchantsPlugin plugin;
    private final NamespacedKey key;

    public EnchantItems(EnchantsPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, PDC_KEY);
    }

    /** Все чары предмета (id→уровень). */
    public Map<String, Integer> read(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return Map.of();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return Map.of();
        }
        String raw = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return raw == null ? Map.of() : Map.copyOf(EnchantCodec.decode(raw));
    }

    /** Уровень конкретной чары на предмете (0 — нет). */
    public int levelOf(ItemStack item, String id) {
        return read(item).getOrDefault(id, 0);
    }

    /** Точно записывает карту чар в PDC и перерисовывает лор. */
    public void write(ItemStack item, Map<String, Integer> enchants) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        String encoded = EnchantCodec.encode(enchants);
        if (encoded.isEmpty()) {
            meta.getPersistentDataContainer().remove(key);
        } else {
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, encoded);
        }
        refreshLore(meta, enchants);
        item.setItemMeta(meta);
    }

    /** Добавляет чару с уровнем (правила применимости — на вызывающем). */
    public void apply(ItemStack item, EnchantDef def, int level) {
        Map<String, Integer> enchants = new LinkedHashMap<>(read(item));
        enchants.put(def.id(), Math.min(def.maxLevel(), Math.max(1, level)));
        write(item, enchants);
    }

    /** Книга чар (жители, выдача админом, наковальня). */
    public ItemStack makeEnchantBook(EnchantDef def, int level) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        apply(book, def, level);
        ItemMeta meta = book.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.messages().get("gui-book-name",
                    "name", plugin.messages().raw("enchants.en." + def.id() + ".name")));
            book.setItemMeta(meta);
        }
        return book;
    }

    /** Это наша книга чар? */
    public boolean isEnchantBook(ItemStack item) {
        return item != null && item.getType() == Material.ENCHANTED_BOOK
                && !read(item).isEmpty();
    }

    /** Чары на сущности (стрелы и пр.) через её PDC. */
    public Map<String, Integer> readEntity(org.bukkit.entity.Entity entity) {
        if (entity == null) {
            return Map.of();
        }
        String raw = entity.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return raw == null ? Map.of() : Map.copyOf(EnchantCodec.decode(raw));
    }

    /** Записывает чары на сущность (стрела при выстреле). */
    public void writeEntity(org.bukkit.entity.Entity entity, Map<String, Integer> enchants) {
        String encoded = EnchantCodec.encode(enchants);
        if (encoded.isEmpty()) {
            entity.getPersistentDataContainer().remove(key);
        } else {
            entity.getPersistentDataContainer().set(key, PersistentDataType.STRING, encoded);
        }
    }

    public NamespacedKey key() {
        return key;
    }

    /** Есть ли на предмете хоть одна из указанных чар. */
    public boolean hasEnchant(ItemStack item, String... ids) {
        Map<String, Integer> enchants = read(item);
        for (String id : ids) {
            if (enchants.containsKey(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Перерисовывает секцию лора чар: старые наши строки (с маркером) удаляются,
     * новые добавляются по реестру.
     */
    private void refreshLore(ItemMeta meta, Map<String, Integer> enchants) {
        List<Component> lore = meta.hasLore() && meta.lore() != null
                ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.removeIf(EnchantItems::isOurs);
        for (Map.Entry<String, Integer> entry : new LinkedHashMap<>(enchants).entrySet()) {
            EnchantDef def = EnchantRegistry.byId(entry.getKey());
            if (def == null) continue;
            lore.add(plugin.messages().get("lore-line",
                    "name", plugin.messages().raw("enchants.en." + def.id() + ".name"),
                    "levelroman", EnchantCodec.roman(entry.getValue())));
        }
        meta.lore(lore.isEmpty() ? null : lore);
    }

    private static boolean isOurs(Component line) {
        String plain = PlainTextComponentSerializer.plainText().serialize(line);
        return plain.startsWith(LORE_MARKER);
    }
}
