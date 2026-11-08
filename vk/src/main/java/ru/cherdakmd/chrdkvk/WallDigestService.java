package ru.cherdakmd.chrdkvk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.api.events.PassGrantedEvent;
import ru.cherdakmd.chrdkskills.ChrdkSkillsApi;

/**
 * Оркестратор суточного дайджеста. Живёт целиком в CHRDK_VK на сервере
 * владельца: слушает Bukkit-события, раз в несколько минут опрашивает престиж
 * через публичный ServicesManager API навыков, в назначенное время рисует
 * постер и публикует его на стену сообщества.
 *
 * <p>Владелец стены ровно один — этот плагин. Советы, оперативные события и
 * релизные новости по-прежнему уходят в беседу и на стену не дублируются.
 * Один пост в сутки защищает от VK flood error 9, а локальный ledger в
 * {@code wall-digest.json} — от дублей после рестарта.</p>
 */
public final class WallDigestService implements Listener {

    private final VkPlugin plugin;
    private volatile WallDigestConfig config;
    private final WallDigestCollector collector;
    private volatile VkWallPublisher publisher;

    private BukkitTask pollTask;
    private BukkitTask postTask;
    private volatile boolean stopped;
    private boolean listenerRegistered;

    public WallDigestService(VkPlugin plugin, WallDigestConfig config,
                             WallDigestStore store, VkStore vkStore) {
        this.plugin = plugin;
        this.config = config == null ? WallDigestConfig.DEFAULT : config;
        this.collector = new WallDigestCollector(store, store.load(),
                name -> vkStore == null || vkStore.receivesGroupChat(name), this.config);
        refreshClients();
    }

    public void start() {
        String unresolved = collector.takeUnresolvedPosting();
        if (!unresolved.isEmpty()) {
            // Сбой между wall.post и сохранением ledger: намеренно не повторяем,
            // чтобы на публичной стене не появился дубль.
            plugin.getLogger().warning("[wall-digest] прерванная публикация за " + unresolved
                    + " пропущена во избежание дубля");
            collector.save();
        }
        if (!config.enabled()) {
            plugin.getLogger().info("[wall-digest] автопостинг выключен: wall-digest.enabled: false");
            return;
        }
        if (!plugin.config().apiConfigured()) {
            plugin.getLogger().warning("[wall-digest] нет community.group-id/access-token — стена недоступна");
            return;
        }
        registerListenerOnce();
        scheduleTasks();
        plugin.getLogger().info("[wall-digest] дайджест на стену включён: ежедневно "
                + config.scheduleLabel() + ", владелец стены — CHRDK_VK");
    }

    /** Перезагрузка конфига плагина: новое расписание и ключ без рестарта сервера. */
    public void applyConfig(WallDigestConfig next) {
        if (next == null || stopped) {
            return;
        }
        this.config = next;
        collector.applyConfig(next);
        cancel(pollTask);
        cancel(postTask);
        pollTask = null;
        postTask = null;
        if (!next.enabled()) {
            plugin.getLogger().info("[wall-digest] автопостинг выключен перезагрузкой конфига");
            return;
        }
        refreshClients();
        registerListenerOnce();
        scheduleTasks();
        plugin.getLogger().info("[wall-digest] расписание обновлено: " + next.scheduleLabel());
    }

    private void registerListenerOnce() {
        if (!listenerRegistered) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            listenerRegistered = true;
        }
    }

    private void refreshClients() {
        VkApiClient api = new VkApiClient(plugin.config());
        this.publisher = new VkWallPublisher(api, plugin.config().groupId());
    }

    private void scheduleTasks() {
        long pollTicks = 20L * 60L * config.pollMinutes();
        pollTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::pollPrestige, 20L * 30L, pollTicks);
        postTask = plugin.getServer().getScheduler()
                .runTaskTimerAsynchronously(plugin, this::checkAndPost, 20L * 30L, 20L * 60L);
    }

    public void shutdown() {
        stopped = true;
        cancel(pollTask);
        cancel(postTask);
        pollTask = null;
        postTask = null;
        collector.save();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPassGranted(PassGrantedEvent event) {
        if (stopped || !config.enabled() || !config.includeDonations()) {
            return;
        }
        String source = event.getSource() == null ? "" : event.getSource().toLowerCase(Locale.ROOT);
        if (source.contains("test")) {
            return; // тестовые платежи DonatePay на стену не попадают
        }
        collector.recordDonation(event.getPlayerName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (stopped || !config.enabled() || !config.includeOnline()) {
            return;
        }
        collector.recordOnline(Bukkit.getOnlinePlayers().size());
        if (!event.getPlayer().hasPlayedBefore()) {
            collector.recordNewPlayer();
        }
    }

    /** Опрос престижа идёт на главном потоке: Bukkit API так безопаснее. */
    private void pollPrestige() {
        if (stopped || !config.enabled()) {
            return;
        }
        String day = config.dayKey(Instant.now());
        collector.rollDay(day);
        if (config.includeOnline()) {
            collector.recordOnline(Bukkit.getOnlinePlayers().size());
        }
        if (config.includePrestige()) {
            try {
                ChrdkSkillsApi skills = Bukkit.getServicesManager().load(ChrdkSkillsApi.class);
                if (skills != null) {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        try {
                            collector.observePrestige(player.getName(), skills.totalPrestige(player));
                        } catch (RuntimeException onePlayer) {
                            // Один сломанный игрок не должен ронять весь опрос.
                        }
                    }
                }
            } catch (LinkageError | RuntimeException missingSkills) {
                // CHRDK_Skills не установлен: публикуем поддержку и онлайн без престижа.
            }
        }
        collector.save();
    }

    /** Сетевая часть вынесена в асинхронный таймер, чтобы не блокировать тики. */
    private void checkAndPost() {
        if (stopped || !config.enabled()) {
            return;
        }
        Instant now = Instant.now();
        String day = config.dayKey(now);
        boolean due = config.isPostTime(now)
                || (collector.hasPending(day) && config.isRetryTime(now));
        if (!due || !collector.shouldPost(day, config.minEvents())) {
            return;
        }
        collector.markAttempt(day);
        collector.markPosting(day);
        collector.save();

        WallDigestCollector.Digest digest = collector.snapshot(day);
        try {
            byte[] png = WallDigestCard.render(digest, config);
            String marker = "#chrdk_" + shortHash(day);
            String text = WallDigestCard.message(digest, config, marker);
            long postId = publisher.publish(png, text, "chrdk-wall-" + day);
            collector.markPosted(day, postId);
            plugin.getLogger().info("[wall-digest] запись опубликована: "
                    + plugin.config().wallPostUrl(postId));
            if (plugin.socialService() != null) {
                plugin.socialService().audit().write("social.wall.digest", "", "vk", "post=" + postId);
            }
        } catch (IOException | InterruptedException | RuntimeException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            plugin.getLogger().log(Level.WARNING,
                    "[wall-digest] публикация не удалась: " + failure.getMessage(), failure);
        } finally {
            collector.save();
        }
    }

    private static void cancel(BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (RuntimeException | java.security.NoSuchAlgorithmException bad) {
            return "dedup";
        }
    }
}
