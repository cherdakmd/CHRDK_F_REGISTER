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

/** /tiers — таблица редкости + личная статистика; /tiersadmin give|give-unidentified|reload. */
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
        if (args.length >= 1 && "stats".equalsIgnoreCase(args[0])) {
            return handleStats(player);
        }
        return handleTiers(player);
    }

    private boolean handleTiers(Player player) {
        plugin.messages().send(player, "list-header");
        // 1.4.80: показываем ДЕЙСТВУЮЩИЙ шанс каждой группы, а не только общий —
        // craft.weapons/armor/tools-chance-percent раньше никак не отражались в UI.
        plugin.messages().send(player, "list-chances",
                "weapons", plugin.config().craftChanceFor(ItemKind.SWORD),
                "armor", plugin.config().craftChanceFor(ItemKind.CHESTPLATE),
                "tools", plugin.config().craftChanceFor(ItemKind.PICKAXE));
        for (TierDef.Snapshot tier : plugin.config().tiers()) {
            String color = plugin.messages().raw("tier." + tier.id() + ".color");
            String name = plugin.messages().raw("tier." + tier.id() + ".name");
            String percent = (tier.percent() > 0 ? "+" : "") + tier.percent();
            player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize(color + name + " <gray>— вес " + tier.weight()
                            + ", характеристики " + percent + "%</gray>"));
        }
        plugin.messages().send(player, "list-footer",
                "own_count", plugin.stats().total(player.getUniqueId()),
                "total_count", plugin.stats().grandTotal());
        return true;
    }

    /** /tiers stats — личные находки по тирам за сессию. */
    private boolean handleStats(Player player) {
        plugin.messages().send(player, "stats-header");
        int shown = 0;
        for (TierDef.Snapshot tier : plugin.config().tiers()) {
            int count = plugin.stats().totalOf(player.getUniqueId(), tier.id());
            if (count <= 0) continue;
            shown++;
            String color = plugin.messages().raw("tier." + tier.id() + ".color");
            String name = plugin.messages().raw("tier." + tier.id() + ".name");
            plugin.messages().send(player, "stats-row",
                    "color", color, "name", name, "count", count);
        }
        if (shown == 0) {
            plugin.messages().send(player, "stats-empty");
        }
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
            Material material = Material.matchMaterial(args[2].toUpperCase(Locale.ROOT));
            ItemKind kind = material == null ? null : ItemKind.classify(material.name());
            if (material == null || kind == null) {
                plugin.messages().send(sender, "admin-bad-material", "material", args[2]);
                return true;
            }
            String tierId = args[3].toLowerCase(Locale.ROOT);
            if (!TierDef.isTier(tierId)) {
                plugin.messages().send(sender, "admin-bad-tier",
                        "tier", args[3],
                        "tiers", plugin.config().tiers().stream()
                                .map(TierDef.Snapshot::id).collect(Collectors.joining(", ")));
                return true;
            }
            int amount = 1;
            if (args.length >= 5) {
                try {
                    amount = Math.max(1, Math.min(64, Integer.parseInt(args[4])));
                } catch (NumberFormatException ignored) {
                    plugin.messages().send(sender, "admin-bad-amount", "amount", args[4]);
                    return true;
                }
            }
            TierDef.Snapshot tier = plugin.config().find(tierId);
            giveItems(target, material, amount, item -> plugin.items().apply(item, tier));
            plugin.messages().send(sender, "admin-given",
                    "material", material.name(), "tier", tier.id(),
                    "player", target.getName(), "amount", amount);
            return true;
        }
        if (args.length >= 3 && "give-unidentified".equalsIgnoreCase(args[0])) {
            if (!plugin.config().identifyUnidentified()) {
                plugin.messages().send(sender, "admin-unidentified-off");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                plugin.messages().send(sender, "admin-offline", "player", args[1]);
                return true;
            }
            Material material = Material.matchMaterial(args[2].toUpperCase(Locale.ROOT));
            ItemKind kind = material == null ? null : ItemKind.classify(material.name());
            if (material == null || kind == null) {
                plugin.messages().send(sender, "admin-bad-material", "material", args[2]);
                return true;
            }
            int amount = 1;
            if (args.length >= 4) {
                try {
                    amount = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
                } catch (NumberFormatException ignored) {
                    plugin.messages().send(sender, "admin-bad-amount", "amount", args[3]);
                    return true;
                }
            }
            giveItems(target, material, amount, item -> plugin.items().markUnidentified(item, "give",
                    plugin.messages().get("unidentified-name"),
                    List.of(plugin.messages().get("unidentified-lore"))));
            plugin.messages().send(sender, "admin-given-unidentified",
                    "material", material.name(), "player", target.getName(), "amount", amount);
            return true;
        }
        plugin.messages().sendList(sender, "admin-usage");
        return true;
    }

    /** Выдаёт {@code amount} предметов стеками, модифицируя каждый стек {@code modifier}. */
    private void giveItems(Player target, Material material, int amount,
                           java.util.function.Consumer<ItemStack> modifier) {
        int left = amount;
        while (left > 0) {
            int stack = Math.min(material.getMaxStackSize(), left);
            ItemStack item = new ItemStack(material, stack);
            modifier.accept(item);
            left -= stack;
            target.getInventory().addItem(item).values().forEach(leftover ->
                    target.getWorld().dropItemNaturally(target.getLocation(), leftover));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                     @NotNull String label, String @NotNull [] args) {
        List<String> matches = new ArrayList<>();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if ("tiers".equalsIgnoreCase(command.getName())) {
            if (args.length == 1) {
                List.of("stats").stream().filter(x -> x.startsWith(prefix)).forEach(matches::add);
            }
            return matches;
        }
        if ("tiersadmin".equalsIgnoreCase(command.getName())) {
            boolean unidentified = "give-unidentified".equalsIgnoreCase(args[0]);
            if (args.length == 1) {
                List.of("give", "give-unidentified", "reload").stream()
                        .filter(x -> x.startsWith(prefix)).forEach(matches::add);
            } else if (args.length == 2 && ("give".equalsIgnoreCase(args[0]) || unidentified)) {
                Bukkit.getOnlinePlayers().stream().map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .forEach(matches::add);
            } else if (args.length == 3 && ("give".equalsIgnoreCase(args[0]) || unidentified)) {
                // подсказываем несколько популярных материалов
                List.of("diamond_sword", "netherite_sword", "diamond_helmet", "diamond_chestplate",
                        "diamond_leggings", "diamond_boots", "netherite_pickaxe", "diamond_pickaxe",
                        "diamond_axe", "bow", "crossbow")
                        .stream().filter(x -> x.startsWith(prefix)).forEach(matches::add);
            } else if (args.length == 4 && "give".equalsIgnoreCase(args[0])) {
                plugin.config().tiers().stream().map(TierDef.Snapshot::id)
                        .filter(id -> id.startsWith(prefix)).forEach(matches::add);
            } else if ((args.length == 5 && "give".equalsIgnoreCase(args[0]))
                    || (args.length == 4 && unidentified)) {
                List.of("1", "4", "16", "64").stream().filter(x -> x.startsWith(prefix)).forEach(matches::add);
            }
        }
        return matches;
    }
}
