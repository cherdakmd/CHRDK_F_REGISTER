package ru.cherdakmd.diamondshop;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Команды магазина: /shop (игроки) и /shopadmin (администраторы).
 */
public final class ShopCommandHandler implements TabExecutor {

    private final DiamondShopPlugin plugin;

    public ShopCommandHandler(DiamondShopPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (plugin.coreApi() == null) {
            plugin.messages().send(sender, "trading.no-api");
            return true;
        }
        if ("shopadmin".equalsIgnoreCase(command.getName())) {
            return handleAdmin(sender, args);
        }
        return handleShop(sender, args);
    }

    /* ------------------------------- /shop ------------------------------- */

    private boolean handleShop(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "trading.usage");
            return true;
        }
        if (!plugin.coreApi().isAuthenticated(player)) {
            plugin.messages().send(player, "trading.need-auth");
            return true;
        }
        if (!plugin.shopService().ready(player)) { plugin.messages().send(player, "trading.unavailable"); return true; }
        if (args.length == 0) {
            plugin.gui().open(player, 0);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "balance", "bal" -> plugin.messages().send(player, "trading.balance",
                    "balance", plugin.shopService().balance(player), "physical", plugin.shopService().physical(player));
            case "deposit", "dep", "withdraw", "draw" -> plugin.messages().send(player, "trading.bank-commands");
            case "price" -> handlePrice(player, args);
            case "find", "search" -> handleFind(player, args);
            case "buy" -> handleBuy(player, args);
            case "sell" -> handleSell(player, args);
            case "sellall" -> handleSellAll(player);
            case "stats" -> handleStats(player);
            default -> plugin.messages().send(player, "trading.usage");
        }
        return true;
    }

    private void handlePrice(Player player, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(player, "trading.usage");
            return;
        }
        ShopConfig.ShopItem item = requireItem(player, args[1]);
        if (item == null) return;
        ShopService service = plugin.shopService();
        long stock = service.store().stockOf(item, System.currentTimeMillis(), service.config().stockRecoveryPerHour());
        plugin.messages().send(player, "trading.price-info",
                "material", item.material().toLowerCase(Locale.ROOT),
                "price", service.priceOf(item),
                "bid", service.bidPrice(item),
                "ask", service.askPrice(item), "batch", service.batchSize(item),
                "stock", stock, "ref", item.refStock());
        double[] history = service.historyOf(item);
        if (history.length > 0) {
            plugin.messages().send(player, "trading.price-history",
                    "history", PriceEngine.sparkline(history),
                    "trend", PriceEngine.trend(history));
        }
    }

    /** Поиск товара по фрагменту имени материала: до 10 совпадений с ценами. */
    private void handleFind(Player player, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(player, "trading.usage");
            return;
        }
        String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        ShopService service = plugin.shopService();
        List<ShopConfig.ShopItem> matches = ShopCatalog.search(service.config().items(), query);
        if (matches.isEmpty()) {
            plugin.messages().send(player, "trading.find-empty", "query", query);
            return;
        }
        plugin.messages().send(player, "trading.find-header",
                "query", query, "count", matches.size());
        int shown = Math.min(ShopCatalog.SEARCH_LIMIT, matches.size());
        for (int index = 0; index < shown; index++) {
            ShopConfig.ShopItem item = matches.get(index);
            ShopCategory category = ShopCategory.byId(item.category()).orElse(ShopCategory.DEFAULT);
            plugin.messages().send(player, "trading.find-line",
                    "material", item.material().toLowerCase(Locale.ROOT),
                    "ask", service.askPrice(item), "batch", service.batchSize(item),
                    "bid", PriceEngine.round(service.bidPrice(item)),
                    "category", plugin.messages().mini(category.messageKey()));
        }
        if (matches.size() > shown) {
            plugin.messages().send(player, "trading.find-more",
                    "shown", shown, "count", matches.size());
        }
    }

    private void handleBuy(Player player, String[] args) {
        if (args.length < 3) {
            plugin.messages().send(player, "trading.usage");
            return;
        }
        ShopConfig.ShopItem item = requireItem(player, args[1]);
        if (item == null) return;
        long units = parseLong(args[2], -1);
        ShopService.TradeResult result = plugin.shopService().buy(player, item, units);
        if (result.success()) {
            plugin.messages().send(player, "trading.payment-detail", "account", result.bankPaid(), "physical", result.physicalPaid(), "change", result.change());
            plugin.messages().send(player, "trading.buy-done",
                    "material", item.material().toLowerCase(Locale.ROOT), "units", result.units(),
                    "cost", result.money(),
                    "balance", plugin.shopService().balance(player), "physical", plugin.shopService().physical(player));
        } else {
            plugin.messages().send(player, result.errorKey(),
                    "cost", result.money(), "limit", plugin.shopService().config().dailyTurnoverLimit(),
                    "balance", plugin.shopService().balance(player),
                    "material", item.material().toLowerCase(Locale.ROOT), "units", units, "value", args[2]);
        }
    }

    private void handleSell(Player player, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(player, "trading.usage");
            return;
        }
        ShopConfig.ShopItem item = requireItem(player, args[1]);
        if (item == null) return;
        Material material = Material.matchMaterial(item.material());
        long have = material == null ? 0 : ShopService.countItems(player.getInventory(), material);
        long units;
        if (args.length >= 3 && "all".equalsIgnoreCase(args[2])) {
            units = plugin.shopService().batchSize(item) > 0 ? have / plugin.shopService().batchSize(item) : 0;
        } else if (args.length >= 3) {
            units = parseLong(args[2], -1);
        } else {
            units = 1;
        }
        if (have <= 0) {
            plugin.messages().send(player, "trading.sell-none");
            return;
        }
        ShopService.TradeResult result = plugin.shopService().sell(player, item, units);
        if (result.success()) {
            plugin.messages().send(player, "trading.sell-done",
                    "material", item.material().toLowerCase(Locale.ROOT), "units", result.units(),
                    "income", result.money(),
                    "balance", plugin.shopService().balance(player), "physical", plugin.shopService().physical(player));
        } else {
            plugin.messages().send(player, result.errorKey() == null ? "trading.invalid-number" : result.errorKey(),
                    "cost", result.money(), "limit", plugin.shopService().config().dailyTurnoverLimit(),
                    "balance", plugin.shopService().balance(player),
                    "material", item.material().toLowerCase(Locale.ROOT), "units", units,
                    "value", args.length >= 3 ? args[2] : "1");
        }
    }

    private void handleSellAll(Player player) {
        ShopService.SellAllResult result = plugin.shopService().sellAll(player);
        if (result.kinds() == 0) {
            plugin.messages().send(player, "trading.sellall-none");
            return;
        }
        plugin.messages().send(player, "trading.sellall-done",
                "kinds", result.kinds(), "units", result.units(),
                "income", result.income(),
                "balance", plugin.shopService().balance(player), "physical", plugin.shopService().physical(player));
    }

    private void handleStats(CommandSender sender) {
        ShopService service = plugin.shopService();
        plugin.messages().send(sender, "trading.stats-header");
        plugin.messages().send(sender, "trading.stats-turnover", "turnover", PriceEngine.round(service.store().turnover()));
        List<ShopConfig.ShopItem> items = service.config().items();
        boolean any = false;
        for (ShopConfig.ShopItem item : items) {
            ShopStore.ItemState state = service.store().snapshotOf(item.material());
            if (state == null || (state.boughtByPlayers == 0 && state.soldByPlayers == 0)) continue;
            any = true;
            plugin.messages().send(sender, "trading.stats-line",
                    "material", item.material().toLowerCase(Locale.ROOT),
                    "sold", state.soldByPlayers, "bought", state.boughtByPlayers,
                    "price", service.priceOf(item));
        }
        if (!any) {
            plugin.messages().send(sender, "trading.stats-empty");
        }
    }

    /* ------------------------------- /shopadmin ------------------------------- */

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (args.length == 0) {
            plugin.messages().send(sender, "admin.usage");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                plugin.reloadShopConfig();
                plugin.messages().send(sender, "admin.reloaded");
            }
            case "stock" -> handleAdminStock(sender, args);
            case "top" -> handleAdminTop(sender);
            default -> plugin.messages().send(sender, "admin.usage");
        }
        return true;
    }

    private void handleAdminStock(CommandSender sender, String[] args) {
        if(!plugin.shopService().store().legacyAvailable()||plugin.shopService().store().pending()!=null) { plugin.messages().send(sender,"trading.storage-unavailable"); return; }
        if (args.length < 2) {
            plugin.messages().send(sender, "admin.usage");
            return;
        }
        ShopConfig.ShopItem item = plugin.shopService().findItem(args[1]).orElse(null);
        if (item == null) {
            plugin.messages().send(sender, "trading.unknown-item", "material", args[1]);
            return;
        }
        ShopService service = plugin.shopService();
        if (args.length >= 3) {
            long value = parseLong(args[2], -1);
            if (value < 0) {
                plugin.messages().send(sender, "trading.invalid-number", "value", args[2]);
                return;
            }
            service.store().setStock(item, value, System.currentTimeMillis());
            plugin.messages().send(sender, "admin.stock-set",
                    "material", item.material().toLowerCase(Locale.ROOT), "stock", Math.max(1, value));
            return;
        }
        long stock = service.store().stockOf(item, System.currentTimeMillis(), service.config().stockRecoveryPerHour());
        plugin.messages().send(sender, "admin.stock-info",
                "material", item.material().toLowerCase(Locale.ROOT),
                "stock", stock, "price", service.priceOf(item));
    }

    private void handleAdminTop(CommandSender sender) {
        ShopService service = plugin.shopService();
        List<ShopConfig.ShopItem> ranked = service.config().items().stream()
                .filter(item -> {
                    ShopStore.ItemState state = service.store().snapshotOf(item.material());
                    return state != null && (state.boughtByPlayers + state.soldByPlayers) > 0;
                })
                .sorted(Comparator.comparingLong((ShopConfig.ShopItem item) -> {
                    ShopStore.ItemState state = service.store().snapshotOf(item.material());
                    return state.boughtByPlayers + state.soldByPlayers;
                }).reversed())
                .limit(10)
                .toList();
        plugin.messages().send(sender, "admin.top-header");
        if (ranked.isEmpty()) {
            plugin.messages().send(sender, "admin.top-empty");
            return;
        }
        int index = 1;
        for (ShopConfig.ShopItem item : ranked) {
            ShopStore.ItemState state = service.store().snapshotOf(item.material());
            plugin.messages().send(sender, "admin.top-line",
                    "index", index++,
                    "material", item.material().toLowerCase(Locale.ROOT),
                    "trades", state.boughtByPlayers + state.soldByPlayers,
                    "price", service.priceOf(item));
        }
    }

    /* ------------------------------- вспомогательное ------------------------------- */

    private ShopConfig.ShopItem requireItem(CommandSender sender, String input) {
        ShopConfig.ShopItem item = plugin.shopService().findItem(input).orElse(null);
        if (item == null) {
            plugin.messages().send(sender, "trading.unknown-item", "material", input);
        }
        return item;
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, String @NotNull [] args) {
        List<String> matches = new ArrayList<>();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if ("shopadmin".equalsIgnoreCase(command.getName())) {
            if (args.length == 1) {
                addMatches(matches, prefix, List.of("stock", "top", "reload"));
            } else if (args.length == 2 && "stock".equalsIgnoreCase(args[0])) {
                addItemMatches(matches, prefix);
            }
            return matches;
        }
        if (plugin.coreApi() == null || !(sender instanceof Player player) || !plugin.coreApi().isAuthenticated(player)) {
            return matches;
        }
        switch (args.length) {
            case 1 -> addMatches(matches, prefix,
                    List.of("balance", "deposit", "withdraw", "price", "find", "buy", "sell",
                            "sellall", "stats"));
            case 2 -> {
                if ("price".equalsIgnoreCase(args[0]) || "buy".equalsIgnoreCase(args[0])
                        || "sell".equalsIgnoreCase(args[0]) || "find".equalsIgnoreCase(args[0])) {
                    addItemMatches(matches, prefix);
                } else if ("deposit".equalsIgnoreCase(args[0])) {
                    addMatches(matches, prefix, List.of("all", "hand"));
                }
            }
            case 3 -> {
                if ("sell".equalsIgnoreCase(args[0])) {
                    addMatches(matches, prefix, List.of("1", "16", "64", "all"));
                } else if ("buy".equalsIgnoreCase(args[0])) {
                    addMatches(matches, prefix, List.of("1", "16", "64"));
                }
            }
            default -> {
            }
        }
        return matches;
    }

    private void addMatches(List<String> matches, String prefix, List<String> options) {
        options.stream().filter(option -> option.startsWith(prefix)).forEach(matches::add);
    }

    private void addItemMatches(List<String> matches, String prefix) {
        plugin.shopService().config().items().stream()
                .map(ShopConfig.ShopItem::material)
                .map(String::toLowerCase)
                .filter(name -> name.startsWith(prefix))
                .limit(40)
                .forEach(matches::add);
    }
}
