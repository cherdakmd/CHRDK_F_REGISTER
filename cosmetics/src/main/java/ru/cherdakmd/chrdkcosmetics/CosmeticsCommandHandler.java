package ru.cherdakmd.chrdkcosmetics;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** GUI по умолчанию; старый list тоже открывает GUI, text оставляет явный текстовый список. */
final class CosmeticsCommandHandler implements TabExecutor {
    private final CosmeticsPlugin plugin;
    CosmeticsCommandHandler(CosmeticsPlugin plugin) { this.plugin = plugin; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { plugin.messages().send(sender, "player-only"); return true; }
        if (!player.hasPermission("chrdkcosmetics.use")) { plugin.messages().send(player, "no-permission"); return true; }
        if (!plugin.eligible(player)) { plugin.messages().send(player, "need-auth"); return true; }
        if (args.length > 1) { plugin.messages().send(player, "usage"); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) { plugin.gui().open(player); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "trails" -> plugin.gui().trails(player);
            case "off" -> plugin.select(player, null);
            case "text" -> showList(player);
            default -> {
                var def = CosmeticsDefs.find(plugin.config().trails(), args[0]);
                if (def == null) plugin.messages().send(player, "unknown", "id", args[0]);
                else plugin.select(player, def.id());
            }
        }
        return true;
    }
    private void showList(Player player) {
        plugin.messages().send(player, "header");
        if (plugin.config().trails().isEmpty()) { plugin.messages().send(player, "empty"); return; }
        String selected = plugin.store().selectionOf(player.getUniqueId());
        for (var def : plugin.config().trails()) {
            boolean open = plugin.unlocked(player.getName(), def);
            plugin.messages().send(player, open ? "line" : "line-locked", "state", plugin.messages().mini(!open
                    ? "state-locked" : def.id().equals(selected) ? "state-on" : "state-off"),
                    "name", Messages.trusted(def.displayName()), "id", def.id());
        }
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !(sender instanceof Player player) || !plugin.eligible(player)) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT); List<String> values = new ArrayList<>(List.of("list", "trails", "off", "text"));
        plugin.config().trails().forEach(def -> values.add(def.id()));
        return values.stream().filter(value -> value.startsWith(prefix)).distinct().toList();
    }
}
