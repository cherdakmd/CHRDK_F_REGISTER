package ru.cherdakmd.chrdkparty;

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
 * Команды {@code /party}, {@code /pc} и {@code /partyadmin} (1.4.158).
 *
 * <p>Здесь только разбор аргументов и тексты: все решения принимает
 * {@link PartyService}. Поэтому команда, событие и (если появится) меню не
 * могут разойтись в правилах — например, в том, кому можно исключать.</p>
 */
public final class PartyCommandHandler implements CommandExecutor, TabCompleter {

    private static final List<String> PLAYER_SUBS = List.of("help", "create", "invite",
            "accept", "deny", "leave", "kick", "disband", "list", "info", "chat");
    private static final List<String> ADMIN_SUBS = List.of("reload", "info", "disband");
    /** Потолок длины сообщения в пати-чате: защита от «полотна» на весь экран. */
    private static final int MAX_CHAT_LENGTH = 256;

    private final PartyPlugin plugin;

    public PartyCommandHandler(PartyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("partyadmin")) {
            return admin(sender, args);
        }
        if (name.equals("pc")) {
            return chat(sender, args);
        }
        if (!sender.hasPermission("chrdkparty.use")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(sender, "disabled");
            return true;
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (args.length == 0) {
            Party party = plugin.service().of(player.getUniqueId());
            if (party == null) {
                help(player);
            } else {
                list(player, party);
            }
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "help" -> {
                help(player);
                yield true;
            }
            case "create" -> create(player);
            case "invite" -> invite(player, args);
            case "accept" -> accept(player);
            case "deny" -> deny(player);
            case "leave" -> leave(player);
            case "kick" -> kick(player, args);
            case "disband" -> disband(player);
            case "list" -> {
                Party party = plugin.service().of(player.getUniqueId());
                if (party == null) {
                    plugin.messages().send(player, "not-in-party");
                } else {
                    list(player, party);
                }
                yield true;
            }
            case "info" -> info(player, args);
            case "chat" -> chat(player, args.length > 1
                    ? java.util.Arrays.copyOfRange(args, 1, args.length)
                    : new String[0]);
            default -> {
                plugin.messages().send(player, "unknown-subcommand", "sub", args[0]);
                yield true;
            }
        };
    }

    /* --------------------------------- игрок ---------------------------------- */

    private void help(CommandSender sender) {
        Messages messages = plugin.messages();
        messages.send(sender, "help-header");
        for (String key : List.of("help-create", "help-invite", "help-accept", "help-leave",
                "help-kick", "help-disband", "help-list", "help-chat", "help-info")) {
            messages.send(sender, key);
        }
        messages.send(sender, "help-footer",
                "max", String.valueOf(plugin.config().maxBonusPercent()));
    }

    private boolean create(Player player) {
        Party party = plugin.service().create(player.getUniqueId(), player.getName());
        if (party == null) {
            plugin.messages().send(player, "already-in-party");
            return true;
        }
        plugin.requestSave();
        plugin.messages().send(player, "created");
        return true;
    }

    private boolean invite(Player player, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(player, "help-invite");
            return true;
        }
        String raw = args[1].trim();
        if (raw.length() < 3 || raw.length() > 16 || raw.indexOf(' ') >= 0) {
            plugin.messages().send(player, "invalid-name", "player", raw);
            return true;
        }
        Player online = Bukkit.getPlayerExact(raw);
        UUID target;
        String targetName;
        if (online != null) {
            target = online.getUniqueId();
            targetName = online.getName();
        } else {
            var offline = Bukkit.getOfflinePlayerIfCached(raw);
            if (offline == null) {
                plugin.messages().send(player, "target-not-found", "player", raw);
                return true;
            }
            target = offline.getUniqueId();
            targetName = offline.getName() == null ? raw : offline.getName();
        }
        PartyService.Result result = plugin.service().invite(player.getUniqueId(),
                player.getName(), target, targetName, System.currentTimeMillis());
        switch (result) {
            case OK -> {
                plugin.messages().send(player, "invite-sent",
                        "player", targetName,
                        "seconds", String.valueOf(plugin.config().inviteExpireSeconds()));
                Player targetPlayer = Bukkit.getPlayer(target);
                if (targetPlayer != null) {
                    plugin.messages().send(targetPlayer, "invite-received",
                            "player", player.getName());
                }
                Party party = plugin.service().of(player.getUniqueId());
                if (party != null) {
                    for (UUID member : party.memberIds()) {
                        if (member.equals(player.getUniqueId())) {
                            continue;
                        }
                        Player other = Bukkit.getPlayer(member);
                        if (other != null) {
                            plugin.messages().send(other, "invite-broadcast",
                                    "player", targetName);
                        }
                    }
                }
            }
            case SELF -> plugin.messages().send(player, "invite-self");
            case NOT_IN_PARTY -> plugin.messages().send(player, "not-in-party");
            case NOT_LEADER -> plugin.messages().send(player, "not-leader");
            case TARGET_IN_PARTY -> plugin.messages().send(player, "invite-target-in-party",
                    "player", targetName);
            case PARTY_FULL -> plugin.messages().send(player, "party-full",
                    "max", String.valueOf(plugin.config().maxSize()));
            case ALREADY_INVITED -> plugin.messages().send(player, "invite-already-sent",
                    "player", targetName);
            case INVITE_LIMIT -> plugin.messages().send(player, "invite-limit",
                    "limit", String.valueOf(plugin.config().inviteMaxPending()));
            default -> plugin.messages().send(player, "invite-limit",
                    "limit", String.valueOf(plugin.config().inviteMaxPending()));
        }
        return true;
    }

    private boolean accept(Player player) {
        PartyService.Result result = plugin.service().accept(player.getUniqueId(),
                player.getName(), System.currentTimeMillis());
        switch (result) {
            case OK -> {
                Party party = plugin.service().of(player.getUniqueId());
                if (party == null) {
                    plugin.messages().send(player, "invite-none");
                    return true;
                }
                plugin.requestSave();
                plugin.messages().send(player, "invite-accepted-target",
                        "size", String.valueOf(party.size()),
                        "max", String.valueOf(plugin.config().maxSize()));
                for (UUID member : party.memberIds()) {
                    if (member.equals(player.getUniqueId())) {
                        continue;
                    }
                    Player other = Bukkit.getPlayer(member);
                    if (other != null) {
                        plugin.messages().send(other, "invite-accepted-sender",
                                "player", player.getName(),
                                "size", String.valueOf(party.size()),
                                "max", String.valueOf(plugin.config().maxSize()));
                        plugin.messages().send(other, "player-joined",
                                "player", player.getName(),
                                "size", String.valueOf(party.size()),
                                "max", String.valueOf(plugin.config().maxSize()));
                    }
                }
            }
            case EXPIRED -> plugin.messages().send(player, "invite-expired");
            case NO_INVITE -> plugin.messages().send(player, "invite-none");
            case PARTY_FULL -> plugin.messages().send(player, "party-full",
                    "max", String.valueOf(plugin.config().maxSize()));
            case ALREADY_IN_PARTY -> plugin.messages().send(player, "already-in-party");
            default -> plugin.messages().send(player, "invite-none");
        }
        return true;
    }

    private boolean deny(Player player) {
        PartyService.Invite invite = plugin.service().inviteOf(player.getUniqueId(),
                System.currentTimeMillis());
        boolean denied = plugin.service().deny(player.getUniqueId());
        plugin.messages().send(player, denied ? "invite-denied-target" : "invite-none");
        if (denied && invite != null) {
            Player host = Bukkit.getPlayer(invite.from());
            if (host != null) {
                plugin.messages().send(host, "invite-denied-sender",
                        "player", player.getName());
            }
        }
        return true;
    }

    private boolean leave(Player player) {
        Party party = plugin.service().of(player.getUniqueId());
        if (party == null) {
            plugin.messages().send(player, "not-in-party");
            return true;
        }
        List<UUID> members = party.memberIds();
        boolean wasLeader = party.isLeader(player.getUniqueId());
        PartyService.Result result = plugin.service().leave(player.getUniqueId());
        if (result == PartyService.Result.NOT_IN_PARTY) {
            plugin.messages().send(player, "not-in-party");
            return true;
        }
        plugin.requestSave();
        plugin.messages().send(player, "you-left");
        Party after = plugin.service().of(player.getUniqueId());
        for (UUID member : members) {
            if (member.equals(player.getUniqueId())) {
                continue;
            }
            Player other = Bukkit.getPlayer(member);
            if (other == null) {
                continue;
            }
            if (after == null) {
                plugin.messages().send(other, "party-disbanded");
                continue;
            }
            plugin.messages().send(other, "player-left",
                    "player", player.getName(),
                    "size", String.valueOf(after.size()),
                    "max", String.valueOf(plugin.config().maxSize()));
            if (wasLeader && after.isLeader(member)) {
                plugin.messages().send(other, "leader-changed",
                        "player", after.leaderName());
            }
        }
        return true;
    }

    private boolean kick(Player player, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(player, "help-kick");
            return true;
        }
        Party party = plugin.service().of(player.getUniqueId());
        if (party == null) {
            plugin.messages().send(player, "not-in-party");
            return true;
        }
        UUID target = findMember(party, args[1]);
        if (target == null) {
            plugin.messages().send(player, "target-not-in-party", "player", args[1]);
            return true;
        }
        PartyService.Result result = plugin.service().kick(player.getUniqueId(), target);
        switch (result) {
            case OK -> {
                plugin.requestSave();
                plugin.messages().send(player, "kicked", "player", args[1]);
                Player kicked = Bukkit.getPlayer(target);
                if (kicked != null) {
                    plugin.messages().send(kicked, "you-kicked", "player", player.getName());
                }
            }
            case NOT_LEADER -> plugin.messages().send(player, "not-leader");
            case SELF -> plugin.messages().send(player, "invite-self");
            default -> plugin.messages().send(player, "target-not-in-party", "player", args[1]);
        }
        return true;
    }

    private boolean disband(Player player) {
        Party party = plugin.service().of(player.getUniqueId());
        if (party == null) {
            plugin.messages().send(player, "not-in-party");
            return true;
        }
        List<UUID> members = party.memberIds();
        PartyService.Result result = plugin.service().disband(player.getUniqueId());
        if (result == PartyService.Result.NOT_LEADER) {
            plugin.messages().send(player, "not-leader");
            return true;
        }
        plugin.requestSave();
        plugin.messages().send(player, "you-disbanded");
        for (UUID member : members) {
            if (member.equals(player.getUniqueId())) {
                continue;
            }
            Player other = Bukkit.getPlayer(member);
            if (other != null) {
                plugin.messages().send(other, "party-disbanded");
            }
        }
        return true;
    }

    /** Группа другого игрока: состав и бонус, без права что-то менять. */
    private boolean info(Player player, String[] args) {
        UUID targetId = player.getUniqueId();
        if (args.length > 1) {
            Player online = Bukkit.getPlayerExact(args[1]);
            var offline = online == null ? Bukkit.getOfflinePlayerIfCached(args[1]) : null;
            if (online != null) {
                targetId = online.getUniqueId();
            } else if (offline != null) {
                targetId = offline.getUniqueId();
            } else {
                plugin.messages().send(player, "target-not-found", "player", args[1]);
                return true;
            }
        }
        Party party = plugin.service().of(targetId);
        if (party == null) {
            plugin.messages().send(player, "profile-none");
            return true;
        }
        if (targetId.equals(player.getUniqueId())) {
            list(player, party);
            return true;
        }
        plugin.messages().send(player, "list-header",
                "size", String.valueOf(party.size()),
                "max", String.valueOf(plugin.config().maxSize()),
                "bonus", String.valueOf(plugin.service().potentialBonus(party)));
        for (UUID member : party.memberIds()) {
            Player online = Bukkit.getPlayer(member);
            String name = party.nameOf(member);
            Messages.Trusted role = party.isLeader(member)
                    ? Messages.trusted(plugin.messages().raw("list-role-leader"))
                    : Messages.trusted(plugin.messages().raw("list-role-member"));
            if (online == null) {
                plugin.messages().send(player, "list-row-offline", "role", role,
                        "player", name);
            } else {
                plugin.messages().send(player, "list-row", "role", role,
                        "player", name, "hp", health(online));
            }
        }
        return true;
    }

    /* ---------------------------------- чат ----------------------------------- */

    private boolean chat(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdkparty.use")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(player, "disabled");
            return true;
        }
        if (args.length == 0) {
            plugin.messages().send(player, "chat-usage");
            return true;
        }
        Party party = plugin.service().of(player.getUniqueId());
        if (party == null) {
            plugin.messages().send(player, "not-in-party");
            return true;
        }
        String text = String.join(" ", args).trim();
        if (text.isEmpty()) {
            plugin.messages().send(player, "chat-usage");
            return true;
        }
        if (text.length() > MAX_CHAT_LENGTH) {
            text = text.substring(0, MAX_CHAT_LENGTH);
        }
        // Себе тоже: в чате канала своя строка должна быть видна, иначе
        // непонятно, дошло ли сообщение до группы.
        for (UUID member : party.memberIds()) {
            Player other = Bukkit.getPlayer(member);
            if (other == null) {
                continue;
            }
            plugin.messages().send(other, "chat-format",
                    "player", player.getName(), "message", text);
        }
        return true;
    }

    /* --------------------------------- список --------------------------------- */

    private void list(CommandSender sender, Party party) {
        Messages messages = plugin.messages();
        messages.send(sender, "list-header",
                "size", String.valueOf(party.size()),
                "max", String.valueOf(plugin.config().maxSize()),
                "bonus", String.valueOf(plugin.service().potentialBonus(party)));
        for (UUID member : party.memberIds()) {
            Player online = Bukkit.getPlayer(member);
            String name = party.nameOf(member);
            Messages.Trusted role = party.isLeader(member)
                    ? Messages.trusted(messages.raw("list-role-leader"))
                    : Messages.trusted(messages.raw("list-role-member"));
            if (online == null) {
                messages.send(sender, "list-row-offline", "role", role, "player", name);
            } else {
                messages.send(sender, "list-row", "role", role, "player", name,
                        "hp", health(online));
            }
        }
        if (party.size() == 1) {
            messages.send(sender, "list-empty");
        }
        messages.send(sender, "list-footer",
                "percent", String.valueOf(plugin.service().potentialBonus(party)));
    }

    private static String health(Player player) {
        return String.valueOf(Math.max(0L, Math.round(player.getHealth())));
    }

    /* -------------------------------- админские ------------------------------- */

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdkparty.admin")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            plugin.messages().send(sender, "admin-usage");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadParty();
                plugin.messages().send(sender, "admin-reloaded");
                return true;
            }
            case "info" -> {
                plugin.messages().send(sender, "admin-info",
                        "parties", String.valueOf(plugin.store().size()),
                        "members", String.valueOf(plugin.store().memberCount()),
                        "max", String.valueOf(plugin.config().maxSize()),
                        "enabled", plugin.config().enabled() ? "да" : "нет");
                return true;
            }
            case "disband" -> {
                if (args.length < 2) {
                    plugin.messages().send(sender, "admin-usage");
                    return true;
                }
                Player online = Bukkit.getPlayerExact(args[1]);
                var offline = online == null ? Bukkit.getOfflinePlayerIfCached(args[1]) : null;
                if (online == null && offline == null) {
                    plugin.messages().send(sender, "target-not-found", "player", args[1]);
                    return true;
                }
                UUID id = online != null ? online.getUniqueId() : offline.getUniqueId();
                if (!plugin.service().forceDisband(id)) {
                    plugin.messages().send(sender, "target-not-in-party", "player", args[1]);
                    return true;
                }
                plugin.requestSave();
                plugin.messages().send(sender, "admin-disbanded", "player", args[1]);
                return true;
            }
            default -> {
                plugin.messages().send(sender, "unknown-subcommand", "sub", args[0]);
                return true;
            }
        }
    }

    /* ------------------------------ доп. утилиты ------------------------------ */

    private static UUID findMember(Party party, String name) {
        if (name == null) {
            return null;
        }
        for (UUID member : party.memberIds()) {
            if (party.nameOf(member).equalsIgnoreCase(name)) {
                return member;
            }
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias,
                                      String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("pc")) {
            return List.of();
        }
        if (name.equals("partyadmin")) {
            if (!sender.hasPermission("chrdkparty.admin")) {
                return List.of();
            }
            return args.length == 1 ? filter(ADMIN_SUBS, args[0]) : List.of();
        }
        if (args.length == 1) {
            return filter(PLAYER_SUBS, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("invite") || sub.equals("info")) {
                return filter(onlineNames(), args[1]);
            }
            if (sub.equals("kick")) {
                Party party = sender instanceof Player player
                        ? plugin.service().of(player.getUniqueId()) : null;
                if (party == null) {
                    return List.of();
                }
                List<String> names = new ArrayList<>();
                for (UUID member : party.memberIds()) {
                    names.add(party.nameOf(member));
                }
                return filter(names, args[1]);
            }
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
        String needle = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(needle)) {
                out.add(option);
            }
        }
        return out;
    }
}
