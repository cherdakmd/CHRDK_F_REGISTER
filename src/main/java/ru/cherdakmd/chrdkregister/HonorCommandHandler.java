package ru.cherdakmd.chrdkregister;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Команды особых званий.
 *
 * <p>{@code /honor} — публичный реестр: кто, когда и за что получил звание.
 * Доступен всем, именно поэтому выдача не выглядит тайным «кумовством».
 * {@code /honoradmin} — полноценный редактор: создать звание, поправить
 * градиент/знак/ступень, выдать конкретному игроку, снять и перечитать файл.
 * Права на изменения — {@code chrdkregister.honors.admin} или op.</p>
 */
public final class HonorCommandHandler implements CommandExecutor, TabCompleter {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.systemDefault());

    private static final List<String> PUBLIC = List.of("list", "my", "info", "rules", "help");
    private static final List<String> ADMIN = List.of(
            "gui", "create", "edit", "delete", "grant", "revoke", "list", "info", "reload");
    private static final List<String> FIELDS = List.of(
            "name", "format", "badge", "level", "description", "join", "listed");

    private final RegisterPlugin plugin;
    private final HonorService honors;
    private final HonorGui gui;

    public HonorCommandHandler(RegisterPlugin plugin, HonorService honors, HonorGui gui) {
        this.plugin = plugin;
        this.honors = honors;
        this.gui = gui;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        boolean admin = command.getName().equalsIgnoreCase("honoradmin");
        return admin ? adminCommand(sender, args) : publicCommand(sender, args);
    }

    /* --------------------------------- /honor -------------------------------- */

    private boolean publicCommand(CommandSender sender, String[] args) {
        if (!honors.enabled()) {
            plugin.messages().send(sender, "honor.disabled");
            return true;
        }
        String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list", "реестр" -> list(sender, honors.publicRegistry(), false);
            case "my", "моё" -> {
                String name = args.length >= 2 ? args[1] : sender.getName();
                if (!sender.getName().equalsIgnoreCase(name) && !honors.canEdit(sender)) {
                    plugin.messages().send(sender, "honoradmin.no-permission");
                    return true;
                }
                HonorGrant grant = honors.ledger().grantOf(name);
                HonorDefinition def = honors.ledger().active(name);
                if (grant == null || def == null) {
                    plugin.messages().send(sender, "honor.my-none", "player", name);
                } else {
                    plugin.messages().send(sender, "honor.my",
                            "honor", def.name(), "date", date(grant.grantedAt()),
                            "reason", grant.reason(), "actor", grant.grantedBy());
                }
            }
            case "info", "карточка" -> {
                if (args.length < 2) {
                    plugin.messages().send(sender, "honor.usage");
                    return true;
                }
                info(sender, args[1], false);
            }
            case "rules", "правила" -> plugin.messages().sendList(sender, "honor.rules");
            case "help", "?" -> plugin.messages().send(sender, "honor.usage");
            default -> plugin.messages().send(sender, "honor.usage");
        }
        return true;
    }

    /* ------------------------------ /honoradmin ----------------------------- */

    private boolean adminCommand(CommandSender sender, String[] args) {
        if (!honors.canEdit(sender)) {
            plugin.messages().send(sender, "honoradmin.no-permission");
            return true;
        }
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "gui", "панель" -> {
                if (!(sender instanceof Player player)) {
                    plugin.messages().send(sender, "honoradmin.gui-only");
                    return true;
                }
                gui.open(player, HonorGui.Page.DEFINITIONS, "");
            }
            case "create", "создать" -> {
                if (args.length < 2) {
                    plugin.messages().send(sender, "honoradmin.usage-create");
                    return true;
                }
                honors.create(sender, args[1], join(args, 2));
            }
            case "edit", "изменить" -> {
                if (args.length < 4) {
                    plugin.messages().send(sender, "honoradmin.usage-edit");
                    return true;
                }
                honors.edit(sender, args[1], args[2], join(args, 3));
            }
            case "delete", "remove", "удалить" -> {
                if (args.length < 2) {
                    plugin.messages().send(sender, "honoradmin.usage-delete");
                    return true;
                }
                honors.remove(sender, args[1]);
            }
            case "grant", "выдать" -> {
                if (args.length < 3) {
                    plugin.messages().send(sender, "honoradmin.usage-grant");
                    return true;
                }
                honors.grant(sender, args[1], args[2], join(args, 3));
            }
            case "revoke", "take", "снять" -> {
                if (args.length < 2) {
                    plugin.messages().send(sender, "honoradmin.usage-revoke");
                    return true;
                }
                honors.revoke(sender, args[1], args.length >= 3 ? args[2] : "");
            }
            case "list", "реестр" -> list(sender, honors.ledger().allGrants(), true);
            case "info", "карточка" -> {
                if (args.length < 2) {
                    plugin.messages().send(sender, "honoradmin.usage-info");
                    return true;
                }
                info(sender, args[1], true);
            }
            case "reload", "перечитать" -> {
                honors.reload(plugin.getConfig());
                honors.reloadStore();
                gui.refreshAll();
                plugin.messages().send(sender, "honoradmin.reloaded");
            }
            case "help", "?" -> plugin.messages().send(sender, "honoradmin.usage");
            default -> plugin.messages().send(sender, "honoradmin.usage");
        }
        return true;
    }

    private void list(CommandSender sender, List<HonorGrant> grants, boolean full) {
        if (grants.isEmpty()) {
            plugin.messages().send(sender, full ? "honoradmin.list-empty" : "honor.list-empty");
            return;
        }
        plugin.messages().send(sender, full ? "honoradmin.list-header" : "honor.list-header",
                "count", grants.size());
        for (HonorGrant grant : grants) {
            plugin.messages().send(sender, full ? "honoradmin.list-row" : "honor.list-row",
                    "player", grant.player(),
                    "honor", honors.ledger().display(grant.honorId()),
                    "date", date(grant.grantedAt()),
                    "reason", grant.reason(),
                    "actor", grant.grantedBy());
        }
    }

    private void info(CommandSender sender, String rawId, boolean full) {
        HonorDefinition def = honors.ledger().definition(rawId);
        if (def == null) {
            plugin.messages().send(sender, "honoradmin.not-found",
                    "id", HonorDefinition.normalizeId(rawId));
            return;
        }
        plugin.messages().send(sender, full ? "honoradmin.info" : "honor.info",
                "id", def.id(), "name", def.name(), "level", def.grantLevel(),
                "badge", def.badge(), "format", def.format(),
                "description", def.description(),
                "listed", def.listed() ? "да" : "нет");
    }

    private static String date(long millis) {
        return millis <= 0 ? "—" : DATE.format(Instant.ofEpochMilli(millis));
    }

    private static String join(String[] args, int from) {
        if (args.length <= from) return "";
        return String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length)).trim();
    }

    /* ------------------------------- tab-complete --------------------------- */

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command, @NotNull String alias,
                                                @NotNull String[] args) {
        boolean admin = command.getName().equalsIgnoreCase("honoradmin");
        if (admin && !honors.canEdit(sender)) return List.of();
        List<String> options = admin ? ADMIN : PUBLIC;
        if (args.length == 1) return filter(options, args[0]);
        if (args.length == 2) {
            String action = args[0].toLowerCase(Locale.ROOT);
            if (!admin) {
                return action.equals("info") ? filter(ids(), args[1]) : List.of();
            }
            if (action.equals("grant") || action.equals("выдать")
                    || action.equals("revoke") || action.equals("take") || action.equals("снять")) {
                return filter(onlineNames(), args[1]);
            }
            if (List.of("edit", "изменить", "delete", "remove", "удалить", "info", "карточка")
                    .contains(action)) {
                return filter(ids(), args[1]);
            }
            return List.of();
        }
        if (admin && args.length == 3) {
            String action = args[0].toLowerCase(Locale.ROOT);
            if (action.equals("edit") || action.equals("изменить")) return filter(FIELDS, args[2]);
            if (action.equals("grant") || action.equals("выдать")) return filter(ids(), args[2]);
            if (action.equals("revoke") || action.equals("take")) return filter(ids(), args[2]);
        }
        return List.of();
    }

    private List<String> ids() {
        return honors.ledger().definitions().stream().map(HonorDefinition::id).toList();
    }

    private static List<String> onlineNames() {
        List<String> out = new ArrayList<>();
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) out.add(player.getName());
        return out;
    }

    private static List<String> filter(List<String> source, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return source.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
