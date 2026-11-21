package ru.cherdakmd.chrdkitems;

import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/** Test-only construction; persistent tags are identifiers, not proof of authenticity. */
final class PrototypeItems {
    static final NamespacedKey ID = new NamespacedKey("chrdk_items", "item_id");
    static final NamespacedKey SCHEMA = new NamespacedKey("chrdk_items", "schema");
    static final int CURRENT_SCHEMA = 1;

    static ItemStack create(ItemCatalog.Definition definition) {
        var stack = new ItemStack(definition.material());
        var meta = stack.getItemMeta();
        if (meta == null) throw new IllegalStateException("material has no item metadata");
        meta.displayName(Component.text("Прототип: " + definition.id()));
        meta.lore(List.of(Component.text("Тестовый предмет: свойства и крафт не реализованы.")));
        meta.getPersistentDataContainer().set(ID, PersistentDataType.STRING, definition.id());
        meta.getPersistentDataContainer().set(SCHEMA, PersistentDataType.INTEGER, CURRENT_SCHEMA);
        // A model key is deliberately not applied before a compatible resource pack exists:
        // clients without that model must keep the vanilla appearance, not a missing texture.
        if (!stack.setItemMeta(meta)) throw new IllegalStateException("could not set item metadata");
        return stack;
    }
}
