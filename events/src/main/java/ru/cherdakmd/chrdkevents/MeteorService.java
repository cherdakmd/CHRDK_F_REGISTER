package ru.cherdakmd.chrdkevents;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Падающие звёзды события (1.4.176).
 *
 * <p>Сервис держит на небе столько звёзд, сколько разрешает {@link MeteorSpawn},
 * и ведёт их вниз сам: звезда — это {@link BlockDisplay} с блоком из конфига,
 * который раз в два тика опускается и тянет за собой огненный след. На земле
 * она рассыпается добычей, светом и звуком, а игрокам рядом уходит строка с
 * координатами.</p>
 *
 * <p>Почему display, а не {@code FallingBlock}: настоящий падающий блок при
 * посадке ставит блок в мире, то есть событие строило бы и рушило ландшафт.
 * Display ничего не меняет, не грузит чанки дольше нужного и исчезает вместе с
 * событием. Блоки в точке падения не ломаются и не появляются.</p>
 */
final class MeteorService {

    /** Звезда двигается раз в два тика — плавно и без лишней нагрузки. */
    private static final int TICK_PERIOD = 2;
    /** Сколько блоков звезда проходит за тик. */
    private static final double FALL_STEP = 1.15;
    /** С какой высоты она начинает падать. */
    private static final double START_HEIGHT = 26.0;
    /** Кто ближе этого — получает координаты падения. */
    private static final double ANNOUNCE_RADIUS = 64.0;
    /** Дольше этого звезда в воздухе не висит: чанк мог выгрузиться. */
    private static final long MAX_FLIGHT_MS = 30_000L;
    /** Пауза после неудачной попытки (нет подходящего игрока или мира). */
    private static final long RETRY_MS = 5_000L;
    /** Ближе этого к игроку звезда не падает — чтобы не бить по голове. */
    private static final double MIN_DISTANCE = 6.0;

    private final EventsPlugin plugin;
    private final Random random = new Random();
    private final List<Star> stars = new ArrayList<>();
    private MeteorSpawn spawn = MeteorSpawn.NONE;
    private BukkitTask task;
    private long nextLaunchAt;

    MeteorService(EventsPlugin plugin) {
        this.plugin = plugin;
    }

    /* -------------------------------- жизненный цикл --------------------------- */

    /** Включает звёзды события; без секции {@code meteors} просто ничего не делает. */
    void start(WorldEvent event) {
        stop();
        if (event == null || !event.meteors().usable()) {
            return;
        }
        this.spawn = event.meteors();
        // Первая звезда — почти сразу: событие должно быть видно с первой минуты.
        this.nextLaunchAt = System.currentTimeMillis() + 2_000L;
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, TICK_PERIOD);
    }

    /** Гасит звёзды: недолетевшие исчезают без лута. */
    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Star star : stars) {
            star.remove();
        }
        stars.clear();
        spawn = MeteorSpawn.NONE;
        nextLaunchAt = 0L;
    }

    /** Летят ли звёзды прямо сейчас. */
    boolean active() {
        return spawn.usable() && task != null;
    }

    /** Сколько звёзд в воздухе — для тестов и отладки. */
    int fallingCount() {
        return stars.size();
    }

    /* ---------------------------------- полёт ---------------------------------- */

    private void tick() {
        if (!spawn.usable()) {
            stop();
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= nextLaunchAt && stars.size() < spawn.maxActive()) {
            nextLaunchAt = now + spawn.intervalSeconds() * 1_000L;
            launch();
        }
        Iterator<Star> iterator = stars.iterator();
        while (iterator.hasNext()) {
            Star star = iterator.next();
            if (star.expired(now)) {
                // Чанк выгрузился или звезда потерялась — тихо убираем без добычи.
                star.remove();
                iterator.remove();
                continue;
            }
            if (star.step()) {
                star.remove();
                iterator.remove();
                impact(star);
                continue;
            }
            trail(star.location());
        }
    }

    /** Запускает одну звезду над случайным игроком. */
    private void launch() {
        Player target = pickTarget();
        if (target == null) {
            nextLaunchAt = System.currentTimeMillis() + RETRY_MS;
            return;
        }
        World world = target.getWorld();
        Location base = target.getLocation();
        double angle = random.nextDouble() * Math.PI * 2.0;
        double distance = MIN_DISTANCE
                + random.nextDouble() * Math.max(1.0, spawn.radius() - MIN_DISTANCE);
        double x = base.getX() + Math.cos(angle) * distance;
        double z = base.getZ() + Math.sin(angle) * distance;
        if (!world.isChunkLoaded(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4)) {
            nextLaunchAt = System.currentTimeMillis() + RETRY_MS;
            return;
        }
        int groundY = world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z));
        Location start = new Location(world, x, groundY + 1.0 + START_HEIGHT, z);
        BlockDisplay display = world.spawn(start, BlockDisplay.class, entity -> {
            entity.setBlock(spawn.block(random).createBlockData());
            entity.setTeleportDuration(TICK_PERIOD);
            entity.setPersistent(false);
            entity.setInvulnerable(true);
        });
        if (display == null) {
            return;
        }
        stars.add(new Star(display, x, z, start.getY(), groundY + 1.0,
                System.currentTimeMillis()));
        trail(start);
        // Звук старта: игрок поднимает голову раньше, чем увидит след.
        world.playSound(start, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.8f, 0.9f);
    }

    /** Случайный игрок в обычном мире: в Нижнем и Энде звезда упасть не может. */
    private Player pickTarget() {
        List<Player> candidates = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld() != null
                    && player.getWorld().getEnvironment() == World.Environment.NORMAL
                    && player.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
                candidates.add(player);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(random.nextInt(candidates.size()));
    }

    /** Огненный след: голова звезды и хвост. */
    private void trail(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        world.spawnParticle(Particle.FLAME, location, 8, 0.22, 0.28, 0.22, 0.01);
        world.spawnParticle(Particle.END_ROD, location, 4, 0.18, 0.2, 0.18, 0.01);
        world.spawnParticle(Particle.FIREWORK, location, 3, 0.2, 0.2, 0.2, 0.0);
    }

    /** Звезда долетела: вспышка, звук и добыча на земле. */
    private void impact(Star star) {
        World world = star.world();
        if (world == null) {
            return;
        }
        Location at = new Location(world, star.x, star.landY + 0.2, star.z);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1);
        world.spawnParticle(Particle.FLAME, at, 28, 0.6, 0.4, 0.6, 0.02);
        world.spawnParticle(Particle.END_ROD, at, 22, 0.8, 0.5, 0.8, 0.05);
        world.spawnParticle(Particle.FIREWORK, at, 18, 0.7, 0.4, 0.7, 0.02);
        world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1.0f, 0.8f);
        world.playSound(at, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 0.7f, 0.7f);
        int stacks = spawn.lootAmount(random);
        for (int index = 0; index < stacks; index++) {
            ItemStack loot = new ItemStack(spawn.lootItem(random), 1);
            world.dropItemNaturally(at, loot);
        }
        if (spawn.announce()) {
            announce(at);
        }
    }

    /** Сообщает координаты падения тем, кто рядом: без координат искать иголку в стоге. */
    private void announce(Location at) {
        String x = String.valueOf(at.getBlockX());
        String y = String.valueOf(at.getBlockY());
        String z = String.valueOf(at.getBlockZ());
        for (Player player : at.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(at) > ANNOUNCE_RADIUS * ANNOUNCE_RADIUS) {
                continue;
            }
            player.sendMessage(plugin.messages().get("meteor-fell", "x", x, "y", y, "z", z));
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.4f);
        }
    }

    /** Одна летящая звезда: display, координаты и высота земли под ней. */
    private static final class Star {

        private final BlockDisplay display;
        private final double x;
        private final double z;
        private final double landY;
        private final long bornAt;
        private double y;

        private Star(BlockDisplay display, double x, double z, double y, double landY, long bornAt) {
            this.display = display;
            this.x = x;
            this.z = z;
            this.y = y;
            this.landY = landY;
            this.bornAt = bornAt;
        }

        private World world() {
            return display.getWorld();
        }

        private boolean expired(long now) {
            return !display.isValid() || now - bornAt > MAX_FLIGHT_MS;
        }

        /**
         * Шаг вниз.
         *
         * @return {@code true}, если звезда уже на земле
         */
        private boolean step() {
            y -= FALL_STEP;
            if (y <= landY) {
                y = landY;
                return true;
            }
            World world = world();
            if (world == null) {
                return true;
            }
            display.teleport(new Location(world, x, y, z));
            return false;
        }

        private Location location() {
            World world = world();
            return world == null ? display.getLocation() : new Location(world, x, y, z);
        }

        private void remove() {
            if (display.isValid()) {
                display.remove();
            }
        }
    }
}
