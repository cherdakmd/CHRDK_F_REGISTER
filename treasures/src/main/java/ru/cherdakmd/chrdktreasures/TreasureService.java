package ru.cherdakmd.chrdktreasures;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Клады: бросок редкости, выдача добычи, прогрессия и коллекция.
 *
 * <p>Порядок одного события: посчитать блок и глубину, бросить редкость,
 * собрать содержимое, записать статистику, выдать предметы и проверить вехи
 * коллекции. Всё, что можно проверить без сервера, вынесено в чистые классы
 * ({@link TreasureRates}, {@link TreasureRoll}, {@link TreasureStore}), поэтому
 * здесь остаются только Bukkit-операции.</p>
 *
 * <p>Выдача не теряет предметы: если инвентарь полон, остаток падает под ноги.
 * Это важно для мифических находок — их нельзя «сжечь» переполненным рюкзаком.</p>
 */
public final class TreasureService {

    /** Итог одного найденного клада. */
    public record Found(TreasureTier tier, TreasureRegion region, TreasureItemDef item,
                        List<TreasureLoot.LootStack> loot, List<Integer> milestones) {

        public boolean named() {
            return item != null;
        }
    }

    private final TreasuresPlugin plugin;
    private final TreasureStore store;
    private final Random random = new Random();

    public TreasureService(TreasuresPlugin plugin, TreasureStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    /* -------------------------------- прогресс -------------------------------- */

    public long mined(Player player) {
        return player == null ? 0L : store.mined(key(player));
    }

    public long found(Player player) {
        return player == null ? 0L : store.found(key(player));
    }

    public long tierCount(Player player, TreasureTier tier) {
        return player == null ? 0L : store.tierCount(key(player), tier);
    }

    public int foundItems(Player player) {
        return player == null ? 0 : store.foundItems(key(player));
    }

    public boolean hasItem(Player player, String itemId) {
        return player != null && store.itemCount(key(player), itemId) > 0;
    }

    public int itemCount(Player player, String itemId) {
        return player == null ? 0 : store.itemCount(key(player), itemId);
    }

    public int totalItems() {
        return TreasureCatalog.size();
    }

    /** Сколько блоков осталось до следующей редкости (0 — всё открыто). */
    public long blocksToNextTier(Player player) {
        long mined = mined(player);
        long best = 0L;
        for (TreasureTier tier : TreasureTier.values()) {
            long need = tier.minMined() - mined;
            if (need > 0) {
                best = best == 0L ? need : Math.min(best, need);
            }
        }
        return best;
    }

    /** Уровень прогрессии: сколько редкостей уже открыто. */
    public int unlockedTiers(Player player) {
        long mined = mined(player);
        int unlocked = 0;
        for (TreasureTier tier : TreasureTier.values()) {
            if (tier.minMined() <= mined) {
                unlocked++;
            }
        }
        return unlocked;
    }

    /** Сбрасывает прогресс игрока (админская команда). */
    public void reset(Player player) {
        if (player != null) {
            store.reset(key(player));
        }
    }

    public void rememberName(Player player) {
        if (player != null) {
            store.setName(key(player), player.getName());
        }
    }

    /* ---------------------------------- бросок -------------------------------- */

    /** Регион по блоку: измерение, ключ биома и высота. */
    public TreasureRegion regionOf(Block block) {
        if (block == null) {
            return TreasureRegion.FOREST;
        }
        TreasureRegion.Dimension dimension = switch (block.getWorld().getEnvironment()) {
            case NETHER -> TreasureRegion.Dimension.NETHER;
            case THE_END -> TreasureRegion.Dimension.END;
            default -> TreasureRegion.Dimension.OVERWORLD;
        };
        String biomeKey = block.getBiome().getKey().getKey();
        return TreasureRegion.resolve(dimension, biomeKey, block.getY());
    }

    /**
     * Бросает редкость для блока. {@code null} — клада нет.
     *
     * <p>Счётчик добытого берётся до прибавления текущего блока: прогрессия
     * открывается «со следующего», иначе первый же камень на нуле мог бы
     * выдать редкий клад, который по порогу ещё не положен.</p>
     */
    public TreasureTier rollTier(Player player, Material material, int y) {
        if (player == null || material == null) {
            return null;
        }
        double multiplier = plugin.config().blockMultiplier(material.name());
        if (multiplier <= 0.0) {
            return null;
        }
        double depth = TreasureRates.depthBonus(y,
                plugin.config().depthPerBlock(), plugin.config().depthMaxBonus());
        Map<TreasureTier, Double> chances = new LinkedHashMap<>();
        for (TreasureTier tier : TreasureTier.values()) {
            chances.put(tier, plugin.config().chance(tier));
        }
        return TreasureRates.pick(random, chances, multiplier, depth, mined(player));
    }

    /** Бросает содержимое клада выбранной редкости. */
    public TreasureRoll.Roll rollContent(TreasureTier tier, TreasureRegion region) {
        return TreasureRoll.roll(tier, region, plugin.config().itemChance(tier), random);
    }

    /* --------------------------------- выдача --------------------------------- */

    /**
     * Выдаёт клад игроку и записывает его в статистику.
     *
     * @return запись находки для сообщений и витрины
     */
    public Found give(Player player, TreasureTier tier, TreasureRegion region,
                      TreasureRoll.Roll roll) {
        if (player == null || tier == null || roll == null) {
            return new Found(tier, region, null, List.of(), List.of());
        }
        store.setName(key(player), player.getName());
        TreasureItemDef item = roll.item();
        if (item != null) {
            ItemStack stack = plugin.items().create(item);
            if (stack != null) {
                giveStack(player, stack);
            }
        }
        for (TreasureLoot.LootStack stack : roll.loot()) {
            ItemStack itemStack = stackOf(stack);
            if (itemStack != null) {
                giveStack(player, itemStack);
            }
        }
        store.recordTreasure(key(player), tier, item == null ? null : item.id());
        List<Integer> milestones = checkMilestones(player);
        return new Found(tier, region, item, roll.loot(), milestones);
    }

    /** Принудительная выдача находки (админская команда). */
    public boolean giveItem(Player player, TreasureItemDef def) {
        if (player == null || def == null) {
            return false;
        }
        ItemStack stack = plugin.items().create(def);
        if (stack == null) {
            return false;
        }
        giveStack(player, stack);
        store.recordTreasure(key(player), def.tier(), def.id());
        return true;
    }

    private ItemStack stackOf(TreasureLoot.LootStack stack) {
        if (stack == null) {
            return null;
        }
        Material material = Material.matchMaterial(stack.material());
        if (material == null || material.isAir()) {
            plugin.getLogger().warning("[CHRDK_Treasures] Неизвестный материал добычи: "
                    + stack.material());
            return null;
        }
        return new ItemStack(material, Math.max(1, Math.min(64, stack.amount())));
    }

    private void giveStack(Player player, ItemStack stack) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        if (leftover.isEmpty()) {
            return;
        }
        Location location = player.getLocation();
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(location, rest);
        }
    }

    /* ---------------------------------- вехи ---------------------------------- */

    private List<Integer> checkMilestones(Player player) {
        int found = foundItems(player);
        List<Integer> granted = new ArrayList<>();
        for (Map.Entry<Integer, TreasuresConfig.Milestone> entry
                : plugin.config().milestones().entrySet()) {
            int threshold = entry.getKey();
            if (found < threshold) {
                continue;
            }
            if (!store.markMilestone(key(player), threshold)) {
                continue;
            }
            granted.add(threshold);
            int diamonds = entry.getValue().diamonds();
            if (diamonds > 0) {
                ItemStack reward = new ItemStack(Material.DIAMOND, Math.min(64, diamonds));
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(reward);
                for (ItemStack rest : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), rest);
                }
            }
            plugin.messages().send(player, "collection-milestone",
                    "found", String.valueOf(threshold),
                    "total", String.valueOf(TreasureCatalog.size()),
                    "diamonds", String.valueOf(diamonds));
        }
        return List.copyOf(granted);
    }

    /* ----------------------------------- топ ---------------------------------- */

    public record Entry(String name, long found, int items) {
    }

    public List<Entry> top() {
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, Long> row : store.topByFound(plugin.config().topSize())) {
            String name = store.name(row.getKey());
            if (name.isBlank()) {
                name = row.getKey().length() < 8 ? "?" : row.getKey().substring(0, 8);
            }
            int items = store.foundItems(row.getKey());
            if (row.getValue() > 0) {
                out.add(new Entry(name, row.getValue(), items));
            }
        }
        return List.copyOf(out);
    }

    /** Подсказка игроку о том, что даёт следующая редкость. */
    public String nextTierHint(Player player) {
        long mined = mined(player);
        for (TreasureTier tier : TreasureTier.fromRarest()) {
            if (tier.minMined() > mined) {
                String name = plugin.messages().raw("tier." + tier.id());
                return plugin.messages().raw("stats-next-tier",
                        "tier", Messages.trusted(name),
                        "blocks", String.valueOf(tier.minMined() - mined));
            }
        }
        return "";
    }

    public TreasureStore store() {
        return store;
    }

    public Random random() {
        return random;
    }

    private static String key(Player player) {
        return player.getUniqueId().toString();
    }
}
