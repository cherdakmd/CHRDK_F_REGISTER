package ru.cherdakmd.chrdktierforge;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Random;

/**
 * Накат редкости при крафте (и сохранение тира при апгрейде на кузнечном столе).
 *
 * <p>Ролл откладывается на «игрок + тип результата» ({@link CraftRollKeeper}): перестановка
 * ингредиентов в окне крафта НЕ перевыполняет ролл — новую попытку даёт только
 * завершённый крафт. Это исключает бесконечный перепбор редкости без траты материалов.
 */
public final class TierCraftListener implements Listener {

    private final TierForgePlugin plugin;
    private final Random random = new Random();
    /** Отложенный ролл тира (старый флоу, без неопознанных). */
    private final CraftRollKeeper<TierDef.Snapshot> rollKeeper = new CraftRollKeeper<>();
    /**
     * 1.4.89: отложенное решение «крафт будет неопознанным». Раньше шанс в
     * identify-флоу пересчитывался на КАЖДОЕ превью: игрок дёргал ингредиенты,
     * пока не выпадал тир, и только потом крафтил — шанс превращался в 100%.
     */
    private final CraftRollKeeper<Boolean> identifyKeeper = new CraftRollKeeper<>();

    public TierCraftListener(TierForgePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result == null) return;
        Player player = event.getView().getPlayer() instanceof Player p ? p : null;
        if (player == null) return;
        if (plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(player)) return;
        ItemKind kind = ItemKind.classify(result.getType().name());
        if (!plugin.config().kindAllowed(kind)) {
            return;
        }
        // не пересматриваем уже тир-предметы и «неопознанные» заготовки
        if (plugin.items().tierOf(result) != null || plugin.items().isUnidentified(result)) return;
        // чёрный список материалов — накат запрещён
        if (plugin.config().materialBlocked(result.getType())) return;

        // --- Новый флоу (1.4.44): редкость скрывается до попадания в инвентарь ---
        if (plugin.config().identifyUnidentified()) {
            // 1.4.89: решение отложено до реального крафта (см. CraftRollKeeper) —
            // иначе перебор ингредиентов давал бесконечные попытки без траты материалов.
            Boolean marked = identifyKeeper.preview(player.getUniqueId(),
                    result.getType().name(), () -> {
                        int chance = craftChance(player, kind);
                        return chance > 0 && random.nextInt(100) < chance;
                    });
            if (Boolean.TRUE.equals(marked)) {
                plugin.items().markUnidentified(result, "craft",
                        plugin.messages().get("unidentified-name"),
                        java.util.List.of(plugin.messages().get("unidentified-lore")));
            }
            return;
        }

        // отложенный ролл: стабилен до тех пор, пока результат реально не забрали
        // 1.4.80: шанс берётся по виду предмета (craft.*-chance-percent), как и в
        // identify-флоу, — раньше здесь жил только общий roll-on-craft-chance-percent.
        TierDef.Snapshot roll = rollKeeper.preview(
                player.getUniqueId(), result.getType().name(), () -> rollFresh(player, kind));
        if (roll == null) return;
        plugin.items().apply(result, roll);
    }

    /** Результат забран: отложенный ролл сгорает, следующее превью роллит заново. */
    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getRecipe() == null) return;
        ItemStack current = event.getCurrentItem();
        if (current == null) return;
        if (plugin.config().identifyUnidentified()) {
            // 1.4.89: крафт состоялся — следующее превью этой пары роллит заново.
            identifyKeeper.consume(player.getUniqueId(), current.getType().name());
            return; // оповещение — на опознании
        }
        String tier = plugin.items().tierOf(current);
        TierDef.Snapshot consumed = rollKeeper.consume(
                player.getUniqueId(), current.getType().name());
        if (tier == null || consumed == null) return;
        // 1.4.81: находки считаются и в «старом» флоу (раньше — только при опознании).
        plugin.stats().count(player.getUniqueId(), consumed.id());
        notifyRolled(player, consumed);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        rollKeeper.clear(event.getPlayer().getUniqueId());
        identifyKeeper.clear(event.getPlayer().getUniqueId());
    }

    /** Кузнечный стол: апгрейд в незерит сохраняет накатанный тир. */
    @EventHandler(ignoreCancelled = true)
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        ItemStack result = event.getResult();
        if (result == null) return;
        ItemStack base = event.getInventory().getInputEquipment();
        if (base == null) return;
        String tier = plugin.items().tierOf(base);
        if (tier == null) return;
        TierDef.Snapshot snapshot = plugin.config().find(tier);
        if (snapshot == null) return;
        // 1.4.80: незеритовый апгрейд переносит и кузнечное состояние — кличку и
        // уровни всех улучшений (раньше они молча сгорали вместе со старым предметом).
        plugin.items().copyForgeState(base, result);
        plugin.items().apply(result, snapshot);
        event.setResult(result);
    }

    /**
     * Шанс наката для вида предмета: свой (craft.weapons/armor/tools-chance-percent)
     * или общий, плюс бонус активной проходки. Один расчёт на оба флоу — identify
     * и «старый» превью-ролл (1.4.80).
     */
    private int craftChance(Player player, ItemKind kind) {
        int chance = plugin.config().craftChanceFor(kind);
        if (chance > 0 && plugin.config().passBonusCraftPercent() > 0
                && plugin.coreApi() != null
                && plugin.coreApi().hasActivePass(player.getName())) {
            chance = Math.min(100, chance + plugin.config().passBonusCraftPercent());
        }
        return chance;
    }

    /** Честная попытка случайности: null — «ровно обычный предмет, без тира». */
    private TierDef.Snapshot rollFresh(Player player, ItemKind kind) {
        int chance = craftChance(player, kind);
        if (chance <= 0 || random.nextInt(100) >= chance) return null;
        return TierRoller.roll(plugin.config().tiers(), random);
    }

    private void notifyRolled(Player player, TierDef.Snapshot roll) {
        if ("cursed".equals(roll.id())) {
            plugin.messages().send(player, "rolled-cursed");
        } else if (roll.percent() != 0) {
            plugin.messages().send(player, "rolled",
                    "tier", plugin.messages().raw("tier." + roll.id() + ".name"));
        }
    }
}
