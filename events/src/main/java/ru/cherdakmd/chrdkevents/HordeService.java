package ru.cherdakmd.chrdkevents;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Нашествие мобов события (1.4.176).
 *
 * <p>Две половины: множитель естественного спавна («эхо») и волны. Эхо
 * добавляет мобов к тому, что сервер и так заспавнил рядом с игроком; волны
 * раз в {@code wave-interval-seconds} поднимают группу мобов вокруг случайного
 * игрока. Оба механизма упираются в {@code max-near-player}: без потолка один
 * вечер события уронил бы TPS.</p>
 *
 * <p>Свои спавны идут с причиной {@code CUSTOM}, а обработчик эха смотрит
 * только на {@code NATURAL}, поэтому рекурсии нет. Само добавление мобов
 * отложено на следующий тик: спавнить внутри обработчика спавна — плохая
 * привычка, которая ломает порядок тика мира.</p>
 *
 * <p>Приваты событие не трогает: ни эхо, ни волна не поднимают мобов на чужом
 * участке ({@link ClaimGuard}). Это важнее «плотности» — базу не должно
 * заливать мобами из-за события, которое включил сервер.</p>
 */
final class HordeService {

    /** Волны считаются раз в секунду. */
    private static final int TICK_PERIOD = 20;
    /** Первая волна не сразу: игрок должен увидеть объявление о событии. */
    private static final long FIRST_WAVE_DELAY_MS = 15_000L;
    /** Ближе этого к игроку волна не появляется. */
    private static final double WAVE_MIN_DISTANCE = 8.0;
    /** Кто дальше этого от точки спавна — уже не участник события. */
    private static final double REINFORCE_RADIUS = 48.0;
    /** В этом радиусе сообщение о волне видно. */
    private static final double ANNOUNCE_RADIUS = 48.0;
    /** Сколько раз пробуем найти безопасную точку, прежде чем пропустить моба. */
    private static final int SPOT_ATTEMPTS = 8;
    /** Как часто пересчитываем «сколько мобов уже рядом» (мс). */
    private static final long COUNT_TTL_MS = 1_000L;
    /** Блоки, на которые моб не должен вставать. */
    private static final List<Material> HAZARDS = List.of(
            Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.FIRE, Material.SOUL_FIRE, Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH,
            Material.POINTED_DRIPSTONE, Material.WITHER_ROSE);

    private final EventsPlugin plugin;
    private final Random random = new Random();
    private final Map<UUID, Count> counts = new HashMap<>();
    private HordeSpawn horde = HordeSpawn.NONE;
    private BukkitTask task;
    private long nextWaveAt;

    HordeService(EventsPlugin plugin) {
        this.plugin = plugin;
    }

    /* -------------------------------- жизненный цикл --------------------------- */

    /** Включает нашествие события; без секции {@code horde} ничего не делает. */
    void start(WorldEvent event) {
        stop();
        if (event == null || !event.horde().usable()) {
            return;
        }
        this.horde = event.horde();
        this.nextWaveAt = System.currentTimeMillis() + FIRST_WAVE_DELAY_MS;
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_PERIOD, TICK_PERIOD);
    }

    /** Выключает нашествие: уже заспавненные мобы остаются жить своей жизнью. */
    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        horde = HordeSpawn.NONE;
        nextWaveAt = 0L;
        counts.clear();
    }

    boolean active() {
        return horde.usable() && task != null;
    }

    /** Множитель спавна в процентах — для лога и отладочных сообщений. */
    int multiplierPercent() {
        return (int) Math.round(horde.multiplier() * 100.0);
    }

    /* ----------------------------------- волны --------------------------------- */

    private void tick() {
        if (!horde.usable()) {
            stop();
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextWaveAt) {
            return;
        }
        nextWaveAt = now + horde.waveIntervalSeconds() * 1_000L;
        wave();
    }

    /** Поднимает волну вокруг случайного игрока, если рядом ещё есть место. */
    private void wave() {
        List<Player> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
                players.add(player);
            }
        }
        if (players.isEmpty()) {
            return;
        }
        Player target = players.get(random.nextInt(players.size()));
        World world = target.getWorld();
        if (world == null || world.getEnvironment() != World.Environment.NORMAL) {
            return;
        }
        if (!horde.allowSpawnNow(true, world.isDayTime())) {
            return;
        }
        if (!hasSkyAccess(target)) {
            // Волна спавнит на поверхности: игроку в шахте она не видна, а мобов
            // над головой только прибавит. Под землёй работает обычное эхо.
            return;
        }
        int free = horde.maxNearPlayer() - nearCount(world, target, true);
        if (free <= 0) {
            return;
        }
        int count = Math.min(horde.waveSize(), free);
        List<Location> spots = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            Location spot = spotAround(target);
            if (spot != null) {
                spots.add(spot);
            }
        }
        if (spots.isEmpty()) {
            return;
        }
        spawn(spots, null);
        announce(target.getLocation(), spots.size());
    }

    /**
     * Спавнит по мобу в каждой точке — уже следующим тиком.
     *
     * @param preferred конкретный моб (эхо повторяет того, кого заспавнил сервер)
     *                  или {@code null} — тогда моб берётся из списка события
     */
    private void spawn(List<Location> spots, EntityType preferred) {
        List<Location> safe = List.copyOf(spots);
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            // Пока задача ждала тика, событие могло закончиться — тогда не спавним.
            if (!plugin.isEnabled() || !horde.usable()) {
                return;
            }
            for (Location spot : safe) {
                EntityType type = preferred != null ? preferred : horde.mob(random);
                if (type == null || spot.getWorld() == null) {
                    continue;
                }
                spot.getWorld().spawnEntity(spot, type, CreatureSpawnEvent.SpawnReason.CUSTOM);
            }
        });
    }

    private void announce(Location center, int count) {
        for (Player player : center.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(center) > ANNOUNCE_RADIUS * ANNOUNCE_RADIUS) {
                continue;
            }
            player.sendMessage(plugin.messages().get("horde-wave", "count",
                    String.valueOf(count)));
        }
    }

    /* ------------------------------ эхо спавна --------------------------------- */

    /**
     * Добавляет мобов к естественному спавну рядом с игроком.
     *
     * <p>Вызывается из обработчика {@code CreatureSpawnEvent} только для
     * причины {@code NATURAL}: спавнеры, призывы команд и наши собственные
     * спавны множитель не получают.</p>
     *
     * @param location точка естественного спавна
     * @param type     кого заспавнил сервер
     */
    void reinforce(Location location, EntityType type) {
        if (!active() || location == null || type == null || location.getWorld() == null) {
            return;
        }
        World world = location.getWorld();
        if (!horde.allowSpawnNow(world.getEnvironment() == World.Environment.NORMAL,
                world.isDayTime())) {
            return;
        }
        if (ClaimGuard.claimed(world, location.getBlockX(), location.getBlockZ())) {
            return;
        }
        Player nearest = nearestPlayer(world, location, REINFORCE_RADIUS);
        if (nearest == null) {
            return;
        }
        if (nearCount(world, nearest, false) >= horde.maxNearPlayer()) {
            return;
        }
        int extra = horde.extraSpawns(random);
        if (extra <= 0) {
            return;
        }
        List<Location> spots = new ArrayList<>(extra);
        for (int index = 0; index < extra; index++) {
            Location spot = spotNear(location, 2.0, 6.0);
            spots.add(spot == null ? location : spot);
        }
        spawn(spots, horde.mobs().contains(type) ? type : null);
    }

    /* --------------------------------- помощники ------------------------------- */

    /** Случайная точка вокруг игрока: от 8 блоков до {@code max-distance}. */
    private Location spotAround(Player player) {
        double max = Math.max(WAVE_MIN_DISTANCE, horde.maxDistance());
        return spotNear(player.getLocation(), Math.max(WAVE_MIN_DISTANCE, horde.minDistance()), max);
    }

    /** Безопасная точка на поверхности в кольце расстояний; {@code null} — не нашли. */
    private Location spotNear(Location center, double min, double max) {
        World world = center.getWorld();
        if (world == null) {
            return null;
        }
        for (int attempt = 0; attempt < SPOT_ATTEMPTS; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = min + random.nextDouble() * Math.max(0.5, max - min);
            int x = (int) Math.floor(center.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(center.getZ() + Math.sin(angle) * distance);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            if (ClaimGuard.claimed(world, x, z)) {
                continue;
            }
            int y = world.getHighestBlockYAt(x, z);
            if (y < world.getMinHeight() + 2 || y >= world.getMaxHeight() - 2) {
                continue;
            }
            Location spot = new Location(world, x + 0.5, y + 1.0, z + 0.5);
            if (safe(spot)) {
                return spot;
            }
        }
        return null;
    }

    /** Стоит ли моб на твёрдом блоке, а не в воде, листьях или магме. */
    private static boolean safe(Location spot) {
        World world = spot.getWorld();
        if (world == null) {
            return false;
        }
        Block feet = world.getBlockAt(spot);
        Block below = feet.getRelative(0, -1, 0);
        if (!feet.isPassable() || feet.isLiquid()) {
            return false;
        }
        Material floor = below.getType();
        if (!floor.isSolid() || floor.isAir() || below.isLiquid() || HAZARDS.contains(floor)) {
            return false;
        }
        return true;
    }

    /** Видно ли игроку небо: волна спавнит только на поверхности. */
    private static boolean hasSkyAccess(Player player) {
        World world = player.getWorld();
        if (world == null) {
            return false;
        }
        int surface = world.getHighestBlockYAt(player.getLocation());
        return player.getLocation().getBlockY() >= surface - 3;
    }

    /** Ближайший игрок в радиусе или {@code null}. */
    private static Player nearestPlayer(World world, Location location, double radius) {
        Player best = null;
        double bestDistance = radius * radius;
        for (Player player : world.getPlayers()) {
            if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(location);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }

    /**
     * Сколько враждебных мобов уже рядом с игроком.
     *
     * <p>Считается не чаще раза в секунду на мир: естественный спавн зовёт эхо
     * десятки раз в секунду, и обход сущностей на каждый вызов был бы дороже
     * самого события.</p>
     */
    private int nearCount(World world, Player player, boolean fresh) {
        long now = System.currentTimeMillis();
        Count cached = counts.get(world.getUID());
        if (!fresh && cached != null && now - cached.at < COUNT_TTL_MS) {
            return cached.value;
        }
        int count = world.getNearbyEntities(player.getLocation(), HordeSpawn.NEAR_RADIUS,
                HordeSpawn.NEAR_RADIUS, HordeSpawn.NEAR_RADIUS,
                entity -> entity instanceof Enemy).size();
        counts.put(world.getUID(), new Count(now, count));
        return count;
    }

    /** Кэш подсчёта мобов: мир → значение и время. */
    private record Count(long at, int value) {
    }
}
