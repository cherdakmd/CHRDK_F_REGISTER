package ru.cherdakmd.chrdkartifacts;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Предметы артефактов: постройка стека (имя из messages.yml, плашки лора, glint у
 * светлых) и чтение/поиск по инвентарю. Хранилище — только PDC предмета
 * ({@code artifact_id}), отдельного файла у плагина нет.
 */
public final class ArtifactItems {

    private final ArtifactsPlugin plugin;
    private final NamespacedKey idKey;

    public ArtifactItems(ArtifactsPlugin plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "artifact_id");
    }

    /** Собирает стек артефакта. Количество ограничено размером стака материала. */
    public ItemStack build(ArtifactDef def, int amount) {
        ItemStack stack = new ItemStack(def.material(),
                Math.max(1, Math.min(amount, def.material().getMaxStackSize())));
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(nameOf(def), color(def))
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(buildLore(def));
        if (def.side() == ArtifactDef.Side.GOOD) {
            meta.setEnchantmentGlintOverride(Boolean.TRUE);
        }
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, def.id());
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * Имя артефакта для предмета и сообщений: строка из messages.yml, а если её
     * нет (удалённая секция, неполный перевод) — читаемое имя из ида. Так игрок
     * никогда не видит безымянный предмет и пустое место в шаблоне.
     */
    public String nameOf(ArtifactDef def) {
        return ArtifactText.orFallback(
                plugin.messages().raw("art." + def.id() + ".name"), def.id());
    }

    /** Ид артефакта из PDC предмета (null для обычных предметов). */
    public String idOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String id = item.getItemMeta().getPersistentDataContainer()
                .get(idKey, PersistentDataType.STRING);
        return id != null && ArtifactRegistry.byId(id) != null ? id : null;
    }

    /** Все иды артефактов, что игрок носит при себе (основной инвентарь + броня + вторая рука). */
    public Set<String> carriedIds(Player player) {
        Set<String> ids = new HashSet<>();
        collect(ids, player.getInventory().getStorageContents());
        collect(ids, player.getInventory().getArmorContents());
        collect(ids, new ItemStack[]{player.getInventory().getItemInOffHand(),
                player.getInventory().getItemInMainHand()});
        return ids;
    }

    private void collect(Set<String> into, ItemStack[] items) {
        if (items == null) {
            return;
        }
        for (ItemStack item : items) {
            String id = idOf(item);
            if (id != null) {
                into.add(id);
            }
        }
    }

    private static NamedTextColor color(ArtifactDef def) {
        if (def.side() == ArtifactDef.Side.GOOD) {
            return def.mode() == ArtifactDef.Mode.ACTIVE ? NamedTextColor.GOLD : NamedTextColor.YELLOW;
        }
        return def.mode() == ArtifactDef.Mode.ACTIVE ? NamedTextColor.DARK_RED : NamedTextColor.RED;
    }

    private List<Component> buildLore(ArtifactDef def) {
        List<Component> lore = new ArrayList<>();
        String lineKey = switch (def.side()) {
            case GOOD -> def.mode() == ArtifactDef.Mode.PASSIVE
                    ? "line-good-passive" : "line-good-active";
            case CURSED -> def.mode() == ArtifactDef.Mode.PASSIVE
                    ? "line-cursed-passive" : "line-cursed-active";
        };
        lore.add(plugin.messages().get(lineKey));
        String desc = ArtifactText.loreOrEmpty(plugin.messages().raw("art." + def.id() + ".lore"));
        if (!desc.isEmpty()) {
            lore.add(plugin.messages().get("lore-desc", "text", desc));
        }
        if (def.mode() == ArtifactDef.Mode.ACTIVE) {
            lore.add(plugin.messages().get("lore-active-info",
                    "cooldown", String.valueOf(plugin.config().cooldownSeconds(def)),
                    "xp", String.valueOf(plugin.config().xpCostLevels(def))));
        }
        return lore;
    }
}
