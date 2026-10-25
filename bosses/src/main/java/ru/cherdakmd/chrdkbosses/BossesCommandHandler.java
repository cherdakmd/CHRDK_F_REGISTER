package ru.cherdakmd.chrdkbosses;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Команды {@code /boss} и {@code /bossadmin} (1.4.162).
 *
 * <p>Здесь только разбор аргументов и тексты: спавн, награды и фазы решает
 * {@link BossService}. Поэтому меню и команда рассказывают одно и то же, а
 * админская команда не может выдать победу в обход боя.</p>
 */
public final class BossesCommandHandler implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("help", "menu", "list", "info", "zones",
            "where");
    private static final List<String> ADMIN_SUBS = List.of("spawn", "stop", "reload", "info",
            "cooldown", "history", "reset");

    private final BossesPlugin plugin;

    public BossesCommandHandler(BossesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("bossadmin")) {
            return admin(sender, args);
        }
        if (!sender.hasPermission("chrdkbosses.use")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(sender, "disabled");
            return true;
        }
        if (args.length == 0) {
            return menu(sender);
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> help(sender);
            case "menu" -> menu(sender);
            case "list" -> list(sender);
            case "info" -> info(sender, args);
            case "zones" -> zones(sender);
            case "where" -> where(sender);
            default -> {
                plugin.messages().send(sender, "unknown-subcommand", "sub", args[0]);
                help(sender);
            }
        }
        return true;
    }

    /* --------------------------------- игроку --------------------------------- */

    private boolean menu(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        plugin.gui().open(player);
        return true;
    }

    private void help(CommandSender sender) {
        plugin.messages().send(sender, "help-header");
        for (String key : List.of("help-menu", "help-list", "help-info", "help-zones",
                "help-where")) {
            plugin.messages().send(sender, key);
        }
    }

    private void list(CommandSender sender) {
        List<BossDefinition> bosses = plugin.config().bossList();
        if (bosses.isEmpty()) {
            plugin.messages().send(sender, "list-empty");
            return;
        }
        long now = System.currentTimeMillis();
        plugin.messages().send(sender, "list-header", "count", String.valueOf(bosses.size()));
        for (BossDefinition definition : bosses) {
            ActiveBoss active = plugin.service().current(definition.id());
            Object state;
            if (active != null) {
                state = plugin.messages().mini("list-alive",
                        "phase", active.phase().name(),
                        "percent", String.valueOf(Math.round(active.progress() * 100)));
            } else {
                long seconds = plugin.service().secondsToSpawn(definition.id(), now);
                state = seconds > 0
                        ? plugin.messages().mini("list-cooldown", "time", BossMath.clock(seconds))
                        : plugin.messages().mini("list-ready");
            }
            plugin.messages().send(sender, "list-line",
                    "name", definition.name(),
                    "entity", definition.entity().name().toLowerCase(Locale.ROOT),
                    "state", state);
        }
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "info-usage");
            return;
        }
        BossDefinition definition = plugin.config().boss(args[1]);
        if (definition == null) {
            plugin.messages().send(sender, "boss-not-found", "id", args[1]);
            return;
        }
        plugin.messages().send(sender, "boss-info-header", "name", definition.name());
        if (!definition.description().isEmpty()) {
            plugin.messages().send(sender, "boss-info-description",
                    "description", definition.description());
        }
        plugin.messages().send(sender, "boss-info-arena",
                "arena", definition.arena().label());
        plugin.messages().send(sender, "boss-info-health",
                "health", String.valueOf((int) definition.health()),
                "phases", String.valueOf(definition.phases().size()));
        for (BossPhase phase : definition.phases()) {
            plugin.messages().send(sender, "boss-info-phase",
                    "percent", String.valueOf(phase.fromPercent()),
                    "name", phase.name(),
                    "abilities", String.valueOf(phase.abilities().size()));
        }
        ActiveBoss active = plugin.service().current(definition.id());
        if (active != null) {
            plugin.messages().send(sender, "boss-info-alive",
                    "phase", active.phase().name(),
                    "percent", String.valueOf(Math.round(active.progress() * 100)));
        } else {
            long seconds = plugin.service().secondsToSpawn(definition.id(),
                    System.currentTimeMillis());
            plugin.messages().send(sender, seconds > 0 ? "boss-info-cooldown" : "boss-info-ready",
                    "time", BossMath.clock(seconds));
        }
        plugin.messages().send(sender, "boss-info-reward",
                "xp", String.valueOf(definition.reward().xp()),
                "items", String.valueOf(definition.reward().items().size()));
    }

    private void zones(CommandSender sender) {
        List<DangerZone> zones = plugin.config().zoneList();
        if (zones.isEmpty()) {
            plugin.messages().send(sender, "zones-empty");
            return;
        }
        plugin.messages().send(sender, "zones-header", "count", String.valueOf(zones.size()));
        for (DangerZone zone : zones) {
            plugin.messages().send(sender, "zones-line",
                    "name", zone.name(),
                    "level", zone.levelLabel(),
                    "radius", String.valueOf(Math.round(zone.radius())),
                    "bonus", String.valueOf(zone.xpBonusPercent()),
                    "state", plugin.messages().mini(zone.enabled()
                            ? "zones-on" : "zones-off"));
        }
    }

    private void where(CommandSender sender) {
        List<ActiveBoss> active = plugin.service().active();
        if (active.isEmpty()) {
            plugin.messages().send(sender, "where-empty");
            return;
        }
        for (ActiveBoss boss : active) {
            var location = boss.entity().getLocation();
            plugin.messages().send(sender, "where-line",
                    "name", boss.definition().name(),
                    "phase", boss.phase().name(),
                    "world", location.getWorld().getName(),
                    "x", String.valueOf(location.getBlockX()),
                    "y", String.valueOf(location.getBlockY()),
                    "z", String.valueOf(location.getBlockZ()));
        }
    }

    /* --------------------------------- админу --------------------------------- */

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdkbosses.admin")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            plugin.messages().send(sender, "admin-usage");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "spawn" -> spawn(sender, args);
            case "stop" -> stop(sender, args);
            case "reload" -> reload(sender);
            case "info" -> adminInfo(sender, args);
            case "cooldown" -> cooldown(sender, args);
            case "history" -> history(sender, args);
            case "reset" -> reset(sender);
            default -> plugin.messages().send(sender, "admin-usage");
        }
        return true;
    }

    private void spawn(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "admin-usage");
            return;
        }
        BossDefinition definition = plugin.config().boss(args[1]);
        if (definition == null) {
            plugin.messages().send(sender, "boss-not-found", "id", args[1]);
            return;
        }
        ActiveBoss boss = plugin.service().spawn(definition.id(), true);
        if (boss == null) {
            plugin.messages().send(sender, "admin-spawn-failed", "name", definition.name());
            return;
        }
        plugin.messages().send(sender, "admin-spawned", "name", definition.name());
    }

    private void stop(CommandSender sender, String[] args) {
        if (args.length < 2) {
            int count = plugin.service().aliveCount();
            plugin.service().despawnAll();
            plugin.messages().send(sender, "admin-stopped-all", "count", String.valueOf(count));
            return;
        }
        if (args[1].equalsIgnoreCase("all")) {
            int count = plugin.service().aliveCount();
            plugin.service().despawnAll();
            plugin.messages().send(sender, "admin-stopped-all", "count", String.valueOf(count));
            return;
        }
        BossDefinition definition = plugin.config().boss(args[1]);
        if (definition == null) {
            plugin.messages().send(sender, "boss-not-found", "id", args[1]);
            return;
        }
        ActiveBoss boss = plugin.service().current(definition.id());
        if (boss == null) {
            plugin.messages().send(sender, "admin-not-alive", "name", definition.name());
            return;
        }
        plugin.service().despawn(boss, "boss-despawned");
        plugin.messages().send(sender, "admin-stopped", "name", definition.name());
    }

    private void reload(CommandSender sender) {
        plugin.reloadBosses();
        plugin.messages().send(sender, "admin-reloaded",
                "bosses", String.valueOf(plugin.config().bossList().size()),
                "zones", String.valueOf(plugin.config().activeZones().size()));
    }

    private void adminInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "admin-usage");
            return;
        }
        BossDefinition definition = plugin.config().boss(args[1]);
        if (definition == null) {
            plugin.messages().send(sender, "boss-not-found", "id", args[1]);
            return;
        }
        long now = System.currentTimeMillis();
        ActiveBoss active = plugin.service().current(definition.id());
        plugin.messages().send(sender, "admin-info",
                "name", definition.name(),
                "state", active == null
                        ? plugin.messages().mini("admin-state-idle")
                        : plugin.messages().mini("admin-state-alive"),
                "kills", String.valueOf(plugin.store().kills(definition.id())),
                "next", BossMath.clock(plugin.service().secondsToSpawn(definition.id(), now)),
                "arena", definition.arena().label());
    }

    private void cooldown(CommandSender sender, String[] args) {
        if (args.length < 3 || !args[1].equalsIgnoreCase("reset")) {
            plugin.messages().send(sender, "admin-cooldown-usage");
            return;
        }
        if (args[2].equalsIgnoreCase("all")) {
            int cleared = plugin.store().clearAllNextAt();
            plugin.requestSave();
            plugin.messages().send(sender, "admin-cooldown-all", "count", String.valueOf(cleared));
            return;
        }
        BossDefinition definition = plugin.config().boss(args[2]);
        if (definition == null) {
            plugin.messages().send(sender, "boss-not-found", "id", args[2]);
            return;
        }
        plugin.store().clearNextAt(definition.id());
        plugin.requestSave();
        plugin.messages().send(sender, "admin-cooldown", "name", definition.name());
    }

    private void history(CommandSender sender, String[] args) {
        int limit = 5;
        if (args.length > 1) {
            try {
                limit = Math.clamp(Integer.parseInt(args[1]), 1, BossStore.MAX_HISTORY);
            } catch (NumberFormatException error) {
                plugin.messages().send(sender, "admin-history-usage");
                return;
            }
        }
        List<BossStore.Entry> entries = plugin.store().history();
        if (entries.isEmpty()) {
            plugin.messages().send(sender, "admin-history-empty");
            return;
        }
        int shown = 0;
        for (int index = entries.size() - 1; index >= 0 && shown < limit; index--) {
            BossStore.Entry entry = entries.get(index);
            plugin.messages().send(sender, "admin-history-line",
                    "time", entry.time(),
                    "name", entry.name(),
                    "killer", entry.killer().isEmpty()
                            ? plugin.messages().mini("admin-history-unknown") : entry.killer(),
                    "participants", String.valueOf(entry.participants()));
            shown++;
        }
    }

    private void reset(CommandSender sender) {
        plugin.store().resetCounters();
        plugin.requestSave();
        plugin.messages().send(sender, "admin-reset");
    }

    /* ------------------------------- помощники -------------------------------- */

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias,
                                      String[] args) {
        if (command.getName().equalsIgnoreCase("bossadmin")) {
            if (!sender.hasPermission("chrdkbosses.admin")) {
                return List.of();
            }
            if (args.length == 1) {
                return filter(ADMIN_SUBS, args[0]);
            }
            if (args.length == 2) {
                return switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "spawn", "stop", "info" -> filter(withAll(plugin.config().bossIds()), args[1]);
                    case "cooldown" -> filter(List.of("reset"), args[1]);
                    default -> List.of();
                };
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("cooldown")) {
                return filter(withAll(plugin.config().bossIds()), args[2]);
            }
            return List.of();
        }
        if (!sender.hasPermission("chrdkbosses.use")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(SUBS, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("info")) {
            return filter(plugin.config().bossIds(), args[1]);
        }
        return List.of();
    }

    private static List<String> withAll(List<String> options) {
        List<String> out = new ArrayList<>(options);
        out.add("all");
        return out;
    }

    private static List<String> filter(List<String> options, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return List.copyOf(options);
        }
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }
}
