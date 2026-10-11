package ru.cherdakmd.diamondshop;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
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
            plugin.messages().send(sender, "shop.no-api");
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
            plugin.messages().send(sender, "shop.usage");
            return true;
        }
        if (!plugin.coreApi().isAuthenticated(player)) {
            plugin.messages().send(player, "shop.need-auth");
            return true;
        }
        if (args.length == 0) {
            plugin.gui().open(player, 0);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "balance", "bal" -> plugin.messages().send(player, "shop.balance",
                    "balance", PriceEngine.round(plugin.shopService().store().balanceOf(player.getUniqueId())));
            case "deposit", "dep" -> handleDeposit(player, args);
            case "withdraw", "draw" -> handleWithdraw(player, args);
            case "price" -> handlePrice(player, args);
            case "buy" -> handleBuy(player, args);
            case "sell" -> handleSell(player, args);
            case "stats" -> handleStats(player);
            default -> plugin.messages().send(player, "shop.usage");
        }
        return true;
    }

    private void handleDeposit(Player player, String[] args) {
        boolean handOnly = args.length >= 2 && "hand".equalsIgnoreCase(args[1]);
        double credited = plugin.shopService().depositAll(player, handOnly);
        if (credited <= 0) {
            plugin.messages().send(player, "shop.deposit-none",
                    "materials", plugin.shopService().currencyNames());
        } else {
            plugin.messages().send(player, "shop.deposit-done",
                    "amount", PriceEngine.round(credited),
                    "balance", PriceEngine.round(plugin.shopService().store().balanceOf(player.getUniqueId())));
        }
    }

    private void handleWithdraw(Player player, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(player, "shop.usage");
            return;
        }
        long amount = parseLong(args[1], -1);
        int max = plugin.shopService().config().maxWithdraw();
        if (amount < 1 || amount > max) {
            plugin.messages().send(player, "shop.withdraw-invalid", "max", max);
            return;
        }
        if (plugin.shopService().store().balanceOf(player.getUniqueId()) < amount) {
            plugin.messages().send(player, "shop.withdraw-too-little");
            return;
        }
        long actual = plugin.shopService().withdraw(player, amount);
        if (actual < 0) {
            plugin.messages().send(player, "shop.withdraw-too-little");
        } else {
            plugin.messages().send(player, "shop.withdraw-done",
                    "amount", actual,
                    "balance", PriceEngine.round(plugin.shopService().store().balanceOf(player.getUniqueId())));
        }
    }

    private void handlePrice(Player player, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(player, "shop.usage");
            return;
        }
        ShopConfig.ShopItem item = requireItem(player, args[1]);
        if (item == null) return;
        ShopService service = plugin.shopService();
        long stock = service.store().stockOf(item, System.currentTimeMillis(), service.config().stockRecoveryPerHour());
        plugin.messages().send(player, "shop.price-info",
                "material", item.material().toLowerCase(Locale.ROOT),
                "price", service.priceOf(item),
                "stock", stock, "ref", item.refStock());
    }

    private void handleBuy(Player player, String[] args) {
        if (args.length < 3) {
            plugin.messages().send(player, "shop.usage");
            return;
        }
        ShopConfig.ShopItem item = requireItem(player, args[1]);
        if (item == null) return;
        long units = parseLong(args[2], 1);
        ShopService.TradeResult result = plugin.shopService().buy(player, item, units);
        if (result.success()) {
            plugin.messages().send(player, "shop.buy-done",
                    "material", item.material().toLowerCase(Locale.ROOT), "units", units,
                    "cost", result.money(),
                    "balance", PriceEngine.round(plugin.shopService().store().balanceOf(player.getUniqueId())));
        } else {
            plugin.messages().send(player, result.errorKey(),
                    "cost", result.money(),
                    "balance", PriceEngine.round(plugin.shopService().store().balanceOf(player.getUniqueId())),
                    "material", item.material().toLowerCase(Locale.ROOT), "units", units, "value", args[2]);
        }
    }

    private void handleSell(Player player, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(player, "shop.usage");
            return;
        }
        ShopConfig.ShopItem item = requireItem(player, args[1]);
        if (item == null) return;
        Material material = Material.matchMaterial(item.material());
        long have = material == null ? 0 : ShopService.countItems(player.getInventory(), material);
        long units;
        if (args.length >= 3 && "all".equalsIgnoreCase(args[2])) {
            units = have;
        } else if (args.length >= 3) {
            units = parseLong(args[2], 1);
        } else {
            units = 1;
        }
        if (have <= 0) {
            plugin.messages().send(player, "shop.sell-none");
            return;
        }
        units = Math.min(units, have);
        ShopService.TradeResult result = plugin.shopService().sell(player, item, units);
        if (result.success()) {
            plugin.messages().send(player, "shop.sell-done",
                    "material", item.material().toLowerCase(Locale.ROOT), "units", units,
                    "income", result.money(),
                    "balance", PriceEngine.round(plugin.shopService().store().balanceOf(player.getUniqueId())));
        } else {
            plugin.messages().send(player, result.errorKey() == null ? "shop.invalid-number" : result.errorKey(),
                    "cost", result.money(),
                    "balance", PriceEngine.round(plugin.shopService().store().balanceOf(player.getUniqueId())),
                    "material", item.material().toLowerCase(Locale.ROOT), "units", units,
                    "value", args.length >= 3 ? args[2] : "1");
        }
    }

    private void handleStats(CommandSender sender) {
        ShopService service = plugin.shopService();
        plugin.messages().send(sender, "shop.stats-header");
        plugin.messages().send(sender, "shop.stats-turnover", "turnover", PriceEngine.round(service.store().turnover()));
        List<ShopConfig.ShopItem> items = service.config().items();
        boolean any = false;
        for (ShopConfig.ShopItem item : items) {
            ShopStore.ItemState state = service.store().snapshotOf(item.material());
            if (state == null || (state.boughtByPlayers == 0 && state.soldByPlayers == 0)) continue;
            any = true;
            plugin.messages().send(sender, "shop.stats-line",
                    "material", item.material().toLowerCase(Locale.ROOT),
                    "sold", state.soldByPlayers, "bought", state.boughtByPlayers,
                    "price", service.priceOf(item));
        }
        if (!any) {
            plugin.messages().send(sender, "shop.stats-empty");
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
        if (args.length < 2) {
            plugin.messages().send(sender, "admin.usage");
            return;
        }
        ShopConfig.ShopItem item = plugin.shopService().findItem(args[1]).orElse(null);
        if (item == null) {
            plugin.messages().send(sender, "shop.unknown-item", "material", args[1]);
            return;
        }
        ShopService service = plugin.shopService();
        if (args.length >= 3) {
            long value = parseLong(args[2], -1);
            if (value < 0) {
                plugin.messages().send(sender, "shop.invalid-number", "value", args[2]);
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
            plugin.messages().send(sender, "shop.unknown-item", "material", input);
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
                    List.of("balance", "deposit", "withdraw", "price", "buy", "sell", "stats"));
            case 2 -> {
                if ("price".equalsIgnoreCase(args[0]) || "buy".equalsIgnoreCase(args[0])
                        || "sell".equalsIgnoreCase(args[0])) {
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
