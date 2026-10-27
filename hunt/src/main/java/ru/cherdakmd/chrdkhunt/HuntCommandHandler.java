package ru.cherdakmd.chrdkhunt;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Команды {@code /hunt} и {@code /huntadmin} (1.4.155).
 *
 * <p>Игрок открывает меню, смотрит статистику и рейтинг, покупает в лавке,
 * обменивает монеты и открывает сундуки. Администратор перечитывает конфиг и
 * выдаёт монеты, осколки или ключи — в том числе офлайн-игроку из базы.</p>
 */
public final class HuntCommandHandler implements CommandExecutor, TabCompleter {

    private static final List<String> PLAYER_SUBCOMMANDS =
            List.of("help", "stats", "shop", "buy", "exchange", "keys", "chest",
                    "bestiary", "trophies", "trophy", "top", "danger", "inspect", "observations", "elites", "hud", "history");
    private static final List<String> ADMIN_SUBCOMMANDS =
            List.of("reload", "give", "reset", "info");
    private static final List<String> GIVE_KINDS = List.of("coins", "shards", "keys");

    private final HuntPlugin plugin;

    public HuntCommandHandler(HuntPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("huntadmin")) {
            return admin(sender, args);
        }
        if (!sender.hasPermission("chrdkhunt.use")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                plugin.messages().send(sender, "player-only");
                return true;
            }
            if (!plugin.config().enabled()) {
                plugin.messages().send(sender, "disabled");
                return true;
            }
            plugin.gui().open(player);
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> {
                plugin.report().help(sender);
                yield true;
            }
            case "stats" -> plugin.report().stats(sender, args.length > 1 ? args[1] : null);
            case "shop" -> shop(sender);
            case "buy" -> buy(sender, args);
            case "exchange" -> exchange(sender, args);
            case "keys" -> keys(sender);
            case "chest" -> chest(sender, args);
            case "bestiary" -> bestiary(sender, args);
            case "trophies" -> trophies(sender);
            case "trophy" -> trophy(sender, args);
            case "top" -> plugin.report().top(sender);
            case "danger", "inspect", "observations", "elites", "hud", "history" -> new MobReport(plugin).command(sender, args);
            default -> {
                plugin.messages().send(sender, "unknown-subcommand", "sub", args[0]);
                yield true;
            }
        };
    }

    /* --------------------------------- игрок ---------------------------------- */

    private boolean shop(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(player, "disabled");
            return true;
        }
        plugin.shopGui().open(player);
        return true;
    }

    private boolean bestiary(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(player, "disabled");
            return true;
        }
        if (args.length > 1 && args[1].equalsIgnoreCase("claim")) {
            if (args.length < 3) {
                plugin.messages().send(player, "help-bestiary");
                return true;
            }
            if (plugin.service().claimBestiary(player, args[2])) {
                plugin.bestiaryGui().reopen(player, args[2]);
            }
            return true;
        }
        if (args.length > 1) {
            plugin.bestiaryGui().openSection(player, args[1]);
            return true;
        }
        plugin.bestiaryGui().open(player);
        return true;
    }

    private boolean trophies(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(player, "disabled");
            return true;
        }
        plugin.trophyGui().open(player);
        return true;
    }

    private boolean keys(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(player, "disabled");
            return true;
        }
        plugin.gui().openKeys(player);
        return true;
    }

    private boolean buy(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(player, "disabled");
            return true;
        }
        if (args.length < 2) {
            plugin.messages().send(player, "help-buy");
            return true;
        }
        int quantity = 1;
        if (args.length > 2) {
            try {
                quantity = Integer.parseInt(args[2]);
            } catch (NumberFormatException exception) {
                quantity = 0;
            }
            if (quantity < 1 || quantity > 64) {
                plugin.messages().send(player, "shop-bad-amount");
                return true;
            }
        }
        plugin.service().buy(player, args[1], quantity);
        return true;
    }

    private boolean exchange(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(player, "disabled");
            return true;
        }
        if (args.length < 2) {
            plugin.messages().send(player, "help-exchange");
            return true;
        }
        if (args[1].equalsIgnoreCase("max")) {
            plugin.service().exchangeMax(player);
            return true;
        }
        int diamonds;
        try {
            diamonds = Integer.parseInt(args[1]);
        } catch (NumberFormatException exception) {
            plugin.messages().send(player, "exchange-bad-amount");
            return true;
        }
        plugin.service().exchange(player, diamonds);
        return true;
    }

    private boolean chest(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(player, "disabled");
            return true;
        }
        if (args.length < 2) {
            plugin.gui().openKeys(player);
            return true;
        }
        plugin.service().openChest(player, args[1]);
        return true;
    }

    private boolean trophy(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(player, "disabled");
            return true;
        }
        if (args.length < 2) {
            plugin.messages().send(player, "help-trophies");
            return true;
        }
        plugin.service().craftTrophy(player, args[1]);
        return true;
    }

    /* -------------------------------- админские -------------------------------- */

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdkhunt.admin")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            plugin.messages().send(sender, "admin-usage");
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> reload(sender);
            case "give" -> give(sender, args);
            case "reset" -> reset(sender, args);
            case "info" -> info(sender);
            default -> {
                plugin.messages().send(sender, "admin-usage");
                yield true;
            }
        };
    }

    private boolean reload(CommandSender sender) {
        if (plugin.tryReloadHunt()) plugin.messages().send(sender, "admin-reloaded");
        else plugin.messages().send(sender, "admin-reload-failed");
        return true;
    }

    private boolean give(CommandSender sender, String[] args) {
        if (args.length < 4) {
            plugin.messages().send(sender, "admin-usage");
            return true;
        }
        Target target = resolve(args[1]);
        if (target == null) {
            plugin.messages().send(sender, "admin-player-not-found", "player", args[1]);
            return true;
        }
        String kind = args[2].toLowerCase(Locale.ROOT);
        if (!GIVE_KINDS.contains(kind)) {
            plugin.messages().send(sender, "admin-bad-kind", "kind", args[2]);
            return true;
        }
        long amount;
        try {
            amount = Long.parseLong(args[3]);
        } catch (NumberFormatException exception) {
            amount = -1L;
        }
        if (amount < 1L || amount > 1_000_000L) {
            plugin.messages().send(sender, "admin-bad-amount");
            return true;
        }
        String familyId = args.length > 4 ? args[4] : "";
        if (!kind.equals("coins") && plugin.config().family(familyId) == null) {
            plugin.messages().send(sender, "admin-unknown-family",
                    "id", familyId.isBlank() ? "?" : familyId);
            return true;
        }
        String familyName = "";
        switch (kind) {
            case "coins" -> plugin.store().addCoins(target.id(), target.name(), amount);
            case "shards" -> {
                plugin.store().addShards(target.id(), target.name(), familyId, amount);
                familyName = plugin.config().family(familyId).displayName();
            }
            case "keys" -> {
                plugin.store().addKeys(target.id(), target.name(), familyId, amount);
                familyName = plugin.config().family(familyId).displayName();
            }
            default -> {
                plugin.messages().send(sender, "admin-bad-kind", "kind", kind);
                return true;
            }
        }
        plugin.requestSave();
        plugin.messages().send(sender, "admin-given",
                "player", target.name(),
                "kind", kind,
                "amount", String.valueOf(amount),
                "family", familyName);
        return true;
    }

    private boolean reset(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "admin-usage");
            return true;
        }
        Target target = resolve(args[1]);
        if (target == null) {
            plugin.messages().send(sender, "admin-player-not-found", "player", args[1]);
            return true;
        }
        if (!plugin.store().reset(target.id())) {
            plugin.messages().send(sender, "admin-reset-missing", "player", target.name());
            return true;
        }
        plugin.requestSave();
        if (plugin.historyTracker() != null) plugin.historyTracker().forget(target.id());
        plugin.messages().send(sender, "admin-reset", "player", target.name());
        return true;
    }

    private boolean info(CommandSender sender) {
        plugin.messages().send(sender, "admin-info",
                "families", String.valueOf(plugin.config().families().size()),
                "mobs", String.valueOf(plugin.config().mobRules().size()),
                "shop", String.valueOf(plugin.config().shop().size()),
                "trophies", String.valueOf(plugin.config().trophies().size()),
                "players", String.valueOf(plugin.store().players()),
                "earned", String.valueOf(plugin.store().totalEarned()),
                "enabled", plugin.config().enabled() ? "да" : "нет");
        return true;
    }

    /** Игрок онлайн или запись из {@code hunt.json}. */
    private Target resolve(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return new Target(online.getUniqueId(), online.getName());
        }
        HuntStore.Row row = plugin.store().byName(name);
        return row == null ? null : new Target(row.id(), row.name());
    }

    private record Target(UUID id, String name) {
    }

    /* ------------------------------ автодополнение ------------------------------ */

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias,
                                      String[] args) {
        if (command.getName().equalsIgnoreCase("huntadmin")) {
            if (!sender.hasPermission("chrdkhunt.admin")) {
                return List.of();
            }
            if (args.length == 1) {
                return filter(ADMIN_SUBCOMMANDS, args[0]);
            }
            if (args.length == 2 && (args[0].equalsIgnoreCase("give")
                    || args[0].equalsIgnoreCase("reset"))) {
                return filter(onlineNames(), args[1]);
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
                return filter(GIVE_KINDS, args[2]);
            }
            if (args.length == 5 && args[0].equalsIgnoreCase("give")
                    && !args[2].equalsIgnoreCase("coins")) {
                return filter(familyIds(), args[4]);
            }
            return List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("hud") && sender.hasPermission("chrdkhunt.use"))
            return filter(List.of("on", "off"), args[1]);
        if (args.length == 1) {
            return filter(PLAYER_SUBCOMMANDS, args[0]);
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "buy" -> filter(offerIds(), args[1]);
                case "chest" -> filter(familyIds(), args[1]);
                case "bestiary" -> {
                    List<String> options = new ArrayList<>(familyIds());
                    options.add("claim");
                    yield filter(options, args[1]);
                }
                case "trophy" -> filter(trophyIds(), args[1]);
                case "stats" -> filter(onlineNames(), args[1]);
                case "exchange" -> filter(List.of("max"), args[1]);
                case "history" -> sender.hasPermission("chrdkhunt.use") ? filter(List.of("1", "2", "3", "4"), args[1]) : List.of();
                default -> List.of();
            };
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("bestiary")
                && args[1].equalsIgnoreCase("claim")) {
            return filter(familyIds(), args[2]);
        }
        return List.of();
    }

    private List<String> familyIds() {
        List<String> out = new ArrayList<>();
        for (HuntFamily family : plugin.config().families()) {
            out.add(family.id());
        }
        return out;
    }

    private List<String> offerIds() {
        List<String> out = new ArrayList<>();
        for (HuntShopOffer offer : plugin.config().shop()) {
            out.add(offer.id());
        }
        return out;
    }

    private List<String> trophyIds() {
        List<String> out = new ArrayList<>();
        for (HuntTrophy trophy : plugin.config().trophies()) {
            out.add(trophy.id());
        }
        return out;
    }

    private static List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }
}
