package ru.cherdakmd.chrdktreasures;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Клады-бочки (1.4.166): найденный клад появляется бочкой и открывается с
 * анимацией.
 *
 * <p>Раньше находка сразу падала в инвентарь. Теперь на месте добытого блока
 * встаёт настоящая бочка: добыча лежит внутри, владелец открывает её сам, а
 * появление сопровождается частицами, звуком и хлопком крышки. Бочка — не
 * декорация: если её сломать, ванильная механика высыплет содержимое, поэтому
 * находку нельзя потерять.</p>
 *
 * <p>Записи о владельце и сроке жизни хранятся в PersistentDataContainer самой
 * бочки, а не в отдельном файле: перезапуск сервера и выгрузка чанка ничего не
 * теряют, и дублировать содержимое в JSON не нужно. В памяти держится только
 * индекс живых бочек — для лимита на игрока и уборки по сроку.</p>
 *
 * <p>Защита от потери и воровства: чужая бочка не открывается и не ломается
 * (если включён {@code owner-only}), воронки не тянут из неё предметы, а взрыв
 * не уничтожает клад. Просроченная бочка исчезает с эффектом, а её содержимое
 * высыпается на землю.</p>
 */
public final class TreasureBarrels implements Listener {

    /** Потолок индекса живых бочек: защита от утечки на больших серверах. */
    private static final int MAX_LIVE = 4_096;
    /** Сколько позиций пробуем, прежде чем выдать клад в инвентарь. */
    private static final int MAX_PLACES = 3;
    /** Раз в секунду: бочка живёт 15 секунд, и уборка должна успевать. */
    private static final long SWEEP_TICKS = 20L;
    private static final long LID_FLICK_TICKS = 8L;
    /** За сколько до исчезновения предупредить владельца. */
    private static final long EXPIRE_WARN_MILLIS = 5_000L;

    private record Key(UUID world, int x, int y, int z) {
    }

    private final TreasuresPlugin plugin;
    private final NamespacedKey ownerKey;
    private final NamespacedKey nameKey;
    private final NamespacedKey tierKey;
    private final NamespacedKey createdKey;
    /** Живые бочки: для лимита на игрока, уборки по сроку и анимации. */
    private final Map<Key, TreasureBarrel> live = new ConcurrentHashMap<>();
    /** Уже открывавшиеся бочки: появление и открытие анимируются по разу. */
    private final Set<Key> opened = ConcurrentHashMap.newKeySet();
    /** Бочки, у которых уже запланирована уборка после опустошения. */
    private final Set<Key> scheduled = ConcurrentHashMap.newKeySet();
    /** Кому уже сказали, что бочка вот-вот исчезнет. */
    private final Set<Key> warned = ConcurrentHashMap.newKeySet();
    /** Индекс на диске: срок жизни и лимит должны пережить перезапуск. */
    private final Path indexPath;
    private final AtomicBoolean dirty = new AtomicBoolean();
    private BukkitTask sweepTask;

    TreasureBarrels(TreasuresPlugin plugin) {
        this.plugin = plugin;
        this.ownerKey = new NamespacedKey(plugin, "treasure_owner");
        this.nameKey = new NamespacedKey(plugin, "treasure_owner_name");
        this.tierKey = new NamespacedKey(plugin, "treasure_tier");
        this.createdKey = new NamespacedKey(plugin, "treasure_created");
        this.indexPath = plugin.getDataFolder().toPath().resolve("barrels.tsv");
        load();
    }

    /* -------------------------------- служебное ------------------------------- */

    /** Читает индекс с диска: бочки, поставленные до перезапуска, снова под учётом. */
    private void load() {
        if (!Files.exists(indexPath)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(indexPath, StandardCharsets.UTF_8)) {
                TreasureBarrelIndex.Entry entry = TreasureBarrelIndex.decode(line);
                if (entry == null) {
                    continue;
                }
                live.put(new Key(entry.world(), entry.x(), entry.y(), entry.z()), entry.barrel());
            }
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().warning("Не удалось прочитать индекс кладов-бочек: "
                    + exception.getMessage());
        }
    }

    /** Отмечает индекс изменённым: запись уйдёт вместе с общим сохранением. */
    private void touch() {
        dirty.set(true);
    }

    /** Сохраняет индекс, если он менялся; вызывается из асинхронной задачи. */
    public void flush() {
        if (!dirty.get()) {
            return;
        }
        saveNow();
    }

    /** Пишет индекс на диск; вызывается при выключении плагина и по таймеру. */
    public void saveNow() {
        dirty.set(false);
        List<String> lines = new ArrayList<>(live.size() + 1);
        lines.add(TreasureBarrelIndex.HEADER);
        for (Map.Entry<Key, TreasureBarrel> row : live.entrySet()) {
            Key key = row.getKey();
            lines.add(TreasureBarrelIndex.encode(new TreasureBarrelIndex.Entry(
                    key.world(), key.x(), key.y(), key.z(), row.getValue())));
        }
        Path tmp = indexPath.resolveSibling("barrels.tsv.tmp");
        try {
            Path folder = indexPath.getParent();
            if (folder != null) {
                Files.createDirectories(folder);
            }
            Files.write(tmp, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            Files.move(tmp, indexPath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException exception) {
            dirty.set(true);
            plugin.getLogger().warning("Не удалось сохранить индекс кладов-бочек: "
                    + exception.getMessage());
        }
    }

    /** Запускает уборку просроченных бочек; вызывается при старте и reload. */
    public void start() {
        stop();
        this.sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep,
                SWEEP_TICKS, SWEEP_TICKS);
    }

    public void stop() {
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
    }

    /** Сколько живых бочек сейчас в индексе — для лога и диагностики. */
    public int liveCount() {
        return live.size();
    }

    /**
     * Сколько живых бочек принадлежит игроку.
     *
     * <p>Просроченные бочки в незагруженных чанках ещё висят в индексе (содержимое
     * нельзя высыпать, пока чанк не загружен), но лимит они не занимают: иначе
     * игрок остался бы без новых бочек из-за старых.</p>
     */
    public int countOf(UUID owner) {
        if (owner == null) {
            return 0;
        }
        long ttl = plugin.config().barrels().expireMillis();
        long now = System.currentTimeMillis();
        int count = 0;
        for (TreasureBarrel model : live.values()) {
            if (model.ownedBy(owner) && !model.expired(now, ttl)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Запись о бочке по блоку или {@code null}, если это обычная бочка.
     *
     * <p>Если после перезапуска бочки нет в индексе, она «усыновляется» по
     * данным самого блока: владелец и срок жизни не теряются.</p>
     */
    public TreasureBarrel at(Block block) {
        if (block == null || block.getType() != Material.BARREL) {
            return null;
        }
        Key key = key(block);
        TreasureBarrel cached = live.get(key);
        if (cached != null) {
            return cached;
        }
        BlockState state;
        try {
            state = block.getState();
        } catch (RuntimeException unloaded) {
            return null;
        }
        if (!(state instanceof Barrel barrel)) {
            return null;
        }
        PersistentDataContainer data = barrel.getPersistentDataContainer();
        String rawOwner = data.get(ownerKey, PersistentDataType.STRING);
        UUID owner = parseUuid(rawOwner);
        if (owner == null) {
            return null;
        }
        String name = orEmpty(data.get(nameKey, PersistentDataType.STRING));
        TreasureTier tier = TreasureTier.byId(
                orEmpty(data.get(tierKey, PersistentDataType.STRING)));
        Long created = data.get(createdKey, PersistentDataType.LONG);
        TreasureBarrel model = new TreasureBarrel(owner, name, tier,
                created == null ? 0L : created);
        if (live.size() < MAX_LIVE) {
            live.putIfAbsent(key, model);
            touch();
        }
        return model;
    }

    /* --------------------------------- появление ------------------------------ */

    /**
     * Принимает клад к постановке бочкой.
     *
     * <p>Бочка ставится следующим тиком: находка рождается внутри
     * {@code BlockBreakEvent}, а сервер заменит блок воздухом уже после
     * обработчика — поставленная раньше бочка была бы стёрта. Задержка в один
     * тик незаметна игроку, зато клад гарантированно остаётся на месте.</p>
     *
     * @return {@code true}, если бочка будет поставлена; {@code false} —
     *         вызывающий выдаёт добычу в инвентарь сам
     */
    public boolean offer(Player owner, Location location, TreasureTier tier,
                         List<ItemStack> contents) {
        TreasuresConfig.Barrels settings = plugin.config().barrels();
        if (!settings.enabled() || owner == null || location == null
                || contents == null || contents.isEmpty()) {
            return false;
        }
        int limit = settings.maxPerPlayer();
        if (countOf(owner.getUniqueId()) >= limit) {
            plugin.messages().send(owner, "barrel-limit", "max", String.valueOf(limit));
            return false;
        }
        List<ItemStack> copy = new ArrayList<>(contents.size());
        for (ItemStack stack : contents) {
            if (stack != null) {
                copy.add(stack.clone());
            }
        }
        if (copy.isEmpty()) {
            return false;
        }
        // Владелец мог выйти между находкой и постановкой — добыча не должна
        // исчезнуть вместе с бочкой.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!owner.isOnline()) {
                drop(location, copy);
                return;
            }
            if (spawn(owner, location, tier, copy)) {
                plugin.messages().send(owner, "barrel-placed",
                        "tier", Messages.trusted(plugin.messages().raw(
                                "tier." + (tier == null ? TreasureTier.COMMON : tier).id())));
                plugin.messages().send(owner, "barrel-hint");
            } else {
                plugin.messages().send(owner, "barrel-fallback");
                plugin.treasures().givePayload(owner, copy);
            }
        }, 1L);
        return true;
    }

    /**
     * Ставит бочку с добычей и запускает анимацию появления.
     *
     * @return {@code false}, если бочку поставить нельзя: тогда вызывающий
     *         обязан выдать содержимое в инвентарь, чтобы клад не пропал
     */
    public boolean spawn(Player owner, Location location, TreasureTier tier,
                         List<ItemStack> contents) {
        if (owner == null || location == null || contents == null || contents.isEmpty()) {
            return false;
        }
        TreasuresConfig.Barrels settings = plugin.config().barrels();
        if (!settings.enabled()) {
            return false;
        }
        if (countOf(owner.getUniqueId()) >= settings.maxPerPlayer()) {
            plugin.messages().send(owner, "barrel-limit",
                    "max", String.valueOf(settings.maxPerPlayer()));
            return false;
        }
        Block block = place(location, owner);
        if (block == null) {
            return false;
        }
        block.setType(Material.BARREL, false);
        orient(block, owner);
        if (!fill(block, owner, tier, contents)) {
            block.setType(Material.AIR, false);
            return false;
        }
        TreasureBarrel model = new TreasureBarrel(owner.getUniqueId(), owner.getName(),
                tier, System.currentTimeMillis());
        live.put(key(block), model);
        touch();
        trim();
        animateSpawn(block, tier);
        return true;
    }

    /** Ищет свободную позицию: место блока, два блока над ним, затем ноги игрока. */
    private Block place(Location location, Player owner) {
        World world = location.getWorld();
        if (world == null) {
            return null;
        }
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        for (int dy = 0; dy < MAX_PLACES; dy++) {
            Block candidate = world.getBlockAt(x, y + dy, z);
            if (suitable(candidate)) {
                return candidate;
            }
        }
        Block feet = owner.getLocation().getBlock();
        return suitable(feet) ? feet : null;
    }

    /**
     * Свободна ли позиция. Бочка — твёрдый блок, поэтому внутрь живого
     * существа её не ставим: иначе игрок окажется в стене и задохнётся.
     */
    private static boolean suitable(Block block) {
        if (!block.getType().isAir() || block.isLiquid()) {
            return false;
        }
        World world = block.getWorld();
        for (Entity entity : world.getNearbyEntities(block.getBoundingBox())) {
            if (entity instanceof LivingEntity) {
                return false;
            }
        }
        return true;
    }

    /** Крышка смотрит на игрока: так бочка сразу читается как «его» клад. */
    private static void orient(Block block, Player owner) {
        BlockData data = block.getBlockData();
        if (!(data instanceof org.bukkit.block.data.type.Barrel barrel)) {
            return;
        }
        BlockFace face = owner.getFacing();
        if (face == null || face == BlockFace.SELF || face.getModY() != 0) {
            face = BlockFace.NORTH;
        }
        barrel.setFacing(face.getOppositeFace());
        block.setBlockData(barrel, false);
    }

    /** Кладёт добычу в бочку и записывает владельца в сам блок. */
    private boolean fill(Block block, Player owner, TreasureTier tier,
                         List<ItemStack> contents) {
        BlockState state = block.getState();
        if (!(state instanceof Barrel barrel)) {
            return false;
        }
        Inventory inventory = barrel.getInventory();
        List<ItemStack> leftovers = new ArrayList<>();
        for (ItemStack stack : contents) {
            if (stack == null) {
                continue;
            }
            for (ItemStack rest : inventory.addItem(stack.clone()).values()) {
                leftovers.add(rest);
            }
        }
        boolean empty = true;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && !stack.getType().isAir()) {
                empty = false;
                break;
            }
        }
        if (empty) {
            return false;
        }
        PersistentDataContainer data = barrel.getPersistentDataContainer();
        data.set(ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
        data.set(nameKey, PersistentDataType.STRING, owner.getName());
        data.set(tierKey, PersistentDataType.STRING,
                (tier == null ? TreasureTier.COMMON : tier).id());
        data.set(createdKey, PersistentDataType.LONG, System.currentTimeMillis());
        barrel.update(true, false);
        // Добыча не должна исчезнуть даже в невероятном случае переполнения.
        for (ItemStack rest : leftovers) {
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 1.0, 0.5), rest);
        }
        return true;
    }

    /** Держит индекс в разумных границах: самые старые записи уходят первыми. */
    private void trim() {
        if (live.size() <= MAX_LIVE) {
            return;
        }
        List<Map.Entry<Key, TreasureBarrel>> rows = new ArrayList<>(live.entrySet());
        rows.sort(Comparator.comparingLong(row -> row.getValue().createdAt()));
        Iterator<Map.Entry<Key, TreasureBarrel>> iterator = rows.iterator();
        while (live.size() > MAX_LIVE && iterator.hasNext()) {
            live.remove(iterator.next().getKey());
        }
    }

    /* --------------------------------- события -------------------------------- */

    /** Открытие: чужому нельзя, владельцу — анимация один раз. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getInventory().getHolder() instanceof Barrel barrel)) {
            return;
        }
        Block block = barrel.getBlock();
        TreasureBarrel model = at(block);
        if (model == null) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }
        TreasuresConfig.Barrels settings = plugin.config().barrels();
        if (settings.ownerOnly() && !model.ownedBy(player.getUniqueId())
                && !player.hasPermission("chrdktreasures.admin")) {
            event.setCancelled(true);
            plugin.messages().send(player, "barrel-locked", "player", model.ownerLabel());
            return;
        }
        if (opened.add(key(block)) && settings.animation()) {
            animateOpen(block, model.tier());
        }
        if (opened.size() > MAX_LIVE) {
            opened.clear();
        }
    }

    /** Пустую бочку убираем через паузу: игрок мог закрыть её случайно. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Barrel barrel)) {
            return;
        }
        Block block = barrel.getBlock();
        if (at(block) == null || !event.getInventory().isEmpty()) {
            return;
        }
        scheduleRemoval(block);
    }

    /** Владелец может сломать свою бочку — добыча высыпается ванильно. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.BARREL) {
            return;
        }
        TreasureBarrel model = at(block);
        if (model == null) {
            return;
        }
        TreasuresConfig.Barrels settings = plugin.config().barrels();
        if (settings.ownerOnly() && !model.ownedBy(event.getPlayer().getUniqueId())
                && !event.getPlayer().hasPermission("chrdktreasures.admin")) {
            event.setCancelled(true);
            plugin.messages().send(event.getPlayer(), "barrel-locked",
                    "player", model.ownerLabel());
            return;
        }
        forget(block);
    }

    /** Взрыв не должен уничтожать клад: убираем бочку из списка разрушений. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> block.getType() == Material.BARREL
                && at(block) != null);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> block.getType() == Material.BARREL
                && at(block) != null);
    }

    /** Огонь не должен уничтожать клад: бочку с добычей не поджигаем. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (event.getBlock().getType() == Material.BARREL
                && at(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    /** Воронки и прочая автоматика не вытягивают клад из бочки. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        if (treasureHolder(event.getSource()) || treasureHolder(event.getDestination())) {
            event.setCancelled(true);
        }
    }

    private boolean treasureHolder(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Barrel barrel
                && at(barrel.getBlock()) != null;
    }

    /* --------------------------------- уборка --------------------------------- */

    /** Планирует уборку опустевшей бочки; повторный вызов ничего не делает. */
    private void scheduleRemoval(Block block) {
        Key key = key(block);
        if (!scheduled.add(key)) {
            return;
        }
        long delay = Math.max(20, plugin.config().barrels().removeEmptyTicks());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            scheduled.remove(key);
            if (block.getType() != Material.BARREL) {
                forget(block);
                return;
            }
            if (at(block) == null) {
                return;
            }
            if (isViewed(block)) {
                scheduleRemoval(block);
                return;
            }
            if (!isEmpty(block)) {
                return;
            }
            remove(block, false, false);
        }, delay);
    }

    /** Раз в секунду предупреждает о скором исчезновении и убирает просроченные бочки. */
    private void sweep() {
        TreasuresConfig.Barrels settings = plugin.config().barrels();
        long ttl = settings.expireMillis();
        if (ttl <= 0L) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<Key, TreasureBarrel> entry : live.entrySet()) {
            TreasureBarrel model = entry.getValue();
            Key key = entry.getKey();
            long left = ttl - (now - model.createdAt());
            if (left > 0L) {
                if (left > EXPIRE_WARN_MILLIS || !warned.add(key)) {
                    continue;
                }
                Player owner = Bukkit.getPlayer(model.owner());
                if (owner != null) {
                    plugin.messages().send(owner, "barrel-expiring",
                            "seconds", String.valueOf(Math.max(1L, (left + 999L) / 1000L)));
                }
                continue;
            }
            World world = Bukkit.getWorld(key.world());
            if (world == null || !world.isChunkLoaded(key.x() >> 4, key.z() >> 4)) {
                continue;
            }
            Block block = world.getBlockAt(key.x(), key.y(), key.z());
            if (block.getType() != Material.BARREL) {
                forget(block);
                continue;
            }
            // Игрок как раз забирает добычу — не выдёргиваем бочку из-под рук.
            if (isViewed(block)) {
                continue;
            }
            remove(block, settings.dropOnExpire(), true);
        }
    }

    /**
     * Убирает бочку: сначала забирает содержимое, потом ставит воздух.
     *
     * <p>1.4.166: добыча не «телепортируется» в рюкзак — она высыпается у
     * места бочки, как из обычного сломанного контейнера. Игрок либо успевает
     * открыть бочку, либо подбирает предметы с земли; {@code drop=false}
     * (конфиг {@code drop-on-expire}) оставляет добычу исчезнуть вместе с
     * бочкой — это осознанный выбор администратора.</p>
     */
    private void remove(Block block, boolean drop, boolean notifyOwner) {
        Key key = key(block);
        TreasureBarrel model = live.remove(key);
        opened.remove(key);
        scheduled.remove(key);
        warned.remove(key);
        touch();
        List<ItemStack> contents = drain(block);
        if (plugin.config().barrels().animation()) {
            animateRemove(block);
        }
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        World world = block.getWorld();
        block.setType(Material.AIR, false);
        if (drop) {
            for (ItemStack stack : contents) {
                world.dropItemNaturally(center, stack);
            }
        }
        if (notifyOwner && model != null) {
            Player owner = Bukkit.getPlayer(model.owner());
            if (owner != null) {
                plugin.messages().send(owner, "barrel-expired",
                        "tier", Messages.trusted(plugin.messages().raw(
                                "tier." + model.tier().id())));
            }
        }
    }

    /** Забывает бочку без удаления блока: её сломал владелец или она исчезла. */
    private void forget(Block block) {
        Key key = key(block);
        if (live.remove(key) != null) {
            touch();
        }
        opened.remove(key);
        scheduled.remove(key);
        warned.remove(key);
    }

    private static List<ItemStack> drain(Block block) {
        List<ItemStack> contents = new ArrayList<>();
        if (!(block.getState() instanceof Barrel barrel)) {
            return contents;
        }
        for (ItemStack stack : barrel.getInventory().getContents()) {
            if (stack != null && !stack.getType().isAir()) {
                contents.add(stack.clone());
            }
        }
        return contents;
    }

    private static boolean isEmpty(Block block) {
        return drain(block).isEmpty();
    }

    /* --------------------------------- анимация -------------------------------- */

    /** Появление: пыль, искры, хлопок крышки и короткая трель. */
    private void animateSpawn(Block block, TreasureTier tier) {
        if (!plugin.config().barrels().animation()) {
            return;
        }
        World world = block.getWorld();
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        world.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.4f);
        world.spawnParticle(Particle.CRIT, center, 18, 0.45, 0.45, 0.45, 0.05);
        world.spawnParticle(Particle.WAX_ON, center, 14, 0.4, 0.4, 0.4, 0.0);
        setOpen(block, true);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (block.getType() != Material.BARREL || isViewed(block)) {
                return;
            }
            setOpen(block, false);
            world.playSound(center, Sound.BLOCK_BARREL_CLOSE, 1.0f, 1.1f);
        }, LID_FLICK_TICKS);
        new BukkitRunnable() {
            private int pulses;

            @Override
            public void run() {
                if (pulses++ >= 4 || block.getType() != Material.BARREL) {
                    cancel();
                    return;
                }
                world.spawnParticle(Particle.END_ROD, center, 6, 0.3, 0.4, 0.3, 0.02);
                world.playSound(center, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.45f,
                        1.0f + pulses * 0.15f);
            }
        }.runTaskTimer(plugin, 4L, 5L);
        if (tier != null && tier.ordinal() >= TreasureTier.EPIC.ordinal()) {
            world.spawnParticle(Particle.HAPPY_VILLAGER, center, 20, 0.5, 0.5, 0.5, 0.0);
        }
    }

    /** Открытие: тёплый звон и лёгкий фейерверк искр вокруг крышки. */
    private void animateOpen(Block block, TreasureTier tier) {
        World world = block.getWorld();
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        world.playSound(center, Sound.BLOCK_BARREL_OPEN, 1.0f, 1.0f);
        world.playSound(center, Sound.ENTITY_PLAYER_LEVELUP, 0.55f, 1.6f);
        world.spawnParticle(Particle.HAPPY_VILLAGER, center, 16, 0.4, 0.4, 0.4, 0.0);
        world.spawnParticle(Particle.END_ROD, center, 10, 0.2, 0.5, 0.2, 0.05);
        if (tier != null && tier.ordinal() >= TreasureTier.LEGENDARY.ordinal()) {
            world.spawnParticle(Particle.WAX_ON, center, 24, 0.5, 0.5, 0.5, 0.02);
        }
    }

    /** Исчезновение: облачко пыли и тихий хлопок, без драмы. */
    private void animateRemove(Block block) {
        World world = block.getWorld();
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        world.spawnParticle(Particle.CLOUD, center, 16, 0.35, 0.35, 0.35, 0.02);
        world.playSound(center, Sound.BLOCK_BARREL_CLOSE, 0.9f, 0.8f);
    }

    private static void setOpen(Block block, boolean open) {
        BlockData data = block.getBlockData();
        if (!(data instanceof org.bukkit.block.data.type.Barrel barrel)
                || barrel.isOpen() == open) {
            return;
        }
        barrel.setOpen(open);
        block.setBlockData(barrel, false);
    }

    /** Смотрит ли кто-то внутрь бочки прямо сейчас: снимок состояния этого не знает. */
    private static boolean isViewed(Block block) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            Inventory top = online.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof Barrel barrel && block.equals(barrel.getBlock())) {
                return true;
            }
        }
        return false;
    }

    /* -------------------------------- помощники ------------------------------- */

    /** Высыпает добычу в мир: последний рубеж, когда инвентарь недоступен. */
    private static void drop(Location location, List<ItemStack> contents) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        Location center = location.clone().add(0.5, 0.5, 0.5);
        for (ItemStack stack : contents) {
            world.dropItemNaturally(center, stack);
        }
    }

    private static Key key(Block block) {
        return new Key(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException broken) {
            return null;
        }
    }
}
