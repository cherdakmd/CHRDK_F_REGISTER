package ru.cherdakmd.chrdkcosmetics;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /cosmetics [list|off|<эффект>] — выбор шлейфа. */
final class CosmeticsCommandHandler implements CommandExecutor, TabCompleter {

    private final CosmeticsPlugin plugin;

    CosmeticsCommandHandler(CosmeticsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length > 1) {
            plugin.messages().send(sender, "usage");
            return true;
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "usage");
            return true;
        }
        if (plugin.config().passOnly()
                && (plugin.coreApi() == null
                || !plugin.coreApi().hasActivePass(player.getName()))) {
            plugin.messages().send(sender, "denied");
            return true;
        }
        if (args.length == 0 || "list".equalsIgnoreCase(args[0])) {
            showList(player);
            return true;
        }
        if ("off".equalsIgnoreCase(args[0])) {
            if (plugin.store().selectionOf(player.getUniqueId()) == null) {
                plugin.messages().send(sender, "already");
                return true;
            }
            plugin.store().select(player.getUniqueId(), null);
            plugin.messages().send(sender, "cleared");
            return true;
        }
        CosmeticsDefs.TrailDef def = CosmeticsDefs.find(plugin.config().trails(), args[0]);
        if (def == null) {
            plugin.messages().send(sender, "unknown", "id", args[0]);
            return true;
        }
        if (def.id().equals(plugin.store().selectionOf(player.getUniqueId()))) {
            plugin.messages().send(sender, "already");
            return true;
        }
        plugin.store().select(player.getUniqueId(), def.id());
        plugin.messages().send(sender, "selected", "name", def.displayName());
        return true;
    }

    private void showList(Player player) {
        plugin.messages().send(player, "header");
        if (plugin.config().trails().isEmpty()) {
            plugin.messages().send(player, "empty");
            return;
        }
        String selected = plugin.store().selectionOf(player.getUniqueId());
        for (CosmeticsDefs.TrailDef def : plugin.config().trails()) {
            plugin.messages().send(player, "line",
                    "state", plugin.messages().raw(
                            def.id().equals(selected) ? "state-on" : "state-off"),
                    "name", def.displayName(), "id", def.id());
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String fixed : List.of("list", "off")) {
            if (fixed.startsWith(prefix)) {
                out.add(fixed);
            }
        }
        for (CosmeticsDefs.TrailDef def : plugin.config().trails()) {
            if (def.id().startsWith(prefix)) {
                out.add(def.id());
            }
        }
        return out;
    }
}
