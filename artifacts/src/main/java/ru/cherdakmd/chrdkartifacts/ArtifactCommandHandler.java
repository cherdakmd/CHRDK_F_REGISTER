package ru.cherdakmd.chrdkartifacts;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code /artifacts} — каталог из messages.yml (светлые/проклятые, с подсветкой носимых);
 * {@code /artifactsadmin} — give/reload. Динамические цвета собираются MiniMessage
 * напрямую (плейсхолдеры Messages экранируют теги).
 */
public final class ArtifactCommandHandler implements CommandExecutor, TabCompleter {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final ArtifactsPlugin plugin;

    public ArtifactCommandHandler(ArtifactsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("artifacts")) {
            return handleList(sender);
        }
        if (command.getName().equalsIgnoreCase("artifactsadmin")) {
            return handleAdmin(sender, args);
        }
        return false;
    }

    private boolean handleList(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (!player.hasPermission("chrdkartifacts.use")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(player)) {
            plugin.messages().send(sender, "need-auth");
            return true;
        }
        var carried = plugin.items().carriedIds(player);
        plugin.messages().send(player, "list-header-good");
        sendGroup(player, ArtifactRegistry.bySide(ArtifactDef.Side.GOOD), carried);
        plugin.messages().send(player, "list-header-cursed");
        sendGroup(player, ArtifactRegistry.bySide(ArtifactDef.Side.CURSED), carried);
        plugin.messages().send(player, "sources-header");
        plugin.messages().send(player, "sources-line");
        return true;
    }

    private void sendGroup(Player player, List<ArtifactDef> defs, java.util.Set<String> carried) {
        for (ArtifactDef def : defs) {
            String name = plugin.items().nameOf(def);
            String colorHex = switch (def.side()) {
                case GOOD -> def.mode() == ArtifactDef.Mode.ACTIVE ? "#FFD700" : "#FFEE88";
                case CURSED -> def.mode() == ArtifactDef.Mode.ACTIVE ? "#8B0000" : "#CC4444";
            };
            String mark = carried.contains(def.id()) ? " <green>✔</green>" : "";
            String modeTag = def.mode() == ArtifactDef.Mode.ACTIVE ? "⌛" : "∞";
            player.sendMessage(MINI.deserialize(
                    "<color:" + colorHex + ">" + modeTag + " " + name + "</color>"
                            + " <gray><dark_gray>(</dark_gray>" + def.id() + "<dark_gray>)</dark_gray></gray>" + mark));
        }
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdkartifacts.admin")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadArtifactsConfig();
            plugin.messages().send(sender, "admin-reloaded");
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("give")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                plugin.messages().send(sender, "admin-player-not-found");
                return true;
            }
            ArtifactDef def = args.length >= 3 ? ArtifactRegistry.byId(args[2]) : null;
            if (def == null) {
                plugin.messages().send(sender, "admin-unknown-artifact");
                return true;
            }
            int amount = 1;
            if (args.length >= 4) {
                try {
                    amount = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
                } catch (NumberFormatException ignored) {
                    // оставляем 1
                }
            }
            ItemStack stack = plugin.items().build(def, amount);
            // Полный инвентарь не должен «съедать» выдачу: излишек падает под ноги.
            Map<Integer, ItemStack> leftover = target.getInventory().addItem(stack);
            int dropped = 0;
            for (ItemStack left : leftover.values()) {
                dropped += left.getAmount();
                target.getWorld().dropItemNaturally(target.getLocation(), left);
            }
            plugin.messages().send(sender, "admin-given",
                    "player", target.getName(),
                    "name", plugin.items().nameOf(def),
                    "amount", String.valueOf(amount));
            if (dropped > 0) {
                plugin.messages().send(sender, "admin-given-dropped",
                        "player", target.getName(),
                        "amount", String.valueOf(dropped));
            }
            plugin.messages().send(target, "admin-received",
                    "name", plugin.items().nameOf(def));
            return true;
        }
        plugin.messages().send(sender, "admin-usage");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("artifactsadmin")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(List.of("give", "reload"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .limit(20).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            List<String> ids = new ArrayList<>();
            for (ArtifactDef def : ArtifactRegistry.all()) {
                ids.add(def.id());
            }
            return filter(ids, args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String low = prefix.toLowerCase();
        return options.stream().filter(o -> o.toLowerCase().startsWith(low)).toList();
    }
}
