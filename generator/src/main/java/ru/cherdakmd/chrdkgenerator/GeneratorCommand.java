package ru.cherdakmd.chrdkgenerator;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * Команда /generator (alias /gen): единое окно генератора, снятие ядра
 * с подтверждением, покупка улучшений (п.31, §9).
 */
public final class GeneratorCommand implements CommandExecutor, TabCompleter {

    private final GeneratorPlugin plugin;

    public GeneratorCommand(GeneratorPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (!player.hasPermission("chrdkgenerator.use")) {
            plugin.messages().send(player, "no-permission");
            return true;
        }
        if (args.length == 0) {
            plugin.gui().open(player);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "take" -> {
                GeneratorStore.Entry target = requireNearby(player, "take-none");
                if (target == null) {
                    return true;
                }
                GeneratorTake.Result result = plugin.take().request(player, target.slot());
                switch (result.status()) {
                    case NO_GENERATOR -> plugin.messages().send(player, "take-none");
                    case TOO_FAR -> plugin.messages().send(player, "command-too-far",
                            "range", String.valueOf(plugin.config().menuRange()));
                    case PENDING -> plugin.messages().send(player, "take-confirm");
                    case DONE -> plugin.messages().send(player, "take-ok",
                            "bunker", String.valueOf(result.units()));
                }
            }
            case "upgrade" -> {
                if (args.length != 2) {
                    plugin.messages().send(player, "usage");
                    return true;
                }
                GeneratorStore.Entry target = requireNearby(player, "upgrade-none");
                if (target == null) {
                    return true;
                }
                switch (args[1].toLowerCase(Locale.ROOT)) {
                    case "tier" -> plugin.upgrades().upgradeTier(player, target.slot());
                    case "speed", "capacity", "luck" ->
                            plugin.upgrades().upgradeModule(player, target.slot(), args[1].toLowerCase(Locale.ROOT));
                    case "chunk" -> plugin.upgrades().buyChunk(player, target.slot());
                    default -> plugin.messages().send(player, "usage");
                }
            }
            default -> plugin.messages().send(player, "usage");
        }
        return true;
    }

    /**
     * Цель take/upgrade — ближайшее установленное ядро в радиусе; иначе
     * объясняет причину ({@code noneKey} — генераторов нет, иначе — далеко).
     */
    private GeneratorStore.Entry requireNearby(Player player, String noneKey) {
        GeneratorStore.Entry target = plugin.nearbyEntry(player);
        if (target != null) {
            return target;
        }
        if (plugin.store().entries(player.getUniqueId()).isEmpty()) {
            plugin.messages().send(player, noneKey);
        } else {
            plugin.messages().send(player, "command-too-far",
                    "range", String.valueOf(plugin.config().menuRange()));
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("chrdkgenerator.use")) {
            return List.of();
        }
        if (args.length == 1) {
            return List.of("take", "upgrade").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && "upgrade".equalsIgnoreCase(args[0])) {
            return List.of("tier", "speed", "capacity", "luck", "chunk").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
