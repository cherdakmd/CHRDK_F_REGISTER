package ru.cherdakmd.chrdkorders;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Команды {@code /orders} и {@code /ordersadmin} (1.4.154).
 *
 * <p>Игрок открывает меню, читает список в чате, сдаёт заказ по номеру и
 * смотрит статистику с рейтингом. Администратор перечитывает конфиг, ставит
 * заказ нужной категории, очищает доску и правит репутацию.</p>
 */
public final class OrderCommandHandler implements CommandExecutor, TabCompleter {

    private static final List<String> PLAYER_SUBCOMMANDS =
            List.of("help", "list", "deliver", "stats", "top");
    private static final List<String> ADMIN_SUBCOMMANDS =
            List.of("reload", "new", "clear", "give", "info", "awards");

    private final OrdersPlugin plugin;

    public OrderCommandHandler(OrdersPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("ordersadmin")) {
            return admin(sender, args);
        }
        if (!sender.hasPermission("chrdkorders.use")) {
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
            case "help" -> help(sender);
            case "list" -> list(sender);
            case "deliver" -> deliver(sender, args);
            case "stats" -> stats(sender, args);
            case "top" -> top(sender);
            default -> {
                plugin.messages().send(sender, "unknown-subcommand", "sub", args[0]);
                yield true;
            }
        };
    }

    /* --------------------------------- игрок ---------------------------------- */

    private boolean help(CommandSender sender) {
        plugin.messages().send(sender, "help-header");
        plugin.messages().send(sender, "help-list");
        plugin.messages().send(sender, "help-deliver");
        plugin.messages().send(sender, "help-stats");
        plugin.messages().send(sender, "help-top");
        plugin.messages().send(sender, "help-footer");
        return true;
    }

    private boolean list(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(player, "disabled");
            return true;
        }
        List<BotOrder> orders = plugin.service().board();
        if (orders.isEmpty()) {
            plugin.messages().send(player, "list-empty");
            return true;
        }
        int reputation = plugin.service().reputation(player);
        plugin.messages().send(player, "list-header",
                "count", String.valueOf(plugin.service().availableFor(player)),
                "reputation", String.valueOf(reputation),
                "rank", Messages.trusted(plugin.service().rankText(reputation)));
        long now = System.currentTimeMillis();
        for (BotOrder order : orders) {
            String material = order.materialOrNull() == null
                    ? order.material() : OrdersService.itemName(order.materialOrNull()).text();
            String time = OrderBoard.formatDuration(order.millisLeft(now));
            if (order.minReputation() > reputation) {
                plugin.messages().send(player, "list-row-locked",
                        "num", order.id(),
                        "material", Messages.trusted(material),
                        "amount", String.valueOf(order.amount()),
                        "bot", order.botName(),
                        "need", String.valueOf(order.minReputation()));
                continue;
            }
            if (order.hasPassReward()) {
                plugin.messages().send(player, "list-row-pass",
                        "num", order.id(),
                        "material", Messages.trusted(material),
                        "amount", String.valueOf(order.amount()),
                        "bot", order.botName(),
                        "reward", String.valueOf(order.rewardDiamonds()),
                        "hours", String.valueOf(order.rewardPassHours()),
                        "time", time.isEmpty() ? "—" : time);
            } else {
                plugin.messages().send(player, "list-row",
                        "num", order.id(),
                        "material", Messages.trusted(material),
                        "amount", String.valueOf(order.amount()),
                        "bot", order.botName(),
                        "reward", String.valueOf(order.rewardDiamonds()),
                        "time", time.isEmpty() ? "—" : time);
            }
        }
        plugin.messages().send(player, "list-footer");
        return true;
    }

    private boolean deliver(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(player, "disabled");
            return true;
        }
        if (args.length < 2) {
            plugin.messages().send(player, "help-deliver");
            return true;
        }
        plugin.service().deliver(player, args[1]);
        return true;
    }

    private boolean stats(CommandSender sender, String[] args) {
        String target;
        if (args.length > 1) {
            target = args[1];
        } else if (sender instanceof Player player) {
            target = player.getName();
        } else {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        UUID id = null;
        String name = target;
        Player online = Bukkit.getPlayerExact(target);
        if (online != null) {
            id = online.getUniqueId();
            name = online.getName();
        } else {
            OrdersStore.Row row = plugin.store().byName(target);
            if (row == null) {
                plugin.messages().send(sender, "admin-player-not-found");
                return true;
            }
            id = row.id();
            name = row.name();
        }
        int reputation = plugin.store().reputation(id);
        int completed = plugin.store().completed(id);
        int today = plugin.store().today(id, System.currentTimeMillis());
        int limit = plugin.config().maxPerDay();
        plugin.messages().send(sender, "stats-header", "player", name);
        plugin.messages().send(sender, "stats-completed", "completed", String.valueOf(completed));
        plugin.messages().send(sender, "stats-reputation",
                "reputation", String.valueOf(reputation));
        plugin.messages().send(sender, "stats-rank",
                "rank", Messages.trusted(plugin.service().rankText(reputation)));
        int left = plugin.service().reputationToNext(reputation);
        if (left > 0) {
            plugin.messages().send(sender, "stats-rank-next", "left", String.valueOf(left));
        } else {
            plugin.messages().send(sender, "stats-rank-max");
        }
        plugin.messages().send(sender, "stats-today",
                "today", String.valueOf(today),
                "limit", limit <= 0 ? "∞" : String.valueOf(limit));
        plugin.messages().send(sender, "stats-footer");
        return true;
    }

    private boolean top(CommandSender sender) {
        List<OrdersStore.Row> rows = plugin.store().top(plugin.config().topSize());
        if (rows.isEmpty()) {
            plugin.messages().send(sender, "top-empty");
            return true;
        }
        plugin.messages().send(sender, "top-header");
        int place = 1;
        for (OrdersStore.Row row : rows) {
            plugin.messages().send(sender, "top-row",
                    "num", String.valueOf(place++),
                    "player", row.name(),
                    "completed", String.valueOf(row.completed()),
                    "reputation", String.valueOf(row.reputation()),
                    "rank", Messages.trusted(plugin.service().rankText(row.reputation())));
        }
        return true;
    }

    /* -------------------------------- админские -------------------------------- */

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdkorders.admin")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            plugin.messages().send(sender, "admin-usage");
            return true;
        }
        if(args[0].equalsIgnoreCase("awards")) { plugin.finance().status(sender); return true; }
        if(plugin.finance().paused()&&!args[0].equalsIgnoreCase("info")&&!args[0].equalsIgnoreCase("reload")) {
            plugin.messages().send(sender,"finance-wait"); return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> reload(sender);
            case "new" -> spawn(sender, args);
            case "clear" -> clear(sender);
            case "give" -> giveReputation(sender, args);
            case "info" -> info(sender);
            default -> {
                plugin.messages().send(sender, "admin-usage");
                yield true;
            }
        };
    }

    private boolean reload(CommandSender sender) {
        plugin.reloadOrders();
        plugin.messages().send(sender, "admin-reloaded");
        return true;
    }

    private boolean spawn(CommandSender sender, String[] args) {
        String category = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : null;
        if (category != null && plugin.config().category(category) == null) {
            plugin.messages().send(sender, "admin-unknown-category", "id", args[1]);
            return true;
        }
        BotOrder order = plugin.service().create(category);
        if (order == null) {
            plugin.messages().send(sender, "admin-spawn-failed");
            return true;
        }
        String material = order.materialOrNull() == null
                ? order.material() : OrdersService.itemName(order.materialOrNull()).text();
        plugin.messages().send(sender, "admin-spawned",
                "material", Messages.trusted(material),
                "amount", String.valueOf(order.amount()),
                "bot", order.botName());
        return true;
    }

    private boolean clear(CommandSender sender) {
        int removed = plugin.service().clearBoard();
        plugin.messages().send(sender, "admin-cleared", "count", String.valueOf(removed));
        return true;
    }

    private boolean giveReputation(CommandSender sender, String[] args) {
        if (args.length < 3) {
            plugin.messages().send(sender, "admin-usage");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            plugin.messages().send(sender, "admin-player-not-found");
            return true;
        }
        int value;
        try {
            value = Integer.parseInt(args[2]);
        } catch (NumberFormatException exception) {
            value = -1;
        }
        if (value < 0 || value > 100_000) {
            plugin.messages().send(sender, "admin-bad-amount");
            return true;
        }
        plugin.store().setReputation(target.getUniqueId(), target.getName(), value);
        plugin.requestSave();
        plugin.messages().send(sender, "admin-rep-given",
                "player", target.getName(),
                "reputation", String.valueOf(value));
        return true;
    }

    private boolean info(CommandSender sender) {
        plugin.messages().send(sender, "admin-info",
                "orders", String.valueOf(plugin.store().size()),
                "size", String.valueOf(plugin.config().boardSize()),
                "categories", String.valueOf(plugin.config().categories().size()),
                "bots", String.valueOf(plugin.config().bots().size()),
                "players", String.valueOf(plugin.store().players()),
                "completed", String.valueOf(plugin.store().totalCompleted()));
        return true;
    }

    /* ------------------------------ автодополнение ------------------------------ */

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias,
                                      String[] args) {
        if (command.getName().equalsIgnoreCase("ordersadmin")) {
            if (!sender.hasPermission("chrdkorders.admin")) {
                return List.of();
            }
            if (args.length == 1) {
                return filter(ADMIN_SUBCOMMANDS, args[0]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("new")) {
                return filter(new ArrayList<>(plugin.config().categories().keySet()), args[1]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
                return filter(onlineNames(), args[1]);
            }
            return List.of();
        }
        if (args.length == 1) {
            return filter(PLAYER_SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("deliver")) {
            List<String> ids = new ArrayList<>();
            for (BotOrder order : plugin.service().board()) {
                ids.add(order.id());
            }
            return filter(ids, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("stats")) {
            return filter(onlineNames(), args[1]);
        }
        return List.of();
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
