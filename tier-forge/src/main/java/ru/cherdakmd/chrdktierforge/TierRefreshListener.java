package ru.cherdakmd.chrdktierforge;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Догоняющее обновление старых тир-предметов (1.4.117).
 *
 * <p>До 1.4.117 инструменты получали от тира урон и скорость атаки. Менять
 * предметы «задним числом» в сундуках нельзя, поэтому атрибуты пересобираются
 * лениво: при входе игрока (весь инвентарь, включая броню и левую руку) и при
 * смене слота. Имя, лор, кличка и уровни улучшений при этом не трогаются —
 * {@link TierItems#refreshTierModifiers} правит только модификаторы. С 1.4.119
 * тот же проход ставит блеск по стилю тира — без пересборки имени и лора.</p>
 */
public final class TierRefreshListener implements Listener {

    private final TierForgePlugin plugin;

    public TierRefreshListener(TierForgePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Инвентарь на входе ещё догружается клиентом — обновляем следующим тиком.
        Bukkit.getScheduler().runTask(plugin, () -> refreshAll(player));
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        ItemStack stack = player.getInventory().getItem(event.getNewSlot());
        if (plugin.items().refreshTierModifiers(stack)) {
            player.updateInventory();
        }
    }

    /** Обойти инвентарь игрока и обновить все устаревшие тир-предметы. */
    void refreshAll(Player player) {
        boolean changed = false;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (plugin.items().refreshTierModifiers(stack)) {
                changed = true;
            }
        }
        if (changed) {
            player.updateInventory();
        }
    }
}
