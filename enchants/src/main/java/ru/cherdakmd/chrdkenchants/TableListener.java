package ru.cherdakmd.chrdkenchants;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Источник: зачаровательный стол. При ванильном зачаровании с шансом
 * (масштабируется ценой 1..3 лазурита) добавляется одна кастомная чара.
 *
 * <p>1.4.128: после срабатывания шанса отдельный бросок решает, будет ли это
 * светлая чара или проклятие ({@code sources.table.curse-chance-percent}).
 * Если подходящих проклятий нет (выключены или предмет не тот), честно
 * откатываемся на светлый пул — игрок не остаётся без награды.</p>
 */
public final class TableListener implements Listener {

    private final EnchantsPlugin plugin;

    public TableListener(EnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onEnchantItem(EnchantItemEvent event) {
        if (!plugin.config().tableEnabled()) return;
        Player player = event.getEnchanter();
        if (plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(player)) return;
        int tier = event.whichButton() + 1; // 1..3
        double base = plugin.config().tableChancePercent() / 100.0;
        double chance = base * tier / 3.0;
        if (plugin.service().random().nextDouble() >= chance) return;

        ItemStack item = event.getItem();
        EnchantDef.Polarity polarity = EnchantDef.Polarity.LIGHT;
        if (plugin.config().cursesEnabled()
                && plugin.service().random().nextInt(100)
                < plugin.config().tableCurseChancePercent()) {
            polarity = EnchantDef.Polarity.CURSE;
        }
        EnchantService.RollResult roll = plugin.service().rollOnto(item, tier, polarity);
        if (roll == null && polarity == EnchantDef.Polarity.CURSE) {
            // проклятий под этот предмет нет — не наказываем игрока пустотой
            roll = plugin.service().rollOnto(item, tier, EnchantDef.Polarity.LIGHT);
        }
        if (roll == null) return;
        plugin.messages().send(player, roll.def().curse() ? "table-cursed" : "table-added",
                "name", plugin.messages().raw("en." + roll.def().id() + ".name"),
                "levelroman", EnchantCodec.roman(roll.level()));
        // маленький колдовской флёр: убираем известную гибель удачи
        try {
            player.spawnParticle(org.bukkit.Particle.WITCH, player.getLocation().add(0, 1.2, 0),
                    18, 0.5, 0.6, 0.5, 0.02);
        } catch (Throwable ignored) {
            // недоступная частица не губит зачарование
        }
    }
}
