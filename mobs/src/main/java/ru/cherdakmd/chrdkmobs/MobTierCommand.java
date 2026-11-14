package ru.cherdakmd.chrdkmobs;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;

/** {@code /mobtiers} — таблица тиров и текущий баланс модуля. */
final class MobTierCommand implements CommandExecutor, TabCompleter {

    private final MobsPlugin plugin;

    MobTierCommand(MobsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        MobsConfig config = plugin.config();
        if (config == null || !config.enabled()) {
            plugin.messages().send(sender, "mobtiers-off");
            return true;
        }
        plugin.messages().send(sender, "mobtiers-header");
        List<Integer> thresholds = config.thresholds();
        for (int tier = 1; tier <= thresholds.size() + 1; tier++) {
            plugin.messages().send(sender, "mobtiers-line",
                    "tier", tier,
                    "range", range(tier, thresholds),
                    "health", formatFactor(MobTierMath.healthFactor(tier, config.healthPerTier())),
                    "damage", formatFactor(MobTierMath.damageFactor(tier, config.damagePerTier())),
                    "reward", MobTierMath.rewardDiamonds(tier, config.rewardPerTier()));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }

    private static String range(int tier, List<Integer> thresholds) {
        if (thresholds.isEmpty()) {
            return "весь мир";
        }
        if (tier == 1) {
            return "до " + thresholds.get(0) + " блоков";
        }
        return tier <= thresholds.size()
                ? thresholds.get(tier - 2) + "–" + thresholds.get(tier - 1) + " блоков"
                : "дальше " + thresholds.get(thresholds.size() - 1) + " блоков";
    }

    private static String formatFactor(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.format(Locale.ROOT, "%.2f", value);
    }
}
