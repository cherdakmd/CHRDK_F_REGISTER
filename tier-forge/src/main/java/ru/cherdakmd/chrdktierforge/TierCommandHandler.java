package ru.cherdakmd.chrdktierforge;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
        // 1.4.85: chrdktierforge.use объявлен в plugin.yml, но не проверялся —
        // права, выданные менеджером разрешений, игнорировались.
        if (!player.hasPermission("chrdktierforge.use")) {
            plugin.messages().send(player, "no-perm");
            return true;
        }
        if (args.length >= 1 && "stats".equalsIgnoreCase(args[0])) {
            return handleStats(player, args.length >= 2 && "reset".equalsIgnoreCase(args[1]));
        }
        if (args.length >= 1 && "info".equalsIgnoreCase(args[0])) {
            return handleInfo(player);
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
        if (plugin.config().mobDropEnabled()) {
            plugin.messages().send(player, "list-drop-chances",
                    "weapons", plugin.config().mobDropChanceFor(ItemKind.SWORD),
                    "armor", plugin.config().mobDropChanceFor(ItemKind.CHESTPLATE),
                    "tools", plugin.config().mobDropChanceFor(ItemKind.PICKAXE));
        }
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

    /**
     * /tiers info — разбор предмета в руке (1.4.84): тир, кличка, источник,
     * все кузнечные улучшения с описанием, цена слияния и переката.
     */
    private boolean handleInfo(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR) {
            plugin.messages().send(player, "info-empty");
            return true;
        }
        boolean unidentified = plugin.items().isUnidentified(item);
        String tierId = plugin.items().tierOf(item);
        if (!unidentified && tierId == null) {
            plugin.messages().send(player, "info-empty");
            return true;
        }
        String itemTag = TierInfoFormat.languageTag(item.getType());
        plugin.messages().send(player, "info-header");
        if (unidentified) {
            plugin.messages().send(player, "info-unidentified",
                    "item", Messages.trusted(itemTag),
                    "source", sourceLabel(plugin.items().unidentifiedSource(item)));
            return true;
        }

        String color = plugin.messages().raw("tier." + tierId + ".color");
        String name = plugin.messages().raw("tier." + tierId + ".name");
        plugin.messages().send(player, "info-line",
                "item", Messages.trusted(itemTag),
                "tier", Messages.trusted(color + name));
        String source = plugin.items().unidentifiedSource(item);
        if (!source.isBlank()) {
            plugin.messages().send(player, "info-source", "source", sourceLabel(source));
        }
        String forgeName = plugin.items().forgeNameOf(item);
        if (!forgeName.isBlank()) {
            plugin.messages().send(player, "info-forge-name", "name", forgeName);
        }

        Map<ForgeUpgrade.Def, Integer> upgrades = plugin.items().upgradesOf(item);
        if (upgrades.isEmpty()) {
            plugin.messages().send(player, "info-no-upgrades");
        } else {
            plugin.messages().send(player, "info-upgrades-header", "count", upgrades.size());
            for (Map.Entry<ForgeUpgrade.Def, Integer> entry : upgrades.entrySet()) {
                plugin.messages().send(player, "info-upgrade",
                        "name", entry.getKey().displayName(),
                        "level", ForgeUpgrade.roman(entry.getValue()),
                        "effect", ForgeUpgrade.describe(entry.getKey(), entry.getValue()));
            }
        }

        if (!plugin.config().anvilEnabled()) {
            plugin.messages().send(player, "info-anvil-off");
            return true;
        }
        String next = TierDef.next(tierId);
        if (next == null) {
            plugin.messages().send(player, "info-merge-max");
        } else {
            plugin.messages().send(player, "info-merge",
                    "next", Messages.trusted(plugin.messages().raw("tier." + next + ".color")
                            + plugin.messages().raw("tier." + next + ".name")),
                    "xp", TierMath.upgradeXpCost(plugin.config().anvilBaseXp(),
                            TierDef.indexOf(next)));
        }
        if (plugin.config().rerollEnabled()) {
            plugin.messages().send(player,
                    plugin.config().rerollNoDowngrade() ? "info-reroll-safe" : "info-reroll",
                    "xp", TierMath.rerollXpCost(plugin.config().rerollBaseXp(),
                            TierDef.indexOf(tierId)),
                    "catalyst", Messages.trusted(TierInfoFormat.languageTag(
                            Material.matchMaterial(plugin.config().rerollCatalyst()))));
        }
        return true;
    }

    /** UUID из аргумента (null — это ник или опечатка). */
    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** Читаемое название источника происхождения тира (ключи messages.yml). */
    private String sourceLabel(String source) {
        return plugin.messages().plain(TierInfoFormat.sourceKey(source));
    }

    /** /tiers stats [reset] — личные находки за всё время (1.4.81). */
    private boolean handleStats(Player player, boolean reset) {
        if (reset) {
            plugin.stats().reset(player.getUniqueId());
            plugin.messages().send(player, "stats-reset");
            return true;
        }
        return showStats(player, player.getUniqueId(), "stats-header", "stats-empty", null);
    }

    /** Печать таблицы находок; {@code playerName != null} — админский вариант с ником. */
    private boolean showStats(CommandSender sender, UUID playerId, String headerKey,
                              String emptyKey, String playerName) {
        String nameArg = playerName == null ? "" : playerName;
        plugin.messages().send(sender, headerKey, "player", nameArg);
        int shown = 0;
        for (TierDef.Snapshot tier : plugin.config().tiers()) {
            int count = plugin.stats().totalOf(playerId, tier.id());
            if (count <= 0) continue;
            shown++;
            String color = plugin.messages().raw("tier." + tier.id() + ".color");
            String name = plugin.messages().raw("tier." + tier.id() + ".name");
            plugin.messages().send(sender, "stats-row",
                    "color", color, "name", name, "count", count);
        }
        if (shown == 0) {
            plugin.messages().send(sender, emptyKey, "player", nameArg);
        }
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdktierforge.admin")) {
            plugin.messages().send(sender, "admin-no-perm");
            return true;
        }
        if (args.length >= 1 && "reload".equalsIgnoreCase(args[0])) {
            plugin.reloadTierConfig();
            plugin.messages().send(sender, "admin-reloaded");
            return true;
        }
        if (args.length >= 2 && ("stats".equalsIgnoreCase(args[0])
                || "resetstats".equalsIgnoreCase(args[0]))) {
            // 1.4.85: принимаем и UUID из stats.yml — иначе офлайн-статистику
            // (без онлайна игрока) посмотреть было нечем.
            Player online = Bukkit.getPlayerExact(args[1]);
            UUID targetId = online != null ? online.getUniqueId() : parseUuid(args[1]);
            if (targetId == null) {
                plugin.messages().send(sender, "admin-offline", "player", args[1]);
                return true;
            }
            String name = online != null ? online.getName() : args[1];
            if ("resetstats".equalsIgnoreCase(args[0])) {
                plugin.stats().reset(targetId);
                plugin.messages().send(sender, "admin-stats-reset", "player", name);
                return true;
            }
            return showStats(sender, targetId, "admin-stats-header",
                    "admin-stats-empty", name);
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
            if (tier == null) {
                plugin.messages().send(sender, "admin-bad-tier", "tier", args[3],
                        "tiers", plugin.config().tiers().stream()
                                .map(TierDef.Snapshot::id).collect(Collectors.joining(", ")));
                return true;
            }
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
        if (args.length == 0) {
            return matches;
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if ("tiers".equalsIgnoreCase(command.getName())) {
            if (args.length == 1) {
                List.of("info", "stats").stream()
                        .filter(x -> x.startsWith(prefix)).forEach(matches::add);
            } else if (args.length == 2 && "stats".equalsIgnoreCase(args[0])) {
                List.of("reset").stream().filter(x -> x.startsWith(prefix)).forEach(matches::add);
            }
            return matches;
        }
        if ("tiersadmin".equalsIgnoreCase(command.getName())) {
            boolean unidentified = "give-unidentified".equalsIgnoreCase(args[0]);
            if (args.length == 1) {
                List.of("give", "give-unidentified", "reload", "stats", "resetstats").stream()
                        .filter(x -> x.startsWith(prefix)).forEach(matches::add);
            } else if (args.length == 2 && ("give".equalsIgnoreCase(args[0]) || unidentified
                    || "stats".equalsIgnoreCase(args[0])
                    || "resetstats".equalsIgnoreCase(args[0]))) {
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
