package ru.cherdakmd.chrdkclaims;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Телепорт к привату (1.4.120): разбег, кулдаун и поиск безопасной площадки.
 *
 * <p>Порядок: проверка мира, прав и кулдауна → обратный отсчёт (игрок должен стоять
 * на месте и не получать урон) → асинхронная загрузка чанка привата → поиск ближайшей
 * к центру площадки, где есть твёрдая земля и два свободных блока над ней →
 * {@link Player#teleportAsync(Location)}. Кулдаун срабатывает только после удачного
 * телепорта, поэтому отмена разбега его не съедает.</p>
 *
 * <p>Чистая геометрия вынесена в {@link ClaimTeleport}; этот класс отвечает за
 * Bukkit-часть и сообщения.</p>
 */
public final class ClaimTeleportService implements Listener {

    /** Допустимое смещение во время разбега (как в CHRDK_Teleports). */
    private static final double MOVE_TOLERANCE = 0.15;
    /** Та же погрешность по вертикали: лестницы и вода. */
    private static final double VERTICAL_TOLERANCE = 0.5;
    /** Глубина просмотра колонки: ниже поверхности на 32 блока уже пещеры. */
    private static final int MAX_DROP = 32;

    /** Блоки, на которые нельзя ставить игрока даже при «твёрдой» земле. */
    private static final Material[] UNSAFE_GROUND = {
            Material.MAGMA_BLOCK, Material.CACTUS, Material.SWEET_BERRY_BUSH,
            Material.POWDER_SNOW, Material.SCAFFOLDING, Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE, Material.FIRE, Material.SOUL_FIRE
    };

    /** Блоки, сквозь которые нельзя «пройти» при телепорте, хотя они не твёрдые. */
    private static final Material[] UNSAFE_PASSAGE = {
            Material.WATER, Material.LAVA, Material.FIRE, Material.SOUL_FIRE,
            Material.POWDER_SNOW, Material.COBWEB, Material.SWEET_BERRY_BUSH,
            Material.NETHER_PORTAL, Material.END_PORTAL
    };

    /** Ожидающий телепорт: где игрок стоял и задача отсчёта. */
    private static final class Pending {
        private BukkitTask task;
        private String world;
        private double x;
        private double y;
        private double z;
    }

    private final ClaimsPlugin plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public ClaimTeleportService(ClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Готовится ли телепорт игрока прямо сейчас. */
    public boolean isBusy(UUID uuid) {
        return uuid != null && pending.containsKey(uuid);
    }

    /** Остаток кулдауна в секундах (0 — телепорт доступен). */
    public long cooldownLeft(UUID uuid, long nowMillis) {
        if (uuid == null) {
            return 0;
        }
        Long stamp = cooldowns.get(uuid);
        if (stamp == null) {
            return 0;
        }
        long left = stamp + plugin.config().teleportCooldown * 1000L - nowMillis;
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    /**
     * Запрашивает телепорт к привату.
     *
     * @return {@code true}, если разбег запущен (или телепорт выполнен сразу);
     *         {@code false} — запрос отклонён, игрок уже получил причину
     */
    public boolean request(Player player, Claim claim) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        if (!plugin.config().teleportEnabled) {
            plugin.messages().send(player, "tp-disabled");
            return false;
        }
        if (claim == null) {
            plugin.messages().send(player, "tp-failed");
            return false;
        }
        if (!player.hasPermission("chrdkclaims.teleport")) {
            plugin.messages().send(player, "no-permission");
            return false;
        }
        if (!plugin.config().worldEnabled(claim.world)) {
            plugin.messages().send(player, "tp-world-disabled", "world", claim.world);
            return false;
        }
        if (Bukkit.getWorld(claim.world) == null) {
            plugin.messages().send(player, "tp-world-missing", "world", claim.world);
            return false;
        }
        boolean member = claim.isOwner(player.getName()) || claim.isMember(player.getName());
        // Приват на продажу — публичный лот: его можно осмотреть (защита при этом действует).
        boolean viewable = claim.salePrice > 0;
        if (!member && !viewable && !plugin.isBypassing(player)) {
            plugin.messages().send(player, "tp-foreign", "owner", claim.owner);
            return false;
        }
        long now = System.currentTimeMillis();
        long left = cooldownLeft(player.getUniqueId(), now);
        if (left > 0) {
            plugin.messages().send(player, "tp-cooldown", "seconds", String.valueOf(left));
            return false;
        }
        if (pending.containsKey(player.getUniqueId())) {
            plugin.messages().send(player, "tp-busy");
            return false;
        }
        startWarmup(player, claim);
        return true;
    }

    /** Немедленно отменяет разбег игрока (например, при выходе с сервера). */
    public void cancel(UUID uuid, String messageKey) {
        if (uuid == null) {
            return;
        }
        Pending state = pending.remove(uuid);
        if (state == null) {
            return;
        }
        if (state.task != null) {
            state.task.cancel();
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && messageKey != null) {
            plugin.messages().send(player, messageKey);
        }
    }

    /** Отменяет все разбеги — вызывается при выключении плагина. */
    public void shutdown() {
        for (UUID uuid : pending.keySet()) {
            Pending state = pending.remove(uuid);
            if (state != null && state.task != null) {
                state.task.cancel();
            }
        }
        cooldowns.clear();
    }

    // ------------------------------------------------------------- разбег --

    private void startWarmup(Player player, Claim claim) {
        int seconds = plugin.config().teleportWarmup;
        String where = claim.where();
        if (seconds <= 0) {
            finish(player, claim);
            return;
        }
        Location from = player.getLocation();
        Pending state = new Pending();
        state.world = from.getWorld().getName();
        state.x = from.getX();
        state.y = from.getY();
        state.z = from.getZ();
        state.task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending.remove(player.getUniqueId(), state);
            if (!player.isOnline()) {
                return;
            }
            if (!player.getWorld().getName().equals(state.world)) {
                plugin.messages().send(player, "tp-cancelled-move");
                return;
            }
            finish(player, claim);
        }, seconds * 20L);
        pending.put(player.getUniqueId(), state);
        plugin.messages().send(player, "tp-warmup",
                "where", where, "seconds", String.valueOf(seconds));
    }

    /** Завершение разбега: загрузка чанка и выбор точки. Всегда на главной нити. */
    private void finish(Player player, Claim claim) {
        World world = Bukkit.getWorld(claim.world);
        if (world == null) {
            plugin.messages().send(player, "tp-world-missing", "world", claim.world);
            return;
        }
        // Загрузка чанка — колбэком Paper: CompletableFuture-перегрузки в API уже нет.
        world.getChunkAtAsync(claim.x, claim.z, true, chunk -> runMain(() -> {
            if (!player.isOnline()) {
                return;
            }
            Location target = safeLocation(world, claim, player.getLocation().getYaw());
            if (target == null) {
                plugin.messages().send(player, "tp-unsafe", "where", claim.where());
                return;
            }
            teleport(player, claim, target);
        }));
    }

    private void teleport(Player player, Claim claim, Location target) {
        player.teleportAsync(target).thenAccept(success -> runMain(() -> {
            if (!player.isOnline()) {
                return;
            }
            if (!Boolean.TRUE.equals(success)) {
                plugin.messages().send(player, "tp-failed");
                return;
            }
            cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
            plugin.messages().send(player, "tp-done", "where", claim.where());
        }));
    }

    private void runMain(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    // ------------------------------------------------------- выбор точки --

    /**
     * Ближайшая к центру чанка безопасная площадка: твёрдая земля, два свободных
     * блока над ней и никакой жидкости. Возвращает {@code null}, если привата
     * целиком занят водой, лавой или скалами без площадок.
     */
    private Location safeLocation(World world, Claim claim, float yaw) {
        for (ClaimTeleport.Column column : ClaimTeleport.searchOrder(claim.x, claim.z,
                plugin.config().teleportRadius)) {
            Location found = columnLocation(world, column.x(), column.z(), yaw);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private Location columnLocation(World world, int x, int z, float yaw) {
        int minY = world.getMinHeight();
        int top = Math.min(world.getMaxHeight() - 2, world.getHighestBlockYAt(x, z) + 1);
        int floor = Math.max(minY, top - MAX_DROP);
        for (int y = top; y > floor; y--) {
            Material ground = world.getBlockAt(x, y - 1, z).getType();
            if (!safeGround(ground)) {
                continue;
            }
            if (!safePassage(world.getBlockAt(x, y, z))
                    || !safePassage(world.getBlockAt(x, y + 1, z))) {
                continue;
            }
            return new Location(world, x + 0.5, y, z + 0.5, yaw, 0.0f);
        }
        return null;
    }

    private static boolean safeGround(Material material) {
        if (material == null || material.isAir() || !material.isSolid()) {
            return false;
        }
        if (material.name().endsWith("_LEAVES")) {
            return false; // крона дерева — не площадка, даже если «твёрдая»
        }
        for (Material unsafe : UNSAFE_GROUND) {
            if (material == unsafe) {
                return false;
            }
        }
        return true;
    }

    private static boolean safePassage(Block block) {
        Material material = block.getType();
        for (Material unsafe : UNSAFE_PASSAGE) {
            if (material == unsafe) {
                return false;
            }
        }
        return block.isEmpty() || block.isPassable();
    }

    // ----------------------------------------------------------- слушатели --

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.config().teleportCancelMove) {
            return;
        }
        Pending state = pending.get(event.getPlayer().getUniqueId());
        if (state == null || event.getTo() == null) {
            return;
        }
        Location to = event.getTo();
        if (!to.getWorld().getName().equals(state.world)
                || Math.abs(to.getX() - state.x) > MOVE_TOLERANCE
                || Math.abs(to.getZ() - state.z) > MOVE_TOLERANCE
                || Math.abs(to.getY() - state.y) > VERTICAL_TOLERANCE) {
            cancel(event.getPlayer().getUniqueId(), "tp-cancelled-move");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!plugin.config().teleportCancelDamage) {
            return;
        }
        if (event.getEntity() instanceof Player player && isBusy(player.getUniqueId())) {
            cancel(player.getUniqueId(), "tp-cancelled-damage");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId(), null);
        cooldowns.remove(event.getPlayer().getUniqueId());
    }
}
