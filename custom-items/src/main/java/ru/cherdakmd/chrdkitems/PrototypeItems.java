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

    static ItemStack create(ItemCatalog.Definition definition, boolean prototypeTextures) {
        var stack = new ItemStack(definition.material());
        var meta = stack.getItemMeta();
        if (meta == null) throw new IllegalStateException("material has no item metadata");
        meta.displayName(Component.text("Прототип: " + definition.id()));
        meta.lore(List.of(Component.text("Тестовый предмет: свойства и крафт не реализованы.")));
        meta.getPersistentDataContainer().set(ID, PersistentDataType.STRING, definition.id());
        meta.getPersistentDataContainer().set(SCHEMA, PersistentDataType.INTEGER, CURRENT_SCHEMA);
        // Custom model data is ignored by vanilla clients without the experimental pack.
        // Explicit opt-in: other packs may already use the same values on these materials.
        Integer modelData = prototypeTextures ? PrototypeVisuals.modelData(definition) : null;
        if (modelData != null) meta.setCustomModelData(modelData);
        if (!stack.setItemMeta(meta)) throw new IllegalStateException("could not set item metadata");
        return stack;
    }
}
