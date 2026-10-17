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
            case "homes" -> handleHomes(player);
            case "home" -> handleHome(player, args);
            default -> false;
        };
    }

    /* ------------------------------- общие проверки ------------------------------- */

    private boolean vip(Player player) {
        return plugin.coreApi() != null && plugin.coreApi().hasActivePass(player.getName());
    }

    /** false = проверяющий остановлен (сообщение уже отправлено). */
    private boolean gates(Player player, String cooldownKey, int cooldownSeconds) {
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

    /** VIP-суффикс по messages: пусто или фраза с процентом. */
    private String vipSuffix(Player player) {
        if (vip(player) && plugin.config().vipDiscountPercent() > 0) {
            return plugin.messages().raw("tp.vip-suffix",
                    "percent", plugin.config().vipDiscountPercent());
        }
        return plugin.messages().raw("tp.vip-suffix-empty");
    }

    /** Пытается списать стоимость; при недостатке — сообщение и false. */
    private boolean charge(Player player, int baseCost) {
        int cost = PriceCalc.apply(baseCost, vip(player), plugin.config().vipDiscountPercent());
        if (cost <= 0) {
            return true;
        }
        if (!DiamondWallet.charge(player, cost)) {
            plugin.messages().send(player, "tp.insufficient",
                    "cost", cost, "have", DiamondWallet.count(player));
            return false;
        }
        return true;
    }

    private int chargedCost(Player player, int baseCost) {
        return PriceCalc.apply(baseCost, vip(player), plugin.config().vipDiscountPercent());
    }

    /* ------------------------------- /rtp ------------------------------- */

    private boolean handleRtp(Player player) {
        TpConfig config = plugin.config();
        if (!gates(player, COOLDOWN_RTP, config.rtpCooldownSeconds())) return true;
        plugin.messages().send(player, "rtp.searching", "radius", config.rtpRadius());
        boolean started = plugin.teleports().start(player, () -> executeRtp(player));
        if (!started) {
            plugin.messages().send(player, "tp.in-progress");
        }
        return true;
    }

    private void executeRtp(Player player) {
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
            if (!charge(player, config.costRtp())) return;
            player.teleport(new Location(world, x + 0.5, y + 1, z + 0.5));
            plugin.teleports().markCooldown(COOLDOWN_RTP, player.getUniqueId(), System.currentTimeMillis());
            plugin.messages().send(player, "rtp.done",
                    "x", x, "z", z,
                    "cost", chargedCost(player, config.costRtp()),
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
                plugin.config().tpaTimeoutSeconds());
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
        if (!charge(payer, plugin.config().costTpa())) return;
        mover.teleport(anchor.getLocation());
        plugin.teleports().markCooldown(COOLDOWN_TPA, request.requester(), System.currentTimeMillis());
        plugin.messages().send(payer, "tp.teleported",
                "cost", chargedCost(payer, plugin.config().costTpa()),
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
        if (args.length < 1) {
            plugin.messages().send(player, "tp.usage-home");
            return true;
        }
        String name = args[0];
        if (!HomeStore.isValidName(name)) {
            plugin.messages().send(player, "home.bad-name");
            return true;
        }
        HomeStore store = plugin.store();
        boolean exists = store.get(player.getUniqueId(), HomeStore.normalize(name)) != null;
        int max = plugin.config().homeLimit(vip(player));
        if (!exists && store.count(player.getUniqueId()) >= max) {
            plugin.messages().send(player, "home.limit", "max", max);
            return true;
        }
        if (!plugin.config().worldAllowed(player.getWorld().getName())) {
            plugin.messages().send(player, "tp.world-denied");
            return true;
        }
        if (!charge(player, plugin.config().costSethome())) return true;
        Location loc = player.getLocation();
        store.put(player.getUniqueId(), new HomeLocation(name,
                player.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch()));
        int used = store.count(player.getUniqueId());
        if (chargedCost(player, plugin.config().costSethome()) > 0) {
            plugin.messages().send(player, "home.saved-paid",
                    "name", name, "used", used, "max", max,
                    "cost", chargedCost(player, plugin.config().costSethome()));
        } else {
            plugin.messages().send(player, "home.saved",
                    "name", name, "used", used, "max", max);
        }
        return true;
    }

    private boolean handleDelHome(Player player, String[] args) {
        if (args.length < 1) {
            plugin.messages().send(player, "tp.usage-home");
            return true;
        }
        if (!HomeStore.isValidName(args[0])) {
            plugin.messages().send(player, "home.bad-name");
            return true;
        }
        boolean removed = plugin.store().remove(player.getUniqueId(), HomeStore.normalize(args[0]));
        if (removed) {
            plugin.messages().send(player, "home.deleted", "name", args[0]);
        } else {
            plugin.messages().send(player, "home.not-found", "name", args[0]);
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
        int max = plugin.config().homeLimit(vip(player));
        plugin.messages().send(player, "home.list",
                "homes", String.join(", ", names), "used", list.size(), "max", max);
        return true;
    }

    private boolean handleHome(Player player, String[] args) {
        if (args.length < 1) {
            plugin.messages().send(player, "tp.usage-home");
            return true;
        }
        HomeLocation home = plugin.store().get(player.getUniqueId(), HomeStore.normalize(args[0]));
        if (home == null) {
            plugin.messages().send(player, "home.not-found", "name", args[0]);
            return true;
        }
        World world = Bukkit.getWorld(home.world());
        if (world == null) {
            plugin.messages().send(player, "home.world-missing", "world", home.world());
            return true;
        }
        if (!gates(player, COOLDOWN_HOME, 0)) return true; // без собственного кулдауна; только мир/занятость
        boolean started = plugin.teleports().start(player,
                () -> executeHome(player, world, home));
        if (!started) {
            plugin.messages().send(player, "tp.in-progress");
        }
        return true;
    }

    private void executeHome(Player player, World world, HomeLocation home) {
        if (!plugin.config().worldAllowed(world.getName())
                || !plugin.config().worldAllowed(player.getWorld().getName())) {
            plugin.messages().send(player, "tp.world-denied");
            return;
        }
        if (!charge(player, plugin.config().costHome())) return;
        player.teleport(new Location(world, home.x(), home.y(), home.z(),
                home.yaw(), home.pitch()));
        plugin.messages().send(player, "home.here",
                "cost", chargedCost(player, plugin.config().costHome()),
                "vip-suffix", vipSuffix(player));
    }

    /* ------------------------------- /tpadmin ------------------------------- */

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdktele.admin")) {
            plugin.messages().send(sender, "tp.permission");
            return true;
        }
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
            case "tpadmin" -> {
                if (args.length == 1 && "reload".startsWith(prefix)) {
                    matches.add("reload");
                }
            }
            default -> {
            }
        }
        return matches;
    }
}
