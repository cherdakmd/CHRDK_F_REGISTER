package ru.cherdakmd.chrdkenchants;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /enchants (книга-справочник, info по id) и /enchantsadmin (give, reload). */
public final class EnchantCommandHandler implements TabExecutor {

    private final EnchantsPlugin plugin;

    public EnchantCommandHandler(EnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (plugin.coreApi() == null) {
            plugin.messages().send(sender, "no-api");
            return true;
        }
        if ("enchantsadmin".equalsIgnoreCase(command.getName())) {
            return handleAdmin(sender, args);
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (!plugin.coreApi().isAuthenticated(player)) {
            plugin.messages().send(player, "need-auth");
            return true;
        }
        if (args.length >= 2 && "info".equalsIgnoreCase(args[0])) {
            return showInfo(player, args[1]);
        }
        if (args.length == 0) {
            plugin.gui().open(player);
            return true;
        }
        plugin.messages().send(player, "usage");
        return true;
    }

    private boolean showInfo(Player player, String id) {
        EnchantDef def = EnchantRegistry.byId(id);
        if (def == null) {
            plugin.messages().send(player, "not-found", "id", id);
            return true;
        }
        plugin.messages().send(player, "info-header",
                "name", plugin.messages().raw("en." + def.id() + ".name"),
                "rarity", plugin.messages().raw(def.rarity().labelKey()),
                "maxlevel", def.maxLevel());
        plugin.messages().send(player, "info-items",
                "items", plugin.gui().supportedLabel(def));
        if (def.conflictGroup() != null) {
            plugin.messages().send(player, "info-conflicts",
                    "conflicts", "");
        }
        for (net.kyori.adventure.text.Component line
                : plugin.messages().getList("en." + def.id() + ".lore")) {
            player.sendMessage(line);
        }
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdkenchants.admin")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length >= 1 && "reload".equalsIgnoreCase(args[0])) {
            plugin.reloadEnchantsConfig();
            plugin.messages().send(sender, "admin-reloaded");
            return true;
        }
        if (args.length >= 3 && "give".equalsIgnoreCase(args[0])) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                plugin.messages().send(sender, "admin-offline", "player", args[1]);
                return true;
            }
            EnchantDef def = EnchantRegistry.byId(args[2]);
            if (def == null) {
                plugin.messages().send(sender, "not-found", "id", args[2]);
                return true;
            }
            int level = 1;
            if (args.length >= 4) {
                try {
                    level = Integer.parseInt(args[3]);
                } catch (NumberFormatException e) {
                    level = 1;
                }
                level = Math.max(1, Math.min(def.maxLevel(), level));
            }
            ItemStack book = plugin.items().makeEnchantBook(def, level);
            target.getInventory().addItem(book).values()
                    .forEach(leftover -> target.getWorld().dropItemNaturally(target.getLocation(), leftover));
            plugin.messages().send(sender, "admin-given",
                    "name", plugin.messages().raw("en." + def.id() + ".name"),
                    "level", level, "player", target.getName());
            return true;
        }
        plugin.messages().sendList(sender, "admin-usage");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                     @NotNull String label, String @NotNull [] args) {
        List<String> matches = new ArrayList<>();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if ("enchants".equalsIgnoreCase(command.getName())) {
            if (args.length == 1 && "info".startsWith(prefix)) {
                matches.add("info");
            } else if (args.length == 2 && "info".equalsIgnoreCase(args[0])) {
                EnchantRegistry.all().stream().map(EnchantDef::id)
                        .filter(id -> id.startsWith(prefix)).forEach(matches::add);
            }
            return matches;
        }
        if (args.length == 1) {
            List.of("give", "reload").stream().filter(x -> x.startsWith(prefix)).forEach(matches::add);
        } else if (args.length == 2 && "give".equalsIgnoreCase(args[0])) {
            Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .forEach(matches::add);
        } else if (args.length == 3 && "give".equalsIgnoreCase(args[0])) {
            EnchantRegistry.all().stream().map(EnchantDef::id)
                    .filter(id -> id.startsWith(prefix)).forEach(matches::add);
        }
        return matches;
    }
}
