package ru.cherdakmd.chrdkprofessions;

import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Ageable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.BitSet;
import java.util.UUID;

/**
 * Происхождение хранится в PDC чанка, не исчезает по TTL, смене игрока или рестарту.
 *
 * <p>Раньше данные станции (кто вручную положил сырьё в печь или стойку варки)
 * жили в PDC тайла, и каждое чтение/запись завершалось {@code TileState#update}.
 * Вызванный из {@code FurnaceSmeltEvent} и {@code FurnaceExtractEvent} (приоритет
 * MONITOR) такой update записывает в мир снимок тайла, снятый до того, как сервер
 * положил результат плавки или забрал его у игрока. Живой инвентарь печи, её
 * burnTime и cookTime перезаписываются устаревшим снимком: уголь сгорает, а
 * слиток пропадает — либо, наоборот, возвращается в слот результата после выемки
 * и «двоится». Отложенная задача {@code watchStation} делала то же самое на
 * следующий тик после клика по печи.</p>
 *
 * <p>Теперь запись идёт в PDC чанка по позиции блока — так же, как метки
 * {@code placed}. Ни один обработчик плавки не трогает сам тайл: топливо,
 * вход, результат и прогресс горения целиком остаются под управлением сервера.
 * Старые ключи в PDC тайлов просто перестают читаться и не влияют на игру.</p>
 */
final class ProfessionOrigins implements Listener {

    private static final String SEPARATOR = "|";
    private final ProfessionsPlugin plugin;
    private final NamespacedKey placed;

    ProfessionOrigins(ProfessionsPlugin plugin) {
        this.plugin = plugin;
        this.placed = new NamespacedKey(plugin, "placed-v1");
    }

    /**
     * Кто положил сырьё и сколько результатов уже выплавлено. Поля не содержат
     * разделитель: UUID, имя материала и два неотрицательных счётчика.
     */
    record Station(String owner, String material, int budget,
                   String outputOwner, String outputType, int outputCount) {
        static final Station EMPTY = new Station("", "", 0, "", "", 0);
    }

    /** Компактная запись станции без Gson: одна строка на позицию в PDC чанка. */
    static String encode(Station station) {
        return String.join(SEPARATOR, station.owner(), station.material(),
                Integer.toString(station.budget()), station.outputOwner(),
                station.outputType(), Integer.toString(station.outputCount()));
    }

    /** {@code null} — повреждённая запись: её удаляем и работаем как с пустой. */
    static Station decode(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 6) {
            return null;
        }
        try {
            int budget = Integer.parseInt(parts[2]);
            int outputCount = Integer.parseInt(parts[5]);
            if (budget < 0 || outputCount < 0) {
                return null;
            }
            return new Station(parts[0], parts[1], budget, parts[3], parts[4], outputCount);
        } catch (NumberFormatException bad) {
            return null;
        }
    }

    private int position(Block b) {
        return (b.getY() - b.getWorld().getMinHeight()) * 256 + (b.getZ() & 15) * 16 + (b.getX() & 15);
    }

    private NamespacedKey stationKey(Block block) {
        return new NamespacedKey(plugin, "station-" + position(block));
    }

    private Station read(Block block) {
        var data = block.getChunk().getPersistentDataContainer();
        NamespacedKey key = stationKey(block);
        if (!data.has(key, PersistentDataType.STRING)) {
            return Station.EMPTY;
        }
        Station station = decode(data.get(key, PersistentDataType.STRING));
        if (station == null) {
            data.remove(key);
            return Station.EMPTY;
        }
        return station;
    }

    private void write(Block block, Station station) {
        var data = block.getChunk().getPersistentDataContainer();
        NamespacedKey key = stationKey(block);
        if (station.equals(Station.EMPTY)) {
            data.remove(key);
        } else {
            data.set(key, PersistentDataType.STRING, encode(station));
        }
    }

    boolean placed(Block b) {
        var data = b.getChunk().getPersistentDataContainer().get(placed, PersistentDataType.BYTE_ARRAY);
        return data != null && BitSet.valueOf(data).get(position(b));
    }

    void mark(Block b, boolean value) {
        var pdc = b.getChunk().getPersistentDataContainer();
        byte[] data = pdc.get(placed, PersistentDataType.BYTE_ARRAY);
        if (data == null && !value) return;
        var bits = data == null ? new BitSet() : BitSet.valueOf(data);
        bits.set(position(b), value);
        if (bits.isEmpty()) pdc.remove(placed);
        else pdc.set(placed, PersistentDataType.BYTE_ARRAY, bits.toByteArray());
    }

    static boolean mature(Block b) {
        return !(b.getBlockData() instanceof Ageable age) || age.getAge() == age.getMaximumAge();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        mark(e.getBlockPlaced(), true);
        if (e.getBlockPlaced().getState() instanceof TileState) clear(e.getBlockPlaced());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void multi(BlockMultiPlaceEvent e) {
        for (var b : e.getReplacedBlockStates()) mark(b.getBlock(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void form(BlockFormEvent e) {
        mark(e.getBlock(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void extend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            mark(b, true);
            mark(b.getRelative(e.getDirection()), true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void retract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            mark(b, true);
            mark(b.getRelative(e.getDirection().getOppositeFace()), true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void grow(BlockGrowEvent e) {
        if (e.getNewState().getBlockData() instanceof Ageable age && age.getAge() == age.getMaximumAge()) {
            mark(e.getBlock(), false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void fertilize(BlockFertilizeEvent e) {
        for (var b : e.getBlocks()) {
            if (b.getBlockData() instanceof Ageable age && age.getAge() == age.getMaximumAge()) {
                mark(b.getBlock(), false);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void tree(StructureGrowEvent e) {
        for (var b : e.getBlocks()) mark(b.getBlock(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void hopper(InventoryMoveItemEvent e) {
        invalidate(e.getSource());
        invalidate(e.getDestination());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void falling(org.bukkit.event.entity.EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof org.bukkit.entity.FallingBlock) mark(e.getBlock(), true);
    }

    private void invalidate(Inventory inventory) {
        if (inventory.getHolder() instanceof TileState state) clear(state.getBlock());
    }

    /** Чистим только свою запись в чанке; состояние печи при этом не переписывается. */
    void clear(Block block) {
        block.getChunk().getPersistentDataContainer().remove(stationKey(block));
    }

    /** Только фактическое ручное увеличение входа, не открытие контейнера/добыча результата. */
    void load(Block block, UUID actor, ItemStack before, ItemStack after) {
        if (after == null || after.getType().isAir()) {
            clear(block);
            return;
        }
        int old = before != null && before.isSimilar(after) ? before.getAmount() : 0;
        int added = after.getAmount() - old;
        if (added <= 0) {
            if (added < 0) clear(block);
            return;
        }
        if (!ProfessionWorkshop.ordinary(after)) {
            clear(block);
            return;
        }
        Station station = read(block);
        if (old > 0 && !actor.toString().equals(station.owner())) {
            clear(block);
            return;
        }
        int budget = old == 0 ? 0 : station.budget();
        write(block, new Station(actor.toString(), after.getType().name(), Math.min(64, budget + added),
                station.outputOwner(), station.outputType(), station.outputCount()));
    }

    /** Выход нужен только для прежнего бонуса на ручное извлечение, не на каждую плавку. */
    void output(Block block, UUID actor, org.bukkit.Material type) {
        Station station = read(block);
        boolean same = actor.toString().equals(station.outputOwner())
                && type.name().equals(station.outputType());
        int count = same ? station.outputCount() : 0;
        write(block, new Station(station.owner(), station.material(), station.budget(),
                actor.toString(), type.name(), Math.min(64, count + 1)));
    }

    boolean extract(Block block, UUID actor, org.bukkit.Material type, int amount) {
        if (amount <= 0) return false;
        Station station = read(block);
        int count = station.outputCount();
        boolean own = actor.toString().equals(station.outputOwner())
                && type.name().equals(station.outputType());
        write(block, new Station(station.owner(), station.material(), station.budget(),
                station.outputOwner(), station.outputType(), Math.max(0, count - amount)));
        return own && count > 0;
    }

    UUID peek(Block block, ItemStack input) {
        if (input == null) return null;
        Station station = read(block);
        if (station.budget() <= 0 || !input.getType().name().equals(station.material())) return null;
        try {
            return station.owner().isEmpty() ? null : UUID.fromString(station.owner());
        } catch (IllegalArgumentException bad) {
            return null;
        }
    }

    UUID take(Block block, ItemStack input) {
        if (input == null) return null;
        Station station = read(block);
        int left = station.budget();
        if (station.owner().isEmpty() || left <= 0
                || !input.getType().name().equals(station.material())) return null;
        try {
            UUID id = UUID.fromString(station.owner());
            write(block, new Station(station.owner(), station.material(), left - 1,
                    station.outputOwner(), station.outputType(), station.outputCount()));
            return id;
        } catch (IllegalArgumentException bad) {
            clear(block);
            return null;
        }
    }
}
