package ru.cherdakmd.chrdkitems;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

/** Adds our pack without clearing other server packs; clients can decline it. */
final class ResourcePackDelivery implements Listener {
    private final CustomItemsPlugin plugin;
    private final ResourcePackSettings.Pack pack;

    ResourcePackDelivery(CustomItemsPlugin plugin, ResourcePackSettings.Pack pack) {
        this.plugin = plugin;
        this.pack = pack;
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { schedule(event.getPlayer()); }

    void schedule(Player player) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!plugin.isEnabled() || !player.isOnline()) return;
            try {
                player.addResourcePack(ResourcePackSettings.PACK_ID, pack.url(), pack.hashBytes(),
                        "Текстуры предметов CHRDK", false);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Не удалось отправить запрос ресурс-пака игроку "
                        + player.getUniqueId() + ": " + e.getClass().getSimpleName());
            }
        });
    }

    @EventHandler public void onStatus(PlayerResourcePackStatusEvent event) {
        if (!ResourcePackSettings.PACK_ID.equals(event.getID())) return;
        switch (event.getStatus()) {
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED ->
                    plugin.getLogger().warning("Ресурс-пак CHRDK: " + event.getStatus()
                            + " для " + event.getPlayer().getUniqueId());
            case SUCCESSFULLY_LOADED -> plugin.getLogger().info("Ресурс-пак CHRDK загружен: "
                    + event.getPlayer().getUniqueId());
            default -> { }
        }
    }
}
