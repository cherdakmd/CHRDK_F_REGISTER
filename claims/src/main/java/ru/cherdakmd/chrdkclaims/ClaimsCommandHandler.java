package ru.cherdakmd.chrdkclaims;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** /claim — покупка, управление, казна, продажа, карта приватов, меню и телепорт. */
public final class ClaimsCommandHandler implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("help", "menu", "tp", "info", "map", "list",
            "unclaim", "add", "remove", "deposit", "withdraw", "balance", "sell", "sellback",
            "buy", "transfer", "forsale", "borders", "near", "notices", "greeting", "rent", "clan", "budget");

    private final ClaimsPlugin plugin;

    public ClaimsCommandHandler(ClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean quote=args.length>0&&(args[0].equalsIgnoreCase("budget")||args[0].equalsIgnoreCase("sellback"));
        if(!(quote?plugin.financeReadReady():plugin.financeReady())&&(args.length==0||!args[0].equalsIgnoreCase("recovery"))) { plugin.messages().send(sender,"finance.unavailable");return true; }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.messages().plain("player-only"));
            return true;
        }
        if (!player.hasPermission("chrdkclaims.use") && !player.hasPermission("chrdkclaims.admin")) {
            plugin.messages().send(player, "no-permission");
            return true;
        }
        if (args.length == 0) {
            buy(player);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> plugin.messages().send(player, "finance.usage");
            case "menu" -> menu(player, args);
            case "tp" -> teleport(player, args);
            case "info" -> info(player, args);
            case "map" -> map(player, args);
            case "list" -> list(player, args);
            case "forsale" -> forsale(player, args);
            case "borders" -> borders(player);
            case "unclaim" -> unclaim(player, args);
            case "near" -> near(player, args);
            case "notices" -> notices(player, args);
            case "greeting" -> greeting(player, args);
            case "rent" -> rental(player, args);
            case "clan" -> { if (ready(player)) service().clanSharing(player, args); }
            case "sell" -> sell(player, args);
            case "budget" -> {
                if(!ready(player)) return true;
                int days=3;
                try { if(args.length>2) throw new NumberFormatException(); if(args.length==2) days=Integer.parseInt(args[1]); }
                catch(NumberFormatException e) {plugin.messages().send(player,"finance.budget-usage");return true;}
                if(days<1||days>30) {plugin.messages().send(player,"finance.budget-usage");return true;}
                service().budget(player,days);
            }
            case "sellback" -> {
                if (ready(player)) {
                    service().sellback(player,args.length==2&&args[1].equalsIgnoreCase("confirm"));
                }
            }
            case "buy" -> {
                if (ready(player)) {
                    Integer[] parsed = parseCoords(player, args);
                    if (parsed == null) {
                        return true;
                    }
                    if (parsed.length == 2) {
                        service().buyFromPlayer(player, parsed[0], parsed[1]);
                    } else {
                        service().buyFromPlayer(player);
                    }
                }
            }
            case "transfer" -> transfer(player, args);
            case "add" -> members(player, args, true);
            case "remove" -> members(player, args, false);
            case "deposit" -> deposit(player, args);
            case "withdraw" -> withdraw(player, args);
            case "balance" -> balance(player);
            default -> plugin.messages().send(player, "unknown-sub");
        }
        return true;
    }

    private void rental(Player player, String[] args) {
        if (!ready(player)) return;
        if (args.length == 1) {
            Claim claim = service().claimHere(player);
            if (claim == null) plugin.messages().send(player, "unclaim-none");
            else service().rentalInfo(player, claim);
            plugin.messages().send(player, "finance.rent-usage");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("stop") && args.length == 2) {
            service().rental(player, action, 0); return;
        }
        if (action.equals("off") && args.length == 2) { service().rental(player, "offer", 0); return; }
        if ((action.equals("offer") || action.equals("take")) && args.length == 3) {
            try {
                int price = Integer.parseInt(args[2]);
                if (price > 0 && price <= ClaimRentals.MAX_PRICE) { service().rental(player, action, price); return; }
            } catch (NumberFormatException ignored) { }
        }
        plugin.messages().send(player, "finance.rent-usage");
    }

    private ClaimsService service() {
        return plugin.service();
    }

    private void buy(Player player) {
        if (!ready(player)) {
            return;
        }
        service().buy(player);
    }

    /** Покупка/казна/продажа — только для авторизованных. */
    private boolean ready(Player player) {
        if (!service().isAllowed(player)) {
            plugin.messages().send(player, "need-auth");
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- view --

    /** /claim menu [страница] — графическое меню приватов. */
    private void menu(Player player, String[] args) {
        if (!ready(player)) {
            return;
        }
        plugin.menu().open(player, ClaimMenuModel.Tab.MINE, pageArg(args, 1));
    }

    /**
     * /claim tp [x z] — телепорт к привату. Без координат (и с одним числом —
     * номером страницы) открывает меню, из которого телепорт делается кликом.
     */
    private void teleport(Player player, String[] args) {
        if (!ready(player)) {
            return;
        }
        if (args.length < 3) {
            if (!plugin.config().menuEnabled) {
                plugin.messages().send(player, "tp-usage");
                return;
            }
            plugin.menu().open(player, ClaimMenuModel.Tab.MINE, pageArg(args, 1));
            return;
        }
        Integer[] parsed = parseCoords(player, args);
        if (parsed == null || parsed.length < 2) {
            return;
        }
        Claim claim = plugin.store().claimAt(player.getWorld().getName(), parsed[0], parsed[1]);
        if (claim == null) {
            plugin.messages().send(player, "tp-no-claim",
                    "x", String.valueOf(parsed[0]), "z", String.valueOf(parsed[1]));
            return;
        }
        plugin.teleports().request(player, claim);
    }

    private void info(Player player, String[] args) {
        String world = player.getWorld().getName();
        int chunkX;
        int chunkZ;
        Integer[] parsed = parseCoords(player, args);
        if (parsed == null) {
            return;
        }
        if (parsed.length == 2) {
            chunkX = parsed[0];
            chunkZ = parsed[1];
        } else {
            Chunk here = player.getLocation().getChunk();
            chunkX = here.getX();
            chunkZ = here.getZ();
        }
        plugin.messages().send(player, "info-header",
                "x", String.valueOf(chunkX),
                "z", String.valueOf(chunkZ),
                "world", world);
        Claim claim = plugin.store().claimAt(world, chunkX, chunkZ);
        if (claim == null) {
            plugin.messages().send(player, "info-free",
                    "price", String.valueOf(plugin.config().priceIn(world)),
                    "upkeep", String.valueOf(plugin.config().upkeepIn(world)));
            return;
        }
        service().rentalInfo(player, claim);
        plugin.messages().send(player, "info-owner",
                "owner", claim.owner,
                "members-count", String.valueOf(claim.members.size()),
                "members-max", String.valueOf(plugin.config().membersPerChunk));
        plugin.messages().send(player, "info-paid",
                "paid", service().formatDate(claim.paidUntil),
                "debt", claim.debtDays > 0
                        ? plugin.messages().mini("info-debt",
                                "debt-days", String.valueOf(claim.debtDays))
                        : "");
        if (claim.debtDays <= 0) {
            long left = ClaimMath.daysUntil(claim.paidUntil, System.currentTimeMillis());
            if (left > 0) {
                plugin.messages().send(player, "info-left", "days", String.valueOf(left));
            } else {
                plugin.messages().send(player, "info-left-today");
            }
        }
        if (claim.salePrice > 0) {
            plugin.messages().send(player, "info-sale", "price", String.valueOf(claim.salePrice));
        }
        if (claim.members.isEmpty()) {
            plugin.messages().send(player, "info-members-none");
        } else {
            List<String> rendered = new ArrayList<>();
            for (String member : claim.members) {
                rendered.add(member + " (" + service().roleName(claim.roleOf(member)) + ")");
            }
            plugin.messages().send(player, "info-members",
                    "members", String.join(", ", rendered));
        }
        if (claim.hasGreeting()) {
            plugin.messages().send(player, "info-greeting", "text", claim.greeting);
        }
        plugin.messages().send(player, "clan-status", "id", claim.clanId().isEmpty() ? "—" : claim.clanId(),
                "role", claim.clanId().isEmpty() ? "—" : service().roleName(claim.clanRole()));
        if (claim.isOwner(player.getName())) {
            plugin.messages().send(player, "info-you-owner");
        } else if (claim.canUse(player.getName())) {
            plugin.messages().send(player, "info-you-role",
                    "role", service().roleName(claim.roleOf(player.getName())));
        }
    }

    private void map(Player player, String[] args) {
        int radius = plugin.config().mapRadius;
        if (args.length > 1) {
            Integer parsed = parseAmount(args[1]);
            if (parsed == null) {
                plugin.messages().send(player, "map-usage",
                        "min", String.valueOf(ClaimMap.MIN_RADIUS),
                        "max", String.valueOf(ClaimMap.MAX_RADIUS));
                return;
            }
            radius = Math.min(ClaimMap.MAX_RADIUS, Math.max(ClaimMap.MIN_RADIUS, parsed));
        }
        Chunk chunk = player.getLocation().getChunk();
        String me = player.getName();
        plugin.messages().send(player, "map-header");
        for (String row : ClaimMap.render(chunk.getX(), chunk.getZ(), radius,
                (cx, cz) -> {
                    Claim claim = plugin.store().claimAt(chunk.getWorld().getName(), cx, cz);
                    if (claim == null) {
                        return ClaimMap.Cell.FREE;
                    }
                    return new ClaimMap.Cell(true, claim.canBuild(me), claim.salePrice > 0);
                })) {
            plugin.messages().send(player, "map-line", "row", row);
        }
        plugin.messages().send(player, "map-legend");
    }

    private void list(Player player, String[] args) {
        List<Claim> claims = ClaimTerritories.representatives(service().ownedSorted(player.getName()));
        if (claims.isEmpty()) {
            plugin.messages().send(player, "list-none");
            return;
        }
        int pages = Pagination.pageCount(claims.size(), Pagination.PAGE_SIZE);
        int page = Pagination.clampPage(pageArg(args, 1), pages);
        plugin.messages().send(player, "list-header",
                "count", String.valueOf(claims.size()),
                "chunks", plugin.store().count(player.getName()),
                "limit", String.valueOf(service().limitOf(player.getName())));
        for (Claim claim : Pagination.slice(claims, page, Pagination.PAGE_SIZE)) {
            plugin.messages().send(player, "list-line",
                    "world", claim.world, "x", String.valueOf(claim.x),
                    "z", String.valueOf(claim.z), "chunks", plugin.store().territory(claim).size(),
                    "paid", service().formatDate(claim.paidUntil),
                    "sale", claim.salePrice > 0
                            ? plugin.messages().mini("list-sale",
                                    "price", String.valueOf(claim.salePrice))
                            : "",
                    "debt", claim.debtDays > 0
                            ? plugin.messages().mini("list-debt",
                                    "days", String.valueOf(claim.debtDays))
                            : "");
        }
        sendPageLine(player, "list-page", page, pages, "list");
    }

    /** Витрина чужих чанков, выставленных на продажу: свой мир и ближние — первыми. */
    private void forsale(Player player, String[] args) {
        Chunk chunk = player.getLocation().getChunk();
        String world = chunk.getWorld().getName();
        List<ClaimMarket.Offer> offers = new ArrayList<>();
        for (Claim claim : ClaimTerritories.representatives(plugin.store().all())) {
            if (claim.salePrice > 0 && plugin.config().worldEnabled(claim.world)) {
                offers.add(new ClaimMarket.Offer(claim.world, claim.x, claim.z,
                        claim.owner, claim.salePrice));
            }
        }
        if (offers.isEmpty()) {
            plugin.messages().send(player, "forsale-none");
            return;
        }
        List<ClaimMarket.Offer> sorted = ClaimMarket.sorted(offers, world, chunk.getX(), chunk.getZ());
        int pages = Pagination.pageCount(sorted.size(), Pagination.PAGE_SIZE);
        int page = Pagination.clampPage(pageArg(args, 1), pages);
        plugin.messages().send(player, "forsale-header", "count", String.valueOf(sorted.size()));
        for (ClaimMarket.Offer offer : Pagination.slice(sorted, page, Pagination.PAGE_SIZE)) {
            int distance = ClaimMarket.distance(offer, world, chunk.getX(), chunk.getZ());
            plugin.messages().send(player, "forsale-line",
                    "world", offer.world(), "x", String.valueOf(offer.x()),
                    "z", String.valueOf(offer.z()), "price", String.valueOf(offer.price()),
                    "owner", offer.owner(),
                    "chunks", plugin.store().territory(plugin.store().claimAt(offer.world(), offer.x(), offer.z())).size(),
                    "dist", distance == Integer.MAX_VALUE
                            ? ""
                            : plugin.messages().mini("forsale-distance",
                                    "dist", String.valueOf(distance)));
        }
        sendPageLine(player, "forsale-page", page, pages, "forsale");
    }

    /** Подсветка границ текущего чанка частицами (только для этого игрока). */
    private void borders(Player player) {
        boolean enabled = plugin.toggleBorders(player);
        if (!enabled) {
            plugin.messages().send(player, "borders-off");
            return;
        }
        Claim claim = service().claimHere(player);
        String seconds = String.valueOf(plugin.config().borderSeconds);
        if (claim == null) {
            plugin.messages().send(player, "borders-on-free", "seconds", seconds);
        } else {
            plugin.messages().send(player, "borders-on", "seconds", seconds, "owner", claim.owner);
        }
    }

    /** /claim unclaim [all [confirm]] — снять текущий приват или все сразу. */
    private void unclaim(Player player, String[] args) {
        if (!ready(player)) {
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("all")) {
            if (args.length >= 3 && args[2].equalsIgnoreCase("confirm")) {
                service().unclaimAll(player);
            } else {
                int count = plugin.store().count(player.getName());
                if (count == 0) {
                    plugin.messages().send(player, "unclaim-all-none");
                    return;
                }
                plugin.messages().send(player, "finance.unclaim-all-confirm",
                        "count", String.valueOf(count));
            }
            return;
        }
        service().unclaim(player);
    }

    /** /claim near [радиус] — чужие приваты вокруг, ближние первыми. */
    private void near(Player player, String[] args) {
        int radius = ClaimNear.DEFAULT_RADIUS;
        if (args.length > 1) {
            Integer parsed = parseAmount(args[1]);
            if (parsed == null) {
                plugin.messages().send(player, "near-usage",
                        "min", String.valueOf(ClaimNear.MIN_RADIUS),
                        "max", String.valueOf(ClaimNear.MAX_RADIUS),
                        "default", String.valueOf(ClaimNear.DEFAULT_RADIUS));
                return;
            }
            radius = ClaimNear.clampRadius(parsed);
        }
        Chunk chunk = player.getLocation().getChunk();
        ClaimNear.Result result = ClaimNear.search(plugin.store().all(),
                chunk.getWorld().getName(), chunk.getX(), chunk.getZ(), radius, player.getName());
        if (result.entries().isEmpty()) {
            plugin.messages().send(player, "near-none", "radius", String.valueOf(radius));
            return;
        }
        plugin.messages().send(player, "near-header",
                "count", String.valueOf(result.total()), "radius", String.valueOf(radius));
        for (ClaimNear.Entry entry : result.entries()) {
            Claim claim = entry.claim();
            plugin.messages().send(player, "near-line",
                    "x", String.valueOf(claim.x), "z", String.valueOf(claim.z),
                    "owner", claim.owner, "dist", String.valueOf(entry.distance()),
                    "sale", claim.salePrice > 0
                            ? plugin.messages().mini("near-sale",
                                    "price", String.valueOf(claim.salePrice))
                            : "");
        }
    }

    /** /claim notices [on|off] — личный выключатель уведомлений о границах. */
    private void notices(Player player, String[] args) {
        if (!player.hasPermission("chrdkclaims.notices")
                && !player.hasPermission("chrdkclaims.admin")) {
            plugin.messages().send(player, "no-permission");
            return;
        }
        String mode = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        boolean enabled;
        if (mode.isEmpty()) {
            enabled = plugin.toggleNotices(player);
        } else if (mode.equals("on") || mode.equals("вкл") || mode.equals("включить")) {
            enabled = plugin.setNotices(player, true);
        } else if (mode.equals("off") || mode.equals("выкл") || mode.equals("выключить")) {
            enabled = plugin.setNotices(player, false);
        } else {
            plugin.messages().send(player, "notices-usage");
            return;
        }
        plugin.messages().send(player, enabled ? "notices-on" : "notices-off");
    }

    /** /claim greeting [текст|снять] — приветствие гостям текущего привата. */
    private void greeting(Player player, String[] args) {
        if (!ready(player)) {
            return;
        }
        if (args.length < 2) {
            Claim claim = service().claimHere(player);
            if (claim == null) {
                plugin.messages().send(player, "unclaim-none");
                return;
            }
            if (!claim.isOwner(player.getName())) {
                plugin.messages().send(player, "greeting-not-yours");
                return;
            }
            if (claim.hasGreeting()) {
                plugin.messages().send(player, "greeting-current", "text", claim.greeting);
            } else {
                plugin.messages().send(player, "greeting-usage",
                        "max", String.valueOf(Claim.MAX_GREETING));
            }
            return;
        }
        String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        if (text.equalsIgnoreCase("снять") || text.equalsIgnoreCase("off")
                || text.equals("-")) {
            service().setGreeting(player, "");
            return;
        }
        if (text.length() > Claim.MAX_GREETING) {
            plugin.messages().send(player, "greeting-invalid",
                    "max", String.valueOf(Claim.MAX_GREETING));
            return;
        }
        service().setGreeting(player, text);
    }

    /**
     * Координаты чанка из аргументов {@code <x> <z>}: пустой массив — аргументов нет
     * (действуем в текущем чанке), {@code null} — координаты некорректны и игрок уже
     * получил сообщение.
     */
    private Integer[] parseCoords(Player player, String[] args) {
        if (args.length < 3) {
            return new Integer[0];
        }
        try {
            int x = Integer.parseInt(args[1]);
            int z = Integer.parseInt(args[2]);
            return new Integer[]{x, z};
        } catch (NumberFormatException exception) {
            plugin.messages().send(player, "coords-invalid");
            return null;
        }
    }

    /** Номер страницы из аргумента; мусор и отсутствие аргумента — первая страница. */
    private static int pageArg(String[] args, int index) {
        if (args.length <= index) {
            return 1;
        }
        try {
            return Integer.parseInt(args[index]);
        } catch (NumberFormatException exception) {
            return 1;
        }
    }

    /** Строка «страница N/M · далее» с кликабельным переходом; на одной странице не нужна. */
    private void sendPageLine(Player player, String key, int page, int pages, String command) {
        if (pages <= 1) {
            return;
        }
        int next = page >= pages ? 1 : page + 1;
        plugin.messages().send(player, key,
                "page", String.valueOf(page),
                "pages", String.valueOf(pages),
                "next", String.valueOf(next),
                "command", command);
    }

    private void balance(Player player) {
        if (!plugin.store().fundsReady(player.getName())) { plugin.messages().send(player,"finance.unavailable"); return; }
        List<Claim> claims = plugin.store().ofOwner(player.getName());
        boolean debt = claims.stream().anyMatch(claim -> claim.debtDays > 0);
        int treasury = plugin.store().treasuryOf(player.getName());
        String here = player.getWorld().getName();
        plugin.messages().send(player, "finance.balance-line",
                "treasury", String.valueOf(treasury), "physical", DiamondItems.count(player.getInventory()),
                "claims", String.valueOf(claims.size()),
                "limit", String.valueOf(service().limitOf(player.getName())),
                "upkeep", String.valueOf(plugin.config().upkeepIn(here)),
                "debt", debt ? plugin.messages().mini("balance-debt") : "");
        long earliest = Long.MAX_VALUE;
        long need = 0;
        for (Claim claim : claims) {
            if (!plugin.config().worldEnabled(claim.world)) {
                continue;
            }
            earliest = Math.min(earliest, claim.paidUntil);
            need += (long) plugin.config().upkeepIn(claim.world) * (1L + claim.debtDays);
        }
        if (!claims.isEmpty() && earliest != Long.MAX_VALUE) {
            plugin.messages().send(player, "finance.balance-due",
                    "date", service().formatDate(earliest),
                    "balance", String.valueOf(treasury),
                    "need", String.valueOf(Math.min(Integer.MAX_VALUE, need)));
        }
    }

    // ------------------------------------------------------------ mutations --

    private void sell(Player player, String[] args) {
        if (!ready(player)) {
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(player, "sell-usage");
            return;
        }
        if (args[1].equalsIgnoreCase("снять") || args[1].equalsIgnoreCase("off")) {
            service().setSale(player, 0);
            return;
        }
        Integer price = parseAmount(args[1]);
        if (price == null || price < 1 || price > 1_000_000) {
            plugin.messages().send(player, "sell-invalid", "max", "1000000");
            return;
        }
        service().setSale(player, price);
    }

    private void transfer(Player player, String[] args) {
        if (!ready(player)) {
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(player, "transfer-usage");
            return;
        }
        service().transferHere(player, args[1]);
    }

    private void members(Player player, String[] args, boolean add) {
        if (args.length < 2) {
            plugin.messages().send(player, "finance.usage");
            return;
        }
        if (!ready(player)) {
            return;
        }
        String name = args[1];
        Claim.Role role = Claim.Role.BUILD;
        boolean everywhere = false;
        for (int index = 2; index < args.length; index++) {
            String token = args[index];
            if (token.equalsIgnoreCase("все") || token.equalsIgnoreCase("all")) {
                everywhere = true;
                continue;
            }
            if (add) {
                Claim.Role parsed = ClaimStore.parseRole(token);
                if (parsed == null) {
                    plugin.messages().send(player, "role-unknown", "role", token);
                    return;
                }
                role = parsed;
            }
        }
        if (add && name.equalsIgnoreCase(player.getName())) {
            plugin.messages().send(player, "add-self");
            return;
        }
        if (add) {
            service().addMember(player, name, everywhere, role);
        } else {
            service().removeMember(player, name, everywhere);
        }
    }

    private void deposit(Player player, String[] args) {
        if (ready(player)) plugin.messages().send(player, "finance.bank-commands");
    }
    private void withdraw(Player player, String[] args) {
        if (ready(player)) plugin.messages().send(player, "finance.bank-commands");
    }

    private Integer parseAmount(String raw) {
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    // ------------------------------------------------------------------ tab --

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label,
                                      String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String sub : SUBS) {
                if (sub.startsWith(prefix)) {
                    result.add(sub);
                }
            }
            return result;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("add")
                || args[0].equalsIgnoreCase("remove")
                || args[0].equalsIgnoreCase("transfer"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    result.add(online.getName());
                }
            }
            return result;
        }
        if(args.length==2&&(args[0].equalsIgnoreCase("budget")||args[0].equalsIgnoreCase("sellback"))) {
            var values=args[0].equalsIgnoreCase("budget")?List.of("1","3","7","14","30"):List.of("confirm");
            return values.stream().filter(s->s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("clan")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return List.of("access", "containers", "build", "off").stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("rent")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return List.of("offer", "off", "take", "stop").stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("sell")) {
            result.add("снять");
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("unclaim")) {
            result.add("all");
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("notices")) {
            result.add("on");
            result.add("off");
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("near")) {
            result.add(String.valueOf(ClaimNear.DEFAULT_RADIUS));
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("greeting")) {
            result.add("снять");
            return result;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("unclaim")
                && args[1].equalsIgnoreCase("all")) {
            result.add("confirm");
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("deposit")) {
            result.add("все");
            return result;
        }
        return result;
    }
}
