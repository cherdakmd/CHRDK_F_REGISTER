package ru.cherdakmd.chrdkenchants;

import org.bukkit.entity.Player;
import java.util.Locale;

/** Шансы именно источников, не обещание гарантированной книги/конкретной чары. */
final class EnchantSourceReport {
    private final EnchantsPlugin plugin;
    EnchantSourceReport(EnchantsPlugin plugin) { this.plugin = plugin; }
    static double chance(double configured, double fallback, double max) {
        return Double.isFinite(configured) ? Math.clamp(configured, 0, max) : fallback;
    }
    static String percent(double value) { return Double.isFinite(value) ? String.format(Locale.ROOT, "%.2f", value) : "—"; }
    private Object state(boolean value) { return plugin.messages().mini(value ? "catalog-on" : "catalog-off"); }
    void send(Player player) {
        if (!player.hasPermission("chrdkenchants.use") || plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(player)) return;
        var cfg = plugin.config(); int light = cfg.enabledLight().size(), curses = cfg.enabledCurses().size();
        plugin.messages().send(player, "sources-header", "light", light, "curses", curses, "max", cfg.maxPerItem(), "legendary", cfg.maxLegendaryPerItem());
        plugin.messages().send(player, "sources-table", "state", state(cfg.tableEnabled() && light + curses > 0),
                "one", percent(cfg.tableChancePercent() / 3.0), "two", percent(cfg.tableChancePercent() * 2 / 3.0), "three", percent(cfg.tableChancePercent()),
                "curse", cfg.cursesEnabled() ? cfg.tableCurseChancePercent() : 0);
        plugin.messages().send(player, "sources-villager", "state", state(cfg.villagerEnabled() && light > 0), "chance", cfg.villagerConvertChancePercent());
        plugin.messages().send(player, "sources-footer");
    }
}
