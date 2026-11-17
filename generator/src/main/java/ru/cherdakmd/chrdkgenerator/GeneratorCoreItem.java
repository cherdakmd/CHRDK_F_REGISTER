package ru.cherdakmd.chrdkgenerator;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.UUID;

/**
 * Предмет «ядро генератора»: имя и лор из messages.yml, маркер {@code core}
 * и идентификатор {@code core_id} в PDC.
 *
 * <p>Идентификатор выдаётся при крафте и живёт с предметом: скопированная дюпом
 * копия несёт тот же id, и вторая установка отклоняется хранилищем («нельзя
 * размножить ядра перепродажей», п.31, §7). Предмет без идентификатора
 * (распылитель, реставрация) не устанавливается.</p>
 */
public final class GeneratorCoreItem {

    /** Маркер PDC: предмет — ядро генератора. */
    static final String MARKER_KEY = "core";

    /** Идентификатор PDC: UUID предмета-ядра (бинд анти-дюпа). */
    static final String ID_KEY = "core_id";

    private final GeneratorPlugin plugin;
    private final NamespacedKey markerKey;
    private final NamespacedKey idKey;

    public GeneratorCoreItem(GeneratorPlugin plugin) {
        this.plugin = plugin;
        this.markerKey = new NamespacedKey(plugin, MARKER_KEY);
        this.idKey = new NamespacedKey(plugin, ID_KEY);
    }

    /** Собирает ядро; {@code coreId == null} — шаблон рецепта без идентификатора. */
    public ItemStack build(UUID coreId) {
        ItemStack stack = new ItemStack(Material.LODESTONE);
        ItemMeta meta = stack.getItemMeta();
        Component name = plugin.messages().get("core-item-name");
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        List<Component> lore = plugin.messages().getList("core-item-lore");
        meta.lore(lore.stream()
                .map(line -> line.decoration(TextDecoration.ITALIC, false))
                .toList());
        meta.setEnchantmentGlintOverride(Boolean.TRUE);
        meta.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, "1");
        if (coreId != null) {
            meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, coreId.toString());
        }
        stack.setItemMeta(meta);
        return stack;
    }

    /** Предмет — ядро генератора (с идентификатором или шаблон). */
    public boolean isCore(ItemStack item) {
        return item != null && item.getType() == Material.LODESTONE && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer()
                        .has(markerKey, PersistentDataType.STRING);
    }

    /** Идентификатор предмета; {@code null} — не ядро или ядро без id. */
    public UUID coreIdOf(ItemStack item) {
        if (!isCore(item)) {
            return null;
        }
        String raw = item.getItemMeta().getPersistentDataContainer()
                .get(idKey, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** Ставит идентификатор на ядро (шаблон рецепта → готовый предмет). */
    public ItemStack stamp(ItemStack item, UUID coreId) {
        if (!isCore(item)) {
            throw new IllegalArgumentException("generator: это не ядро");
        }
        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, coreId.toString());
        copy.setItemMeta(meta);
        return copy;
    }
}
