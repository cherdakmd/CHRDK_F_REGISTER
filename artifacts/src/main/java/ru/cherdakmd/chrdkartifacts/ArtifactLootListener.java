package ru.cherdakmd.chrdkartifacts;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootTable;

import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Природная добыча артефактов:
 *  • генерация сундуков (LootGenerateEvent): шанс по типу таблицы лута;
 *  • убийства «серьёзных» мобов: индивидуальный шанс по типу существа;
 *  • рыбалка на сокровище (CAUGHT_TREASURE): небольшой шанс бонусной находки.
 */
public final class ArtifactLootListener implements Listener {

    private final ArtifactsPlugin plugin;
    private final Random random = new Random();

    public ArtifactLootListener(ArtifactsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onLootGenerate(LootGenerateEvent event) {
        LootTable table = event.getLootTable();
        if (table == null || table.getKey() == null) {
            return;
        }
        double chance = plugin.config().chestChancePercent(table.getKey().toString());
        if (chance <= 0 || random.nextDouble() * 100.0 >= chance) {
            return;
        }
        ArtifactDef def = ArtifactLooter.roll(plugin.config().goodSideWeightPercent(), random);
        if (def == null) {
            return;
        }
        event.getLoot().add(plugin.items().build(def, 1));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        double chance = plugin.config().mobChancePercent(event.getEntityType());
        if (chance <= 0 || random.nextDouble() * 100.0 >= chance) {
            return;
        }
        ArtifactDef def = ArtifactLooter.roll(plugin.config().goodSideWeightPercent(), random);
        if (def == null) {
            return;
        }
        ItemStack drop = plugin.items().build(def, 1);
        event.getDrops().add(drop);
        plugin.messages().send(killer, "dropped",
                "name", plugin.messages().raw("art." + def.id() + ".name"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH
                || !(event.getCaught() instanceof org.bukkit.entity.Item item)
                || !TreasureLoot.isTreasure(item.getItemStack().getType())) {
            return;
        }
        double chance = plugin.config().fishingTreasurePercent();
        if (chance <= 0 || random.nextDouble() * 100.0 >= chance) {
            return;
        }
        ArtifactDef def = ArtifactLooter.roll(plugin.config().goodSideWeightPercent(), random);
        if (def == null) {
            return;
        }
        Player player = event.getPlayer();
        player.getWorld().dropItem(player.getLocation(), plugin.items().build(def, 1));
        plugin.messages().send(player, "dropped",
                "name", plugin.messages().raw("art." + def.id() + ".name"));
    }
}
