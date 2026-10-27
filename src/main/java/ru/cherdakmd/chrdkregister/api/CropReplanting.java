package ru.cherdakmd.chrdkregister.api;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Общая очередь Core для Skills/Enchants: один урожай — одна посадка и одно настоящее семя. Main thread only. */
public final class CropReplanting {
    private record Key(UUID world, int x, int y, int z) { }
    private static final class Pending {
        final Plugin owner; final BlockBreakEvent source; final Ageable crop;
        final Map<Plugin, BooleanSupplier> providers = new LinkedHashMap<>();
        BukkitTask task;
        Pending(Plugin owner, BlockBreakEvent source, Ageable crop) { this.owner = owner; this.source = source; this.crop = crop; }
    }
    private static final HarvestWindows<Key, BlockBreakEvent, Pending> pending = new HarvestWindows<>();
    private static BlockPlaceEvent currentPlacement;
    /** Только для своих вторичных учётчиков: защиты территории всё равно обрабатывают событие. */
    public static boolean isPlacement(BlockPlaceEvent event) { return event != null && event == currentPlacement; }
    private static Key key(Block block) { return new Key(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()); }
    public static boolean request(Plugin plugin, BlockBreakEvent source, BooleanSupplier eligibility) {
        if (!Bukkit.isPrimaryThread() || !plugin.isEnabled() || source.isCancelled() || !source.isDropItems()
                || CropPolicy.seed(source.getBlock().getType().name()) == null
                || !(source.getBlock().getBlockData() instanceof Ageable data) || !CropPolicy.ripe(data.getAge(), data.getMaximumAge())) return false;
        Key key = key(source.getBlock());
        var crop = (Ageable) data.clone(); crop.setAge(0);
        var reserved = pending.reserve(key, source, new Pending(plugin, source, crop));
        if (reserved == null) return false;
        var entry = reserved.value(); entry.providers.put(plugin, eligibility);
        if (!reserved.fresh()) return true;
        try {
            entry.task = Bukkit.getScheduler().runTask(plugin, () -> {
                try { if (pending.get(key) == entry) plant(key, entry); }
                finally { pending.remove(key, entry); }
            });
        } catch (RuntimeException | Error failure) { pending.remove(key, entry); throw failure; }
        return true;
    }
    private static boolean eligible(Pending entry) {
        Player player = entry.source.getPlayer(); Block block = entry.source.getBlock();
        if (!entry.owner.isEnabled() || entry.source.isCancelled() || !entry.source.isDropItems()
                || !player.isOnline() || player.isDead() || player.hasMetadata("NPC") || player.getGameMode() != GameMode.SURVIVAL
                || !block.getWorld().equals(player.getWorld()) || !block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                || player.getLocation().distanceSquared(block.getLocation().add(.5, .5, .5)) > 36) return false;
        for (var provider : List.copyOf(entry.providers.entrySet())) {
            try { if (provider.getKey().isEnabled() && provider.getValue().getAsBoolean()) return true; }
            catch (RuntimeException | LinkageError ignored) { /* Недоступный источник не даёт посадку. */ }
        }
        return false;
    }
    private static void plant(Key key, Pending entry) {
        Block block = entry.source.getBlock(); Player player = entry.source.getPlayer();
        if (!eligible(entry) || !block.getType().isAir() || !entry.crop.isSupported(block)) return;
        Material seed = Material.valueOf(CropPolicy.seed(entry.crop.getMaterial().name()));
        int slot = -1; ItemStack before = null;
        for (int i = 0; i < player.getInventory().getStorageContents().length; i++) {
            var item = player.getInventory().getItem(i);
            if (item != null && item.getType() == seed && !item.hasItemMeta() && item.getAmount() > 0) { slot = i; before = item.clone(); break; }
        }
        if (slot < 0) return;
        var old = block.getState(); var paid = before.clone(); paid.setAmount(before.getAmount() - 1);
        player.getInventory().setItem(slot, paid.getAmount() == 0 ? null : paid); // До размещения/чужих callbacks.
        boolean placed = false, accepted = false;
        try {
            block.setBlockData(entry.crop, false); placed = true;
            var against = block.getRelative(entry.crop instanceof Directional facing ? facing.getFacing() : BlockFace.DOWN);
            var event = new BlockPlaceEvent(block, old, against, new ItemStack(seed), player, true, EquipmentSlot.HAND);
            var previousPlacement = currentPlacement;
            currentPlacement = event;
            try { Bukkit.getPluginManager().callEvent(event); } // Настоящее состояние ростка для защиты территории.
            finally { currentPlacement = previousPlacement; }
            accepted = !event.isCancelled() && event.canBuild() && pending.get(key) == entry && eligible(entry)
                    && block.getBlockData().equals(entry.crop) && entry.crop.isSupported(block);
        } finally {
            if (!accepted) {
                boolean own = block.getBlockData().equals(entry.crop);
                if (own) old.update(true, false);
                // Если чужой callback уже заменил/сломал росток, не создаём второе семя возвратом.
                if ((!placed || own) && block.getBlockData().equals(old.getBlockData())) {
                    for (var leftover : player.getInventory().addItem(new ItemStack(seed, 1)).values())
                        block.getWorld().dropItemNaturally(block.getLocation().add(.5, .5, .5), leftover);
                }
            }
        }
    }
    /** Остановка ядра: не оставляем статические ссылки на провайдеры и игроков. */
    public static void clear() {
        if (!Bukkit.isPrimaryThread()) return;
        for (var item : pending.snapshot()) remove(item.getKey(), item.getValue());
        currentPlacement = null;
    }
    public static void cancel(Plugin plugin) {
        if (!Bukkit.isPrimaryThread()) return;
        var entries = pending.snapshot();
        for (var item : entries) {
            item.getValue().providers.remove(plugin);
            if (item.getValue().owner == plugin || item.getValue().providers.isEmpty()) remove(item.getKey(), item.getValue());
        }
    }
    public static void cancelPlayer(UUID player) {
        if (!Bukkit.isPrimaryThread()) return;
        for (var item : pending.snapshot()) if (item.getValue().source.getPlayer().getUniqueId().equals(player)) remove(item.getKey(), item.getValue());
    }
    private static void remove(Key key, Pending entry) {
        if (pending.remove(key, entry) && entry.task != null) entry.task.cancel();
    }
    private CropReplanting() { }
}
