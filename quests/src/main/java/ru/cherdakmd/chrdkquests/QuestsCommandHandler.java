package ru.cherdakmd.chrdkquests;

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
 * Команды {@code /quests} и {@code /questsadmin} (1.4.161).
 *
 * <p>Здесь только разбор аргументов и тексты: доступность, прогресс и награды
 * решают {@link QuestService} и {@link QuestsPlugin#claim}. Поэтому клик в меню
 * и команда ведут себя одинаково — включая суточный лимит и списание
 * предметов.</p>
 */
public final class QuestsCommandHandler implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("help", "menu", "list", "info", "claim",
            "join", "leave", "faction");
    private static final List<String> ADMIN_SUBS = List.of("reload", "info", "grant", "reset",
            "complete");

    private final QuestsPlugin plugin;

    public QuestsCommandHandler(QuestsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("questsadmin")) {
            return admin(sender, args);
        }
        if (!sender.hasPermission("chrdkquests.use")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(sender, "disabled");
            return true;
        }
        if (args.length == 0) {
            return openMenu(sender);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> help(sender);
            case "menu" -> openMenu(sender);
            case "list" -> list(sender);
            case "info" -> info(sender, args);
            case "claim" -> claim(sender, args);
            case "join" -> join(sender, args);
            case "leave" -> leave(sender);
            case "faction" -> faction(sender);
            default -> {
                plugin.messages().send(sender, "unknown-subcommand", "sub", args[0]);
                help(sender);
            }
        }
        return true;
    }

    /* --------------------------------- игроку --------------------------------- */

    private boolean openMenu(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        plugin.gui().open(player);
        return true;
    }

    private void help(CommandSender sender) {
        plugin.messages().send(sender, "help-header");
        for (String key : List.of("help-menu", "help-list", "help-info", "help-claim",
                "help-join", "help-leave", "help-faction")) {
            plugin.messages().send(sender, key);
        }
        plugin.messages().send(sender, "help-footer",
                "limit", String.valueOf(plugin.config().claimsPerDay()));
    }

    private void list(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return;
        }
        UUID id = player.getUniqueId();
        List<Quest> current = plugin.service().currentQuests(id);
        if (current.isEmpty()) {
            plugin.messages().send(sender, "list-empty");
            return;
        }
        plugin.messages().send(sender, "list-header", "count", String.valueOf(current.size()));
        for (Quest quest : current) {
            int have = quest.type() == QuestType.GATHER
                    ? Math.min(plugin.countItems(player, quest), quest.amount())
                    : plugin.service().progress(id, quest);
            QuestService.Availability availability = plugin.service().availability(id, quest, have);
            QuestNpc npc = plugin.config().npc(quest.npcId());
            plugin.messages().send(sender, "list-line",
                    "name", quest.name(),
                    "npc", npc == null ? "—" : npc.name(),
                    "have", String.valueOf(have),
                    "need", String.valueOf(quest.amount()),
                    "status", Messages.trusted(plugin.messages().raw(statusKey(availability))));
        }
        plugin.messages().send(sender, "list-footer",
                "left", String.valueOf(plugin.service().claimsLeft(id, plugin.today())),
                "limit", String.valueOf(plugin.config().claimsPerDay()));
    }

    private void info(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "info-usage");
            return;
        }
        Quest quest = plugin.service().find(args[1]);
        if (quest == null) {
            plugin.messages().send(sender, "claim-unknown");
            return;
        }
        QuestNpc npc = plugin.config().npc(quest.npcId());
        int have = quest.type() == QuestType.GATHER
                ? Math.min(plugin.countItems(player, quest), quest.amount())
                : plugin.service().progress(player.getUniqueId(), quest);
        QuestService.Availability availability =
                plugin.service().availability(player.getUniqueId(), quest, have);
        plugin.messages().send(sender, "info-header", "name", quest.name(),
                "npc", npc == null ? "—" : npc.name());
        if (!quest.description().isEmpty()) {
            plugin.messages().send(sender, "info-description",
                    "description", quest.description());
        }
        plugin.messages().send(sender, "info-target",
                "target", Messages.trusted(QuestText.joinTags(quest.targetTags())),
                "amount", String.valueOf(quest.amount()));
        plugin.messages().send(sender, "info-progress",
                "have", String.valueOf(have),
                "need", String.valueOf(quest.amount()));
        plugin.messages().send(sender, "info-reward",
                "xp", String.valueOf(quest.reward().xp()),
                "reputation", String.valueOf(quest.reward().reputation()));
        if (quest.minRank() > 0) {
            plugin.messages().send(sender, "info-rank", "rank", String.valueOf(quest.minRank()));
        }
        plugin.messages().send(sender, "info-status",
                "status", Messages.trusted(plugin.messages().raw(statusKey(availability))));
    }

    private void claim(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "claim-usage");
            return;
        }
        Quest quest = plugin.service().find(args[1]);
        if (quest == null) {
            plugin.messages().send(sender, "claim-unknown");
            return;
        }
        if (plugin.claim(player, quest)) {
            plugin.requestSave();
        }
    }

    private void join(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "join-usage");
            return;
        }
        QuestFaction faction = plugin.config().faction(args[1]);
        if (faction == null) {
            plugin.messages().send(sender, "join-unknown", "faction", args[1]);
            return;
        }
        String current = plugin.service().factionId(player.getUniqueId());
        if (!current.isEmpty() && !current.equals(faction.id())) {
            QuestFaction other = plugin.config().faction(current);
            plugin.messages().send(sender, "join-switch",
                    "current", other == null ? current : other.name());
            return;
        }
        QuestService.Result result = plugin.service().join(player.getUniqueId(), faction.id());
        switch (result) {
            case OK -> {
                plugin.requestSave();
                plugin.messages().send(sender, "join-ok", "faction", faction.name());
                plugin.messages().send(sender, "join-hint");
            }
            case SAME_FACTION -> plugin.messages().send(sender, "join-same");
            default -> plugin.messages().send(sender, "join-unknown", "faction", args[1]);
        }
    }

    private void leave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return;
        }
        QuestService.Result result = plugin.service().leaveFaction(player.getUniqueId());
        if (result == QuestService.Result.NO_FACTION) {
            plugin.messages().send(sender, "leave-none");
            return;
        }
        plugin.requestSave();
        plugin.messages().send(sender, "leave-ok");
    }

    private void faction(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return;
        }
        UUID id = player.getUniqueId();
        QuestFaction faction = plugin.service().faction(id);
        if (faction == null) {
            plugin.messages().send(sender, "faction-none");
            return;
        }
        int reputation = plugin.service().reputation(id);
        plugin.messages().send(sender, "faction-header", "faction", faction.name());
        if (!faction.description().isEmpty()) {
            plugin.messages().send(sender, "faction-description",
                    "description", faction.description());
        }
        plugin.messages().send(sender, "faction-rank",
                "rank", plugin.messages().mini("rank." + plugin.service().rankId(id)),
                "number", String.valueOf(plugin.service().rankNumber(id)));
        plugin.messages().send(sender, "faction-reputation",
                "reputation", String.valueOf(reputation),
                "next", faction.nextThreshold(reputation) < 0
                        ? plugin.messages().mini("faction-max")
                        : String.valueOf(faction.nextThreshold(reputation)));
        plugin.messages().send(sender, "faction-done",
                "done", String.valueOf(plugin.service().doneCount(id)),
                "total", String.valueOf(plugin.service().totalQuests()));
    }

    /* --------------------------------- админу --------------------------------- */

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdkquests.admin")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            plugin.messages().send(sender, "admin-usage");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> reload(sender);
            case "info" -> adminInfo(sender, args);
            case "grant" -> grant(sender, args);
            case "reset" -> reset(sender, args);
            case "complete" -> complete(sender, args);
            default -> plugin.messages().send(sender, "admin-usage");
        }
        return true;
    }

    private void reload(CommandSender sender) {
        plugin.reloadQuests();
        plugin.messages().send(sender, "admin-reloaded",
                "factions", String.valueOf(plugin.config().factions().size()),
                "npcs", String.valueOf(plugin.config().npcList().size()),
                "quests", String.valueOf(plugin.config().totalQuests()));
    }

    private void adminInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "admin-usage");
            return;
        }
        UUID id = resolve(args[1]);
        if (id == null) {
            plugin.messages().send(sender, "admin-player-not-found", "player", args[1]);
            return;
        }
        QuestFaction faction = plugin.service().faction(id);
        plugin.messages().send(sender, "admin-info",
                "player", plugin.store().name(id),
                "faction", faction == null ? "—" : faction.name(),
                "reputation", String.valueOf(plugin.service().reputation(id)),
                "done", String.valueOf(plugin.service().doneCount(id)),
                "total", String.valueOf(plugin.service().totalQuests()),
                "claims", String.valueOf(plugin.service().claimsLeft(id, plugin.today())));
    }

    private void grant(CommandSender sender, String[] args) {
        if (args.length < 4) {
            plugin.messages().send(sender, "admin-usage");
            return;
        }
        UUID id = resolve(args[1]);
        if (id == null) {
            plugin.messages().send(sender, "admin-player-not-found", "player", args[1]);
            return;
        }
        QuestFaction faction = plugin.config().faction(args[2]);
        if (faction == null) {
            plugin.messages().send(sender, "admin-unknown-faction", "faction", args[2]);
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[3]);
        } catch (NumberFormatException error) {
            plugin.messages().send(sender, "admin-bad-number");
            return;
        }
        int value = plugin.store().addReputation(id, faction.id(), Math.max(0, amount));
        plugin.requestSave();
        plugin.messages().send(sender, "admin-granted",
                "player", plugin.store().name(id),
                "faction", faction.name(),
                "amount", String.valueOf(Math.max(0, amount)),
                "reputation", String.valueOf(value));
    }

    private void reset(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "admin-usage");
            return;
        }
        UUID id = resolve(args[1]);
        if (id == null || !plugin.store().reset(id)) {
            plugin.messages().send(sender, "admin-player-not-found", "player", args[1]);
            return;
        }
        plugin.requestSave();
        plugin.messages().send(sender, "admin-reset", "player", plugin.store().name(id));
    }

    private void complete(CommandSender sender, String[] args) {
        if (args.length < 3) {
            plugin.messages().send(sender, "admin-usage");
            return;
        }
        UUID id = resolve(args[1]);
        if (id == null) {
            plugin.messages().send(sender, "admin-player-not-found", "player", args[1]);
            return;
        }
        Quest quest = plugin.service().find(args[2]);
        if (quest == null) {
            plugin.messages().send(sender, "claim-unknown");
            return;
        }
        plugin.store().markDone(id, quest.id());
        plugin.requestSave();
        plugin.messages().send(sender, "admin-completed",
                "player", plugin.store().name(id), "name", quest.name());
    }

    /** UUID по нику: сначала онлайн, затем сохранённые имена. */
    private UUID resolve(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        return plugin.store().byName(name);
    }

    /* ------------------------------- помощники -------------------------------- */

    private String statusKey(QuestService.Availability availability) {
        return switch (availability) {
            case AVAILABLE -> "status-available";
            case IN_PROGRESS -> "status-progress";
            case READY -> "status-ready";
            case DONE -> "status-done";
            case LOCKED -> "status-locked";
            case WRONG_FACTION -> "status-faction";
            case RANK -> "status-rank";
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias,
                                      String[] args) {
        if (command.getName().equalsIgnoreCase("questsadmin")) {
            if (!sender.hasPermission("chrdkquests.admin")) {
                return List.of();
            }
            if (args.length == 1) {
                return filter(ADMIN_SUBS, args[0]);
            }
            if (args.length == 2) {
                return filter(onlineNames(), args[1]);
            }
            if (args.length == 3) {
                return switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "grant" -> filter(plugin.config().factionIds(), args[2]);
                    case "complete" -> filter(plugin.service().questIds(), args[2]);
                    default -> List.of();
                };
            }
            return List.of();
        }
        if (!sender.hasPermission("chrdkquests.use")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(SUBS, args[0]);
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "claim", "info" -> filter(plugin.service().questIds(), args[1]);
                case "join" -> filter(plugin.config().factionIds(), args[1]);
                default -> List.of();
            };
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
