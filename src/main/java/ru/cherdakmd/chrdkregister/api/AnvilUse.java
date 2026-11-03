package ru.cherdakmd.chrdkregister.api;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.block.data.Directional;
import java.util.concurrent.ThreadLocalRandom;

/** Общие правила реальной наковальни для операций с ручным подтверждением. */
public final class AnvilUse {
    private AnvilUse() { }
    public static boolean accessible(AnvilView view, Player player) {
        var location = view.getTopInventory().getLocation();
        return location != null && player.getWorld().equals(location.getWorld()) && player.getLocation().distanceSquared(location) <= 64
                && Tag.ANVIL.isTagged(location.getBlock().getType());
    }
    public static void finish(AnvilView view, Player player) {
        var location = view.getTopInventory().getLocation();
        if (location == null) return;
        var block = location.getBlock();
        if (player.getGameMode() != org.bukkit.GameMode.CREATIVE && Tag.ANVIL.isTagged(block.getType())
                && ThreadLocalRandom.current().nextFloat() < .12f) {
            Material next = switch (block.getType()) { case ANVIL -> Material.CHIPPED_ANVIL; case CHIPPED_ANVIL -> Material.DAMAGED_ANVIL; default -> Material.AIR; };
            var damage = new com.destroystokyo.paper.event.block.AnvilDamagedEvent(view, next == Material.AIR ? null : next.createBlockData());
            if (!damage.callEvent()) return;
            Material target = damage.getDamageState().getMaterial();
            var data = target.createBlockData();
            if (data instanceof Directional facing && block.getBlockData() instanceof Directional old) facing.setFacing(old.getFacing());
            block.setBlockData(data);
            location.getWorld().playSound(location,target == Material.AIR ? Sound.BLOCK_ANVIL_BREAK : Sound.BLOCK_ANVIL_USE,1,1);
        } else location.getWorld().playSound(location,Sound.BLOCK_ANVIL_USE,1,1);
    }
}
