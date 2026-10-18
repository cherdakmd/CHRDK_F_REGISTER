package ru.cherdakmd.chrdktierforge;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Наковальня, три механики:
 *
 * <p>1) <b>Улучшение</b> (1.4.55): тир-предмет + кузнечный материал из реестра
 * {@link ForgeUpgrade} → уровень свойства +1. Детерминировано — превью-ролл не нужен.
 * Материалы дефолта не пересекаются с починкой и катализатором переката.
 *
 * <p>2) <b>Апгрейд</b>: два тир-предмета одного материала и одного тира сливаются
 * в предмет тиром выше (максимум — Легендарный).
 *
 * <p>3) <b>Перекат</b>: тир-предмет + катализатор (по умолчанию аметистовый осколок)
 * → новый случайный тир. Цена опыта растёт с индексом текущего тира.
 * Ролл отложен на «игрок + предмет + тир» ({@link CraftRollKeeper}) — иначе превью
 * перекатывалось бы дерганием слотов, как крафт до 1.4.29.
 */
public final class TierAnvilListener implements Listener {

    private final TierForgePlugin plugin;
    private final CraftRollKeeper rerollKeeper = new CraftRollKeeper();
    private final Random random = new Random();
    /** Последний показанный в превью ключ переката, чтобы изъять ролл на клике. */
    private final Map<UUID, String> previewKeys = new HashMap<>();
    /** Анти-спам: одна и та же подсказка на каждое движение в окне — только раз (1.4.80). */
    private final AnvilNoticeGate notices = new AnvilNoticeGate();

    public TierAnvilListener(TierForgePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack left = event.getInventory().getItem(0);
        ItemStack right = event.getInventory().getItem(1);
        if (left == null || right == null) return;
        Player player = event.getView().getPlayer() instanceof Player p ? p : null;
        if (player == null) return;

        // 1.4.80: anvil.enabled — мастер-выключатель ВСЕХ механик наковальни
        // (улучшение, перекат, слияние). Раньше он гасил только слияние, а ковка
        // и перекат продолжали работать вопреки документации.
        if (!plugin.config().anvilEnabled()) {
            notices.reset(player.getUniqueId());
            return;
        }

        // неопознанные на наковальне не обрабатываем — пусть сначала бывают в инвентаре
        if (plugin.items().isUnidentified(left) || plugin.items().isUnidentified(right)) {
            event.setResult(null);
            notify(player, "unidentified:" + left.getType().name() + ":"
                    + right.getType().name(), "anvil-unidentified");
            return;
        }

        String leftTier = plugin.items().tierOf(left);
        String rightTier = plugin.items().tierOf(right);

        // ---- улучшение: тир-предмет + кузнечный материал → уровень свойства +1 ----
        // (раньше ветки переката проверять не надо: наши материалы ≠ катализатору,
        // а сам катализатор ловит перекат выше)
        if (leftTier != null && rightTier == null && plugin.config().improveEnabled()) {
            ItemKind kind = ItemKind.classify(left.getType().name());
            ForgeUpgrade.Def def = ForgeUpgrade.forMaterial(right.getType().name(), kind,
                    plugin.config().improveMaterials());
            if (def != null && !isCatalyst(right) && plugin.config().kindAllowed(kind)) {
                int level = plugin.items().upgradeLevel(left, def.id());
                if (level >= def.maxLevel()) {
                    event.setResult(null);
                    notify(player, "improve-max:" + def.id(), "improve-max",
                            "name", def.displayName());
                    return;
                }
                ItemStack result = left.clone();
                result.setAmount(1);
                plugin.items().bumpUpgrade(result, def, level + 1);
                event.setResult(result);
                event.getInventory().setRepairCost(
                        Math.min(39, plugin.config().improveBaseXp() + level));
                event.getInventory().setRepairCostAmount(1);
                previewKeys.remove(player.getUniqueId()); // улучшение детерминировано, ролла нет
                // Превью наковальни приходит и на каждую букву переименования —
                // успех показываем один раз на «свойство + уровень» (1.4.80).
                notify(player, "improved:" + def.id() + ":" + (level + 1), "improved",
                        "name", def.displayName(),
                        "level", ForgeUpgrade.roman(level + 1));
                return;
            }
        }

        // ---- перекат: слева тир-предмет, справа катализатор без тира ----
        if (leftTier != null && rightTier == null && plugin.config().rerollEnabled()
                && isCatalyst(right) && plugin.config().kindAllowed(
                        ItemKind.classify(left.getType().name()))) {
            String key = left.getType().name() + ":" + leftTier;
            TierDef.Snapshot roll = rerollKeeper.preview(player.getUniqueId(), key,
                    () -> plugin.config().rerollNoDowngrade()
                            ? rollNotWorseThan(leftTier)
                            : TierRoller.roll(plugin.config().tiers(), random));
            if (roll == null) return;
            ItemStack result = left.clone();
            result.setAmount(1);
            plugin.items().apply(result, roll);
            event.setResult(result);
            event.getInventory().setRepairCost(TierMath.rerollXpCost(
                    plugin.config().rerollBaseXp(), TierDef.indexOf(leftTier)));
            previewKeys.put(player.getUniqueId(), key);
            notices.reset(player.getUniqueId());
            return;
        }

        // ---- апгрейд: два предмета одного материала и одного тира ----
        if (leftTier == null && rightTier == null) { // не наша пара
            notices.reset(player.getUniqueId());
            return;
        }
        if (leftTier == null || rightTier == null
                || !leftTier.equals(rightTier)
                || left.getType() != right.getType()) {
            notify(player, "mismatch:" + left.getType().name() + ":" + leftTier
                    + "|" + right.getType().name() + ":" + rightTier, "up-mismatch");
            return;
        }
        String next = TierDef.next(leftTier);
        if (next == null) {
            notify(player, "max:" + leftTier, "up-max");
            return;
        }
        TierDef.Snapshot target = plugin.config().find(next);
        if (target == null) return;

        ItemStack result = left.clone();
        plugin.items().apply(result, target);
        event.setResult(result);
        event.getInventory().setRepairCost(
                TierMath.upgradeXpCost(plugin.config().anvilBaseXp(), TierDef.indexOf(next)));
        notify(player, "upgraded:" + left.getType().name() + ":" + leftTier,
                "upgraded", "tier", plugin.messages().raw("tier." + next + ".name"));
    }

    /** Подсказка без спама: повтор той же сигнатуры в этом окне молчит. */
    private void notify(Player player, String signature, String key, Object... placeholders) {
        if (notices.shouldNotify(player.getUniqueId(), signature)) {
            plugin.messages().send(player, key, placeholders);
        }
    }

    /** Выход: подчищаем отложенный ролл и анти-спам-память игрока (1.4.80). */
    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        previewKeys.remove(player);
        rerollKeeper.clear(player);
        notices.clear(player);
    }

    /** Результат наковальни забран: отложенный ролл переката сгорает (и шлём весточку). */
    @EventHandler(ignoreCancelled = true)
    public void onAnvilTake(InventoryClickEvent event) {
        if (!(event.getInventory() instanceof AnvilInventory)) return;
        if (event.getRawSlot() != 2) return; // слот результата
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String key = previewKeys.remove(player.getUniqueId());
        if (key == null) return;
        TierDef.Snapshot consumed = rerollKeeper.consume(player.getUniqueId(), key);
        ItemStack taken = event.getCurrentItem();
        String tier = taken == null ? null : plugin.items().tierOf(taken);
        if (consumed == null || tier == null || !consumed.id().equals(tier)) return;
        if ("cursed".equals(consumed.id())) {
            plugin.messages().send(player, "rerolled-cursed");
        } else if (consumed.percent() != 0) {
            plugin.messages().send(player, "rerolled",
                    "tier", plugin.messages().raw("tier." + consumed.id() + ".name"));
        }
    }

    /**
     * Перекат с защитой от понижения ({@code reroll.no-downgrade: true}):
     * ролл ограничен тирами НЕ НИЖЕ текущего.
     */
    private TierDef.Snapshot rollNotWorseThan(String currentTierId) {
        int floor = TierDef.indexOf(currentTierId);
        java.util.List<TierDef.Snapshot> filtered = plugin.config().tiers().stream()
                .filter(tier -> TierDef.indexOf(tier.id()) >= floor && tier.weight() > 0)
                .toList();
        if (filtered.isEmpty()) {
            return plugin.config().find(currentTierId); // потолок — оставляем как есть
        }
        return TierRoller.roll(filtered, random);
    }

    private boolean isCatalyst(ItemStack item) {
        Material catalyst = Material.matchMaterial(plugin.config().rerollCatalyst());
        return catalyst != null && item.getType() == catalyst;
    }
}
