package ru.cherdakmd.chrdktreasures;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Клад-карты (1.4.153): выдача карты, раскопки в зоне и тайник.
 *
 * <p>Карта — обычная пустая карта с метками в {@code PersistentDataContainer}:
 * так она переживает перезапуск, не занимает отдельного файла и не может быть
 * «подделана» переименованием. Мир, точка, зона и остаток раскопок хранятся на
 * самом предмете, поэтому одну карту можно передать другому игроку — прогресс
 * поедет вместе с ней.</p>
 *
 * <p>Раскопки считаются только за честные блоки: поставленный игроком блок
 * отбрасывает общая антинакрутка {@link MinedBlocks}, а креатив и спектатор
 * отсекаются в слушателе до вызова сервиса. Тайник открывается на том блоке,
 * который закрыл счётчик, — и обычный клад с этого блока уже не бросается,
 * иначе за один удар пришло бы две награды.</p>
 *
 * <p>Создание карты не грузит чанки: биом читается только у загруженного
 * участка, иначе карта брала бы регион источника. Это осознанный компромисс —
 * генерация чанка на каждую карту обошлась бы дороже точного биома.</p>
 */
public final class TreasureMapService {

    /** Одна карта в инвентаре: слот, данные и сам предмет. */
    public record Slot(int index, TreasureMap map, ItemStack stack) {
    }

    /** Открытый тайник: карта, которая его нашла, и выданная находка. */
    public record Reveal(TreasureMap map, TreasureService.Found found) {
    }

    private final TreasuresPlugin plugin;
    private final Random random = new Random();
    private final NamespacedKey regionKey;
    private final NamespacedKey tierKey;
    private final NamespacedKey worldKey;
    private final NamespacedKey xKey;
    private final NamespacedKey zKey;
    private final NamespacedKey radiusKey;
    private final NamespacedKey neededKey;
    private final NamespacedKey remainingKey;
    private final NamespacedKey expiresKey;
    private BukkitTask hintTask;

    public TreasureMapService(TreasuresPlugin plugin) {
        this.plugin = plugin;
        this.regionKey = new NamespacedKey(plugin, "map_region");
        this.tierKey = new NamespacedKey(plugin, "map_tier");
        this.worldKey = new NamespacedKey(plugin, "map_world");
        this.xKey = new NamespacedKey(plugin, "map_x");
        this.zKey = new NamespacedKey(plugin, "map_z");
        this.radiusKey = new NamespacedKey(plugin, "map_radius");
        this.neededKey = new NamespacedKey(plugin, "map_needed");
        this.remainingKey = new NamespacedKey(plugin, "map_remaining");
        this.expiresKey = new NamespacedKey(plugin, "map_expires");
    }

    private TreasuresConfig.Maps settings() {
        TreasuresConfig config = plugin.config();
        return config == null ? TreasuresConfig.Maps.DISABLED : config.maps();
    }

    /* --------------------------------- предмет -------------------------------- */

    /** Собирает предмет карты по её данным. */
    public ItemStack create(TreasureMap map) {
        if (map == null) {
            return null;
        }
        ItemStack item = new ItemStack(Material.MAP);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        write(meta, map);
        applyLore(meta, map);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Пересобирает имя и лор карты по её текущим данным (1.4.180).
     *
     * <p>До этой версии раскопки писали только метки: игрок копал блоки, а на
     * карте всё ещё висело «Раскопать: 20 из 20». Со стороны это выглядело так,
     * будто карта не работает. Теперь лор собирается заново при каждом
     * изменении — вместе с полосой и остатком.</p>
     */
    private void applyLore(ItemMeta meta, TreasureMap map) {
        meta.displayName(plugin.messages().get("map-item-name"));
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("map-item-tier",
                "tier", Messages.trusted(plugin.messages().raw("tier." + map.tier().id()))));
        lore.add(plugin.messages().get("map-item-region",
                "region", Messages.trusted(plugin.messages().raw("region." + map.region().id()))));
        if (!map.world().isEmpty()) {
            lore.add(plugin.messages().get("map-item-world", "world", map.world()));
        }
        lore.add(plugin.messages().get("map-item-zone",
                "x", map.x(), "z", map.z(), "radius", map.radius()));
        lore.add(plugin.messages().get("map-item-progress",
                "bar", Messages.trusted(MapProgress.colored(map.dug(), map.needed()))));
        lore.add(plugin.messages().get("map-item-dig",
                "dug", map.dug(), "needed", map.needed(), "left", map.remaining()));
        long left = map.remainingMillis(System.currentTimeMillis());
        if (left > 0L) {
            lore.add(plugin.messages().get("map-item-expire",
                    "time", TreasureMapHint.duration(left)));
        }
        lore.add(plugin.messages().get("map-item-hint"));
        lore.add(plugin.messages().get("map-item-how"));
        meta.lore(lore);
    }

    /** Читает карту из предмета; {@code null} — это не карта. */
    public TreasureMap read(ItemStack stack) {
        if (stack == null || stack.getType() != Material.MAP) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String regionId = pdc.get(regionKey, PersistentDataType.STRING);
        String tierId = pdc.get(tierKey, PersistentDataType.STRING);
        String world = pdc.get(worldKey, PersistentDataType.STRING);
        Integer x = pdc.get(xKey, PersistentDataType.INTEGER);
        Integer z = pdc.get(zKey, PersistentDataType.INTEGER);
        if (regionId == null || tierId == null || x == null || z == null) {
            return null;
        }
        TreasureRegion region = TreasureRegion.byId(regionId);
        TreasureTier tier = TreasureTier.byId(tierId);
        if (region == null || tier == null) {
            return null;
        }
        Integer radius = pdc.get(radiusKey, PersistentDataType.INTEGER);
        Integer needed = pdc.get(neededKey, PersistentDataType.INTEGER);
        Integer remaining = pdc.get(remainingKey, PersistentDataType.INTEGER);
        Long expires = pdc.get(expiresKey, PersistentDataType.LONG);
        int total = needed == null ? 1 : needed;
        return new TreasureMap(region, tier, world == null ? "" : world, x, z,
                radius == null ? settings().radius() : radius,
                total,
                remaining == null ? total : remaining,
                expires == null ? 0L : expires);
    }

    /** Переписывает данные карты в предмет. */
    public void write(ItemStack stack, TreasureMap map) {
        if (stack == null || map == null || stack.getType() != Material.MAP) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        write(meta, map);
        // 1.4.180: лор тоже пересобирается, иначе прогресс раскопок не виден.
        applyLore(meta, map);
        stack.setItemMeta(meta);
    }

    private void write(ItemMeta meta, TreasureMap map) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(regionKey, PersistentDataType.STRING, map.region().id());
        pdc.set(tierKey, PersistentDataType.STRING, map.tier().id());
        pdc.set(worldKey, PersistentDataType.STRING, map.world());
        pdc.set(xKey, PersistentDataType.INTEGER, map.x());
        pdc.set(zKey, PersistentDataType.INTEGER, map.z());
        pdc.set(radiusKey, PersistentDataType.INTEGER, map.radius());
        pdc.set(neededKey, PersistentDataType.INTEGER, map.needed());
        pdc.set(remainingKey, PersistentDataType.INTEGER, map.remaining());
        pdc.set(expiresKey, PersistentDataType.LONG, map.expiresAt());
    }

    /* --------------------------------- выдача --------------------------------- */

    /**
     * Создаёт карту рядом с игроком: мир — текущий, точка — случайный румб на
     * расстоянии из конфига, регион — по биому точки, если чанк уже загружен.
     */
    public TreasureMap createFor(Player player) {
        TreasuresConfig.Maps mapConfig = settings();
        World world = player == null ? null : player.getWorld();
        if (world == null) {
            List<World> worlds = plugin.getServer().getWorlds();
            world = worlds.isEmpty() ? null : worlds.get(0);
        }
        if (world == null) {
            return null;
        }
        TreasureRegion source = player == null
                ? TreasureRegion.FOREST : plugin.treasures().regionOf(player.getLocation());
        int low = mapConfig.distanceMin();
        int high = Math.max(low, mapConfig.distanceMax());
        int distance = low + random.nextInt(high - low + 1);
        double angle = random.nextDouble() * Math.PI * 2.0;
        int x = (int) Math.round(Math.cos(angle) * distance);
        int z = (int) Math.round(Math.sin(angle) * distance);
        if (player != null) {
            x += player.getLocation().getBlockX();
            z += player.getLocation().getBlockZ();
        }
        TreasureRegion region = regionAt(world, x, z, source);
        TreasureTier tier = TreasureMap.pickTier(mapConfig.tierWeights(), random);
        int needed = TreasureMap.digs(mapConfig.digsMin(), mapConfig.digsMax(), random);
        long expires = mapConfig.expireMillis() <= 0L
                ? 0L : System.currentTimeMillis() + mapConfig.expireMillis();
        return new TreasureMap(region, tier, world.getName(), x, z,
                mapConfig.radius(), needed, needed, expires);
    }

    /**
     * Регион точки: биом читается только у загруженного чанка. Незагруженный
     * участок не генерируется ради подписи на карте — берётся регион источника.
     */
    private TreasureRegion regionAt(World world, int x, int z, TreasureRegion fallback) {
        try {
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                return fallback;
            }
            return plugin.treasures().regionOf(world, x,
                    world.getHighestBlockYAt(x, z), z);
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    /**
     * Выдаёт карту игроку. Если инвентарь полон, карта падает под ноги: терять
     * награду из-за рюкзака нельзя.
     *
     * @return {@code true}, если карта ушла в инвентарь
     */
    public boolean give(Player player, TreasureMap map) {
        if (player == null || map == null || !settings().enabled()) {
            return false;
        }
        ItemStack item = create(map);
        if (item == null) {
            return false;
        }
        Map<Integer, ItemStack> left = player.getInventory().addItem(item);
        boolean placed = left.isEmpty();
        for (ItemStack stack : left.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), stack);
        }
        plugin.messages().send(player, placed ? "map-given" : "map-given-dropped",
                "tier", Messages.trusted(plugin.messages().raw("tier." + map.tier().id())),
                "region", Messages.trusted(plugin.messages().raw("region." + map.region().id())),
                "x", map.x(), "z", map.z(), "left", map.remaining());
        // 1.4.180: карту без объяснения игроки не понимали — сразу говорим, что делать.
        plugin.messages().send(player, "map-given-how");
        return placed;
    }

    /**
     * Шанс, что к найденному кладу приложится карта. Вызывается только после
     * обычной находки: тайник из карты карту не выдаёт, иначе пошла бы цепочка
     * «карта из карты».
     */
    public void maybeDrop(Player player) {
        TreasuresConfig.Maps mapConfig = settings();
        if (!mapConfig.enabled() || player == null || mapConfig.dropChance() <= 0.0) {
            return;
        }
        if (active(player).size() >= mapConfig.maxActive()) {
            return;
        }
        if (random.nextDouble() >= mapConfig.dropChance()) {
            return;
        }
        TreasureMap map = createFor(player);
        if (map != null) {
            give(player, map);
        }
    }

    /** Сколько активных (не сгоревших) карт у игрока. */
    public int activeCount(Player player) {
        int count = 0;
        for (Slot slot : active(player)) {
            if (!slot.map().expired(System.currentTimeMillis())) {
                count++;
            }
        }
        return count;
    }

    /** Все карты в инвентаре игрока (включая просроченные — их убирает {@link #dig}). */
    public List<Slot> active(Player player) {
        List<Slot> out = new ArrayList<>();
        if (player == null) {
            return out;
        }
        ItemStack[] contents = player.getInventory().getContents();
        for (int index = 0; index < contents.length; index++) {
            TreasureMap map = read(contents[index]);
            if (map != null) {
                out.add(new Slot(index, map, contents[index]));
            }
        }
        return out;
    }

    /* -------------------------------- раскопки -------------------------------- */

    /**
     * Считает блок за раскопку по всем картам, в чью зону он попадает, и
     * открывает тайники, у которых кончился счётчик.
     *
     * @return открытые тайники (пусто — блок был обычной добычей)
     */
    public List<Reveal> dig(Player player, Block block) {
        List<Reveal> reveals = new ArrayList<>();
        TreasuresConfig.Maps mapConfig = settings();
        if (!mapConfig.enabled() || player == null || block == null) {
            return reveals;
        }
        if (!block.getType().isSolid()) {
            return reveals;
        }
        // Быстрый фильтр без разбора мет: большинство игроков карт не носят,
        // и платить за это чтением 41 меты на каждый блок нельзя.
        if (!player.getInventory().contains(Material.MAP)) {
            return reveals;
        }
        long now = System.currentTimeMillis();
        String world = block.getWorld().getName();
        int x = block.getX();
        int z = block.getZ();
        for (Slot slot : active(player)) {
            TreasureMap map = slot.map();
            if (map.expired(now)) {
                player.getInventory().setItem(slot.index(), null);
                plugin.messages().send(player, "map-expired",
                        "region", Messages.trusted(
                                plugin.messages().raw("region." + map.region().id())));
                continue;
            }
            if (!map.inZone(world, x, z)) {
                continue;
            }
            TreasureMap updated = map.withRemaining(map.remaining() - 1);
            if (updated.done()) {
                player.getInventory().setItem(slot.index(), null);
                reveals.add(new Reveal(updated, reveal(player, updated, block)));
                continue;
            }
            // Предмет в инвентаре — копия слота: пишем в неё и возвращаем назад,
            // иначе PDC обновился бы только у временного объекта.
            write(slot.stack(), updated);
            player.getInventory().setItem(slot.index(), slot.stack());
            // 1.4.180: за каждый блок — полоса и остаток. Без этого игрок копал
            // в тишине и не понимал, считается ли его работа.
            player.sendActionBar(plugin.messages().get("map-dig",
                    "bar", Messages.trusted(MapProgress.colored(updated.percent())),
                    "dug", updated.dug(),
                    "needed", updated.needed(),
                    "left", updated.remaining()));
        }
        return reveals;
    }

    /** Бросает содержимое тайника и проводит его через обычную выдачу клада. */
    private TreasureService.Found reveal(Player player, TreasureMap map, Block block) {
        TreasureService service = plugin.treasures();
        double base = plugin.config().itemChance(map.tier());
        double chance = Math.min(1.0, base * settings().namedChanceMultiplier());
        TreasureRoll.Roll roll = TreasureRoll.roll(map.tier(), map.region(), chance, random,
                service.mined(player), plugin.config().arsenalShare());
        TreasureService.Found found = service.give(player, map.tier(), map.region(), roll,
                block.getLocation());
        if (plugin.config().findNotifications()
                && plugin.store().announce(player.getUniqueId().toString())) {
            plugin.messages().send(player, "map-found",
                    "tier", Messages.trusted(plugin.messages().raw("tier." + map.tier().id())),
                    "region", Messages.trusted(
                            plugin.messages().raw("region." + map.region().id())));
        }
        plugin.listener().celebrate(player, block.getLocation(), found);
        return found;
    }

    /* --------------------------------- подсказки ------------------------------- */

    /** Запускает (или перезапускает) подсказки в экшен-баре под текущий конфиг. */
    public void restart() {
        stop();
        TreasuresConfig.Maps mapConfig = settings();
        if (!mapConfig.enabled() || mapConfig.hintTicks() <= 0) {
            return;
        }
        long period = Math.max(20, mapConfig.hintTicks());
        this.hintTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::hintAll, period, period);
    }

    public void stop() {
        if (hintTask != null) {
            hintTask.cancel();
            hintTask = null;
        }
    }

    private void hintAll() {
        if (!settings().enabled() || !plugin.config().enabled()) {
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            try {
                hint(player);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("[CHRDK_Treasures] не удалось показать подсказку "
                        + player.getName() + ": " + exception.getMessage());
            }
        }
    }

    /** Ближайшая карта в экшен-баре: направление, дистанция и остаток. */
    public void hint(Player player) {
        if (player == null) {
            return;
        }
        TreasureMapHint best = null;
        TreasureMap bestMap = null;
        long now = System.currentTimeMillis();
        for (Slot slot : active(player)) {
            TreasureMap map = slot.map();
            if (map.expired(now)) {
                continue;
            }
            TreasureMapHint hint = TreasureMapHint.of(map, player.getWorld().getName(),
                    player.getLocation().getBlockX(), player.getLocation().getBlockZ());
            // Карта в другом мире в экшен-баре молчит: иначе игрок, держащий
            // нижнемирскую карту, получал бы напоминание каждые две секунды.
            if (!hint.sameWorld()) {
                continue;
            }
            int limit = settings().hintDistance();
            if (limit > 0 && hint.distance() > limit) {
                continue;
            }
            if (best == null || hint.distance() < best.distance()) {
                best = hint;
                bestMap = map;
            }
        }
        if (best == null || bestMap == null) {
            return;
        }
        if (best.inZone()) {
            player.sendActionBar(plugin.messages().get("map-hint-zone",
                    "bar", Messages.trusted(MapProgress.colored(bestMap.percent())),
                    "dug", bestMap.dug(),
                    "needed", bestMap.needed(),
                    "left", bestMap.remaining(),
                    "distance", best.distance()));
            return;
        }
        player.sendActionBar(plugin.messages().get("map-hint",
                "distance", best.distance(),
                "direction", best.direction(),
                "left", bestMap.remaining()));
    }

    /* --------------------------------- список --------------------------------- */

    /** Подсказка по одной карте — правая кнопка по предмету (1.4.153). */
    public void describe(Player player, TreasureMap map) {
        if (player == null || map == null) {
            return;
        }
        String region = plugin.messages().raw("region." + map.region().id());
        String tier = plugin.messages().raw("tier." + map.tier().id());
        TreasureMapHint hint = TreasureMapHint.of(map, player.getWorld().getName(),
                player.getLocation().getBlockX(), player.getLocation().getBlockZ());
        if (!hint.sameWorld()) {
            plugin.messages().send(player, "map-info-world",
                    "tier", Messages.trusted(tier),
                    "region", Messages.trusted(region),
                    "world", map.world(),
                    "x", map.x(), "z", map.z(),
                    "left", map.remaining());
            return;
        }
        plugin.messages().send(player, "map-info",
                "tier", Messages.trusted(tier),
                "region", Messages.trusted(region));
        plugin.messages().send(player, "map-info-where",
                "x", map.x(), "z", map.z(),
                "distance", hint.distance(),
                "direction", hint.direction(),
                "radius", map.radius());
        plugin.messages().send(player, "map-info-progress",
                "bar", Messages.trusted(MapProgress.colored(map.dug(), map.needed())),
                "dug", map.dug(), "needed", map.needed(), "left", map.remaining());
        plugin.messages().send(player, hint.inZone() ? "map-info-here" : "map-info-how");
    }

    /** Список активных карт в чат: `/treasures map`. */
    public void list(Player player) {
        List<Slot> slots = active(player);
        long now = System.currentTimeMillis();
        List<Slot> alive = new ArrayList<>();
        for (Slot slot : slots) {
            if (slot.map().expired(now)) {
                player.getInventory().setItem(slot.index(), null);
                plugin.messages().send(player, "map-expired",
                        "region", Messages.trusted(
                                plugin.messages().raw("region." + slot.map().region().id())));
                continue;
            }
            alive.add(slot);
        }
        if (alive.isEmpty()) {
            plugin.messages().send(player, "map-none");
            return;
        }
        plugin.messages().send(player, "map-header", "count", alive.size());
        int number = 1;
        for (Slot slot : alive) {
            TreasureMap map = slot.map();
            String region = plugin.messages().raw("region." + map.region().id());
            String tier = plugin.messages().raw("tier." + map.tier().id());
            boolean sameWorld = map.world().equals(player.getWorld().getName());
            if (sameWorld) {
                TreasureMapHint hint = TreasureMapHint.of(map, player.getWorld().getName(),
                        player.getLocation().getBlockX(), player.getLocation().getBlockZ());
                plugin.messages().send(player, "map-row",
                        "num", number++,
                        "tier", Messages.trusted(tier),
                        "region", Messages.trusted(region),
                        "x", map.x(), "z", map.z(),
                        "distance", hint.distance(),
                        "direction", hint.direction(),
                        "bar", Messages.trusted(MapProgress.colored(map.dug(), map.needed())),
                        "dug", map.dug(),
                        "needed", map.needed(),
                        "left", map.remaining());
            } else {
                plugin.messages().send(player, "map-row-world",
                        "num", number++,
                        "tier", Messages.trusted(tier),
                        "region", Messages.trusted(region),
                        "world", map.world(),
                        "x", map.x(), "z", map.z(),
                        "bar", Messages.trusted(MapProgress.colored(map.dug(), map.needed())),
                        "dug", map.dug(),
                        "needed", map.needed(),
                        "left", map.remaining());
            }
        }
        plugin.messages().send(player, "map-footer");
    }

    /** Админская выдача карты конкретной редкости (без броска). */
    public boolean giveTier(Player player, TreasureTier tier) {
        TreasureMap map = createFor(player);
        if (map == null) {
            return false;
        }
        TreasureMap forced = new TreasureMap(map.region(), tier, map.world(),
                map.x(), map.z(), map.radius(), map.needed(), map.needed(), map.expiresAt());
        return give(player, forced);
    }

    /** Настройки карт в одной строке для `/treasuresadmin info`. */
    public String summary() {
        TreasuresConfig.Maps mapConfig = settings();
        if (!mapConfig.enabled()) {
            return "выключены";
        }
        return String.format(Locale.ROOT, "%d%%, зона %d, копать %d..%d, карт до %d",
                Math.round(mapConfig.dropChancePercent()), mapConfig.radius(),
                mapConfig.digsMin(), mapConfig.digsMax(), mapConfig.maxActive());
    }
}
