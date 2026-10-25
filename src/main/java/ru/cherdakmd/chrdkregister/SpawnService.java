package ru.cherdakmd.chrdkregister;

import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import ru.cherdakmd.chrdkregister.api.SpawnClaimIndex;
import ru.cherdakmd.chrdkregister.api.SpawnHomeIndex;
import ru.cherdakmd.chrdkregister.api.events.PlayerAuthCompletedEvent;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Случайный спавн (1.4.106): новый игрок появляется в случайной точке мира после
 * регистрации/входа, а погибший — вместо спавна мира, если у него нет своего места.
 *
 * <p>Рандом не применяется, если у игрока есть кровать / якорь возрождения /
 * {@code /spawnpoint}, дом в CHRDK_Teleports ({@code spawn.respect-homes}) или свой
 * приват в CHRDK_Claims ({@code spawn.respect-claims}). Сама точка тоже не выбирается
 * внутри чужого привата ({@code spawn.avoid-claims}).</p>
 *
 * <p>Выбор точки идёт на главном потоке: {@code getHighestBlockYAt} для неисследованного
 * чанка генерирует его синхронно — это разовая плата за вход/смерть, поэтому число
 * попыток ограничено {@code spawn.max-attempts}.</p>
 */
public final class SpawnService implements Listener {

    /** Сколько «заявка первого входа» живёт без повторного захода. */
    private static final long PENDING_MAX_AGE_MILLIS = TimeUnit.DAYS.toMillis(90);

    private final RegisterPlugin plugin;
    private final AccessManager accessManager;
    private final SpawnStore store;
    private final Random random = new Random();
    private volatile SpawnSettings settings;

    public SpawnService(RegisterPlugin plugin, AccessManager accessManager,
                        SpawnStore store, SpawnSettings settings) {
        this.plugin = plugin;
        this.accessManager = accessManager;
        this.store = store;
        this.settings = settings;
    }

    /** Загрузка памяти и сводка в лог (вызывается из {@code onEnable}). */
    public void start() {
        store.load();
        int pruned = store.prunePending(System.currentTimeMillis(), PENDING_MAX_AGE_MILLIS);
        if (pruned > 0) {
            logger().info("[spawn] убрано просроченных заявок первого входа: " + pruned);
        }
        SpawnSettings current = settings;
        logger().info("[spawn] случайный спавн: " + (current.enabled() ? "включён" : "выключен")
                + ", первый вход: " + (current.onFirstJoin() ? "да" : "нет")
                + ", смерть: " + (current.onDeath() ? "да" : "нет")
                + ", радиус " + current.radiusBlocks() + " (от " + current.minRadiusBlocks() + ")"
                + ", мир «" + (current.world().isBlank() ? "мир игрока" : current.world()) + "»");
    }

    /** {@code /pass reload}: новые значения без пересоздания слушателя. */
    public void reload(SpawnSettings settings) {
        this.settings = settings;
    }

    public SpawnSettings settings() {
        return settings;
    }

    /** Остановка: файл уже сохранён при каждом изменении, здесь только сводка. */
    public void shutdown() {
        store.saveNow();
    }

    /* ------------------------------ первый вход ------------------------------ */

    /**
     * Запоминаем новичка до авторизации: если он выйдет, не успев зарегистрироваться,
     * случайная точка всё равно достанется ему при первом успешном входе.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String key = SpawnStore.key(player.getName());
        if (store.isDone(key) || store.isPending(key)) {
            return;
        }
        if (player.hasPlayedBefore()) {
            // Игрок пришёл на сервер до обновления — рандомить его задним числом нельзя.
            store.markDone(key);
        } else {
            store.markPending(key, System.currentTimeMillis());
        }
    }

    /**
     * Игрок полностью вошёл (пароль + обязательная 2FA) — выдаём его случайную точку.
     * Событие приходит и после повторного {@code completeAuthentication} (например,
     * после {@code /pass reset2fa}); заявка к тому моменту уже закрыта, поэтому второй
     * раз игрока не переносит.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAuthCompleted(PlayerAuthCompletedEvent event) {
        Player player = event.getPlayer();
        String key = SpawnStore.key(player.getName());
        if (!store.isPending(key)) {
            return;
        }
        SpawnSettings current = settings;
        boolean allowed = SpawnPolicy.shouldRandomSpawn(current, SpawnTrigger.FIRST_JOIN, true,
                hasRespawnPoint(player), hasHome(player), ownsClaim(player));
        if (!allowed) {
            store.markDone(key);
            return;
        }
        World world = resolveWorld(player);
        Location spot = world == null ? null : findSpot(world, current);
        // Заявка закрывается в любом случае: повторять перенос при каждом входе нельзя.
        store.markDone(key);
        if (spot == null) {
            if (world != null) {
                plugin.messages().send(player, "spawn.failed");
            }
            return;
        }
        logger().info("[spawn] первый вход " + player.getName() + " → "
                + spot.getBlockX() + ", " + spot.getBlockZ() + " (" + world.getName() + ")");
        teleport(player, spot, "spawn.teleported");
    }

    /* --------------------------------- смерть -------------------------------- */

    /**
     * Возрождение после смерти: подменяем точку на случайную, если у игрока нет
     * кровати/якоря/точки возрождения, дома и своего привата.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        if (event.getRespawnReason() != PlayerRespawnEvent.RespawnReason.DEATH) {
            return;
        }
        Player player = event.getPlayer();
        SpawnSettings current = settings;
        // Кровать/якорь/точка /spawnpoint есть и ещё работает. Если блок кровати сломали
        // (isMissingRespawnBlock), личного места фактически нет — игрок начинает заново.
        boolean hasRespawnPoint = event.isBedSpawn() || event.isAnchorSpawn()
                || (hasRespawnPoint(player) && !event.isMissingRespawnBlock());
        boolean allowed = SpawnPolicy.shouldRandomSpawn(current, SpawnTrigger.DEATH,
                !accessManager.isBlocked(player), hasRespawnPoint,
                hasHome(player), ownsClaim(player));
        if (!allowed) {
            return;
        }
        World world = resolveWorld(player);
        if (world == null) {
            return;
        }
        Location spot = findSpot(world, current);
        if (spot == null) {
            plugin.messages().send(player, "spawn.failed");
            return;
        }
        event.setRespawnLocation(spot);
        Location announced = spot.clone();
        // Сообщение — уже после фактического возрождения, а не на экране смерти.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                plugin.messages().send(player, "spawn.respawned",
                        "x", announced.getBlockX(), "z", announced.getBlockZ(),
                        "world", announced.getWorld().getName());
            }
        });
    }

    /* ------------------------------ выбор точки ------------------------------ */

    /**
     * Ищет безопасную точку в кольце вокруг спавна мира (или заданного центра).
     *
     * @return точка для телепорта или {@code null}, если все попытки неудачны
     */
    public Location findSpot(World world, SpawnSettings current) {
        Location spawn = world.getSpawnLocation();
        long centerX = current.useWorldSpawnCenter() ? spawn.getBlockX() : current.centerX();
        long centerZ = current.useWorldSpawnCenter() ? spawn.getBlockZ() : current.centerZ();
        WorldBorder border = world.getWorldBorder();
        for (int attempt = 0; attempt < current.maxAttempts(); attempt++) {
            int[] point = SpawnSpotPicker.pick(random, centerX, centerZ,
                    current.radiusBlocks(), current.minRadiusBlocks());
            int x = point[0];
            int z = point[1];
            int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (y <= world.getMinHeight() || y + 2 >= world.getMaxHeight()) {
                continue;
            }
            Material ground = world.getBlockAt(x, y, z).getType();
            boolean groundSolid = ground.isSolid();
            Material feet = world.getBlockAt(x, y + 1, z).getType();
            Material head = world.getBlockAt(x, y + 2, z).getType();
            if (!SpawnSafety.isSafeSpot(ground, groundSolid, feet, head,
                    y, world.getMinHeight(), world.getMaxHeight())) {
                continue;
            }
            if (!border.isInside(new Location(world, x + 0.5D, y, z + 0.5D))) {
                continue;
            }
            if (current.avoidClaims() && isClaimed(world.getName(), x, z)) {
                continue;
            }
            return new Location(world, x + 0.5D, y + 1.0D, z + 0.5D,
                    random.nextFloat() * 360.0F, 0.0F);
        }
        return null;
    }

    /* ------------------------------- помощники ------------------------------- */

    private void teleport(Player player, Location spot, String messageKey) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> teleport(player, spot, messageKey));
            return;
        }
        if (!player.isOnline()) {
            return;
        }
        player.teleport(spot);
        plugin.messages().send(player, messageKey,
                "x", spot.getBlockX(), "z", spot.getBlockZ(),
                "world", spot.getWorld().getName());
    }

    /**
     * Мир для случайной точки: {@code spawn.world} из конфига, иначе мир игрока.
     * Нижний мир и Энд заменяются основным миром сервера (на крыше Нижнего мира
     * «безопасной» площадкой оказался бы бедрок) — либо, при
     * {@code spawn.other-dimensions: vanilla}, там работает обычное возрождение.
     *
     * @return мир или {@code null}, если случайный спавн в этом измерении не применяется
     */
    private World resolveWorld(Player player) {
        String configured = settings.world();
        if (configured != null && !configured.isBlank()) {
            World world = Bukkit.getWorld(configured);
            if (world != null) {
                return world;
            }
            logger().warning("[spawn] мир spawn.world «" + configured
                    + "» не найден — используем мир игрока " + player.getWorld().getName());
        }
        World playerWorld = player.getWorld();
        if (playerWorld != null && playerWorld.getEnvironment() == World.Environment.NORMAL) {
            return playerWorld;
        }
        if (!settings.otherDimensions()) {
            return null;
        }
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
    }

    /** Заданы ли кровать, якорь возрождения или личная точка {@code /spawnpoint}. */
    private boolean hasRespawnPoint(Player player) {
        return player.getRespawnLocation() != null;
    }

    private boolean hasHome(Player player) {
        SpawnHomeIndex index = Bukkit.getServicesManager().load(SpawnHomeIndex.class);
        return index != null && index.hasHome(player.getUniqueId());
    }

    private boolean ownsClaim(Player player) {
        SpawnClaimIndex index = Bukkit.getServicesManager().load(SpawnClaimIndex.class);
        return index != null && index.ownsAnyClaim(player.getName());
    }

    private boolean isClaimed(String worldName, int blockX, int blockZ) {
        SpawnClaimIndex index = Bukkit.getServicesManager().load(SpawnClaimIndex.class);
        return index != null && index.isClaimed(worldName, blockX, blockZ);
    }

    private Logger logger() {
        return plugin.getLogger();
    }
}
