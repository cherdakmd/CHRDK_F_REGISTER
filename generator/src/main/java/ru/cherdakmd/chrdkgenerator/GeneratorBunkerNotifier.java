package ru.cherdakmd.chrdkgenerator;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Оповещения о полном бункере (п.31, §6): при входе владельца — всегда,
 * живьём в чат — не чаще кулдауна {@code bunker-notify-cooldown-minutes}.
 */
final class GeneratorBunkerNotifier implements Listener {

    private final GeneratorPlugin plugin;
    private final Map<UUID, Long> lastLiveNotify = new HashMap<>();

    GeneratorBunkerNotifier(GeneratorPlugin plugin) {
        this.plugin = plugin;
    }

    /** Живое оповещение из тикера: бункер генератора заполнен. */
    void onProduction(GeneratorStore.Entry entry, long bunkerLevel) {
        int cooldownMinutes = plugin.config().bunkerNotifyCooldownMinutes();
        if (cooldownMinutes <= 0 || entry == null || !entry.installed()) {
            return;
        }
        if (bunkerLevel < GeneratorStats.capacityUnits(entry, plugin.config())) {
            return;
        }
        Player player = Bukkit.getPlayer(entry.owner());
        if (player == null || !player.isOnline()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastLiveNotify.get(entry.owner());
        if (last != null && now - last < cooldownMinutes * 60_000L) {
            return;
        }
        lastLiveNotify.put(entry.owner(), now);
        send(player, "bunker-full", entry);
    }

    /** При входе: каждый заполненный бункер — сообщение без кулдауна. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        for (GeneratorStore.Entry entry : plugin.store().entries(player.getUniqueId())) {
            if (entry.installed()
                    && plugin.combinedBunkerEntry(entry).bunker() >= GeneratorStats.capacityUnits(entry, plugin.config())) {
                send(player, "bunker-full-join", entry);
            }
        }
    }

    private void send(Player player, String key, GeneratorStore.Entry entry) {
        plugin.messages().send(player, key,
                "bunker", String.valueOf(plugin.combinedBunkerEntry(entry).bunker()),
                "capacity", String.valueOf(GeneratorStats.capacityUnits(entry, plugin.config())));
    }
}
