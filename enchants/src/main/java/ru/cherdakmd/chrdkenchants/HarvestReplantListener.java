package ru.cherdakmd.chrdkenchants;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import ru.cherdakmd.chrdkregister.api.CropReplanting;

/** N32: один общий с Skills координатор, не две независимые автопосадки. */
final class HarvestReplantListener implements Listener {
    private final EnchantsPlugin plugin;
    HarvestReplantListener(EnchantsPlugin plugin) { this.plugin = plugin; }
    private boolean eligible(Player player) {
        return player.isOnline() && !player.isDead() && player.getGameMode() == GameMode.SURVIVAL
                && player.hasPermission("chrdkenchants.use") && plugin.coreApi() != null && plugin.coreApi().isAuthenticated(player)
                && plugin.config().isEnabled("harvest_replant");
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void harvest(BlockBreakEvent event) {
        var player = event.getPlayer(); var tool = player.getInventory().getItemInMainHand();
        if (!eligible(player) || tool.getAmount() != 1 || !EnchantRegistry.byId("harvest_replant").supports(tool.getType().name())) return;
        var data = plugin.items().validated(tool);
        if (data == null || data.getOrDefault("harvest_replant", 0) != 1) return;
        var before = tool.clone(); long generation = plugin.sourceGeneration();
        CropReplanting.request(plugin, event, () -> generation == plugin.sourceGeneration() && eligible(player)
                && PveEquipment.same(before, player.getInventory().getItemInMainHand()));
    }
    @EventHandler public void quit(PlayerQuitEvent event) { CropReplanting.cancelPlayer(event.getPlayer().getUniqueId()); }
    @EventHandler public void death(PlayerDeathEvent event) { CropReplanting.cancelPlayer(event.getEntity().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) { CropReplanting.cancelPlayer(event.getPlayer().getUniqueId()); }
}
