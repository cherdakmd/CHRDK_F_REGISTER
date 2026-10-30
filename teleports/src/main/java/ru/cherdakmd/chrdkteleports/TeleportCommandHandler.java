package ru.cherdakmd.chrdkteleports;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Все команды аддона: /rtp, /tpa, /tpahere, /tpaccept, /tpdeny, /tpcancel,
 * /home, /sethome, /delhome, /homes, /tpadmin. Общие ворота: авторизация через API ядра,
 * ограничение миров, кулдауны, оплата алмазами (VIP-скидка).
 */
public final class TeleportCommandHandler implements TabExecutor {

    private static final Set<Material> DANGEROUS_GROUND = Set.of(
            Material.WATER, Material.LAVA, Material.CACTUS, Material.MAGMA_BLOCK,
            Material.POWDER_SNOW, Material.FIRE, Material.SOUL_FIRE,
            Material.LAVA_CAULDRON, Material.SWEET_BERRY_BUSH, Material.POINTED_DRIPSTONE);

    private static final String COOLDOWN_RTP = "rtp";
    private static final String COOLDOWN_TPA = "tpa";
    private static final String COOLDOWN_HOME = "home";

    private final TeleportsPlugin plugin;
    private final Random random = new Random();
    private final ru.cherdakmd.chrdkregister.api.EconomyConfirmations confirmations = new ru.cherdakmd.chrdkregister.api.EconomyConfirmations();
    private record HomeQuote(String key, String value, String previous, int price) { }

    public TeleportCommandHandler(TeleportsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (plugin.coreApi() == null) {
            plugin.messages().send(sender, "tp.no-api");
            return true;
        }
        String name = command.getName().toLowerCase(Locale.ROOT);
        if ("tpadmin".equals(name)) {
            return handleAdmin(sender, args);
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "tp.player-only");
            return true;
        }
        if (!player.hasPermission("chrdktele.use")) { plugin.messages().send(player,"tp.permission"); return true; }
        if (!plugin.coreApi().isAuthenticated(player)) {
            plugin.messages().send(player, "tp.need-auth");
            return true;
        }
        return switch (name) {
            case "rtp" -> handleRtp(player);
            case "tpa" -> handleTpa(player, args, false);
            case "tpahere" -> handleTpa(player, args, true);
            case "tpaccept" -> handleAccept(player);
            case "tpdeny" -> handleDeny(player);
            case "tpcancel" -> handleCancel(player);
            case "sethome" -> handleSetHome(player, args);
            case "delhome" -> handleDelHome(player, args);
            case "homes" -> args.length==1&&args[0].equalsIgnoreCase("prices") ? prices(player) : handleHomes(player);
            case "home" -> handleHome(player, args);
            default -> false;
        };
    }

    /* ------------------------------- общие проверки ------------------------------- */

    /** Только учтённые ядром оплаченные пакеты; LP-право не подтверждает покупку. */
    private int extraHomes(Player player) {
        var api = plugin.coreApi();
        return api == null ? 0 : Math.max(0, api.entitlementCount(player.getName(), "homes-plus"));
    }

    /** Ступень донатного ранга: 0 — без ранга, 1..5 (1.4.167). */
    private int rank(Player player) {
        return plugin.coreApi() == null ? 0 : plugin.coreApi().rankLevel(player.getName());
    }

    /** false = проверяющий остановлен (сообщение уже отправлено). */
    private boolean gates(Player player, String cooldownKey, int cooldownSeconds) {
        if (plugin.payments().occupied(player.getUniqueId())) { plugin.messages().send(player,"finance.service-wait"); return false; }
        if (!plugin.config().worldAllowed(player.getWorld().getName())) {
            plugin.messages().send(player, "tp.world-denied");
            return false;
        }
        long now = System.currentTimeMillis();
        long left = plugin.teleports().cooldownLeft(cooldownKey, player.getUniqueId(),
                cooldownSeconds, now);
        if (left > 0) {
            plugin.messages().send(player, "tp.cooldown", "seconds", left);
            return false;
        }
        if (plugin.teleports().isBusy(player.getUniqueId())) {
            plugin.messages().send(player, "tp.in-progress");
            return false;
        }
        return true;
    }

    /**
     * VIP-суффикс по messages: пусто или фраза с процентом.
     *
     * <p>Возвращается как {@link Messages.Trusted}: суффикс содержит MiniMessage
     * (серый цвет скобок), а обычная строка-заполнитель экранируется — и игрок
     * видел бы в чате сырые {@code <gray>…</gray>}. Тип обязывает передавать
     * разметку явно, чтобы такая ошибка не повторялась.</p>
     */
    private Messages.Trusted vipSuffix(Player player) {
        int rank = rank(player);
        int discount = plugin.config().discountFor(rank);
        if (rank > 0 && discount > 0) {
            return Messages.trusted(plugin.messages().raw("tp.vip-suffix", "percent", discount));
        }
        return Messages.trusted(plugin.messages().raw("tp.vip-suffix-empty"));
    }

    private int chargedCost(Player player, int baseCost) {
        int rank = rank(player);
        return PriceCalc.apply(baseCost, rank > 0, plugin.config().discountFor(rank));
    }

    private boolean prices(Player player) {
        var c=plugin.config();
        plugin.messages().send(player,"finance.personal-prices","rank",rank(player),"discount",c.discountFor(rank(player)),
                "rtp",chargedCost(player,c.costRtp()),"tpa",chargedCost(player,c.costTpa()),"home",chargedCost(player,c.costHome()),
                "sethome",chargedCost(player,c.costSethome()));return true;
    }

    /* ------------------------------- /rtp ------------------------------- */

    private boolean handleRtp(Player player) {
        TpConfig config = plugin.config();
        if (!gates(player, COOLDOWN_RTP, config.rtpCooldownSeconds())) return true;
        plugin.messages().send(player, "rtp.searching", "radius", config.rtpRadius());
        int cost=chargedCost(player,config.costRtp());
        plugin.messages().send(player,"finance.service-quote","cost",cost);
        boolean started = plugin.teleports().start(player, () -> executeRtp(player,cost));
        if (!started) {
            plugin.messages().send(player, "tp.in-progress");
        }
        return true;
    }

    private void executeRtp(Player player,int cost) {
        TpConfig config = plugin.config();
        World world = config.rtpWorld().isBlank()
                ? player.getWorld() : Bukkit.getWorld(config.rtpWorld());
        if (world == null) {
            plugin.messages().send(player, "rtp.world-missing", "world", config.rtpWorld());
            return;
        }
        long centerX = config.rtpUseSpawnCenter() ? world.getSpawnLocation().getBlockX() : config.rtpCenterX();
        long centerZ = config.rtpUseSpawnCenter() ? world.getSpawnLocation().getBlockZ() : config.rtpCenterZ();
        for (int attempt = 0; attempt < config.rtpMaxAttempts(); attempt++) {
            long[] spot = RandomSpotFinder.pick(random, centerX, centerZ,
                    config.rtpRadius(), config.rtpMinRadius());
            int x = Math.toIntExact(Math.max(-30_000_000L, Math.min(30_000_000L, spot[0])));
            int z = Math.toIntExact(Math.max(-30_000_000L, Math.min(30_000_000L, spot[1])));
            int y = world.getHighestBlockYAt(x, z);
            Material ground = world.getBlockAt(x, y, z).getType();
            if (ground.isAir() || DANGEROUS_GROUND.contains(ground) || y <= world.getMinHeight()) {
                continue;
            }
            Location destination = new Location(world, x + 0.5, y + 1, z + 0.5);
            if (!paidTeleport(player, player, cost, "rtp", destination)) return;
            plugin.teleports().markCooldown(COOLDOWN_RTP, player.getUniqueId(), System.currentTimeMillis());
            plugin.messages().send(player, "rtp.done",
                    "x", x, "z", z,
                    "cost", cost,
                    "vip-suffix", vipSuffix(player));
            return;
        }
        plugin.messages().send(player, "rtp.failed");
    }

    /* ------------------------------- /tpa ------------------------------- */

    private boolean handleTpa(Player player, String[] args, boolean here) {
        if (args.length < 1) {
            plugin.messages().send(player, "tp.usage-tpa");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            plugin.messages().send(player, "tpa.offline", "player", args[0]);
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            plugin.messages().send(player, "tpa.self");
            return true;
        }
        if (!plugin.coreApi().isAuthenticated(target)) {
            plugin.messages().send(player, "tpa.target-unverified", "player", target.getName());
            return true;
        }
        if (!gates(player, COOLDOWN_TPA, plugin.config().tpaCooldownSeconds())) return true;
        long now = System.currentTimeMillis();
        TpaRequests.CreateResult result = plugin.tpaRequests().create(
                player.getUniqueId(), target.getUniqueId(), here, now,
                plugin.config().tpaTimeoutSeconds(),chargedCost(player,plugin.config().costTpa()));
        switch (result) {
            case SELF -> plugin.messages().send(player, "tpa.self");
            case OUTGOING_EXISTS -> plugin.messages().send(player, "tpa.outgoing-exists");
            case INCOMING_EXISTS -> plugin.messages().send(player, "tpa.incoming-exists",
                    "player", target.getName());
            case OK -> {
                if (here) {
                    plugin.messages().send(player, "tpa.sent-here", "player", target.getName(),
                            "cost", chargedCost(player, plugin.config().costTpa()));
                    plugin.messages().send(target, "tpa.received-here", "player", player.getName(),
                            "seconds", plugin.config().tpaTimeoutSeconds());
                } else {
                    plugin.messages().send(player, "tpa.sent", "player", target.getName(),
                            "cost", chargedCost(player, plugin.config().costTpa()));
                    plugin.messages().send(target, "tpa.received", "player", player.getName(),
                            "seconds", plugin.config().tpaTimeoutSeconds());
                }
            }
        }
        return true;
    }

    private boolean handleAccept(Player player) {
        TpaRequests.Request request = plugin.tpaRequests().accept(
                player.getUniqueId(), System.currentTimeMillis());
        if (request == null) {
            plugin.messages().send(player, "tpa.no-request");
            return true;
        }
        Player requester = Bukkit.getPlayer(request.requester());
        Player target = Bukkit.getPlayer(request.target());
        if (requester == null) {
            plugin.messages().send(player, "tpa.payer-offline");
            return true;
        }
        if (target == null) {
            return true; // сам же отключён? не случится, но пусть молча
        }
        Player mover = request.here() ? target : requester;
        Player moverAnchor = request.here() ? requester : target;
        if (plugin.teleports().isBusy(mover.getUniqueId())) {
            plugin.messages().send(mover, "tp.in-progress");
            return true;
        }
        plugin.messages().send(target, "tpa.accepted-target", "player", requester.getName());
        plugin.messages().send(requester, "tpa.accepted-requester");
        boolean started = plugin.teleports().start(mover, () -> executeTpa(request, mover, moverAnchor));
        if (!started) {
            plugin.messages().send(mover, "tp.in-progress");
        }
        return true;
    }

    private void executeTpa(TpaRequests.Request request, Player mover, Player anchor) {
        if (!anchor.isOnline() || !mover.isOnline()) {
            plugin.messages().send(mover, "tpa.payer-offline");
            return;
        }
        if (!plugin.config().worldAllowed(anchor.getWorld().getName())
                || !plugin.config().worldAllowed(mover.getWorld().getName())) {
            plugin.messages().send(mover, "tp.world-denied");
            return;
        }
        Player payer = Bukkit.getPlayer(request.requester());
        if (payer == null) {
            plugin.messages().send(mover, "tpa.payer-offline");
            return;
        }
        if (!plugin.coreApi().isAuthenticated(anchor) || !plugin.coreApi().isAuthenticated(mover)) return;
        if (!paidTeleport(payer, mover, request.quotedCost(), "tpa", anchor.getLocation())) return;
        plugin.teleports().markCooldown(COOLDOWN_TPA, request.requester(), System.currentTimeMillis());
        plugin.messages().send(payer, "tp.teleported",
                "cost", request.quotedCost(),
                "vip-suffix", vipSuffix(payer));
    }

    private boolean handleDeny(Player player) {
        TpaRequests.Request request = plugin.tpaRequests().deny(
                player.getUniqueId(), System.currentTimeMillis());
        if (request == null) {
            plugin.messages().send(player, "tpa.no-request");
            return true;
        }
        plugin.messages().send(player, "tpa.denied-target",
                "player", TeleportsPlugin.displayName(request.requester()));
        Player requester = Bukkit.getPlayer(request.requester());
        if (requester != null) {
            plugin.messages().send(requester, "tpa.denied-requester",
                    "player", TeleportsPlugin.displayName(player.getUniqueId()));
        }
        return true;
    }

    private boolean handleCancel(Player player) {
        if (plugin.teleports().isBusy(player.getUniqueId())) { plugin.teleports().cancel(player.getUniqueId(),"tpa.cancelled"); return true; }
        TpaRequests.Request request = plugin.tpaRequests().cancelOutgoing(
                player.getUniqueId(), System.currentTimeMillis());
        if (request == null) {
            plugin.messages().send(player, "tpa.no-request");
            return true;
        }
        plugin.messages().send(player, "tpa.cancelled");
        return true;
    }

    /* ------------------------------- дома ------------------------------- */

    private boolean handleSetHome(Player player, String[] args) {
        if (args.length < 1 || args.length > 2 || (args.length==2&&!args[1].equalsIgnoreCase("confirm"))) {
            plugin.messages().send(player, "tp.usage-home");
            return true;
        }
        if (plugin.payments().occupied(player.getUniqueId())) { plugin.messages().send(player,"finance.service-wait"); return true; }
        String name = args[0];
        if (HomeStore.isBedName(name)) {
            plugin.messages().send(player, "home.bed-reserved");
            return true;
        }
        if (!HomeStore.isValidName(name)) {
            plugin.messages().send(player, "home.bad-name");
            return true;
        }
        HomeStore store = plugin.store();
        boolean exists = store.get(player.getUniqueId(), HomeStore.normalize(name)) != null;
        int max = plugin.config().homeLimit(rank(player), extraHomes(player));
        // Кровать слот не занимает: считаем только точки, поставленные игроком.
        if (!exists && store.countPlayerHomes(player.getUniqueId()) >= max) {
            plugin.messages().send(player, "home.limit", "max", max);
            return true;
        }
        if (!plugin.config().worldAllowed(player.getWorld().getName())) {
            plugin.messages().send(player, "tp.world-denied");
            return true;
        }
        Location loc = player.getLocation();
        HomeLocation point = new HomeLocation(name,player.getWorld().getName(),loc.getX(),loc.getY(),loc.getZ(),loc.getYaw(),loc.getPitch());
        int price=chargedCost(player,plugin.config().costSethome());
        if (price > 0) {
            String key=HomeStore.financeKey(player.getUniqueId(),name);
            var quote=new HomeQuote(key,HomeStore.financeValue(point),String.valueOf(store.get(player.getUniqueId(),HomeStore.normalize(name))),price);
            boolean confirmed=args.length==2&&args[1].equalsIgnoreCase("confirm")
                    && confirmations.consume(player.getUniqueId(),player,quote,System.nanoTime());
            if(!confirmed) {
                if(!confirmations.offer(player.getUniqueId(),player,quote,System.nanoTime())) {plugin.messages().send(player,"finance.confirm-busy");return true;}
                plugin.messages().send(player,"finance.home-preview","name",name,"cost",price,"replace",exists);return true;
            }
        }
        if (!plugin.payments().putValue(player,price,HomeStore.financeKey(player.getUniqueId(),name),HomeStore.financeValue(point))) return true;
        int used = store.countPlayerHomes(player.getUniqueId());
        if (price > 0) {
            plugin.messages().send(player, "home.saved-paid",
                    "name", name, "used", used, "max", max,
                    "cost", price);
        } else {
            plugin.messages().send(player, "home.saved",
                    "name", name, "used", used, "max", max);
        }
        return true;
    }

    private boolean handleDelHome(Player player, String[] args) {
        if (args.length < 1 || args.length > 2 || (args.length==2&&!args[1].equalsIgnoreCase("confirm"))) {
            plugin.messages().send(player, "tp.usage-home");
            return true;
        }
        if (!HomeStore.isValidName(args[0])) {
            plugin.messages().send(player, "home.bad-name");
            return true;
        }
        if (plugin.payments().occupied(player.getUniqueId())) { plugin.messages().send(player,"finance.service-wait"); return true; }
        boolean removed;
        try { removed = plugin.store().remove(player.getUniqueId(), HomeStore.normalize(args[0])); }
        catch (RuntimeException error) { plugin.getLogger().warning("Дом не удалён: "+error); plugin.messages().send(player,"finance.service-wait"); return true; }
        if (!removed) {
            plugin.messages().send(player, "home.not-found", "name", args[0]);
        } else if (HomeStore.isBedName(args[0])) {
            plugin.messages().send(player, "home.bed-deleted");
        } else {
            plugin.messages().send(player, "home.deleted", "name", args[0]);
        }
        return true;
    }

    private boolean handleHomes(Player player) {
        List<HomeLocation> list = plugin.store().list(player.getUniqueId());
        if (list.isEmpty()) {
            plugin.messages().send(player, "home.list-empty");
            return true;
        }
        List<String> names = new ArrayList<>();
        list.forEach(home -> names.add(home.name()));
        int max = plugin.config().homeLimit(rank(player), extraHomes(player));
        plugin.messages().send(player, "home.list",
                "homes", String.join(", ", names),
                "used", plugin.store().countPlayerHomes(player.getUniqueId()), "max", max);
        return true;
    }

    /**
     * {@code /home} без имени ведёт к кровати, если она запомнена, иначе — к
     * единственному дому. Если домов несколько, игрок видит список: молча
     * выбирать за него нельзя.
     */
    private boolean handleHome(Player player, String[] args) {
        HomeStore store = plugin.store();
        String requested;
        if (args.length >= 1) {
            requested = args[0];
        } else {
            HomeLocation bed = store.get(player.getUniqueId(), HomeStore.BED);
            if (bed != null) {
                requested = HomeStore.BED;
            } else {
                List<HomeLocation> all = store.list(player.getUniqueId());
                if (all.isEmpty()) {
                    plugin.messages().send(player, "home.default-none");
                    return true;
                }
                if (all.size() > 1) {
                    List<String> names = new ArrayList<>();
                    all.forEach(home -> names.add(home.name()));
                    plugin.messages().send(player, "home.default-many",
                            "homes", String.join(", ", names));
                    return true;
                }
                requested = all.get(0).name();
            }
        }
        HomeLocation found = store.get(player.getUniqueId(), HomeStore.normalize(requested));
        if (found != null && HomeStore.isBedName(requested) && !plugin.config().bedSpawnEnabled()) {
            // Админ выключил кровать: старая запись не должна работать в обход настройки.
            found = null;
        }
        final HomeLocation home = found;
        if (home == null) {
            if (HomeStore.isBedName(requested)) {
                plugin.messages().send(player, "home.bed-none");
            } else {
                plugin.messages().send(player, "home.not-found", "name", requested);
            }
            return true;
        }
        World world = Bukkit.getWorld(home.world());
        if (world == null) {
            plugin.messages().send(player, "home.world-missing", "world", home.world());
            return true;
        }
        if (!gates(player, COOLDOWN_HOME, 0)) return true; // без собственного кулдауна; только мир/занятость
        int cost=chargedCost(player,plugin.config().costHome());
        plugin.messages().send(player,"finance.service-quote","cost",cost);
        boolean started = plugin.teleports().start(player,
                () -> executeHome(player, world, home,cost));
        if (!started) {
            plugin.messages().send(player, "tp.in-progress");
        }
        return true;
    }

    private void executeHome(Player player, World world, HomeLocation home,int cost) {
        if (!plugin.config().worldAllowed(world.getName())
                || !plugin.config().worldAllowed(player.getWorld().getName())) {
            plugin.messages().send(player, "tp.world-denied");
            return;
        }
        boolean bed = BedHome.isBed(home);
        // Кровать могли сломать или застроить: тогда встаём рядом, а не внутрь блока.
        Location target = bed
                ? BedHome.safeSpot(world, home)
                : new Location(world, home.x(), home.y(), home.z(), home.yaw(), home.pitch());
        if (target == null) {
            plugin.messages().send(player, "home.world-missing", "world", home.world());
            return;
        }
        if (!paidTeleport(player, player, cost, "home", target)) return;
        plugin.messages().send(player, bed ? "home.bed-here" : "home.here",
                "cost", cost,
                "vip-suffix", vipSuffix(player));
    }

    private boolean paidTeleport(Player payer, Player mover, int cost, String kind, Location target) {
        if (!payer.hasPermission("chrdktele.use") || !mover.hasPermission("chrdktele.use")) { plugin.messages().send(payer,"tp.permission"); return false; }
        if (!plugin.config().worldAllowed(mover.getWorld().getName()) || !plugin.config().worldAllowed(target.getWorld().getName())) {
            plugin.messages().send(payer,"tp.world-denied"); return false;
        }
        String payload=target.getWorld().getName()+":"+target.getX()+":"+target.getY()+":"+target.getZ();
        return plugin.payments().perform(payer,mover,Math.max(10,cost),kind,payload,()->ru.cherdakmd.chrdkregister.api.PaidActions.teleport(mover,target));
    }

    /* ------------------------------- /tpadmin ------------------------------- */

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdktele.admin")) {
            plugin.messages().send(sender, "tp.permission");
            return true;
        }
        if (plugin.payments().admin(sender,args)) return true;
        if (args.length >= 1 && "reload".equalsIgnoreCase(args[0])) {
            plugin.reloadTeleportsConfig();
            plugin.messages().send(sender, "admin.reloaded");
            return true;
        }
        plugin.messages().sendList(sender, "admin.usage");
        return true;
    }

    /* ------------------------------- таб-дополнение ------------------------------- */

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                     @NotNull String label, String @NotNull [] args) {
        List<String> matches = new ArrayList<>();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        String name = command.getName().toLowerCase(Locale.ROOT);
        switch (name) {
            case "tpa", "tpahere" -> {
                if (args.length == 1) {
                    Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .filter(playerName -> playerName.toLowerCase(Locale.ROOT).startsWith(prefix))
                            .forEach(matches::add);
                }
            }
            case "home", "delhome" -> {
                if (args.length == 1 && sender instanceof Player player) {
                    plugin.store().list(player.getUniqueId()).stream()
                            .map(HomeLocation::name)
                            .filter(homeName -> HomeStore.normalize(homeName).startsWith(prefix))
                            .forEach(matches::add);
                }
            }
            case "homes" -> { if(args.length==1&&"prices".startsWith(prefix)) matches.add("prices"); }
            case "sethome" -> { if(args.length==2&&"confirm".startsWith(prefix)) matches.add("confirm"); }
            case "tpadmin" -> {
                if (args.length==1) for (String option:List.of("reload","finance","recover","resolve")) if (option.startsWith(prefix)) matches.add(option);
            }
            default -> {
            }
        }
        return matches;
    }
}
