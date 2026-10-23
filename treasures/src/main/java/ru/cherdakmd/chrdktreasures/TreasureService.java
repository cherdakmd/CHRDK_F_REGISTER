package ru.cherdakmd.chrdktreasures;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
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

    /**
     * Итог одного найденного клада.
     *
     * <p>{@code bonus} — дополнительная добыча за серию находок (может быть
     * пустой), {@code streak} — длина серии после этой находки, {@code rank} —
     * звание игрока уже с учётом находки.</p>
     */
    public record Found(TreasureTier tier, TreasureRegion region, TreasureItemDef item,
                        List<TreasureLoot.LootStack> loot, List<Integer> milestones,
                        int streak, TreasureRank rank,
                        List<TreasureLoot.LootStack> bonus) {

        public boolean named() {
            return item != null;
        }

        public boolean bonusGranted() {
            return !bonus.isEmpty();
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
        return TreasureRegistry.size();
    }

    /** Текущее звание кладоискателя. */
    public TreasureRank rank(Player player) {
        return TreasureRank.of(found(player));
    }

    /** Следующее звание или {@code null}, если игрок уже легенда. */
    public TreasureRank nextRank(Player player) {
        return rank(player).next();
    }

    public int streak(Player player) {
        return player == null ? 0 : store.streak(key(player));
    }

    public int bestStreak(Player player) {
        return player == null ? 0 : store.bestStreak(key(player));
    }

    /** Сколько миллисекунд осталось жить серии (0 — уже прервана). */
    public long streakRemaining(Player player) {
        if (player == null) {
            return 0L;
        }
        return TreasureStreak.remainingMillis(store.lastFind(key(player)),
                System.currentTimeMillis(), plugin.config().streakWindowMillis());
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

    /** Сбрасывает прогресс всех игроков; возвращает число профилей. */
    public int resetAll() {
        return store.resetAll();
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
        return regionOf(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    /**
     * Регион по координатам — без создания блока (1.4.153: точку клад-карты
     * нельзя превратить в {@link Block}, не загрузив чанк).
     */
    public TreasureRegion regionOf(World world, int x, int y, int z) {
        if (world == null) {
            return TreasureRegion.FOREST;
        }
        TreasureRegion.Dimension dimension = switch (world.getEnvironment()) {
            case NETHER -> TreasureRegion.Dimension.NETHER;
            case THE_END -> TreasureRegion.Dimension.END;
            default -> TreasureRegion.Dimension.OVERWORLD;
        };
        String biomeKey = world.getBiome(x, y, z).getKey().getKey();
        return TreasureRegion.resolve(dimension, biomeKey, y);
    }

    /** Регион точки, где стоит игрок. */
    public TreasureRegion regionOf(Location location) {
        if (location == null || location.getWorld() == null) {
            return TreasureRegion.FOREST;
        }
        return regionOf(location.getWorld(), location.getBlockX(),
                location.getBlockY(), location.getBlockZ());
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
        double depth = TreasureRates.depthBonus(y,
                plugin.config().depthPerBlock(), plugin.config().depthMaxBonus());
        // Таблица блока (точные проценты по редкостям) важнее множителя:
        // в ней уже заложено, насколько порода «кладоносная».
        Map<TreasureTier, Double> table = plugin.config().blockChances(material.name());
        if (table != null) {
            return TreasureRates.pick(random, table, 1.0, depth, mined(player));
        }
        double multiplier = plugin.config().blockMultiplier(material.name());
        if (multiplier <= 0.0) {
            return null;
        }
        return TreasureRates.pick(random, plugin.config().chances(),
                multiplier, depth, mined(player));
    }

    /** Бросает содержимое клада выбранной редкости без учёта прогресса. */
    public TreasureRoll.Roll rollContent(TreasureTier tier, TreasureRegion region) {
        return rollContent(tier, region, Long.MAX_VALUE);
    }

    /**
     * Бросает содержимое клада с учётом прогресса: лут-таблицы открывают
     * дорогие записи только после порога добытых блоков.
     */
    public TreasureRoll.Roll rollContent(TreasureTier tier, TreasureRegion region, long mined) {
        return TreasureRoll.roll(tier, region, plugin.config().itemChance(tier), random, mined,
                plugin.config().arsenalShare());
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
            return new Found(tier, region, null, List.of(), List.of(), 0,
                    TreasureRank.NOVICE, List.of());
        }
        TreasureRank before = rank(player);
        store.setName(key(player), player.getName());
        TreasureItemDef item = roll.item();
        if (item != null) {
            ItemStack stack = plugin.items().create(item);
            if (stack != null) {
                giveStack(player, stack);
            }
        }
        List<TreasureLoot.LootStack> loot = new ArrayList<>(roll.loot());
        // 1.4.157: склянки опыта — отдельным шагом поверх любой loot.yml.
        // Клад без опыта ощущался бы пустым, а таблицы администратор правит
        // под свой сервер: «часто» не должно зависеть от версии файла.
        int bottles = TreasureExperience.roll(plugin.config().experience(), tier, random);
        if (bottles > 0) {
            // В начало списка: сообщение обрезается по feedback.loot-details-max,
            // и опыт не должен выпасть из него последним пунктом.
            loot.add(0, new TreasureLoot.LootStack(TreasureExperience.MATERIAL, bottles));
        }
        for (TreasureLoot.LootStack stack : loot) {
            ItemStack itemStack = stackOf(stack);
            if (itemStack != null) {
                giveStack(player, itemStack);
            }
        }
        int streak = store.recordTreasure(key(player), tier,
                item == null ? null : item.id(), System.currentTimeMillis(),
                plugin.config().streakWindowMillis());
        List<Integer> milestones = checkMilestones(player);
        List<TreasureLoot.LootStack> bonus = grantStreakBonus(player, tier, region, streak);
        checkRank(player, before);
        return new Found(tier, region, item, loot, milestones, streak,
                rank(player), bonus);
    }

    /**
     * Бонус за серию: дополнительная порция добычи без именной находки.
     *
     * <p>Именные предметы в бонус не попадают намеренно — иначе серия стала бы
     * вторым, скрытым броском коллекции и обесценила бы редкость. Бонус — это
     * ресурсы, и он честно объявляется игроку.</p>
     */
    private List<TreasureLoot.LootStack> grantStreakBonus(Player player, TreasureTier tier,
                                                          TreasureRegion region, int streak) {
        if (!TreasureStreak.bonusDue(streak, plugin.config().streakBonusEvery())) {
            return List.of();
        }
        // Нулевой шанс именной находки: бонус — только ресурсы, иначе серия
        // превращалась бы во второй бросок коллекции.
        TreasureRoll.Roll extra = TreasureRoll.roll(tier, region, 0.0, random,
                mined(player));
        for (TreasureLoot.LootStack stack : extra.loot()) {
            ItemStack itemStack = stackOf(stack);
            if (itemStack != null) {
                giveStack(player, itemStack);
            }
        }
        if (!extra.loot().isEmpty()) {
            // 1.4.148: бонус тоже называет состав, иначе игрок снова лезет в инвентарь.
            String loot = plugin.config().showLoot()
                    ? TreasureLootMarkup.markup(plugin.messages(), TreasureLootText.summarize(
                            extra.loot(), plugin.config().lootDetailsMax()))
                    : "";
            if (loot.isEmpty()) {
                plugin.messages().send(player, "streak-bonus", "streak", String.valueOf(streak));
            } else {
                plugin.messages().send(player, "streak-bonus-loot",
                        "streak", String.valueOf(streak),
                        "loot", Messages.trusted(loot));
            }
        }
        return extra.loot();
    }

    /** Объявляет новый ранг и выдаёт награду. */
    private void checkRank(Player player, TreasureRank before) {
        TreasureRank after = rank(player);
        if (after == null || before == null || after.ordinal() <= before.ordinal()) {
            return;
        }
        int diamonds = plugin.config().rankReward(after);
        if (diamonds > 0) {
            giveDiamonds(player, diamonds);
        }
        String rankName = plugin.messages().raw("rank." + after.id());
        plugin.messages().send(player, "rank-up",
                "rank", Messages.trusted(rankName),
                "diamonds", String.valueOf(diamonds));
        if (after == TreasureRank.LEGEND && plugin.config().broadcastLegend()) {
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                plugin.messages().send(online, "rank-up-broadcast",
                        "player", player.getName(),
                        "rank", Messages.trusted(rankName));
            }
        }
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
        TreasureRank before = rank(player);
        giveStack(player, stack);
        store.recordTreasure(key(player), def.tier(), def.id(), System.currentTimeMillis(),
                plugin.config().streakWindowMillis());
        checkMilestones(player);
        checkRank(player, before);
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
        if (stack.hasElixir()) {
            ItemStack elixir = plugin.items().elixir(TreasureElixir.byId(stack.elixir()),
                    stack.name(), stack.amount());
            if (elixir != null) {
                return elixir;
            }
        }
        if (stack.name() != null && !stack.name().isBlank()) {
            ItemStack named = plugin.items().named(material, stack.name(), stack.amount());
            if (named != null) {
                return named;
            }
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
                giveDiamonds(player, diamonds);
            }
            plugin.messages().send(player, "collection-milestone",
                    "found", String.valueOf(threshold),
                    "total", String.valueOf(TreasureRegistry.size()),
                    "diamonds", String.valueOf(diamonds));
        }
        return List.copyOf(granted);
    }

    /** Выдаёт алмазы стеками по 64: веха на 96 не должна терять 32 алмаза. */
    private void giveDiamonds(Player player, int amount) {
        int left = amount;
        while (left > 0) {
            int stack = Math.min(64, left);
            left -= stack;
            Map<Integer, ItemStack> leftover =
                    player.getInventory().addItem(new ItemStack(Material.DIAMOND, stack));
            for (ItemStack rest : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
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
        // Ближайшая закрытая редкость — по возрастанию порога, а не от самой
        // редкой: иначе новичку показывали бы «легендарный через 15 000».
        for (TreasureTier tier : TreasureTier.values()) {
            if (plugin.config().chance(tier) <= 0.0) {
                continue;
            }
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
