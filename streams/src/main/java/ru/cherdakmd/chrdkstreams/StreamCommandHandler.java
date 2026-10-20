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
 * зрительские заявки и чек-ины по коду из эфира, панель стримера и
 * подтверждение зрителей-фолловеров, админская статистика и перечитывание
 * конфига.
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
            usage(sender, label, admin);
            if (sender instanceof Player player
                    && service.store().get(player.getUniqueId()).isPresent()) {
                status(player, label);
            }
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "link" -> link(sender, args, label);
            case "check" -> check(sender, args, label);
            case "status" -> {
                if (sender instanceof Player player) {
                    status(player, label);
                } else {
                    plugin.messages().send(sender, "players-only");
                }
            }
            case "unlink" -> unlink(sender, args, label);
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
        StreamBinding current = store.get(player.getUniqueId())
                .orElseGet(() -> new StreamBinding(player.getUniqueId(), player.getName(),
                        List.of(), StreamRewards.State.empty()));
        // Этот же канал уже привязан: подтверждён — подсказываем статус, нет — повторяем код.
        if (current.has(platform, channel)) {
            StreamBinding.Channel existing = current.find(platform).orElse(null);
            if (existing != null && existing.verified()) {
                plugin.messages().send(sender, "check-already");
            } else if (existing != null) {
                showCode(player, existing, label);
            }
            return;
        }
        // На площадке уже есть канал: заменяем его, лимит каналов не тратится.
        StreamBinding.Channel previous = current.find(platform).orElse(null);
        if (previous == null && current.channels().size() >= config.maxChannels()) {
            plugin.messages().send(sender, "link-limit",
                    "max", config.maxChannels(),
                    "channels", current.labels(),
                    "label", label);
            return;
        }
        // Без проверки кодом канал доверенный — подтверждаем сразу.
        long verifiedAt = config.requireVerified() ? 0L : System.currentTimeMillis();
        StreamBinding.Channel fresh = new StreamBinding.Channel(platform, channel,
                StreamVerification.generate(), verifiedAt);
        StreamBinding binding = current.renamed(player.getName()).withChannel(fresh);
        store.put(binding);
        service.forget(player.getUniqueId());
        saveAsync();

        plugin.messages().send(player, "link-header", "platform", platform.displayName());
        if (previous != null) {
            plugin.messages().send(player, "link-replaced",
                    "channel", previous.channel(), "platform", platform.displayName());
        }
        plugin.messages().send(player, "link-created",
                "channel", channel, "platform", platform.displayName());
        if (binding.channels().size() > 1) {
            plugin.messages().send(player, "link-summary", "channels", binding.labels());
        }
        if (fresh.verified()) {
            plugin.messages().send(player, "link-trusted");
        } else {
            showCode(player, fresh, label);
        }
    }

    private void showCode(Player player, StreamBinding.Channel channel, String label) {
        plugin.messages().send(player, "link-code", "code", channel.code());
        plugin.messages().send(player, "link-howto",
                "platform", channel.platform().displayName(), "label", label);
        plugin.messages().send(player, "link-url", "url", channel.url());
    }

    /* -------------------------------- check -------------------------------- */

    /**
     * {@code /stream check [площадка]} — проверка всех неподтверждённых каналов.
     * Площадки опрашиваются в одном фоновом проходе, ответы показываются на
     * главной нити: чат и инвентарь из чужого потока трогать нельзя.
     */
    private void check(CommandSender sender, String[] args, String label) {
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
        if (binding == null || !binding.linked()) {
            plugin.messages().send(sender, "check-no-channel", "label", label);
            return;
        }
        StreamPlatform filter = null;
        if (args.length >= 2) {
            Optional<StreamPlatform> parsed = StreamPlatform.parse(args[1]);
            if (parsed.isEmpty()) {
                plugin.messages().send(sender, "link-unknown-platform",
                        "name", args[1], "list", StreamPlatform.ids());
                return;
            }
            filter = parsed.get();
            if (binding.find(filter).isEmpty()) {
                plugin.messages().send(sender, "check-no-channel-platform",
                        "platform", filter.displayName());
                return;
            }
        }
        List<StreamBinding.Channel> targets = new ArrayList<>();
        boolean blocked = false;
        for (StreamBinding.Channel channel : binding.channels()) {
            if (filter != null && channel.platform() != filter) {
                continue;
            }
            if (channel.verified()) {
                continue;
            }
            if (!config.available(channel.platform())) {
                plugin.messages().send(sender, "no-platform",
                        "name", channel.platform().displayName(),
                        "list", StreamPlatform.ids(config.platforms()));
                blocked = true;
                continue;
            }
            targets.add(channel);
        }
        if (targets.isEmpty()) {
            if (!blocked) {
                plugin.messages().send(sender, "check-already");
            }
            return;
        }
        for (StreamBinding.Channel channel : targets) {
            plugin.messages().send(sender, "check-live",
                    "channel", channel.channel(),
                    "platform", channel.platform().displayName());
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<CheckResult> results = new ArrayList<>(targets.size());
            StreamBinding current = service.store().get(player.getUniqueId()).orElse(binding);
            boolean changed = false;
            for (StreamBinding.Channel channel : targets) {
                LiveStream live;
                try {
                    live = service.fetchLive(channel.platform(), channel.channel());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (IOException | RuntimeException exception) {
                    results.add(new CheckResult(channel, null, exception.getMessage(), false));
                    continue;
                }
                boolean verified = live.live()
                        && StreamVerification.matches(live.title(), channel.code());
                if (verified) {
                    // Пока шёл запрос, привязку могли отвязать: подтверждаем только живой канал.
                    StreamBinding latest = service.store().get(player.getUniqueId()).orElse(current);
                    if (latest.has(channel.platform(), channel.channel())) {
                        current = latest.markVerified(channel.platform(), channel.channel(),
                                System.currentTimeMillis());
                        changed = true;
                    }
                }
                results.add(new CheckResult(channel, live, null, verified));
            }
            if (changed) {
                service.store().put(current);
                service.store().save();
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (CheckResult result : results) {
                    reportCheck(player, result);
                }
            });
        });
    }

    /** Итог проверки одного канала: ответ площадки, ошибка или подтверждение. */
    private record CheckResult(StreamBinding.Channel channel, LiveStream live,
                               String error, boolean verified) {
    }

    private void reportCheck(Player player, CheckResult result) {
        StreamBinding.Channel channel = result.channel();
        if (result.error() != null) {
            plugin.messages().send(player, "check-error",
                    "platform", channel.platform().displayName(), "error", result.error());
            return;
        }
        LiveStream live = result.live();
        if (!live.live()) {
            plugin.messages().send(player, "check-offline",
                    "platform", channel.platform().displayName(),
                    "channel", channel.channel(), "code", channel.code());
            return;
        }
        if (!result.verified()) {
            plugin.messages().send(player, "check-no-code",
                    "platform", channel.platform().displayName(),
                    "channel", channel.channel(),
                    "title", live.title().isBlank() ? "—" : live.title(),
                    "code", channel.code());
            return;
        }
        plugin.getLogger().info("[Streams] Канал подтверждён: " + player.getName()
                + " → " + channel.label());
        plugin.messages().send(player, "check-verified",
                "platform", channel.platform().displayName(), "channel", channel.channel());
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

    private void status(Player player, String label) {
        StreamConfig config = service.config();
        if (!config.enabled()) {
            plugin.messages().send(player, "disabled");
        }
        StreamBinding binding = service.store().get(player.getUniqueId()).orElse(null);
        if (binding == null || !binding.linked()) {
            plugin.messages().send(player, "not-linked", "label", label);
            return;
        }
        plugin.messages().send(player, "status-header", "player", player.getName());
        for (StreamBinding.Channel channel : binding.channels()) {
            String stateKey = channel.verified() ? "status-verified" : "status-unverified";
            Object state = Messages.trusted(plugin.messages().raw(stateKey, "code", channel.code()));
            plugin.messages().send(player, "status-channel",
                    "channel", channel.channel(),
                    "platform", channel.platform().displayName(),
                    "state", state);
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
        List<StreamBinding.Channel> checkable = new ArrayList<>();
        for (StreamBinding.Channel channel : binding.channels()) {
            if (config.available(channel.platform())) {
                checkable.add(channel);
            }
        }
        if (checkable.isEmpty()) {
            plugin.messages().send(player, "status-no-platform");
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<CheckResult> results = new ArrayList<>(checkable.size());
            for (StreamBinding.Channel channel : checkable) {
                try {
                    results.add(new CheckResult(channel,
                            service.fetchLive(channel.platform(), channel.channel()), null, false));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (IOException | RuntimeException exception) {
                    results.add(new CheckResult(channel, null, exception.getMessage(), false));
                }
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (CheckResult result : results) {
                    StreamBinding.Channel channel = result.channel();
                    if (result.error() != null) {
                        plugin.messages().send(player, "status-error",
                                "platform", channel.platform().displayName(),
                                "channel", channel.channel());
                    } else if (result.live().live()) {
                        plugin.messages().send(player, "status-live",
                                "platform", channel.platform().displayName(),
                                "channel", channel.channel(),
                                "viewers", result.live().viewers());
                    } else {
                        plugin.messages().send(player, "status-offline",
                                "platform", channel.platform().displayName(),
                                "channel", channel.channel());
                    }
                }
            });
        });
    }

    /* -------------------------------- unlink -------------------------------- */

    /**
     * {@code /stream unlink [площадка]} — отвязка одного канала или всех сразу.
     * Площадка без аргумента означает «всё»: так привычнее по прежнему поведению.
     */
    private void unlink(CommandSender sender, String[] args, String label) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only");
            return;
        }
        StreamBinding binding = service.store().get(player.getUniqueId()).orElse(null);
        if (binding == null || !binding.linked()) {
            plugin.messages().send(sender, "not-linked", "label", label);
            return;
        }
        if (args.length < 2) {
            service.store().remove(player.getUniqueId());
            service.forget(player.getUniqueId());
            saveAsync();
            plugin.messages().send(sender, "unlinked", "label", label);
            return;
        }
        Optional<StreamPlatform> parsed = StreamPlatform.parse(args[1]);
        if (parsed.isEmpty()) {
            plugin.messages().send(sender, "link-unknown-platform",
                    "name", args[1], "list", StreamPlatform.ids());
            return;
        }
        StreamPlatform platform = parsed.get();
        StreamBinding.Channel removed = binding.find(platform).orElse(null);
        if (removed == null) {
            plugin.messages().send(sender, "not-linked-platform",
                    "platform", platform.displayName());
            return;
        }
        StreamBinding updated = binding.without(platform);
        if (updated.linked()) {
            service.store().put(updated);
        } else {
            service.store().remove(player.getUniqueId());
        }
        service.forget(player.getUniqueId());
        saveAsync();
        plugin.messages().send(sender, "unlinked-platform",
                "platform", platform.displayName(),
                "channel", removed.channel(),
                "left", updated.channels().size());
    }

    /* -------------------------------- admin -------------------------------- */

    private void stats(CommandSender sender) {
        List<StreamBinding> all = new ArrayList<>(service.store().all());
        int channels = all.stream().mapToInt(binding -> binding.channels().size()).sum();
        plugin.messages().send(sender, "stats-header",
                "count", all.size(), "channels", channels);
        plugin.messages().send(sender, "stats-viewers",
                "count", plugin.viewerService().viewerCount(),
                "streamers", plugin.viewerService().streamerCount());
        if (all.isEmpty()) {
            plugin.messages().send(sender, "stats-empty");
            return;
        }
        int shown = 0;
        stats:
        for (StreamBinding binding : all) {
            for (StreamBinding.Channel channel : binding.channels()) {
                if (shown >= STATS_LIMIT) {
                    break stats;
                }
                plugin.messages().send(sender, "stats-line",
                        "player", binding.playerName(),
                        "platform", channel.platform().displayName(),
                        "channel", channel.channel(),
                        "state", trustedState(channel.verified()),
                        "days", binding.state().totalDays());
                shown++;
            }
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
        if (args.length == 2 && ("check".equalsIgnoreCase(args[0])
                || "unlink".equalsIgnoreCase(args[0]))) {
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
