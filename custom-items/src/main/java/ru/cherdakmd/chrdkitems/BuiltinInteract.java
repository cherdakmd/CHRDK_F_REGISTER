package ru.cherdakmd.chrdkitems;

import net.kyori.adventure.text.Component;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Read-only utility activation; preserves protection denials on clicked blocks. */
final class BuiltinInteract implements Listener {
    private static final long COOLDOWN_NANOS = 1_000_000_000L;
    private final Map<UUID, Long> lastUse = new HashMap<>();
    private final Map<String, ItemCatalog.Definition> catalog;
    BuiltinInteract(Map<String, ItemCatalog.Definition> catalog) { this.catalog = catalog; }

    static boolean allowed(Action action, EquipmentSlot hand, boolean blockDenied, boolean permitted, boolean matches) {
        return permitted && matches && hand == EquipmentSlot.HAND
                && (action == Action.RIGHT_CLICK_AIR || (action == Action.RIGHT_CLICK_BLOCK && !blockDenied));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void interact(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        var player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) return;
        String id = item.getItemMeta().getPersistentDataContainer().get(PrototypeItems.ID, PersistentDataType.STRING);
        var def = id == null ? null : catalog.get(id);
        boolean matches = BuiltinUse.supports(def) && PrototypeEffects.identityMatches(item, def);
        boolean denied = event.useInteractedBlock() == Event.Result.DENY || event.useItemInHand() == Event.Result.DENY;
        // Bukkit may pre-cancel RIGHT_CLICK_AIR when vanilla predicts no action. The three
        // abilities below only read public game state and send feedback, never mutate it.
        if (!allowed(action, event.getHand(), denied, player.hasPermission("chrdkcustomitems.use"), matches)) return;
        if (action == Action.RIGHT_CLICK_BLOCK) event.setUseItemInHand(Event.Result.DENY);
        long now = System.nanoTime();
        Long previous = lastUse.get(player.getUniqueId());
        if (previous != null && now - previous < COOLDOWN_NANOS) return;
        lastUse.put(player.getUniqueId(), now);
        try {
            String text = BuiltinUse.describe(def, player);
            player.sendMessage(Component.text(text == null ? "Данные недоступны." : text));
        } catch (RuntimeException e) { player.sendMessage(Component.text("Не удалось получить данные.")); }
    }
    @EventHandler public void quit(PlayerQuitEvent event) { lastUse.remove(event.getPlayer().getUniqueId()); }
}
