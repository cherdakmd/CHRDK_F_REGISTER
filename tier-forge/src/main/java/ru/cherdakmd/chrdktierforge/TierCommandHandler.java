package ru.cherdakmd.chrdktierforge;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** /tiers — таблица редкости; /tiersadmin give|reload. */
public final class TierCommandHandler implements TabExecutor {

    private final TierForgePlugin plugin;

    public TierCommandHandler(TierForgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (plugin.coreApi() == null) {
            plugin.messages().send(sender, "no-api");
            return true;
        }
        if ("tiersadmin".equalsIgnoreCase(command.getName())) {
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
        return handleTiers(sender);
    }

    private boolean handleTiers(CommandSender sender) {
        plugin.messages().send(sender, "list-header",
                "chance", plugin.config().rollOnCraftChancePercent());
        for (TierDef.Snapshot tier : plugin.config().tiers()) {
            String color = plugin.messages().raw("tier." + tier.id() + ".color");
            String name = plugin.messages().raw("tier." + tier.id() + ".name");
            String percent = (tier.percent() > 0 ? "+" : "") + tier.percent();
            sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize(color + name + " <gray>— вес " + tier.weight()
                            + ", характеристики " + percent + "%</gray>"));
        }
        plugin.messages().send(sender, "list-footer");
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdktierforge.admin")) {
            plugin.messages().send(sender, "usage");
            return true;
        }
        if (args.length >= 1 && "reload".equalsIgnoreCase(args[0])) {
            plugin.reloadTierConfig();
            plugin.messages().send(sender, "admin-reloaded");
            return true;
        }
        if (args.length >= 4 && "give".equalsIgnoreCase(args[0])) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                plugin.messages().send(sender, "admin-offline", "player", args[1]);
                return true;
            }
            String materialName = args[2].toUpperCase(Locale.ROOT);
            Material material = Material.matchMaterial(materialName);
            ItemKind kind = material == null ? null : ItemKind.classify(material.name());
            if (material == null || kind == null) {
                plugin.messages().send(sender, "admin-bad-material", "material", args[2]);
                return true;
            }
            if (!TierDef.isTier(args[3].toLowerCase(Locale.ROOT))) {
                plugin.messages().send(sender, "admin-bad-tier",
                        "tier", args[3],
                        "tiers", plugin.config().tiers().stream()
                                .map(TierDef.Snapshot::id).collect(Collectors.joining(", ")));
                return true;
            }
            TierDef.Snapshot tier = plugin.config().find(args[3].toLowerCase(Locale.ROOT));
            ItemStack item = new ItemStack(material);
            plugin.items().apply(item, tier);
            target.getInventory().addItem(item).values().forEach(leftover ->
                    target.getWorld().dropItemNaturally(target.getLocation(), leftover));
            plugin.messages().send(sender, "admin-given",
                    "material", material.name(), "tier", tier.id(), "player", target.getName());
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
        if ("tiersadmin".equalsIgnoreCase(command.getName())) {
            if (args.length == 1) {
                List.of("give", "reload").stream().filter(x -> x.startsWith(prefix)).forEach(matches::add);
            } else if (args.length == 2 && "give".equalsIgnoreCase(args[0])) {
                Bukkit.getOnlinePlayers().stream().map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .forEach(matches::add);
            } else if (args.length == 3 && "give".equalsIgnoreCase(args[0])) {
                // подсказываем несколько популярных материалов
                List.of("diamond_sword", "netherite_sword", "diamond_helmet", "diamond_chestplate",
                        "diamond_leggings", "diamond_boots", "netherite_pickaxe", "diamond_pickaxe",
                        "diamond_axe", "bow", "crossbow")
                        .stream().filter(x -> x.startsWith(prefix)).forEach(matches::add);
            } else if (args.length == 4 && "give".equalsIgnoreCase(args[0])) {
                plugin.config().tiers().stream().map(TierDef.Snapshot::id)
                        .filter(id -> id.startsWith(prefix)).forEach(matches::add);
            }
        }
        return matches;
    }
}
