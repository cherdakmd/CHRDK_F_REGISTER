package ru.cherdakmd.chrdkregister.item;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Слушатель совместимого слияния атрибутов для наковальни, крафта и кузнечного стола.
 *
 * <p>Плагины чар/тиров пересобирают {@code ItemMeta} на HIGH. На LOWEST мы
 * снимаем копию компонента атрибутов у предмета-источника, на HIGHEST — уже
 * после всех HIGH-обработчиков — возвращаем в результат ванильную базу, базу
 * Mine Treasure и чужие модификаторы, не трогая новые чары/тиры. Ядро
 * регистрирует этот слушатель <b>до</b> {@code AnvilCostListener}: его
 * HIGHEST-котировка цены и результат наковальни видят уже объединённый
 * предмет. {@code CHRDK_Enchants} отдельно обновляет свой preview-quote на
 * MONITOR, чтобы проверка результата при клике не отвергла объединённый стек.</p>
 *
 * <p>Снимки живут только внутри одного prepare-события в {@link WeakHashMap};
 * PDC, конфиги, команды и чужие плагины не изменяются. Источники берутся
 * консервативно: левый слот наковальни, equipment кузнечного стола и только
 * ингредиенты крафта того же типа (или апгрейд в незерит). Книга/катализатор/
 * расходник со своими атрибутами в результат не копируются.</p>
 */
public final class ItemAttributeMergeListener implements Listener {

    private final Plugin plugin;
    private final Set<String> ownedNamespaces;
    /** Ключ — верхний инвентарь prepare-события; значение живёт до MONITOR. */
    private final Map<Inventory, List<ItemAttributeMerge.Snapshot>> snapshots = new WeakHashMap<>();

    public ItemAttributeMergeListener(Plugin plugin) {
        this.plugin = plugin;
        this.ownedNamespaces = ownedNamespaces(plugin);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureAnvil(PrepareAnvilEvent event) {
        capture(event.getInventory(), single(event.getInventory().getItem(0)));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void mergeAnvil(PrepareAnvilEvent event) {
        merge(event.getResult(), event.getInventory(), event::setResult);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void clearAnvil(PrepareAnvilEvent event) {
        snapshots.remove(event.getInventory());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureCraft(PrepareItemCraftEvent event) {
        List<ItemStack> candidates = new ArrayList<>();
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (item != null && !item.getType().isAir()) {
                candidates.add(item);
            }
        }
        capture(event.getInventory(), candidates);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void mergeCraft(PrepareItemCraftEvent event) {
        merge(event.getInventory().getResult(), event.getInventory(), event.getInventory()::setResult);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void clearCraft(PrepareItemCraftEvent event) {
        snapshots.remove(event.getInventory());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureSmithing(PrepareSmithingEvent event) {
        capture(event.getInventory(), single(event.getInventory().getInputEquipment()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void mergeSmithing(PrepareSmithingEvent event) {
        merge(event.getResult(), event.getInventory(), event::setResult);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void clearSmithing(PrepareSmithingEvent event) {
        snapshots.remove(event.getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void clearOnClick(InventoryClickEvent event) {
        snapshots.remove(event.getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void clearOnClose(InventoryCloseEvent event) {
        snapshots.remove(event.getInventory());
    }

    private void capture(Inventory inventory, List<ItemStack> candidates) {
        if (inventory == null || candidates == null || candidates.isEmpty()) {
            if (inventory != null) {
                snapshots.remove(inventory);
            }
            return;
        }
        List<ItemAttributeMerge.Snapshot> captured = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            ItemStack item = candidates.get(index);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            try {
                captured.add(ItemAttributeMerge.capture(item, ownedNamespaces, "slot" + index));
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.FINE, "Не удалось снять атрибуты источника", exception);
            }
        }
        if (captured.isEmpty()) {
            snapshots.remove(inventory);
        } else {
            snapshots.put(inventory, captured);
        }
    }

    private void merge(ItemStack result, Inventory inventory, Consumer<ItemStack> setter) {
        if (result == null || result.getType().isAir()) {
            return;
        }
        List<ItemAttributeMerge.Snapshot> sources = new ArrayList<>();
        List<ItemAttributeMerge.Snapshot> captured = snapshots.get(inventory);
        if (captured != null) {
            for (ItemAttributeMerge.Snapshot source : captured) {
                if (source != null && sameTypeOrUpgrade(source.type(), result.getType())) {
                    sources.add(source);
                }
            }
        }
        try {
            // Даже без подходящих источников полезно снять ванильный default,
            // который ItemAttributes.keep добавил поверх кастомной базы результата.
            if (ItemAttributeMerge.merge(result, sources, ownedNamespaces)) {
                setter.accept(result);
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.FINE, "Слияние атрибутов результата пропущено", exception);
        }
    }

    /** Тот же предмет или штатный апгрейд DIAMOND_* → NETHERITE_* в кузнечном столе. */
    private static boolean sameTypeOrUpgrade(Material source, Material target) {
        if (source == null || target == null) {
            return false;
        }
        if (source == target) {
            return true;
        }
        String from = source.name();
        String to = target.name();
        if (!to.startsWith("NETHERITE_")) {
            return false;
        }
        int underscore = from.indexOf('_');
        return underscore >= 0 && underscore < from.length() - 1
                && to.equals("NETHERITE_" + from.substring(underscore + 1));
    }

    private static List<ItemStack> single(ItemStack item) {
        return item == null ? List.of() : List.of(item);
    }

    /**
     * Пространства имён всех CHRDK-плагинов. Их модификаторы считаются «своими»
     * и при конфликте переименовываются, а база Mine Treasure/другого плагина
     * остаётся под исходным ключом.
     */
    private static Set<String> ownedNamespaces(Plugin plugin) {
        Set<String> result = new LinkedHashSet<>();
        addNamespace(result, plugin);
        for (Plugin loaded : Bukkit.getPluginManager().getPlugins()) {
            if (loaded.getName().startsWith("CHRDK_")) {
                addNamespace(result, loaded);
            }
        }
        return Set.copyOf(result);
    }

    private static void addNamespace(Set<String> result, Plugin plugin) {
        if (plugin == null) {
            return;
        }
        try {
            result.add(new NamespacedKey(plugin, "attribute_merge_probe").getNamespace());
        } catch (RuntimeException ignored) {
            // кривое имя плагина не должно ломать загрузку ядра
        }
    }
}
