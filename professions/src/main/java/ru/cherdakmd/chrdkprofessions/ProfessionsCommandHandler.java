package ru.cherdakmd.chrdkprofessions;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Команды {@code /prof} и {@code /profadmin} (1.4.165).
 *
 * <p>Здесь только разбор аргументов и тексты: можно ли выбрать профессию,
 * сколько опыта осталось и вырос ли уровень, решают {@link ProfessionService}
 * и хранилище. Поэтому команда и меню ведут себя одинаково — включая кулдаун
 * смены и суточный потолок.</p>
 */
public final class ProfessionsCommandHandler implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("help", "menu", "choose", "info", "stats", "top");
    private static final List<String> ADMIN_SUBS = List.of("reload", "info", "set", "xp", "reset");
    private static final int MAX_ADMIN_XP = 100_000;

    private final ProfessionsPlugin plugin;

    public ProfessionsCommandHandler(ProfessionsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("profadmin")) {
            return admin(sender, args);
        }
        if (!sender.hasPermission("chrdkprofessions.use")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (!plugin.config().enabled()) {
            plugin.messages().send(sender, "disabled");
            return true;
        }
        if (args.length == 0) {
            menu(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> help(sender);
            case "menu" -> menu(sender);
            case "choose" -> choose(sender, args);
            case "info" -> info(sender, args);
            case "stats" -> stats(sender);
            case "top" -> top(sender);
            default -> plugin.messages().send(sender, "unknown-subcommand", "sub", args[0]);
        }
        return true;
    }

    /* --------------------------------- игроку --------------------------------- */

    private void help(CommandSender sender) {
        plugin.messages().send(sender, "help-header");
        plugin.messages().send(sender, "help-menu");
        plugin.messages().send(sender, "help-choose");
        plugin.messages().send(sender, "help-info");
        plugin.messages().send(sender, "help-stats");
        plugin.messages().send(sender, "help-top");
        plugin.messages().send(sender, "help-footer",
                "cap", cap() <= 0 ? "∞" : String.valueOf(cap()),
                "hours", String.valueOf(plugin.config().chooseCooldownHours()));
    }

    private void menu(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return;
        }
        plugin.gui().open(player);
    }

    private void choose(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "choose-usage");
            return;
        }
        ProfessionResult result = plugin.service().choose(player.getUniqueId(), args[1],
                System.currentTimeMillis());
        sendResult(sender, result);
        if (result.ok()) {
            plugin.requestSave();
        }
    }

    private void info(CommandSender sender, String[] args) {
        Profession target = args.length > 1
                ? Profession.parse(args[1])
                : plugin.service().active(id(sender));
        if (args.length > 1 && target == null) {
            plugin.messages().send(sender, "profession-unknown", "profession", args[1]);
            return;
        }
        if (target == null) {
            plugin.messages().send(sender, "info-none");
            plugin.messages().send(sender, "info-none-hint",
                    "hours", String.valueOf(plugin.config().chooseCooldownHours()));
            return;
        }
        UUID player = id(sender);
        int level = plugin.service().level(player, target);
        int needed = ProfessionMath.needed(level);
        plugin.messages().send(sender, "info-header", "profession",
                ProfessionText.nameComponent(plugin.messages(), target));
        plugin.messages().send(sender, "profession." + target.id() + ".desc");
        plugin.messages().send(sender, "info-level",
                "level", String.valueOf(level),
                "rank", ProfessionText.rankComponent(plugin.messages(), level),
                "xp", String.valueOf(ProfessionMath.progress(plugin.service().xp(player, target))),
                "needed", needed <= 0 ? "∞" : String.valueOf(needed));
        plugin.messages().send(sender, "info-perk",
                "perk", ProfessionText.perk(plugin.messages(), target, level));
        daily(sender, player, target);
        if (plugin.service().active(player) != target) {
            plugin.messages().send(sender, "info-not-active");
        }
        long left = plugin.service().chooseCooldownLeft(player, System.currentTimeMillis());
        if (left > 0) {
            plugin.messages().send(sender, "info-cooldown", "time", ProfessionMath.clock(left));
        }
        plugin.messages().send(sender, "info-footer");
    }

    private void stats(CommandSender sender) {
        UUID player = id(sender);
        if (player == null) {
            plugin.messages().send(sender, "player-only");
            return;
        }
        plugin.messages().send(sender, "stats-header");
        Profession active = plugin.service().active(player);
        for (Profession profession : Profession.values()) {
            int level = plugin.service().level(player, profession);
            int xp = plugin.service().xp(player, profession);
            int needed = ProfessionMath.needed(level);
            String key = profession == active ? "stats-current" : "stats-line";
            plugin.messages().send(sender, key,
                    "profession", ProfessionText.nameComponent(plugin.messages(), profession),
                    "level", String.valueOf(level),
                    "rank", ProfessionText.rankComponent(plugin.messages(), level),
                    "xp", String.valueOf(ProfessionMath.progress(xp)),
                    "needed", needed <= 0 ? "∞" : String.valueOf(needed));
        }
        plugin.messages().send(sender, "stats-footer");
    }

    private void top(CommandSender sender) {
        List<Map.Entry<Profession, Integer>> rows = new ArrayList<>();
        for (Profession profession : Profession.values()) {
            rows.add(Map.entry(profession, plugin.store().countOf(profession)));
        }
        rows.sort(Comparator.<Map.Entry<Profession, Integer>>comparingInt(Map.Entry::getValue)
                .reversed()
                .thenComparing(entry -> entry.getKey().id()));
        plugin.messages().send(sender, "top-header", "count", String.valueOf(plugin.store().size()));
        int place = 1;
        for (Map.Entry<Profession, Integer> row : rows) {
            plugin.messages().send(sender, "top-line",
                    "place", String.valueOf(place++),
                    "profession", ProfessionText.nameComponent(plugin.messages(), row.getKey()),
                    "count", String.valueOf(row.getValue()));
        }
    }

    /* --------------------------------- админу --------------------------------- */

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdkprofessions.admin")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            plugin.messages().send(sender, "admin-usage");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> reload(sender);
            case "info" -> adminInfo(sender, args);
            case "set" -> adminSet(sender, args);
            case "xp" -> adminXp(sender, args);
            case "reset" -> adminReset(sender, args);
            default -> plugin.messages().send(sender, "unknown-subcommand", "sub", args[0]);
        }
        return true;
    }

    private void reload(CommandSender sender) {
        plugin.reloadProfessions();
        plugin.messages().send(sender, "admin-reloaded",
                "count", String.valueOf(Profession.values().length));
    }

    private void adminInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "admin-usage");
            return;
        }
        UUID target = target(sender, args[1]);
        if (target == null) {
            return;
        }
        String name = displayName(target, args[1]);
        Profession active = plugin.service().active(target);
        if (active == null) {
            plugin.messages().send(sender, "admin-info-none", "player", name);
            return;
        }
        plugin.messages().send(sender, "admin-info-header", "player", name);
        plugin.messages().send(sender, "admin-info-line",
                "profession", ProfessionText.nameComponent(plugin.messages(), active),
                "level", String.valueOf(plugin.service().level(target, active)),
                "xp", String.valueOf(plugin.service().xp(target, active)),
                "total", String.valueOf(plugin.service().totalXp(target)));
    }

    private void adminSet(CommandSender sender, String[] args) {
        if (args.length < 3) {
            plugin.messages().send(sender, "admin-usage");
            return;
        }
        UUID target = target(sender, args[1]);
        if (target == null) {
            return;
        }
        ProfessionResult result = plugin.service().setByAdmin(target, args[2],
                System.currentTimeMillis());
        sendResult(sender, result);
        if (!result.ok()) {
            return;
        }
        plugin.requestSave();
        Profession profession = Profession.parse(result.args().get(1));
        Player online = Bukkit.getPlayer(target);
        if (online != null && profession != null) {
            plugin.messages().send(online, "admin-set-notice",
                    "profession", ProfessionText.nameComponent(plugin.messages(), profession));
        }
    }

    private void adminXp(CommandSender sender, String[] args) {
        if (args.length < 4) {
            plugin.messages().send(sender, "admin-usage");
            return;
        }
        UUID target = target(sender, args[1]);
        if (target == null) {
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[3]);
        } catch (NumberFormatException broken) {
            plugin.messages().send(sender, "admin-xp-amount", "max", String.valueOf(MAX_ADMIN_XP));
            return;
        }
        if (amount <= 0 || amount > MAX_ADMIN_XP) {
            plugin.messages().send(sender, "admin-xp-amount", "max", String.valueOf(MAX_ADMIN_XP));
            return;
        }
        ProfessionResult result = plugin.service().grantByAdmin(target, args[2], amount);
        sendResult(sender, result);
        if (!result.ok()) {
            return;
        }
        plugin.requestSave();
        plugin.messages().send(sender, "admin-xp-note",
                "player", displayName(target, args[1]));
    }

    private void adminReset(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "admin-usage");
            return;
        }
        UUID target = target(sender, args[1]);
        if (target == null) {
            return;
        }
        ProfessionResult result = plugin.service().resetByAdmin(target);
        if (!result.ok()) {
            sendResult(sender, result);
            return;
        }
        plugin.requestSave();
        plugin.messages().send(sender, "admin-reset-ok",
                "player", displayName(target, args[1]));
    }

    /* -------------------------------- помощники -------------------------------- */

    private void daily(CommandSender sender, UUID player, Profession profession) {
        int used = plugin.service().dailyUsed(player, profession, System.currentTimeMillis());
        int left = plugin.service().dailyLeft(player, profession, System.currentTimeMillis());
        if (left < 0) {
            plugin.messages().send(sender, "info-daily-unlimited", "used", String.valueOf(used));
            return;
        }
        plugin.messages().send(sender, "info-daily",
                "used", String.valueOf(used),
                "cap", String.valueOf(cap()),
                "left", String.valueOf(left));
    }

    private int cap() {
        return plugin.config().dailyXpCap();
    }

    private void sendResult(CommandSender sender, ProfessionResult result) {
        plugin.messages().send(sender, result.key(),
                ProfessionText.localise(plugin.messages(), result).toArray());
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        plugin.messages().send(sender, "player-only");
        return null;
    }

    private static UUID id(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }

    /** Ид игрока по нику: сначала онлайн, потом сохранённые данные. */
    private UUID target(CommandSender sender, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            plugin.store().rename(online.getUniqueId(), online.getName());
            return online.getUniqueId();
        }
        UUID stored = plugin.store().byName(name);
        if (stored != null) {
            return stored;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (offline.hasPlayedBefore() || offline.isOnline()) {
            return offline.getUniqueId();
        }
        plugin.messages().send(sender, "admin-target-none", "player", name);
        return null;
    }

    private String displayName(UUID id, String fallback) {
        String stored = plugin.store().name(id);
        return stored == null || stored.isBlank() ? fallback : stored;
    }

    /* -------------------------------- автодополнение --------------------------- */

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias,
                                      String[] args) {
        boolean admin = command.getName().equalsIgnoreCase("profadmin");
        if (admin) {
            if (!sender.hasPermission("chrdkprofessions.admin")) {
                return List.of();
            }
            if (args.length == 1) {
                return filter(ADMIN_SUBS, args[0]);
            }
            if (args.length == 2) {
                String sub = args[0].toLowerCase(Locale.ROOT);
                return sub.equals("reload") ? List.of() : filter(playerNames(), args[1]);
            }
            if (args.length == 3) {
                String sub = args[0].toLowerCase(Locale.ROOT);
                return sub.equals("set") || sub.equals("xp")
                        ? filter(professionIds(), args[2]) : List.of();
            }
            return List.of();
        }
        if (!sender.hasPermission("chrdkprofessions.use")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(SUBS, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            return sub.equals("choose") || sub.equals("info")
                    ? filter(professionIds(), args[1]) : List.of();
        }
        return List.of();
    }

    private static List<String> professionIds() {
        List<String> ids = new ArrayList<>();
        for (Profession profession : Profession.values()) {
            ids.add(profession.id());
        }
        return ids;
    }

    private static List<String> playerNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    private static List<String> filter(List<String> values, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(value);
            }
        }
        return out;
    }
}
