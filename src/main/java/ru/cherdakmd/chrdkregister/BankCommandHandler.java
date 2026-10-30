package ru.cherdakmd.chrdkregister;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Команды алмазного банка: {@code /bal}, {@code /pay}, {@code /baltop}.
 *
 * <p>Все суммы — целые алмазы; разбор ввода терпит {@code 1 000}, {@code 1k},
 * {@code 1.5k}. Депозит и снятие работают только предметами (курс блока — из
 * конфига), переводы — по счёту и потому доступны офлайн-получателю.</p>
 *
 * <p>Ошибки не «глотаются»: на каждую есть отдельный ключ messages.yml, чтобы
 * владелец мог переписать текст, не трогая код.</p>
 */
public final class BankCommandHandler implements CommandExecutor, TabCompleter {

    private static final List<String> BAL_SUBCOMMANDS = List.of("deposit", "withdraw", "help", "recovery", "stats", "health");

    private final RegisterPlugin plugin;
    private final BankService bank;
    private volatile Messages messages;
    private final ru.cherdakmd.chrdkregister.api.EconomyConfirmations confirmations = new ru.cherdakmd.chrdkregister.api.EconomyConfirmations();
    private record WithdrawQuote(long amount, int blockWorth) { }

    public BankCommandHandler(RegisterPlugin plugin, BankService bank, Messages messages) {
        this.plugin = plugin;
        this.bank = bank;
        this.messages = messages;
    }

    public void setMessages(Messages messages) {
        this.messages = messages;
        confirmations.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if(sender instanceof Player player&&!bank.authenticated(player)) {messages.send(sender,"economy.need-auth");return true;}
        String name = command.getName().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "pay" -> handlePay(sender, args);
            case "baltop" -> handleBaltop(sender, args);
            default -> handleBalance(sender, args);
        };
    }

    /* ---------------------------------- /bal ---------------------------------- */

    private boolean handleBalance(CommandSender sender, String[] args) {
        if(args.length==1&&args[0].equalsIgnoreCase("health")) {
            if (!(sender instanceof org.bukkit.command.ConsoleCommandSender || sender instanceof org.bukkit.command.RemoteConsoleCommandSender)
                    || !sender.hasPermission("chrdkregister.admin")) { messages.send(sender,"common.no-permission");return true; }
            var h=bank.store().health();
            String share=h.total()==0?"0":java.math.BigDecimal.valueOf(h.topTen()).multiply(java.math.BigDecimal.valueOf(100))
                    .divide(java.math.BigDecimal.valueOf(h.total()),2,java.math.RoundingMode.HALF_UP).toPlainString();
            messages.send(sender,"economy.health","healthy",bank.enabled()&&h.available(),"accounts",h.accounts(),"total",BankService.format(h.total()),
                    "share",share,"pending",h.pending(),"receipts",h.receipts());return true;
        }
        if(args.length==1&&args[0].equalsIgnoreCase("recovery")) {
            if(!sender.hasPermission("chrdkregister.admin")) { messages.send(sender,"common.no-permission"); return true; }
            messages.send(sender,"economy.cash-status","healthy",bank.store().available(),"pending",bank.store().cashPendingCount(),"receipts",bank.store().cashReceiptCount()); return true;
        }
        if (!bank.enabled()) {
            messages.send(sender, "economy.disabled");
            return true;
        }
        String first = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (!first.isEmpty() && BAL_SUBCOMMANDS.contains(first)) {
            if (!(sender instanceof Player player)) {
                messages.send(sender, "common.player-only");
                return true;
            }
            if(first.equals("stats")) {
                if(!player.hasPermission("chrdkregister.bal")) {messages.send(player,"common.no-permission");return true;}
                var s=bank.store().personalStats(player.getUniqueId());
                messages.send(player,"economy.personal-stats","balance",BankService.format(s.balance()),"deposited",BankService.format(s.deposited()),
                        "withdrawn",BankService.format(s.withdrawn()),"sent",BankService.format(s.sent()),"received",BankService.format(s.received()));return true;
            }
            switch (first) {
                case "deposit" -> {
                    deposit(player, args);
                    return true;
                }
                case "withdraw" -> {
                    withdraw(player, args);
                    return true;
                }
                default -> {
                    messages.send(sender, "economy.help");
                    return true;
                }
            }
        }
        if (!sender.hasPermission("chrdkregister.bal")) {
            messages.send(sender, "common.no-permission");
            return true;
        }
        if (args.length >= 1) {
            showBalanceOf(sender, args[0]);
            return true;
        }
        if (!(sender instanceof Player player)) {
            messages.send(sender, "common.player-only");
            return true;
        }
        long balance = bank.balance(player);
        long inInventory = diamondsInInventory(player);
        messages.send(sender, "economy.balance",
                "balance", BankService.format(balance),
                "inventory", BankService.format(inInventory),
                "total", BankService.format(balance + inInventory));
        messages.send(sender, "economy.balance-hint");
        return true;
    }

    private void showBalanceOf(CommandSender sender, String targetName) {
        if (!sender.hasPermission("chrdkregister.bal.others")) {
            messages.send(sender, "common.no-permission");
            return;
        }
        Player online = Bukkit.getPlayerExact(targetName);
        if (online != null) {
            bank.balance(online); // открывает счёт при первом обращении
        }
        long balance = online != null ? bank.balanceOf(online.getName()) : bank.balanceOf(targetName);
        if (balance <= 0L && !bank.known(targetName)) {
            messages.send(sender, "economy.unknown-player", "player", targetName);
            return;
        }
        messages.send(sender, "economy.balance-other",
                "player", online != null ? online.getName() : targetName,
                "balance", BankService.format(balance));
    }

    private void deposit(Player player, String[] args) {
        if (!player.hasPermission("chrdkregister.bal")) {
            messages.send(player, "common.no-permission");
            return;
        }
        String mode = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "all";
        long credited;
        if (mode.equals("hand") || mode.equals("рука")) {
            credited = bank.deposit(player, true);
        } else if (mode.equals("all") || mode.equals("все") || mode.equals("всё")) {
            credited = bank.deposit(player, false);
        } else {
            long amount = BankService.parseAmount(mode);
            if (amount <= 0L) {
                messages.send(player, "economy.usage-deposit");
                return;
            }
            credited = bank.depositAmount(player, amount);
            if (credited < 0L) { messages.send(player,"economy.cash-unavailable"); return; }
            if (credited <= 0L) {
                messages.send(player, "economy.not-enough-items", "amount", BankService.format(amount));
                return;
            }
        }
        if (credited < 0L) { messages.send(player,"economy.cash-unavailable"); return; }
        if (credited <= 0L) {
            messages.send(player, "economy.nothing-to-deposit");
            return;
        }
        messages.send(player, "economy.deposit-done",
                "amount", BankService.format(credited),
                "balance", BankService.format(bank.balance(player)));
    }

    private void withdraw(Player player, String[] args) {
        if (!player.hasPermission("chrdkregister.bal")) {
            messages.send(player, "common.no-permission");
            return;
        }
        if (args.length < 2 || args.length > 3 || (args.length==3&&!args[2].equalsIgnoreCase("confirm"))) {
            messages.send(player, "economy.usage-withdraw");
            return;
        }
        long amount = BankService.parseAmount(args[1]);
        if (amount <= 0L) {
            messages.send(player, "economy.invalid-amount");
            return;
        }
        if (amount >= 64) {
            var quote=new WithdrawQuote(amount,bank.settings().blockWorth());
            boolean confirmRequested=args.length==3&&args[2].equalsIgnoreCase("confirm");
            if(confirmRequested&&!confirmations.consume(player.getUniqueId(),player,quote,System.nanoTime())) {
                messages.send(player,"economy.confirm-missing");return;
            }
            if(!confirmRequested) {
                if(!confirmations.offer(player.getUniqueId(),player,quote,System.nanoTime())) {messages.send(player,"economy.confirm-busy");return;}
                messages.send(player,"economy.withdraw-preview","amount",BankService.format(amount),"raw",amount);return;
            }
        }
        BankService.WithdrawResult result = bank.withdraw(player, amount);
        switch (result.status()) {
            case OK -> messages.send(player, "economy.withdraw-done",
                    "amount", BankService.format(result.amount()),
                    "balance", BankService.format(result.balance()));
            case NO_SPACE -> messages.send(player, "economy.no-space");
            case UNAVAILABLE -> messages.send(player,"economy.cash-unavailable");
            default -> messages.send(player, "economy.not-enough-balance",
                    "balance", BankService.format(result.balance()),
                    "amount", BankService.format(amount));
        }
    }

    /* ---------------------------------- /pay ---------------------------------- */

    private boolean handlePay(CommandSender sender, String[] args) {
        if (!bank.enabled()) {
            messages.send(sender, "economy.disabled");
            return true;
        }
        if (!(sender instanceof Player player)) {
            messages.send(sender, "common.player-only");
            return true;
        }
        if (!player.hasPermission("chrdkregister.pay")) {
            messages.send(sender, "common.no-permission");
            return true;
        }
        if (args.length < 2) {
            messages.send(sender, "economy.usage-pay");
            return true;
        }
        long amount = BankService.parseAmount(args[1]);
        if (amount <= 0L) {
            messages.send(sender, "economy.invalid-amount");
            return true;
        }
        EconomySettings config = bank.settings();
        if (amount < config.minPay() || amount > config.maxPay()) {
            messages.send(player, "economy.pay-limits",
                    "min", BankService.format(config.minPay()),
                    "max", BankService.format(config.maxPay()));
            return true;
        }
        if(args.length>3 || (args.length==3&&!args[2].equalsIgnoreCase("confirm")&&!args[2].equalsIgnoreCase("preview"))) {
            messages.send(player,"economy.usage-pay");return true;
        }
        var quote=bank.previewPay(player,args[0],amount);
        if(quote.status()!=BankStore.Status.OK) {
            messages.send(player,"economy.pay-preview-refused","reason",quote.status());return true;
        }
        boolean confirmRequested=args.length==3&&args[2].equalsIgnoreCase("confirm");
        if(confirmRequested&&!confirmations.consume(player.getUniqueId(),player,quote,System.nanoTime())) {
            messages.send(player,"economy.confirm-missing");return true;
        }
        if(!confirmRequested) {
            if(!confirmations.offer(player.getUniqueId(),player,quote,System.nanoTime())) {messages.send(player,"economy.confirm-busy");return true;}
            messages.send(player,"economy.pay-preview","player",quote.name(),"amount",BankService.format(amount),"tax",BankService.format(quote.tax()),
                    "net",BankService.format(quote.net()),"raw",amount);return true;
        }
        BankService.PayResult result = bank.pay(player, args[0], amount);
        switch (result.status()) {
            case OK -> {
                messages.send(player, "economy.pay-sent",
                        "player", result.targetName(),
                        "amount", BankService.format(result.amount()),
                        "credited", BankService.format(result.credited()),
                        "balance", BankService.format(result.fromBalance()));
                if (result.tax() > 0L) {
                    messages.send(player, "economy.pay-tax", "tax", BankService.format(result.tax()));
                }
                Player target = Bukkit.getPlayerExact(result.targetName());
                if (target != null) {
                    messages.send(target, "economy.pay-received",
                            "player", player.getName(),
                            "amount", BankService.format(result.credited()));
                }
            }
            case UNAVAILABLE -> messages.send(player, "economy.disabled");
            case UNKNOWN_PLAYER -> messages.send(player, "economy.unknown-player",
                    "player", result.targetName());
            case SELF_TRANSFER -> messages.send(player, "economy.pay-self");
            case NOT_ENOUGH -> messages.send(player, "economy.not-enough-balance",
                    "balance", BankService.format(bank.balanceOf(player.getName())),
                    "amount", BankService.format(result.amount()));
            case LIMIT_EXCEEDED -> messages.send(player, "economy.pay-limit-target");
            case COOLDOWN -> messages.send(player, "economy.pay-cooldown",
                    "seconds", String.valueOf(result.cooldownSeconds()));
            default -> messages.send(player, "economy.invalid-amount");
        }
        return true;
    }

    /* -------------------------------- /baltop --------------------------------- */

    private boolean handleBaltop(CommandSender sender, String[] args) {
        if (!bank.enabled()) {
            messages.send(sender, "economy.disabled");
            return true;
        }
        if (!sender.hasPermission("chrdkregister.baltop")) {
            messages.send(sender, "common.no-permission");
            return true;
        }
        List<BankStore.Entry> top = bank.baltop();
        if (top.isEmpty()) {
            messages.send(sender, "economy.baltop-empty");
            return true;
        }
        int perPage = 10;
        int pages = Math.max(1, (top.size() + perPage - 1) / perPage);
        int page = 1;
        if (args.length >= 1) {
            try {
                page = Math.clamp(Integer.parseInt(args[0]), 1, pages);
            } catch (NumberFormatException ignored) {
                page = 1;
            }
        }
        messages.send(sender, "economy.baltop-title",
                "total", String.valueOf(top.size()),
                "page", String.valueOf(page),
                "pages", String.valueOf(pages));
        int from = (page - 1) * perPage;
        int to = Math.min(top.size(), from + perPage);
        for (int index = from; index < to; index++) {
            BankStore.Entry entry = top.get(index);
            messages.send(sender, "economy.baltop-line",
                    "place", String.valueOf(index + 1),
                    "player", entry.name(),
                    "balance", BankService.format(entry.balance()));
        }
        if (pages > 1) {
            messages.send(sender, "economy.baltop-footer",
                    "next", String.valueOf(page >= pages ? 1 : page + 1),
                    "pages", String.valueOf(pages));
        }
        return true;
    }

    /* ------------------------------ доп. функции ------------------------------ */

    /** Алмазы в инвентаре: обычные и в блоках по курсу. */
    private long diamondsInInventory(Player player) {
        long total = 0L;
        int blockWorth = bank.settings().blockWorth();
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null) {
                continue;
            }
            if (stack.getType() == org.bukkit.Material.DIAMOND) {
                total += stack.getAmount();
            } else if (stack.getType() == org.bukkit.Material.DIAMOND_BLOCK) {
                total += (long) stack.getAmount() * blockWorth;
            }
        }
        return total;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("pay") && args.length == 1) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(player -> player.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (name.equals("pay") && args.length == 2) {
            return List.of("10", "50", "100", "500", "1000");
        }
        if(name.equals("pay")&&args.length==3) return List.of("preview","confirm");
        if(name.equals("bal")&&args.length==3&&args[0].equalsIgnoreCase("withdraw")) return List.of("confirm");
        if (name.equals("baltop")) {
            return List.of("1", "2", "3");
        }
        if (args.length == 1) {
            List<String> options = new ArrayList<>(BAL_SUBCOMMANDS);
            if (sender.hasPermission("chrdkregister.bal.others")) {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    options.add(online.getName());
                }
            }
            return options.stream()
                    .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("deposit")) {
            return List.of("all", "hand", "100");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("withdraw")) {
            return List.of("100", "500", "1000");
        }
        return List.of();
    }
}
