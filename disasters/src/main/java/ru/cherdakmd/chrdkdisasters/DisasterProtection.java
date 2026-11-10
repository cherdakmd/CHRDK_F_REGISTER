package ru.cherdakmd.chrdkdisasters;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Защита мира «по-нашему»: приваты CHRDK_Claims, спавн и белый список миров.
 *
 * <p>Это главное отличие от стороннего DeadlyDisasters: тот умеет работать
 * только с фиксированным набором region-плагинов и меняет блоки через
 * {@code setType} в обход {@code BlockBreakEvent}, поэтому защита приватов на
 * Bukkit-событиях его не останавливает. Здесь проверка делается <b>до</b>
 * изменения блока, причём для площадных катастроф проверяется весь набор
 * чанков в радиусе, а не только центральная точка.</p>
 *
 * <p>{@code CHRDK_Claims} объявлен как {@code softdepend}: плагина может не
 * быть на сервере. Этот класс намеренно <b>не содержит ни одной ссылки</b> на
 * его бинарные типы — они живут в отдельном {@link ClaimsHook}, который JVM
 * загрузит только после проверки {@code isPluginEnabled}. Иначе отсутствие
 * софт-зависимости привело бы к {@code NoClassDefFoundError} уже при загрузке
 * защиты.</p>
 */
public final class DisasterProtection {

    private final DisastersPlugin plugin;

    public DisasterProtection(DisastersPlugin plugin) {
        this.plugin = plugin;
    }

    private DisastersConfig config() {
        return plugin.settings();
    }

    /** Можно ли вообще трогать этот блок/точку. */
    public boolean blocked(Location location) {
        if (location == null || location.getWorld() == null) {
            return true;
        }
        World world = location.getWorld();
        if (!config().worldAllowed(world.getName())) {
            return true;
        }
        if (nearSpawn(location)) {
            return true;
        }
        return claimAt(world.getName(), location.getBlockX(), location.getBlockZ());
    }

    /** Площадная проверка: любой приват в радиусе отменяет всю катастрофу. */
    public boolean areaBlocked(Location center, int radius) {
        if (blocked(center)) {
            return true;
        }
        if (!claimsAvailable()) {
            return false;
        }
        String world = center.getWorld().getName();
        for (int[] chunk : chunkKeysInRadius(center.getBlockX(), center.getBlockZ(), radius)) {
            if (claimAt(world, chunk[0] << 4, chunk[1] << 4)) {
                return true;
            }
        }
        return false;
    }

    private boolean nearSpawn(Location location) {
        int radius = config().spawnProtectionRadius();
        if (radius <= 0 || location.getWorld() == null) {
            return false;
        }
        Location spawn = location.getWorld().getSpawnLocation();
        double dx = location.getX() - spawn.getX();
        double dz = location.getZ() - spawn.getZ();
        return dx * dx + dz * dz < (double) radius * radius;
    }

    /** Софт-зависимость проверяется по имени, без загрузки её классов. */
    private boolean claimsAvailable() {
        return config().respectClaims()
                && Bukkit.getPluginManager().isPluginEnabled(ClaimsHook.PLUGIN);
    }

    private boolean claimAt(String world, int blockX, int blockZ) {
        if (!claimsAvailable()) {
            return false;
        }
        return ClaimsHook.hasClaim(plugin.getLogger(), world, blockX >> 4, blockZ >> 4);
    }

    /**
     * Координаты чанков, которые пересекает квадрат радиуса. Чистая функция —
     * покрыта JUnit без сервера.
     */
    public static List<int[]> chunkKeysInRadius(int centerX, int centerZ, int radius) {
        int safe = Math.max(0, radius);
        int minX = (centerX - safe) >> 4;
        int maxX = (centerX + safe) >> 4;
        int minZ = (centerZ - safe) >> 4;
        int maxZ = (centerZ + safe) >> 4;
        List<int[]> out = new ArrayList<>();
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                out.add(new int[]{cx, cz});
            }
        }
        return List.copyOf(out);
    }
}
