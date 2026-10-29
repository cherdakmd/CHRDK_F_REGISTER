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
            plugin.messages().send(player, "list-line",
                    "name", Messages.trusted(plugin.messages().raw("skill." + skill.id() + ".name")),
                    "level", String.valueOf(level),
                    "bar", SkillCurve.bar(progress.percent(), 10),
                    "current", String.valueOf(progress.into()),
                    "need", String.valueOf(progress.need()));
        }
        plugin.messages().send(player, "list-footer");
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
        plugin.messages().send(player, "card-progress",
                "bar", SkillCurve.bar(progress.percent(), 10),
                "current", String.valueOf(progress.into()),
                "need", String.valueOf(progress.need()),
                "percent", String.valueOf(progress.percent()));
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
                List<String> options = new ArrayList<>(List.of("list", "top", "menu", "use", "energy"));
                for (SkillType skill : SkillRegistry.all()) {
                    options.add(skill.id());
                }
                return filter(options, args[0]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("use"))
                return filter(java.util.Arrays.stream(SkillAbility.values()).map(SkillAbility::id).toList(), args[1]);
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
