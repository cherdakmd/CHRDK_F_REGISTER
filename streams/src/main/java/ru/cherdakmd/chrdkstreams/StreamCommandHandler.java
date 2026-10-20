package ru.cherdakmd.chrdkstreams;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.cherdakmd.chrdkregister.Messages;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Команда {@code /stream}: привязка канала, проверка эфира, статус, отвязка,
 * зрительские заявки и чек-ины, панель стримера, админская статистика
 * и перечитывание конфига.
 *
 * <p>Сетевые запросы уходят в отдельный поток: основной поток не ждёт Twitch и
 * VK Play Live, а ответ игрок получает, когда площадка ответила.</p>
 */
public final class StreamCommandHandler implements TabExecutor {

    private static final int STATS_LIMIT = 50;

    private final StreamPlugin plugin;
    private final StreamService service;

    public StreamCommandHandler(StreamPlugin plugin, StreamService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        boolean admin = sender.hasPermission("chrdkstreams.admin");
        if (!sender.hasPermission("chrdkstreams.use") && !admin) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (plugin.coreApi() == null) {
            plugin.messages().send(sender, "no-core");
            return true;
        }
        if (args.length == 0) {
            if (sender instanceof Player player) {
                status(player);
            } else {
                usage(sender, label, admin);
            }
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "link" -> link(sender, args, label);
            case "check" -> check(sender);
            case "status" -> {
                if (sender instanceof Player player) {
                    status(player);
                } else {
                    plugin.messages().send(sender, "players-only");
                }
            }
            case "unlink" -> unlink(sender);
            case "watch" -> watch(sender, args, label);
            case "watching" -> watching(sender);
            case "code" -> code(sender, args, label);
            case "viewers" -> viewers(sender, args, label);
            case "viewer" -> viewer(sender, args, label);
            case "stats" -> {
                if (!admin) {
                    plugin.messages().send(sender, "no-permission");
                } else {
                    stats(sender);
                }
            }
            case "reload" -> {
                if (!admin) {
                    plugin.messages().send(sender, "no-permission");
                } else {
                    reload(sender);
                }
            }
            case "help" -> usage(sender, label, admin);
            default -> usage(sender, label, admin);
        }
        return true;
    }

    private void usage(CommandSender sender, String label, boolean admin) {
        plugin.messages().send(sender, "usage", "label", label);
        plugin.messages().send(sender, "usage-viewer", "label", label);
        if (admin) {
            plugin.messages().send(sender, "usage-admin", "label", label);
        }
    }

    /* -------------------------------- link -------------------------------- */

    private void link(CommandSender sender, String[] args, String label) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only");
            return;
        }
        StreamConfig config = service.config();
        if (!config.enabled()) {
            plugin.messages().send(sender, "disabled");
            return;
        }
        if (config.platforms().isEmpty()) {
            plugin.messages().send(sender, "no-platforms");
            return;
        }
        if (args.length < 3) {
            usage(sender, label, false);
            return;
        }
        Optional<StreamPlatform> parsed = StreamPlatform.parse(args[1]);
        if (parsed.isEmpty()) {
            plugin.messages().send(sender, "link-unknown-platform",
                    "name", args[1], "list", StreamPlatform.ids());
            return;
        }
        StreamPlatform platform = parsed.get();
        if (!config.available(platform)) {
            plugin.messages().send(sender, "no-platform",
                    "name", platform.displayName(),
                    "list", StreamPlatform.ids(config.platforms()));
            return;
        }
        String channel = StreamPlatform.normalizeChannel(args[2]);
        if (!StreamPlatform.validChannel(channel)) {
            plugin.messages().send(sender, "link-invalid-channel");
            return;
        }
        StreamStore store = service.store();
        Optional<StreamBinding> owner = store.byChannel(platform, channel);
        if (owner.isPresent() && !owner.get().uuid().equals(player.getUniqueId())) {
            plugin.messages().send(sender, "link-taken-player", "channel", channel);
            return;
        }
        Optional<StreamBinding> current = store.get(player.getUniqueId());
        boolean same = current.isPresent()
                && current.get().platform() == platform
                && current.get().channel().equalsIgnoreCase(channel);
        if (same) {
            if (current.get().verified()) {
                plugin.messages().send(sender, "check-already");
            } else {
                showCode(player, current.get(), label);
            }
            return;
        }
        boolean replaced = current.isPresent();
        String code = StreamVerification.generate();
        StreamRewards.State state = current.map(StreamBinding::state)
                .orElse(StreamRewards.State.empty());
        // Без проверки кодом канал доверенный — подтверждаем сразу.
        long verifiedAt = config.requireVerified() ? 0L : System.currentTimeMillis();
        StreamBinding binding = new StreamBinding(player.getUniqueId(), player.getName(),
                platform, channel, code, verifiedAt, state);
        store.put(binding);
        service.forget(player.getUniqueId());
        saveAsync();

        plugin.messages().send(player, "link-header", "platform", platform.displayName());
        if (replaced) {
            plugin.messages().send(player, "link-replaced",
                    "channel", current.get().channel());
        }
        plugin.messages().send(player, "link-created", "channel", channel);
        if (binding.verified()) {
            plugin.messages().send(player, "link-trusted");
        } else {
            showCode(player, binding, label);
        }
    }

    private void showCode(Player player, StreamBinding binding, String label) {
        plugin.messages().send(player, "link-code", "code", binding.code());
        plugin.messages().send(player, "link-howto",
                "platform", binding.platform().displayName(), "label", label);
        plugin.messages().send(player, "link-url", "url", binding.url());
    }

    /* -------------------------------- check -------------------------------- */

    private void check(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only");
            return;
        }
        StreamConfig config = service.config();
        if (!config.enabled()) {
            plugin.messages().send(sender, "disabled");
            return;
        }
        if (config.platforms().isEmpty()) {
            plugin.messages().send(sender, "no-platforms");
            return;
        }
        StreamBinding binding = service.store().get(player.getUniqueId()).orElse(null);
        if (binding == null) {
            plugin.messages().send(sender, "check-no-channel");
            return;
        }
        if (binding.verified()) {
            plugin.messages().send(sender, "check-already");
            return;
        }
        if (!config.available(binding.platform())) {
            plugin.messages().send(sender, "no-platform",
                    "name", binding.platform().displayName(),
                    "list", StreamPlatform.ids(config.platforms()));
            return;
        }
        plugin.messages().send(sender, "check-live", "channel", binding.channel());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            LiveStream live;
            try {
                live = service.fetchLive(binding.platform(), binding.channel());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException exception) {
                plugin.messages().send(player, "check-error", "error", exception.getMessage());
                return;
            }
            if (!live.live()) {
                plugin.messages().send(player, "check-offline", "code", binding.code());
                return;
            }
            if (!StreamVerification.matches(live.title(), binding.code())) {
                plugin.messages().send(player, "check-no-code",
                        "title", live.title().isBlank() ? "—" : live.title(),
                        "code", binding.code());
                return;
            }
            service.store().put(binding.markVerified(System.currentTimeMillis()));
            saveAsync();
            plugin.getLogger().info("[Streams] Канал подтверждён: " + binding.playerName()
                    + " → " + binding.platform().id() + "/" + binding.channel());
            plugin.messages().send(player, "check-verified");
        });
    }

    /* ------------------------------- зрители ------------------------------- */

    private void watch(CommandSender sender, String[] args, String label) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only");
            return;
        }
        if (args.length >= 2 && ("stop".equalsIgnoreCase(args[1]) || "off".equalsIgnoreCase(args[1]))) {
            plugin.viewerService().leave(player);
            return;
        }
        if (args.length < 2) {
            plugin.viewerService().watching(player);
            return;
        }
        plugin.viewerService().apply(player, args[1]);
    }

    private void watching(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only");
            return;
        }
        plugin.viewerService().watching(player);
    }

    private void code(CommandSender sender, String[] args, String label) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only");
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "watch-usage", "label", label);
            return;
        }
        plugin.viewerService().checkIn(player, args[1]);
    }

    private void viewers(CommandSender sender, String[] args, String label) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only");
            return;
        }
        if (args.length >= 2 && "on".equalsIgnoreCase(args[1])) {
            plugin.viewerService().enable(player, true);
            return;
        }
        if (args.length >= 2 && "off".equalsIgnoreCase(args[1])) {
            plugin.viewerService().enable(player, false);
            return;
        }
        if (args.length >= 2) {
            plugin.messages().send(sender, "viewers-usage", "label", label);
            return;
        }
        plugin.viewerService().panel(player);
    }

    private void viewer(CommandSender sender, String[] args, String label) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only");
            return;
        }
        if (args.length < 3 || !("accept".equalsIgnoreCase(args[1])
                || "deny".equalsIgnoreCase(args[1]))) {
            plugin.messages().send(sender, "viewers-usage", "label", label);
            return;
        }
        if ("accept".equalsIgnoreCase(args[1])) {
            plugin.viewerService().accept(player, args[2]);
        } else {
            plugin.viewerService().deny(player, args[2]);
        }
    }

    /* -------------------------------- status -------------------------------- */

    private void status(Player player) {
        StreamConfig config = service.config();
        if (!config.enabled()) {
            plugin.messages().send(player, "disabled");
        }
        StreamBinding binding = service.store().get(player.getUniqueId()).orElse(null);
        if (binding == null) {
            plugin.messages().send(player, "not-linked", "label", "stream");
            return;
        }
        plugin.messages().send(player, "status-header", "player", player.getName());
        plugin.messages().send(player, "status-line",
                "channel", binding.channel(),
                "platform", binding.platform().displayName());
        if (binding.verified()) {
            plugin.messages().send(player, "status-verified");
        } else {
            plugin.messages().send(player, "status-unverified", "code", binding.code());
        }
        StreamRewards.Rule rule = config.rule();
        plugin.messages().send(player, "status-progress",
                "minutes", StreamRewards.pendingMinutes(binding.state(), rule),
                "needed", config.minutesPerDay(),
                "today", binding.state().awardedToday(),
                "max", config.maxDaysPerDay());
        plugin.messages().send(player, "status-total", "days", binding.state().totalDays());
        if (config.requireOnline()) {
            plugin.messages().send(player, "status-online-hint");
        }
        plugin.messages().send(player, "status-footer",
                "minutes", config.minutesPerDay(), "max", config.maxDaysPerDay());
        if (!config.available(binding.platform())) {
            plugin.messages().send(player, "status-error");
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                LiveStream live = service.fetchLive(binding.platform(), binding.channel());
                if (live.live()) {
                    plugin.messages().send(player, "status-live", "viewers", live.viewers());
                } else {
                    plugin.messages().send(player, "status-offline");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException exception) {
                plugin.messages().send(player, "status-error");
            }
        });
    }

    /* -------------------------------- unlink -------------------------------- */

    private void unlink(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only");
            return;
        }
        if (!service.store().remove(player.getUniqueId())) {
            plugin.messages().send(sender, "not-linked", "label", "stream");
            return;
        }
        service.forget(player.getUniqueId());
        saveAsync();
        plugin.messages().send(sender, "unlinked");
    }

    /* -------------------------------- admin -------------------------------- */

    private void stats(CommandSender sender) {
        List<StreamBinding> all = new ArrayList<>(service.store().all());
        plugin.messages().send(sender, "stats-header", "count", all.size());
        plugin.messages().send(sender, "stats-viewers",
                "count", plugin.viewerService().viewerCount(),
                "streamers", plugin.viewerService().streamerCount());
        if (all.isEmpty()) {
            plugin.messages().send(sender, "stats-empty");
            return;
        }
        int shown = 0;
        for (StreamBinding binding : all) {
            if (shown >= STATS_LIMIT) {
                break;
            }
            plugin.messages().send(sender, "stats-line",
                    "player", binding.playerName(),
                    "platform", binding.platform().displayName(),
                    "channel", binding.channel(),
                    "state", trustedState(binding.verified()),
                    "days", binding.state().totalDays());
            shown++;
        }
    }

    private void reload(CommandSender sender) {
        plugin.reloadStreams();
        StreamConfig config = plugin.config();
        plugin.messages().send(sender, "reloaded",
                "platforms", StreamPlatform.ids(config.platforms()),
                "minutes", config.minutesPerDay(),
                "max", config.maxDaysPerDay());
    }

    /* ------------------------------ помощники ------------------------------ */

    private Object trustedState(boolean verified) {
        if (verified) {
            return Messages.trusted(plugin.messages().raw("stats-state-verified"));
        }
        return Messages.trusted(plugin.messages().raw("stats-state-unverified"));
    }

    /** Сохранить данные вне основного потока: запись на диск не должна тормозить тик. */
    private void saveAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> service.store().save());
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, String @NotNull [] args) {
        boolean admin = sender.hasPermission("chrdkstreams.admin");
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of(
                    "link", "check", "status", "unlink",
                    "watch", "watching", "code", "viewers", "viewer"));
            if (admin) {
                options.add("stats");
                options.add("reload");
            }
            return filter(options, args[0]);
        }
        if (args.length == 2 && "link".equalsIgnoreCase(args[0])) {
            return filter(service.config().platforms().stream()
                    .map(StreamPlatform::id).toList(), args[1]);
        }
        if (args.length == 2 && "watch".equalsIgnoreCase(args[0])) {
            List<String> names = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                names.add(online.getName());
            }
            names.add("stop");
            return filter(names, args[1]);
        }
        if (args.length == 2 && "viewers".equalsIgnoreCase(args[0])) {
            return filter(List.of("on", "off"), args[1]);
        }
        if (args.length == 2 && "viewer".equalsIgnoreCase(args[0])) {
            return filter(List.of("accept", "deny"), args[1]);
        }
        if (args.length == 3 && "viewer".equalsIgnoreCase(args[0])
                && sender instanceof Player player) {
            return filter(plugin.viewerService().pendingNames(player.getUniqueId()), args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }
}
