package ru.cherdakmd.chrdkevents;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Команды {@code /events} и {@code /eventsadmin} (1.4.160).
 *
 * <p>Игроку — что идёт сейчас, когда следующее и список всех событий.
 * Админу — принудительный запуск, остановка, перепланирование и перезагрузка
 * конфига. Все решения о старте и остановке принимает {@link EventService},
 * команда только показывает итог.</p>
 */
public final class EventsCommandHandler implements CommandExecutor, TabCompleter {

    private static final List<String> PLAYER_SUBS = List.of("help", "list", "next", "info");
    private static final List<String> ADMIN_SUBS = List.of("force", "stop", "next", "reload",
            "list", "history");
    /** Сколько записей истории показывать по умолчанию и максимум. */
    private static final int DEFAULT_HISTORY = 10;
    private static final int MAX_HISTORY = 50;

    private final EventsPlugin plugin;

    public EventsCommandHandler(EventsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("eventsadmin")) {
            return admin(sender, args);
        }
        if (!sender.hasPermission("chrdkevents.use")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "help" -> {
                help(sender);
                yield true;
            }
            case "list" -> {
                list(sender);
                yield true;
            }
            case "next" -> {
                next(sender);
                yield true;
            }
            case "info", "now" -> {
                info(sender);
                yield true;
            }
            default -> {
                plugin.messages().send(sender, "unknown-sub", "sub", args[0]);
                help(sender);
                yield true;
            }
        };
    }

    /* --------------------------------- игроку --------------------------------- */

    private void help(CommandSender sender) {
        plugin.messages().send(sender, "help-header");
        for (String line : List.of("help-info", "help-next", "help-list")) {
            plugin.messages().send(sender, line);
        }
        if (sender.hasPermission("chrdkevents.admin")) {
            plugin.messages().send(sender, "help-admin");
        }
    }

    private void info(CommandSender sender) {
        EventService service = plugin.service();
        long now = System.currentTimeMillis();
        WorldEvent current = service.current();
        if (current == null) {
            plugin.messages().send(sender, "no-active");
            next(sender);
            return;
        }
        plugin.messages().send(sender, "active",
                "name", current.name(),
                "time", EventText.clock(service.secondsLeft(now)),
                "kind", kindName(current.kind()));
        if (!current.description().isEmpty()) {
            plugin.messages().send(sender, "active-description",
                    "description", current.description());
        }
        String bonus = bonusText(current);
        if (!bonus.isEmpty()) {
            plugin.messages().send(sender, "active-bonus", "bonus", bonus);
        }
    }

    private void next(CommandSender sender) {
        EventService service = plugin.service();
        if (service.active()) {
            plugin.messages().send(sender, "next-while-active");
            return;
        }
        long seconds = service.secondsToNext(System.currentTimeMillis());
        if (seconds <= 0L) {
            plugin.messages().send(sender, "next-unknown");
            return;
        }
        plugin.messages().send(sender, "next",
                "time", EventText.clock(seconds),
                "human", EventText.duration(seconds));
    }

    private void list(CommandSender sender) {
        List<WorldEvent> events = plugin.config().events();
        if (events.isEmpty()) {
            plugin.messages().send(sender, "list-empty");
            return;
        }
        plugin.messages().send(sender, "list-header", "count", String.valueOf(events.size()));
        long now = System.currentTimeMillis();
        for (WorldEvent event : events) {
            String state = plugin.service().coolingDown(event.id(), now)
                    ? plugin.messages().plain("list-cooling") : plugin.messages().plain("list-ready");
            plugin.messages().send(sender, "list-line",
                    "name", event.name(),
                    "kind", kindName(event.kind()),
                    "bonus", bonusText(event),
                    "time", EventText.duration(event.durationSeconds()),
                    "state", state);
        }
    }

    /* --------------------------------- админу --------------------------------- */

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chrdkevents.admin")) {
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
            case "force" -> force(sender, args);
            case "stop" -> stop(sender);
            case "next" -> replan(sender);
            case "list" -> list(sender);
            case "history" -> history(sender, args);
            default -> plugin.messages().send(sender, "admin-usage");
        }
        return true;
    }

    private void reload(CommandSender sender) {
        plugin.reloadEvents();
        plugin.messages().send(sender, "admin-reloaded",
                "count", String.valueOf(plugin.config().events().size()),
                "min", String.valueOf(plugin.config().minDelaySeconds()),
                "max", String.valueOf(plugin.config().maxDelaySeconds()));
    }

    private void force(CommandSender sender, String[] args) {
        EventService service = plugin.service();
        if (!plugin.config().enabled()) {
            plugin.messages().send(sender, "disabled");
            return;
        }
        if (service.active()) {
            plugin.messages().send(sender, "admin-force-active",
                    "name", service.current().name());
            return;
        }
        WorldEvent started;
        if (args.length > 1) {
            started = plugin.startById(args[1]);
            if (started == null) {
                plugin.messages().send(sender, "admin-force-unknown", "id", args[1]);
                return;
            }
        } else {
            started = plugin.forceRandom();
            if (started == null) {
                plugin.messages().send(sender, "admin-force-none");
                return;
            }
        }
        plugin.messages().send(sender, "admin-force-ok",
                "name", started.name(),
                "time", EventText.duration(started.durationSeconds()));
    }

    private void stop(CommandSender sender) {
        WorldEvent stopped = plugin.stopCurrent();
        if (stopped == null) {
            plugin.messages().send(sender, "admin-stop-none");
            return;
        }
        plugin.messages().send(sender, "admin-stop-ok", "name", stopped.name());
    }

    private void replan(CommandSender sender) {
        EventService service = plugin.service();
        if (service.active()) {
            plugin.messages().send(sender, "admin-next-active");
            return;
        }
        service.planNext(System.currentTimeMillis());
        plugin.messages().send(sender, "admin-next-ok",
                "time", EventText.clock(service.secondsToNext(System.currentTimeMillis())));
    }

    /** Последние события: когда, что и сколько длилось. */
    private void history(CommandSender sender, String[] args) {
        int limit = DEFAULT_HISTORY;
        if (args.length > 1) {
            try {
                limit = Math.clamp(Integer.parseInt(args[1]), 1, MAX_HISTORY);
            } catch (NumberFormatException error) {
                plugin.messages().send(sender, "admin-history-bad-count");
                return;
            }
        }
        List<EventsStore.Entry> entries = plugin.store().history();
        if (entries.isEmpty()) {
            plugin.messages().send(sender, "admin-history-empty");
            return;
        }
        plugin.messages().send(sender, "admin-history-header",
                "count", String.valueOf(entries.size()));
        int shown = 0;
        for (int index = entries.size() - 1; index >= 0 && shown < limit; index--) {
            EventsStore.Entry entry = entries.get(index);
            String duration = entry.open()
                    ? plugin.messages().plain("admin-history-running")
                    : EventText.duration(entry.duration());
            plugin.messages().send(sender, "admin-history-line",
                    "time", EventText.stamp(entry.started()),
                    "name", entry.name(),
                    "duration", duration);
            shown++;
        }
    }

    /* -------------------------------- помощники ------------------------------- */

    private String kindName(EventKind kind) {
        String key = "kind." + kind.id();
        return plugin.messages().has(key) ? plugin.messages().plain(key) : kind.id();
    }

    /**
     * Короткая строка «что даёт»: для текущего события и списка.
     *
     * <p>К надбавке вида (1.4.176) добавляется его «физическая» половина:
     * падающие звёзды и нашествие мобов. Без этого список обещал бы только
     * проценты, а игрок видел бы в небе незнакомые искры.</p>
     */
    private String bonusText(WorldEvent event) {
        String base = switch (event.kind()) {
            case XP -> plugin.messages().plain("bonus-value-xp", "percent",
                    String.valueOf(event.percent()));
            case MINING -> plugin.messages().plain("bonus-value-mining", "percent",
                    String.valueOf(event.percent()));
            case LOOT -> plugin.messages().plain("bonus-value-loot", "percent",
                    String.valueOf(event.percent()));
            case EFFECT -> plugin.messages().plain("bonus-value-effect");
        };
        StringBuilder out = new StringBuilder(base);
        if (event.fallingStars()) {
            out.append(" · ").append(plugin.messages().plain("bonus-extra-meteors"));
        }
        if (event.hordeActive()) {
            out.append(" · ").append(plugin.messages().plain("bonus-extra-horde"));
        }
        return out.toString();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias,
                                      String[] args) {
        if (!(sender instanceof Player) && args.length > 1) {
            return List.of();
        }
        if (command.getName().equalsIgnoreCase("eventsadmin")) {
            if (!sender.hasPermission("chrdkevents.admin")) {
                return List.of();
            }
            if (args.length == 1) {
                return filter(ADMIN_SUBS, args[0]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("force")) {
                return filter(plugin.config().eventIds(), args[1]);
            }
            return List.of();
        }
        if (!sender.hasPermission("chrdkevents.use")) {
            return List.of();
        }
        return args.length == 1 ? filter(PLAYER_SUBS, args[0]) : List.of();
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
