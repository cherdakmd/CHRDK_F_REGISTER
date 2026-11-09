package ru.cherdakmd.chrdkskills;

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

/**
 * {@code /skills} — витрина, список и топ; {@code /skillsadmin} — управление.
 *
 * <p>Админские команды меняют опыт и уровни, поэтому каждая операция пишется в
 * лог с ником администратора: по опыту потом видно, откуда у игрока высокий
 * навык — ни одно событие не даёт таких чисел, как {@code givexp}.</p>
 */
public final class SkillCommandHandler implements CommandExecutor, TabCompleter {

    private final SkillsPlugin plugin;
    private final SkillService service;
    private final SkillsGui gui;

    public SkillCommandHandler(SkillsPlugin plugin, SkillService service, SkillsGui gui) {
        this.plugin = plugin;
        this.service = service;
        this.gui = gui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("skills")) {
            return handlePlayer(sender, args);
        }
        if (command.getName().equalsIgnoreCase("skillsadmin")) {
            return handleAdmin(sender, args);
        }
        return false;
    }

    private boolean handlePlayer(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdkskills.use")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("menu")) {
            if (!(sender instanceof Player player)) {
                plugin.messages().send(sender, "player-only");
                return true;
            }
            gui.open(player);
            return true;
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (args[0].equalsIgnoreCase("tree") || args[0].equalsIgnoreCase("perktree")
                || args[0].equalsIgnoreCase("perks")) {
            gui.open(player, SkillsMenuApi.Tab.TREE, args.length > 1 ? args[1] : null, 0);
            return true;
        }
        if (args[0].equalsIgnoreCase("rating")) {
            gui.open(player, SkillsMenuApi.Tab.TOP, null, 0);
            return true;
        }
        if (args[0].equalsIgnoreCase("energy")) {
            plugin.abilities().status(player); return true;
        }
        if (args[0].equalsIgnoreCase("use")) {
            if (args.length != 2) { plugin.messages().send(player, "ability-usage"); return true; }
            plugin.abilities().activate(player, SkillAbility.of(SkillType.byId(args[1]))); return true;
        }
        if (args[0].equalsIgnoreCase("list")) {
            return handleList(player);
        }
        if (args[0].equalsIgnoreCase("top")) {
            return handleTop(player);
        }
        if (args[0].equalsIgnoreCase("prestige")) {
            return handlePrestige(player, args);
        }
        if (args[0].equalsIgnoreCase("prestigetop")) {
            return handlePrestigeTop(player);
        }
        if (args[0].equalsIgnoreCase("balance")) {
            return handleBalance(player);
        }
        SkillType skill = SkillType.byId(args[0]);
        if (skill == null) {
            plugin.messages().send(player, "unknown-skill");
            return true;
        }
        return handleCard(player, skill);
    }

    private boolean handleList(Player player) {
        if (!plugin.config().enabled()) {
            plugin.messages().send(player, "skills-disabled");
            return true;
        }
        plugin.messages().send(player, "list-header",
                "total", String.valueOf(service.totalLevel(player)));
        for (SkillType skill : SkillRegistry.all()) {
            int level = service.level(player, skill);
            SkillCurve.LevelProgress progress = service.progress(player, skill);
            if (progress.maxed()) {
                plugin.messages().send(player, "list-line-max",
                        "name", Messages.trusted(plugin.messages().raw("skill." + skill.id() + ".name")),
                        "level", String.valueOf(level),
                        "bar", SkillCurve.bar(progress.percent(), 10),
                        "xp", String.valueOf(service.xp(player, skill)));
            } else {
                plugin.messages().send(player, "list-line",
                        "name", Messages.trusted(plugin.messages().raw("skill." + skill.id() + ".name")),
                        "level", String.valueOf(level),
                        "bar", SkillCurve.bar(progress.percent(), 10),
                        "current", String.valueOf(progress.into()),
                        "need", String.valueOf(progress.need()));
            }
        }
        plugin.messages().send(player, "list-footer");
        return true;
    }

    /** Без аргумента — витрина престижа; с аргументом — двухшаговый сброс. */
    private boolean handlePrestige(Player player, String[] args) {
        if (args.length == 1) {
            gui.open(player, SkillsMenuApi.Tab.PRESTIGE, null, 0);
            return true;
        }
        if (args.length != 2) {
            plugin.messages().send(player, "prestige-usage");
            return true;
        }
        SkillService.PrestigeResult result;
        if (args[1].equalsIgnoreCase("all")) {
            result = service.requestPrestigeAll(player);
        } else {
            SkillType skill = SkillType.byId(args[1]);
            if (skill == null) {
                plugin.messages().send(player, "unknown-skill");
                return true;
            }
            result = service.requestPrestige(player, skill);
        }
        switch (result) {
            case DISABLED -> plugin.messages().send(player, "prestige-disabled");
            case AT_CAP -> plugin.messages().send(player, "prestige-at-cap",
                    "cap", String.valueOf(PrestigeMath.MAX_PER_SKILL));
            case NOT_MAX -> plugin.messages().send(player, "prestige-not-max",
                    "level", String.valueOf(plugin.config().curve().maxLevel()));
            case PENDING -> plugin.messages().send(player, "prestige-confirm");
            case DONE -> {
                plugin.messages().send(player, "prestige-done",
                        "total", String.valueOf(service.totalPrestige(player)),
                        "title", Messages.trusted(service.prestigeTier(player).title()));
                plugin.getLogger().info("[CHRDK_Skills] " + player.getName()
                        + " подтвердил престиж, серверный уровень "
                        + service.totalPrestige(player));
            }
        }
        return true;
    }

    /** Топ престижа в чат — та же выборка, что и на вкладке меню. */
    private boolean handlePrestigeTop(Player player) {
        plugin.messages().send(player, "prestige-top-header");
        int place = 1;
        for (SkillService.PrestigeEntry entry : service.prestigeTop()) {
            plugin.messages().send(player, "prestige-top-line",
                    "place", String.valueOf(place++),
                    "name", entry.name(),
                    "total", String.valueOf(entry.total()));
        }
        if (place == 1) {
            plugin.messages().send(player, "prestige-top-empty");
        }
        return true;
    }

    /** Таблица «навык → ступень → уровень → множитель» и что делать дальше. */
    private boolean handleBalance(Player player) {
        if (!plugin.config().prestigeEnabled() || !plugin.config().prestigeBalanceEnabled()) {
            plugin.messages().send(player, "prestige-disabled");
            return true;
        }
        SkillService.BalanceReport report = service.balanceReport(player);
        if (report == null) {
            plugin.messages().send(player, "prestige-disabled");
            return true;
        }
        plugin.messages().send(player, "balance-header",
                "allowance", String.valueOf(report.allowance()),
                "base", String.valueOf(report.baseMaxLead()),
                "rank_bonus", String.valueOf(report.rankBonus()),
                "rank", String.valueOf(report.rankLevel()),
                "hard_cap", String.valueOf(report.hardCap()));
        for (SkillService.BalanceRow row : report.rows()) {
            String name = plugin.messages().raw("skill." + row.skill().id() + ".name");
            if (!row.tracked()) {
                plugin.messages().send(player, "balance-line-off", "name", Messages.trusted(name));
            } else if (row.factor() <= 0.0) {
                plugin.messages().send(player, "balance-line-stop",
                        "name", Messages.trusted(name),
                        "step", String.valueOf(row.step()),
                        "level", String.valueOf(row.level()),
                        "lead", String.valueOf(row.lead()));
            } else if (row.factor() >= 1.0) {
                plugin.messages().send(player, "balance-line-full",
                        "name", Messages.trusted(name),
                        "step", String.valueOf(row.step()),
                        "level", String.valueOf(row.level()));
            } else {
                plugin.messages().send(player, "balance-line",
                        "name", Messages.trusted(name),
                        "step", String.valueOf(row.step()),
                        "level", String.valueOf(row.level()),
                        "lead", String.valueOf(row.lead()),
                        "factor", PrestigeXpGate.factorText(row.factor()));
            }
        }
        plugin.messages().send(player, "balance-footer",
                "minimum", String.valueOf(report.minimum()));
        return true;
    }

    private boolean handleTop(Player player) {
        List<SkillService.Entry> top = service.top();
        if (top.isEmpty()) {
            plugin.messages().send(player, "top-empty");
            return true;
        }
        plugin.messages().send(player, "top-header");
        int place = 1;
        for (SkillService.Entry entry : top) {
            plugin.messages().send(player, "top-line",
                    "place", String.valueOf(place++),
                    "name", entry.name(),
                    "level", String.valueOf(entry.level()),
                    "xp", String.valueOf(entry.xp()));
        }
        return true;
    }

    private boolean handleCard(Player player, SkillType skill) {
        int level = service.level(player, skill);
        SkillCurve.LevelProgress progress = service.progress(player, skill);
        plugin.messages().send(player, "card-header",
                "name", Messages.trusted(plugin.messages().raw("skill." + skill.id() + ".name")),
                "level", String.valueOf(level));
        plugin.messages().send(player, "card-desc",
                "text", Messages.trusted(plugin.messages().raw("skill." + skill.id() + ".desc")));
        if (progress.maxed()) {
            plugin.messages().send(player, "card-progress-max",
                    "bar", SkillCurve.bar(progress.percent(), 10),
                    "xp", String.valueOf(service.xp(player, skill)));
        } else {
            plugin.messages().send(player, "card-progress",
                    "bar", SkillCurve.bar(progress.percent(), 10),
                    "current", String.valueOf(progress.into()),
                    "need", String.valueOf(progress.need()),
                    "percent", String.valueOf(progress.percent()));
        }
        plugin.messages().send(player, "card-steps", "steps", SkillRegistry.thresholdLine());
        for (SkillPerk perk : SkillRegistry.perksOf(skill)) {
            boolean active = level >= perk.level();
            plugin.messages().send(player, active ? "card-perk-active" : "card-perk-locked",
                    "level", String.valueOf(perk.level()),
                    "name", Messages.trusted(plugin.messages().raw("perk." + perk.id() + ".name")),
                    "desc", Messages.trusted(plugin.messages().raw("perk." + perk.id() + ".desc")));
        }
        plugin.messages().send(player, "card-footer");
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdkskills.admin")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (plugin.awards().admin(sender,args)) return true;
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadSkillsConfig();
            plugin.messages().send(sender, "admin-reloaded");
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("reset")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                plugin.messages().send(sender, "admin-player-not-found");
                return true;
            }
            service.reset(target);
            plugin.messages().send(sender, "admin-reset", "player", target.getName());
            plugin.getLogger().info("[CHRDK_Skills] " + sender.getName()
                    + " сбросил навыки игроку " + target.getName());
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("givexp")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                plugin.messages().send(sender, "admin-player-not-found");
                return true;
            }
            SkillType skill = args.length >= 3 ? SkillType.byId(args[2]) : null;
            if (skill == null) {
                plugin.messages().send(sender, "unknown-skill");
                return true;
            }
            long amount = args.length >= 4 ? parseLong(args[3]) : 0L;
            if (amount <= 0L) {
                plugin.messages().send(sender, "admin-usage");
                return true;
            }
            SkillService.Award award = service.grantXp(target, skill, amount);
            plugin.messages().send(sender, "admin-gave-xp",
                    "player", target.getName(),
                    "xp", String.valueOf(amount),
                    "skill", Messages.trusted(plugin.messages().raw("skill." + skill.id() + ".name")),
                    "level", String.valueOf(award.newLevel()));
            plugin.getLogger().info("[CHRDK_Skills] " + sender.getName() + " начислил "
                    + target.getName() + " " + amount + " опыта навыка " + skill.id());
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("setlevel")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                plugin.messages().send(sender, "admin-player-not-found");
                return true;
            }
            SkillType skill = args.length >= 3 ? SkillType.byId(args[2]) : null;
            if (skill == null) {
                plugin.messages().send(sender, "unknown-skill");
                return true;
            }
            int level = args.length >= 4 ? (int) Math.max(1, Math.min(
                    plugin.config().curve().maxLevel(), parseLong(args[3]))) : 0;
            if (level <= 0) {
                plugin.messages().send(sender, "admin-usage");
                return true;
            }
            service.setLevel(target, skill, level);
            plugin.messages().send(sender, "admin-set-level",
                    "player", target.getName(),
                    "skill", Messages.trusted(plugin.messages().raw("skill." + skill.id() + ".name")),
                    "level", String.valueOf(level));
            plugin.getLogger().info("[CHRDK_Skills] " + sender.getName() + " установил "
                    + target.getName() + " уровень " + level + " навыка " + skill.id());
            return true;
        }
        plugin.messages().send(sender, "admin-usage");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("skills")) {
            if (args.length == 1) {
                List<String> options = new ArrayList<>(List.of(
                        "list", "top", "rating", "menu", "tree", "perks", "perktree",
                        "use", "energy", "prestige", "prestigetop", "balance"));
                for (SkillType skill : SkillRegistry.all()) {
                    options.add(skill.id());
                }
                return filter(options, args[0]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("use"))
                return filter(java.util.Arrays.stream(SkillAbility.values()).map(SkillAbility::id).toList(), args[1]);
            if (args.length == 2 && args[0].equalsIgnoreCase("prestige")) {
                List<String> ids = new ArrayList<>(SkillRegistry.all().stream()
                        .map(SkillType::id).toList());
                ids.add("all");
                return filter(ids, args[1]);
            }
            return List.of();
        }
        if (!command.getName().equalsIgnoreCase("skillsadmin")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(List.of("reload", "givexp", "setlevel", "reset"), args[0]);
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("reload")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT)
                            .startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .limit(20).toList();
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("givexp")
                || args[0].equalsIgnoreCase("setlevel"))) {
            List<String> ids = new ArrayList<>();
            for (SkillType skill : SkillRegistry.all()) {
                ids.add(skill.id());
            }
            return filter(ids, args[2]);
        }
        return List.of();
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value == null ? "" : value.trim());
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private static List<String> filter(List<String> options, String prefix) {
        String low = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(low))
                .toList();
    }
}
