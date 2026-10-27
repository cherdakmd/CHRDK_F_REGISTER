package ru.cherdakmd.chrdkenchants;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /enchants (книга-справочник, info по id) и /enchantsadmin (give, purify, reload). */
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
        if (sender instanceof Player actor && !plugin.coreApi().isAuthenticated(actor)) {
            plugin.messages().send(actor, "need-auth");
            return true;
        }
        if ("enchantsadmin".equalsIgnoreCase(command.getName())) {
            return handleAdmin(sender, args);
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (!player.hasPermission("chrdkenchants.use")) {
            plugin.messages().send(player, "no-permission");
            return true;
        }
        if (args.length == 2 && "info".equalsIgnoreCase(args[0])) {
            return showInfo(player, args[1]);
        }
        if (args.length == 0) {
            plugin.gui().open(player);
            return true;
        }
        if (catalogCommand(player, args)) return true;
        plugin.messages().send(player, "usage", "count", EnchantRegistry.size());
        return true;
    }

    private boolean showInfo(Player player, String id) {
        EnchantDef def = EnchantRegistry.byId(id);
        if (def == null) {
            plugin.messages().send(player, "not-found", "id", id);
            return true;
        }
        plugin.messages().send(player, "info-header",
                "name", plugin.messages().mini("en." + def.id() + ".name"),
                "rarity", plugin.messages().mini(def.rarity().labelKey()),
                "maxlevel", def.maxLevel());
        plugin.messages().send(player, "info-polarity",
                "polarity", plugin.messages().mini(def.polarity().labelKey()));
        plugin.messages().send(player, "info-items",
                "items", Messages.trusted(plugin.gui().supportedLabel(def)));
        if (def.conflictGroup() != null) {
            plugin.messages().send(player, "info-conflicts",
                    "conflicts", Messages.trusted(plugin.gui().conflictsLabel(def)));
        }
        if (def.curse()) {
            plugin.messages().send(player, "info-curse-warning");
        }
        for (net.kyori.adventure.text.Component line
                : plugin.messages().getList("en." + def.id() + ".lore")) {
            player.sendMessage(line);
        }
        return true;
    }

    private boolean catalogCommand(Player player, String[] args) {
        String action = args[0].toLowerCase(Locale.ROOT);
        try {
            EnchantCatalog.Query query = EnchantCatalog.Query.all();
            switch (action) {
                case "guard" -> {
                    if (args.length == 1 || args.length == 2) {
                        plugin.pve().guardPreference(player, args.length == 1 ? null : args[1].toLowerCase(Locale.ROOT)); return true;
                    }
                }
                case "library" -> {
                    if (args.length == 1 || args.length == 2) {
                        int page = args.length == 1 ? 1 : Integer.parseInt(args[1]);
                        if (page < 1) throw new IllegalArgumentException();
                        plugin.library().show(player, page); return true;
                    }
                }
                case "sources" -> { if (args.length == 1) { new EnchantSourceReport(plugin).send(player); return true; } }
                case "find" -> {
                    if (args.length < 2) break;
                    String text = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                    plugin.gui().open(player, new EnchantCatalog.Query(text, null, null, null, false, EnchantCatalog.Sort.NAME));
                    return true;
                }
                case "group" -> { if (args.length == 2) { plugin.gui().open(player, query.group(ItemGroup.valueOf(args[1].toUpperCase(Locale.ROOT)))); return true; } }
                case "rarity" -> { if (args.length == 2) { plugin.gui().open(player, query.rarity(EnchantDef.Rarity.valueOf(args[1].toUpperCase(Locale.ROOT)))); return true; } }
                case "light" -> { if (args.length == 1) { plugin.gui().open(player, query.polarity(EnchantDef.Polarity.LIGHT)); return true; } }
                case "curses" -> { if (args.length == 1) { plugin.gui().open(player, query.polarity(EnchantDef.Polarity.CURSE)); return true; } }
                case "hand" -> { if (args.length == 1) { plugin.gui().open(player, query.hand(true)); return true; } }
                case "sort" -> { if (args.length == 2) { plugin.gui().open(player, query.sort(EnchantCatalog.Sort.valueOf(args[1].toUpperCase(Locale.ROOT)))); return true; } }
                case "inspect" -> { if (args.length == 1) { inspect(player); return true; } }
                case "cooldowns" -> { if (args.length == 1) { cooldowns(player); return true; } }
                default -> { return false; }
            }
        } catch (IllegalArgumentException invalid) {
            plugin.messages().send(player, "catalog-bad-filter");
            return true;
        }
        return false;
    }
    private void inspect(Player player) {
        Map<String, Integer> present = plugin.items().read(player.getInventory().getItemInMainHand());
        if (present.isEmpty()) { plugin.messages().send(player, "catalog-inspect-empty"); return; }
        if (player.getInventory().getItemInMainHand().getType() == org.bukkit.Material.ENCHANTED_BOOK)
            plugin.messages().send(player, "book-source", "source", plugin.messages().mini(
                    plugin.items().bookSourceKey(player.getInventory().getItemInMainHand())));
        plugin.messages().send(player, "catalog-inspect-header", "count", present.size());
        present.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            EnchantDef def = EnchantRegistry.byId(entry.getKey());
            if (def == null) { plugin.messages().send(player, "catalog-inspect-unknown", "id", entry.getKey()); return; }
            plugin.messages().send(player, "catalog-inspect-line", "name", plugin.messages().mini("en." + def.id() + ".name"),
                    "level", entry.getValue(), "max", def.maxLevel(), "state", plugin.config().isEnabled(def.id())
                            ? plugin.messages().mini("catalog-on") : plugin.messages().mini("catalog-off"));
        });
    }
    private void cooldowns(Player player) {
        long now = System.currentTimeMillis();
        plugin.messages().send(player, "catalog-cooldowns", "pve", remaining(player, EnchantTimers.PVE, now),
                "work", remaining(player, EnchantTimers.WORK, now), "air", remaining(player, EnchantTimers.AIR, now));
    }
    private long remaining(Player player, String key, long now) {
        long until = player.getPersistentDataContainer().getOrDefault(new NamespacedKey(plugin, key), PersistentDataType.LONG, 0L);
        return EnchantTimers.seconds(until, now);
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdkenchants.admin")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length >= 1 && "reload".equalsIgnoreCase(args[0])) {
            if (plugin.tryReloadEnchantsConfig()) plugin.messages().send(sender, "admin-reloaded");
            else plugin.messages().send(sender, "admin-reload-failed");
            return true;
        }
        if (args.length >= 3 && "give".equalsIgnoreCase(args[0])) {
            if (args.length > 4) { plugin.messages().sendList(sender, "admin-usage"); return true; }
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
            if (!plugin.config().isEnabled(def.id())) {
                plugin.messages().send(sender, "catalog-admin-disabled", "id", def.id());
                return true;
            }
            if (!plugin.coreApi().isAuthenticated(target)) {
                plugin.messages().send(sender, "catalog-admin-target-auth", "player", target.getName());
                return true;
            }
            int level = 1;
            try {
                if (args.length >= 4) level = Integer.parseInt(args[3]);
                if (level < 1 || level > def.maxLevel()) throw new NumberFormatException();
            } catch (NumberFormatException invalid) {
                plugin.messages().send(sender, "catalog-admin-level", "max", def.maxLevel());
                return true;
            }
            ItemStack book = plugin.items().makeEnchantBook(def, level, ru.cherdakmd.chrdkregister.api.EnchantBookSource.ADMIN);
            target.getInventory().addItem(book).values()
                    .forEach(leftover -> target.getWorld().dropItemNaturally(target.getLocation(), leftover));
            plugin.library().queue(target);
            plugin.messages().send(sender, "admin-given",
                    "name", plugin.messages().mini("en." + def.id() + ".name"),
                    "level", level, "player", target.getName());
            return true;
        }
        if (args.length >= 2 && "purify".equalsIgnoreCase(args[0])) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                plugin.messages().send(sender, "admin-offline", "player", args[1]);
                return true;
            }
            ItemStack held = target.getInventory().getItemInMainHand();
            if (held.getType().isAir()) {
                plugin.messages().send(sender, "admin-purify-empty", "player", target.getName());
                return true;
            }
            Map<String, Integer> enchants = new LinkedHashMap<>(plugin.items().read(held));
            List<String> lifted = new ArrayList<>();
            enchants.keySet().removeIf(id -> {
                EnchantDef def = EnchantRegistry.byId(id);
                if (def != null && def.curse()) {
                    lifted.add(id);
                    return true;
                }
                return false;
            });
            if (lifted.isEmpty()) {
                plugin.messages().send(sender, "admin-purify-empty", "player", target.getName());
                return true;
            }
            plugin.items().write(held, enchants);
            plugin.messages().send(sender, "admin-purified",
                    "count", lifted.size(), "player", target.getName());
            return true;
        }
        plugin.messages().sendList(sender, "admin-usage");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                     @NotNull String label, String @NotNull [] args) {
        List<String> matches = new ArrayList<>();
        if (args.length == 0 || plugin.coreApi() == null || sender instanceof Player actor && !plugin.coreApi().isAuthenticated(actor)) return matches;
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if ("enchants".equalsIgnoreCase(command.getName())) {
            if (!sender.hasPermission("chrdkenchants.use")) return matches;
            if (args.length == 1) return List.of("guard", "library", "sources", "info", "find", "group", "rarity", "light", "curses", "hand", "sort", "inspect", "cooldowns")
                    .stream().filter(value -> value.startsWith(prefix)).toList();
            if (args.length == 2) {
                List<String> values = switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "guard" -> List.of("auto", "briar_guard", "faltering_guard", "signal_guard");
                    case "library" -> java.util.stream.IntStream.rangeClosed(1, EnchantLibrary.pages(EnchantRegistry.size())).mapToObj(Integer::toString).toList();
                    case "info", "find" -> EnchantRegistry.all().stream().map(EnchantDef::id).toList();
                    case "group" -> java.util.Arrays.stream(ItemGroup.values()).map(value -> value.name().toLowerCase(Locale.ROOT)).toList();
                    case "rarity" -> java.util.Arrays.stream(EnchantDef.Rarity.values()).map(value -> value.name().toLowerCase(Locale.ROOT)).toList();
                    case "sort" -> java.util.Arrays.stream(EnchantCatalog.Sort.values()).map(value -> value.name().toLowerCase(Locale.ROOT)).toList();
                    default -> List.of();
                };
                return values.stream().filter(value -> value.startsWith(prefix)).toList();
            }
            return matches;
        }
        if (!sender.hasPermission("chrdkenchants.admin")) return matches;
        if (args.length == 1) {
            List.of("give", "purify", "reload").stream()
                    .filter(x -> x.startsWith(prefix)).forEach(matches::add);
        } else if (args.length == 2 && ("give".equalsIgnoreCase(args[0])
                || "purify".equalsIgnoreCase(args[0]))) {
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
