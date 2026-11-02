package ru.cherdakmd.chrdkhunt;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import ru.cherdakmd.chrdkregister.api.SpawnClaimIndex;

/** Только загруженные чанки; отсутствие/сбой индекса приватов закрывает новые механики. */
final class WildSafety {
    private final HuntPlugin plugin;
    private long nextLog;
    WildSafety(HuntPlugin plugin) { this.plugin = plugin; }
    private SpawnClaimIndex index() {
        var registration = Bukkit.getServicesManager().getRegistration(SpawnClaimIndex.class);
        return registration != null && registration.getPlugin().isEnabled() ? registration.getProvider() : null;
    }
    boolean available() { return index() != null; }
    boolean player(Player p) {
        return p != null && p.isOnline() && !p.isDead() && p.getGameMode() == GameMode.SURVIVAL
                && !p.isInvulnerable() && !p.hasMetadata("NPC")
                && p.getMetadata("vanished").stream().noneMatch(MetadataValue::asBoolean)
                && plugin.config().enabled() && !plugin.config().worldDisabled(p.getWorld().getName())
                && p.hasPermission("chrdkhunt.use") && plugin.coreApi() != null && plugin.coreApi().isAuthenticated(p);
    }
    boolean clear(Location loc, int protection) {
        if (loc == null || loc.getWorld() == null || !plugin.config().enabled()) return false;
        World world = loc.getWorld();
        if (plugin.config().worldDisabled(world.getName()) || !world.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)
                || !world.getWorldBorder().isInside(loc)) return false;
        Location spawn = world.getSpawnLocation();
        double x = loc.getX() - spawn.getX(), z = loc.getZ() - spawn.getZ();
        if (Math.abs(x) <= protection && Math.abs(z) <= protection) return false;
        try {
            SpawnClaimIndex claims = index();
            return claims != null && !claims.isClaimed(world.getName(), loc.getBlockX(), loc.getBlockZ());
        } catch (RuntimeException | LinkageError error) {
            long now = System.currentTimeMillis();
            if (now >= nextLog) { nextLog = now + 60_000; plugin.getLogger().warning("Индекс приватов недоступен: дикие биомы/нашествие приостановлены."); }
            return false;
        }
    }
    boolean target(Player p, int protection) { return player(p) && clear(p.getLocation(), protection); }
    /** Пол в существующем чанке, воздух для ног/головы. Не получает и не генерирует чанк. */
    boolean stand(Location loc, int protection) {
        if (!clear(loc, protection)) return false;
        var w = loc.getWorld(); int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        if (y <= w.getMinHeight() || y + 1 >= w.getMaxHeight()) return false;
        var floor = w.getBlockAt(x, y - 1, z).getType();
        return floor.isSolid() && floor != Material.MAGMA_BLOCK && floor != Material.CAMPFIRE
                && floor != Material.SOUL_CAMPFIRE && floor != Material.CACTUS
                && w.getBlockAt(x, y, z).getType().isAir() && w.getBlockAt(x, y + 1, z).getType().isAir();
    }
    static boolean healthyTick(double ceiling) {
        double mspt = Bukkit.getAverageTickTime();
        return Double.isFinite(mspt) && mspt <= ceiling;
    }
}
