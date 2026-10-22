package ru.cherdakmd.chrdkenchants;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Починка уже выданных предметов с кастомными чарами (1.4.140).
 *
 * <p>До 1.4.140 первый же атрибутный модификатор чары перекрывал ванильный
 * компонент атрибутов: меч с «+4% к урону» терял базовые 7 урона и бил на
 * «полсердечка». Новые предметы собираются уже правильно, а старые чинятся
 * лениво: при входе игрока обходится весь инвентарь (включая броню и левую
 * руку), а взятая в руку вещь проверяется сразу при смене слота. Чинятся
 * только предметы с нашей PDC-меткой чар, чужие не трогаем.</p>
 */
public final class AttributeRepairListener implements Listener {

    private final EnchantsPlugin plugin;

    public AttributeRepairListener(EnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Инвентарь на входе ещё догружается клиентом — чиним следующим тиком.
        Bukkit.getScheduler().runTask(plugin, () -> repair(player));
    }

    /** Смена слота: взятый в руку предмет чинится сразу, без перезахода. */
    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (plugin.items().repairAttributes(player.getInventory().getItem(event.getNewSlot()))) {
            player.updateInventory();
        }
    }

    /** Обойти инвентарь игрока и вернуть ванильную базу всем нашим предметам. */
    void repair(Player player) {
        boolean changed = false;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (plugin.items().repairAttributes(stack)) {
                changed = true;
            }
        }
        if (changed) {
            player.updateInventory();
        }
    }
}
