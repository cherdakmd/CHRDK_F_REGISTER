package ru.cherdakmd.chrdkguilds;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Команды {@code /guild}, {@code /gchat} и {@code /guildadmin} (1.4.163).
 *
 * <p>Разбор аргументов и тексты: всё, что меняет состояние, решает
 * {@link GuildService}. Меню и команда поэтому не могут разойтись — и не может
 * получиться так, что кнопка в GUI обходит проверку прав.</p>
 */
final class GuildsCommandHandler implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("help", "menu", "gui", "create", "invite",
            "accept", "decline", "leave", "kick", "promote", "demote", "transfer", "disband",
            "deposit", "withdraw", "upgrade", "info", "top", "members", "chat", "war",
            "rename", "retag", "mode", "peace", "dues");
    private static final List<String> WAR_SUBS = List.of("declare", "accept", "decline",
            "status", "surrender");
    private static final List<String> ADMIN_SUBS = List.of("reload", "info", "disband",
            "endwar", "cooldown", "history", "finance", "recover");

    private final GuildsPlugin plugin;

    GuildsCommandHandler(GuildsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Общий маршрут GUI сохраняет все проверки авторизации, прав и режима. */
    static String canonicalSubcommand(String argument) {
        String key=argument.toLowerCase(Locale.ROOT);
        return key.equals("gui")?"menu":key;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player actor && !plugin.authenticated(actor)) {
            plugin.messages().send(sender,"finance-auth"); return true;
        }
        if (command.getName().equalsIgnoreCase("guildadmin")) {
            return admin(sender, args);
        }
        if (!sender.hasPermission("chrdkguilds.use")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(sender, "disabled");
            return true;
        }
        if (command.getName().equalsIgnoreCase("gchat")) {
            if (!(sender instanceof Player player)) {
                plugin.messages().send(sender, "player-only");
                return true;
            }
            return chat(player, args);
        }
        if (args.length == 0) {
            return menu(sender);
        }
        switch (canonicalSubcommand(args[0])) {
            case "dues" -> {Player p=player(sender);if(p!=null)plugin.peace().dues(p,args);}
            case "mode", "peace" -> { Player player=player(sender);if(player!=null)plugin.peace().command(player,args); }
            case "help" -> help(sender);
            case "menu" -> menu(sender);
            case "create" -> create(sender, args);
            case "invite" -> invite(sender, args);
            case "accept" -> join(sender,args);
            case "decline" -> decline(sender);
            case "leave" -> leave(sender);
            case "kick" -> kick(sender, args);
            case "promote" -> promote(sender, args);
            case "demote" -> demote(sender, args);
            case "transfer" -> transfer(sender, args);
            case "disband" -> disband(sender, args);
            case "deposit" -> deposit(sender, args);
            case "withdraw" -> withdraw(sender, args);
            case "upgrade" -> upgrade(sender);
            case "info" -> info(sender, args);
            case "top" -> top(sender, args);
            case "members" -> members(sender);
            case "chat" -> chatFrom(sender, args.length > 1
                    ? Arrays.copyOfRange(args, 1, args.length) : new String[0]);
            case "war" -> war(sender, args);
            case "rename" -> rename(sender, args);
            case "retag" -> retag(sender, args);
            default -> {
                plugin.messages().send(sender, "unknown-subcommand", "sub", args[0]);
                help(sender);
            }
        }
        return true;
    }

    /* --------------------------------- справка -------------------------------- */

    private void help(CommandSender sender) {
        plugin.messages().send(sender, "help-header");
        for (String key : List.of("peace-help", "help-create", "help-invite", "help-join", "help-leave",
                "help-ranks", "help-treasury", "help-war", "help-chat", "help-info", "help-top")) {
            plugin.messages().send(sender, key);
        }
    }

    /* ---------------------------------- меню ---------------------------------- */

    private boolean menu(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        plugin.gui().open(player);
        return true;
    }

    /* --------------------------------- создание ------------------------------- */

    private void create(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (args.length < 3) {
            plugin.messages().send(sender, "create-usage",
                    "min", String.valueOf(plugin.config().minName()),
                    "max", String.valueOf(plugin.config().maxName()));
            return;
        }
        boolean confirmed=args.length>=4&&args[args.length-1].equalsIgnoreCase("confirm");
        int end=args.length-(confirmed?1:0);
        String name = join(args, 1, end-1);
        String tag = args[end-1];
        if(!plugin.peace().confirmCreate(player,name,tag,confirmed))return;
        GuildResult result = plugin.finance().create(player,
                name, tag, System.currentTimeMillis());
        plugin.messages().send(sender, result.key(), result.array());
        if (result.ok()) {
            plugin.requestSave();
            Bukkit.getServer().broadcast(plugin.messages().get("create-broadcast",
                    "name", Guild.cleanName(name), "tag", Guild.cleanTag(tag),
                    "leader", player.getName()));
        }
    }

    /* ------------------------------- приглашения ------------------------------ */

    private void invite(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "invite-usage");
            return;
        }
        UUID targetId = resolve(args[1]);
        if (targetId == null) {
            plugin.messages().send(sender, "target-not-found", "player", args[1]);
            return;
        }
        GuildResult result = plugin.service().invite(player.getUniqueId(), targetId,
                System.currentTimeMillis());
        plugin.messages().send(sender, result.key(), result.array());
        if (!result.ok()) {
            return;
        }
        plugin.requestSave();
        Player target = Bukkit.getPlayer(targetId);
        if (plugin.visible(target)) {
            Guild guild = plugin.service().guildOf(player.getUniqueId());
            if(guild!=null)plugin.messages().send(target,"dues-invite","rate",guild.duesRate(),"peace",guild.peaceful()?10:0);
            plugin.messages().send(target, "invite-received",
                    "player", player.getName(),
                    "name", guild == null ? "?" : guild.label(),
                    "seconds", String.valueOf(plugin.config().inviteSeconds()));
        }
    }

    private void join(CommandSender sender,String[] args) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if(!plugin.peace().confirmJoin(player,args.length==2&&args[1].equalsIgnoreCase("confirm")))return;
        GuildResult result = plugin.finance().join(player,System.currentTimeMillis());
        plugin.messages().send(sender, result.key(), result.array());
        if (!result.ok()) {
            return;
        }
        plugin.requestSave();
        Guild guild = plugin.service().guildOf(player.getUniqueId());
        broadcastGuild(guild, "join-broadcast", "player", player.getName());
    }

    private void decline(CommandSender sender) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        GuildResult result = plugin.service().decline(player.getUniqueId());
        plugin.messages().send(sender, result.key(), result.array());
        if (result.ok()) {
            plugin.requestSave();
        }
    }

    /* ---------------------------------- состав -------------------------------- */

    private void leave(CommandSender sender) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        Guild guild = plugin.service().guildOf(player.getUniqueId());
        GuildResult result = plugin.service().leave(player.getUniqueId());
        plugin.messages().send(sender, result.key(), result.array());
        if (result.ok()) {
            plugin.requestSave();
            broadcastGuild(guild, "leave-broadcast", "player", player.getName());
        }
    }

    private void kick(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "kick-usage");
            return;
        }
        UUID targetId = resolve(args[1]);
        if (targetId == null) {
            plugin.messages().send(sender, "target-not-found", "player", args[1]);
            return;
        }
        Guild guild = plugin.service().guildOf(player.getUniqueId());
        GuildResult result = plugin.service().kick(player.getUniqueId(), targetId);
        plugin.messages().send(sender, result.key(), result.array());
        if (!result.ok()) {
            return;
        }
        plugin.requestSave();
        broadcastGuild(guild, "kick-broadcast", "player", args[1], "by", player.getName());
        Player target = Bukkit.getPlayer(targetId);
        if (plugin.visible(target)) {
            plugin.messages().send(target, "kick-notice",
                    "name", guild == null ? "?" : guild.name());
        }
    }

    private void promote(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "rank-usage");
            return;
        }
        UUID targetId = resolve(args[1]);
        if (targetId == null) {
            plugin.messages().send(sender, "target-not-found", "player", args[1]);
            return;
        }
        GuildResult result = plugin.service().promote(player.getUniqueId(), targetId);
        plugin.messages().send(sender, result.key(), result.array());
        if (result.ok()) {
            plugin.requestSave();
            Player target = Bukkit.getPlayer(targetId);
            if (plugin.visible(target)) {
                plugin.messages().send(target, "promote-notice",
                        "rank", plugin.messages().mini("rank.officer"));
            }
        }
    }

    private void demote(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "rank-usage");
            return;
        }
        UUID targetId = resolve(args[1]);
        if (targetId == null) {
            plugin.messages().send(sender, "target-not-found", "player", args[1]);
            return;
        }
        GuildResult result = plugin.service().demote(player.getUniqueId(), targetId);
        plugin.messages().send(sender, result.key(), result.array());
        if (result.ok()) {
            plugin.requestSave();
            Player target = Bukkit.getPlayer(targetId);
            if (plugin.visible(target)) {
                plugin.messages().send(target, "demote-notice",
                        "rank", plugin.messages().mini("rank.member"));
            }
        }
    }

    private void transfer(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "transfer-usage");
            return;
        }
        UUID targetId = resolve(args[1]);
        if (targetId == null) {
            plugin.messages().send(sender, "target-not-found", "player", args[1]);
            return;
        }
        GuildResult result = plugin.service().transfer(player.getUniqueId(), targetId);
        plugin.messages().send(sender, result.key(), result.array());
        if (result.ok()) {
            plugin.requestSave();
            Player target = Bukkit.getPlayer(targetId);
            if (plugin.visible(target)) {
                plugin.messages().send(target, "transfer-notice",
                        "player", player.getName());
            }
        }
    }

    private void disband(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            Guild guild = plugin.service().guildOf(player.getUniqueId());
            plugin.messages().send(sender, "disband-confirm",
                    "name", guild == null ? "?" : guild.name(),
                    "treasury", guild == null ? "0" : GuildMath.diamonds(guild.treasury()),
                    "returns", guild == null ? "0" : GuildMath.diamonds(Math.addExact(guild.warReturns(),guild.duesReserve())));
            return;
        }
        GuildResult result = plugin.service().disband(player.getUniqueId());
        if (!result.ok()) {
            plugin.messages().send(sender, result.key(), result.array());
            return;
        }
        plugin.messages().send(sender, "disband-ok",
                "name", arg(result, "name"),
                "treasury", arg(result, "treasury"),
                "returns", arg(result, "returns"),
                "members", arg(result, "members"),
                "war", plugin.messages().mini(arg(result, "war")));
        plugin.requestSave();
        Bukkit.getServer().broadcast(plugin.messages().get("disband-broadcast",
                "name", arg(result, "name")));
    }

    private void rename(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "rename-usage");
            return;
        }
        GuildResult result = plugin.service().rename(player.getUniqueId(),
                join(args, 1, args.length));
        plugin.messages().send(sender, result.key(), result.array());
        if (result.ok()) {
            plugin.requestSave();
        }
    }

    private void retag(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "retag-usage",
                    "max", String.valueOf(plugin.config().maxTag()));
            return;
        }
        GuildResult result = plugin.service().retag(player.getUniqueId(), args[1]);
        plugin.messages().send(sender, result.key(), result.array());
        if (result.ok()) {
            plugin.requestSave();
        }
    }

    /* ---------------------------------- казна --------------------------------- */

    private void deposit(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "deposit-usage");
            return;
        }
        long amount = amount(player, args[1]);
        if (amount <= 0) {
            plugin.messages().send(sender, "amount-bad");
            return;
        }
        GuildResult result = plugin.finance().deposit(player,
                amount);
        plugin.messages().send(sender, result.key(), result.array());
        if (result.ok()) {
            plugin.requestSave();
            Guild guild = plugin.service().guildOf(player.getUniqueId());
            broadcastGuild(guild, "deposit-broadcast", "player", player.getName(),
                    "amount", arg(result, "amount"));
        }
    }

    private void withdraw(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "withdraw-usage");
            return;
        }
        long amount = all(args[1]) ? (plugin.service().guildOf(player.getUniqueId()) == null ? 0 : plugin.service().guildOf(player.getUniqueId()).treasury()) : parseLong(args[1],-1);
        if (amount <= 0) {
            plugin.messages().send(sender, "amount-bad");
            return;
        }
        GuildResult result = plugin.finance().withdraw(player,
                amount);
        plugin.messages().send(sender, result.key(), result.array());
        if (result.ok()) {
            plugin.requestSave();
        }
    }

    private void upgrade(CommandSender sender) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        Guild guild = plugin.service().guildOf(player.getUniqueId());
        GuildResult result = plugin.service().upgrade(player.getUniqueId());
        plugin.messages().send(sender, result.key(), result.array());
        if (result.ok()) {
            plugin.requestSave();
            broadcastGuild(guild, "upgrade-broadcast", "level", arg(result, "level"),
                    "cap", arg(result, "cap"));
        }
    }

    /* --------------------------------- сведения ------------------------------- */

    private void info(CommandSender sender, String[] args) {
        Guild guild = args.length > 1
                ? plugin.service().find(args[1])
                : (sender instanceof Player player
                ? plugin.service().guildOf(player.getUniqueId()) : null);
        if (guild == null) {
            if (args.length > 1) {
                plugin.messages().send(sender, "guild-not-found", "name", args[1]);
            } else {
                plugin.messages().send(sender, "not-in-guild");
            }
            return;
        }
        showGuild(sender, guild);
    }

    private void showGuild(CommandSender sender, Guild guild) {
        String leaderName = nameOf(guild.leader());
        plugin.messages().send(sender, "info-header", "name", guild.name(),
                "tag", guild.tag().isEmpty() ? "—" : guild.tag());
        plugin.messages().send(sender, "info-leader", "leader", leaderName);
        plugin.messages().send(sender, "info-members",
                "count", String.valueOf(guild.size()),
                "cap", String.valueOf(GuildMath.memberCap(guild.level())));
        plugin.messages().send(sender, "info-level",
                "level", GuildMath.roman(guild.level()),
                "glory", String.valueOf(guild.glory()));
        plugin.messages().send(sender, "info-treasury",
                "amount", GuildMath.diamonds(guild.treasury()));
        if (guild.warReturns() > 0) plugin.messages().send(sender,"treasury-war-returns","amount",GuildMath.diamonds(guild.warReturns()));
        plugin.messages().send(sender,"dues-info","rate",guild.duesRate(),"reserve",guild.duesReserve());
        plugin.messages().send(sender, "info-wars",
                "won", String.valueOf(guild.warsWon()),
                "lost", String.valueOf(guild.warsLost()));
        GuildWar war = plugin.service().warOf(guild.id());
        if (war != null) {
            Guild opponent = plugin.store().guild(war.opponentOf(guild.id()));
            plugin.messages().send(sender, "info-war",
                    "name", opponent == null ? "?" : opponent.label(),
                    "score", war.pointsOf(guild.id()) + ":" + war.pointsOf(war.opponentOf(guild.id())),
                    "state", plugin.messages().mini(war.pending()
                            ? "war-state-pending" : "war-state-active"));
        }
    }

    private void top(CommandSender sender, String[] args) {
        int limit = 10;
        if (args.length > 1) {
            try {
                limit = Math.clamp(Integer.parseInt(args[1]), 1, 25);
            } catch (NumberFormatException error) {
                plugin.messages().send(sender, "top-usage");
                return;
            }
        }
        List<Guild> guilds = plugin.service().top(limit);
        if (guilds.isEmpty()) {
            plugin.messages().send(sender, "top-empty");
            return;
        }
        plugin.messages().send(sender, "top-header", "count", String.valueOf(guilds.size()));
        int place = 1;
        for (Guild guild : guilds) {
            plugin.messages().send(sender, "top-line",
                    "place", String.valueOf(place),
                    "name", guild.label(),
                    "level", GuildMath.roman(guild.level()),
                    "glory", String.valueOf(guild.glory()),
                    "members", String.valueOf(guild.size()));
            place++;
        }
    }

    private void members(CommandSender sender) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        Guild guild = plugin.service().guildOf(player.getUniqueId());
        if (guild == null) {
            plugin.messages().send(sender, "not-in-guild");
            return;
        }
        plugin.messages().send(sender, "members-header", "name", guild.label());
        for (UUID member : guild.memberIds()) {
            plugin.messages().send(sender, "members-line",
                    "player", nameOf(member),
                    "rank", plugin.messages().mini("rank." + (guild.rankOf(member) == null
                            ? GuildRank.MEMBER.id() : guild.rankOf(member).id())),
                    "online", Bukkit.getPlayer(member) == null
                            ? plugin.messages().mini("status-offline")
                            : plugin.messages().mini("status-online"));
        }
    }

    /* --------------------------------- гильдейский чат ------------------------- */

    /** Вход из общей команды: {@code /guild chat} из консоли отвечает, а не падает. */
    private boolean chatFrom(CommandSender sender, String[] args) {
        Player player = player(sender);
        return player == null || chat(player, args);
    }

    private boolean chat(Player player, String[] args) {
        Guild guild = plugin.service().guildOf(player.getUniqueId());
        if (guild == null) {
            plugin.messages().send(player, "not-in-guild");
            return true;
        }
        if (args.length == 0) {
            plugin.messages().send(player, "chat-usage");
            return true;
        }
        String text = String.join(" ", args).trim();
        if (text.isEmpty()) {
            plugin.messages().send(player, "chat-empty");
            return true;
        }
        var message = plugin.messages().get("chat-format",
                "tag", guild.tag().isEmpty() ? guild.name() : guild.tag(),
                "player", player.getName(),
                "message", text);
        for (UUID member : guild.memberIds()) {
            Player online = Bukkit.getPlayer(member);
            if (plugin.visible(online)) {
                online.sendMessage(message);
            }
        }
        return true;
    }

    /* ---------------------------------- войны --------------------------------- */

    private void war(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        Guild guild = plugin.service().guildOf(player.getUniqueId());
        if (guild == null) {
            plugin.messages().send(sender, "not-in-guild");
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "war-usage");
            return;
        }
        long now = System.currentTimeMillis();
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "declare" -> {
                if (args.length < 3) {
                    plugin.messages().send(sender, "war-usage");
                    return;
                }
                long wager = args.length > 3 ? parseLong(args[3], -1) : plugin.config().minWager();
                GuildResult result = plugin.service().declareWar(player.getUniqueId(),
                        args[2], wager, now);
                plugin.messages().send(sender, result.key(), result.array());
                if (!result.ok()) {
                    return;
                }
                plugin.requestSave();
                Guild target = plugin.service().find(args[2]);
                broadcastGuild(target, "war-declared-broadcast",
                        "name", guild.label(), "wager", arg(result, "wager"),
                        "seconds", arg(result, "seconds"));
            }
            case "accept" -> {
                GuildResult result = plugin.service().acceptWar(player.getUniqueId(), now);
                plugin.messages().send(sender, result.key(), result.array());
                if (!result.ok()) {
                    return;
                }
                plugin.requestSave();
                Bukkit.getServer().broadcast(plugin.messages().get("war-start-broadcast",
                        "challenger", arg(result, "challenger"),
                        "target", arg(result, "target"),
                        "wager", arg(result, "wager"),
                        "time", arg(result, "time")));
            }
            case "decline" -> {
                GuildResult result = plugin.service().declineWar(player.getUniqueId(), now);
                plugin.messages().send(sender, result.key(), result.array());
                if (result.ok()) {
                    plugin.requestSave();
                }
            }
            case "surrender" -> {
                GuildResult result = plugin.service().surrenderWar(player.getUniqueId(), now);
                plugin.messages().send(sender, result.key(), result.array());
                if (result.ok()) {
                    plugin.requestSave();
                }
            }
            case "status" -> warStatus(sender, guild);
            default -> plugin.messages().send(sender, "war-usage");
        }
    }

    private void warStatus(CommandSender sender, Guild guild) {
        GuildWar war = plugin.service().warOf(guild.id());
        if (war == null) {
            plugin.messages().send(sender, "war-status-none");
            return;
        }
        Guild opponent = plugin.store().guild(war.opponentOf(guild.id()));
        plugin.messages().send(sender, "war-status",
                "name", guild.label(),
                "opponent", opponent == null ? "?" : opponent.label(),
                "state", plugin.messages().mini(war.pending()
                        ? "war-state-pending" : "war-state-active"),
                "score", war.pointsOf(guild.id()) + ":"
                        + war.pointsOf(war.opponentOf(guild.id())),
                "wager", GuildMath.diamonds(war.wager()),
                "time", war.active() ? GuildMath.clock(war.secondsLeft(System.currentTimeMillis()))
                        : "—");
    }

    /* ---------------------------------- админ --------------------------------- */

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdkguilds.admin")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            plugin.messages().send(sender, "admin-usage");
            return true;
        }
        if (plugin.payments().admin(sender,args)) return true;
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadGuilds();
                plugin.messages().send(sender, "admin-reloaded",
                        "guilds", String.valueOf(plugin.store().count()),
                        "wars", String.valueOf(plugin.store().wars().size()));
            }
            case "info" -> {
                if (args.length < 2) {
                    plugin.messages().send(sender, "admin-usage");
                    return true;
                }
                Guild guild = plugin.service().find(args[1]);
                if (guild == null) {
                    plugin.messages().send(sender, "guild-not-found", "name", args[1]);
                    return true;
                }
                showGuild(sender, guild);
                GuildWar war = plugin.service().warOf(guild.id());
                plugin.messages().send(sender, "admin-guild-info",
                        "id", guild.id(),
                        "created", guild.createdAt() <= 0 ? "—"
                                : GuildMath.clock((System.currentTimeMillis()
                                - guild.createdAt()) / 1_000L),
                        "war", war == null ? "—" : war.label());
            }
            case "disband" -> {
                if (args.length < 2) {
                    plugin.messages().send(sender, "admin-usage");
                    return true;
                }
                GuildResult result = plugin.service().forceDisband(args[1]);
                if (!result.ok()) {
                    plugin.messages().send(sender, result.key(), result.array());
                    return true;
                }
                plugin.messages().send(sender, "admin-disbanded",
                        "name", arg(result, "name"),
                        "treasury", arg(result, "treasury"),
                        "returns", arg(result, "returns"),
                        "war", plugin.messages().mini(arg(result, "war")));
                plugin.requestSave();
            }
            case "endwar" -> {
                if (args.length < 2) {
                    plugin.messages().send(sender, "admin-usage");
                    return true;
                }
                GuildResult result = plugin.service().forceEndWar(args[1],
                        System.currentTimeMillis());
                plugin.messages().send(sender, result.key(), result.array());
                if (result.ok()) {
                    plugin.requestSave();
                }
            }
            case "cooldown" -> {
                if (args.length < 3 || !args[1].equalsIgnoreCase("reset")) {
                    plugin.messages().send(sender, "admin-usage");
                    return true;
                }
                Guild guild = plugin.service().find(args[2]);
                if (guild == null) {
                    plugin.messages().send(sender, "guild-not-found", "name", args[2]);
                    return true;
                }
                if (plugin.service().clearCooldown(guild)) {
                    plugin.requestSave();
                    plugin.messages().send(sender, "admin-cooldown", "name", guild.name());
                } else {
                    plugin.messages().send(sender, "admin-cooldown-none", "name", guild.name());
                }
            }
            case "history" -> {
                var history = plugin.store().history();
                if (history.isEmpty()) {
                    plugin.messages().send(sender, "admin-history-empty");
                    return true;
                }
                int limit = args.length > 1 ? (int) parseLong(args[1], 5) : 5;
                limit = Math.clamp(limit, 1, GuildStore.MAX_HISTORY);
                int shown = 0;
                for (int index = history.size() - 1; index >= 0 && shown < limit; index--) {
                    GuildStore.WarRecord record = history.get(index);
                    plugin.messages().send(sender, "admin-history-line",
                            "time", record.time(),
                            "challenger", record.challenger(),
                            "target", record.target(),
                            "result", plugin.messages().mini(warResultKey(record.result())),
                            "wager", GuildMath.diamonds(record.wager()));
                    shown++;
                }
            }
            default -> plugin.messages().send(sender, "admin-usage");
        }
        return true;
    }

    /* -------------------------------- помощники ------------------------------- */

    /**
     * Ключ текста итога войны. В истории лежит имя константы {@link GuildWar.Result},
     * поэтому сопоставление явное: сборка ключа строкой не прошла бы контракт
     * «код ↔ messages.yml» и молча ломалась бы при переименовании константы.
     */
    static String warResultKey(String result) {
        return switch (result) {
            case "CHALLENGER_WIN" -> "war-result.challenger_win";
            case "TARGET_WIN" -> "war-result.target_win";
            case "DRAW" -> "war-result.draw";
            case "CANCELLED" -> "war-result.cancelled";
            default -> "admin-history-unknown";
        };
    }


    private Player player(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        plugin.messages().send(sender, "player-only");
        return null;
    }

    /** Отправляет гильдии тот же ключ, что и игроку: тексты не расходятся. */
    private void broadcastGuild(Guild guild, String key, String... pairs) {
        if (guild == null) {
            return;
        }
        var message = plugin.messages().get(key, (Object[]) pairs);
        for (UUID member : guild.memberIds()) {
            Player online = Bukkit.getPlayer(member);
            if (plugin.visible(online)) {
                online.sendMessage(message);
            }
        }
    }

    private static String arg(GuildResult result, String name) {
        List<String> args = result.args();
        for (int index = 0; index + 1 < args.size(); index += 2) {
            if (args.get(index).equals(name)) {
                return args.get(index + 1);
            }
        }
        return "";
    }

    private static String join(String[] args, int from, int to) {
        StringBuilder text = new StringBuilder();
        for (int index = from; index < to && index < args.length; index++) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(args[index]);
        }
        return text.toString();
    }

    static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    /** Сумма взноса: число или {@code all} — весь инвентарь. */
    private static boolean all(String raw) {
        return raw.equalsIgnoreCase("all") || raw.equalsIgnoreCase("все") || raw.equalsIgnoreCase("всё");
    }

    private long amount(Player player, String raw) {
        if (raw.equalsIgnoreCase("all") || raw.equalsIgnoreCase("все")
                || raw.equalsIgnoreCase("всё")) {
            return plugin.finance().available(player);
        }
        return parseLong(raw, -1L);
    }

    private static String nameOf(UUID player) {
        if (player == null) {
            return "?";
        }
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            return online.getName();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(player);
        return offline.getName() == null ? player.toString().substring(0, 8) : offline.getName();
    }

    /** UUID по нику: онлайн — сразу, оффлайн — из профиля сервера. */
    private static UUID resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(name);
        return offline == null || offline.getName() == null ? null : offline.getUniqueId();
    }

    /* ------------------------------- автодополнение --------------------------- */

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias,
                                      String[] args) {
        if (sender instanceof Player actor && !plugin.authenticated(actor)) return List.of();
        if (command.getName().equalsIgnoreCase("guildadmin")) {
            if (!sender.hasPermission("chrdkguilds.admin")) {
                return List.of();
            }
            if (args.length == 1) {
                return filter(ADMIN_SUBS, args[0]);
            }
            if (args.length == 2) {
                if (args[0].equalsIgnoreCase("cooldown")) {
                    return filter(List.of("reset"), args[1]);
                }
                List<String> names = new ArrayList<>();
                for (Guild guild : plugin.store().guilds()) {
                    names.add(guild.name());
                }
                return filter(names, args[1]);
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("cooldown")) {
                List<String> names = new ArrayList<>();
                for (Guild guild : plugin.store().guilds()) {
                    names.add(guild.name());
                }
                return filter(names, args[2]);
            }
            return List.of();
        }
        if (command.getName().equalsIgnoreCase("gchat")) {
            return List.of();
        }
        if (!sender.hasPermission("chrdkguilds.use") || !plugin.config().enabled()) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(SUBS, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (sub) {
                case "dues" -> filter(List.of("status","10","20","50","100"),args[1]);
                case "mode", "peace" -> filter(List.of("status","peaceful","battle","prepay"),args[1]);
                case "war" -> filter(WAR_SUBS, args[1]);
                case "info" -> filter(guildNames(), args[1]);
                case "invite", "kick", "promote", "demote", "transfer" -> filter(memberNames(sender),
                        args[1]);
                case "deposit", "withdraw" -> filter(List.of("all"), args[1]);
                case "accept" -> filter(List.of("confirm"),args[1]);
                case "disband" -> filter(List.of("confirm"), args[1]);
                default -> List.of();
            };
        }
        if(args.length==3&&(sub.equals("mode")||sub.equals("peace")))return filter(args[1].equalsIgnoreCase("prepay")?List.of("1","3","7","14","30"):List.of("confirm"),args[2]);
        if(args.length==4&&(sub.equals("mode")||sub.equals("peace"))&&args[1].equalsIgnoreCase("prepay"))return filter(List.of("confirm"),args[3]);
        if (args.length == 3 && sub.equals("war") && args[1].equalsIgnoreCase("declare")) {
            return filter(guildNames(), args[2]);
        }
        return List.of();
    }

    private List<String> guildNames() {
        List<String> names = new ArrayList<>();
        for (Guild guild : plugin.store().guilds()) {
            names.add(guild.name());
        }
        return names;
    }

    private List<String> memberNames(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        Guild guild = plugin.service().guildOf(player.getUniqueId());
        if (guild == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (UUID member : guild.memberIds()) {
            names.add(nameOf(member));
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
