package ru.cherdakmd.chrdkvk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

/**
 * Оркестратор четырёх персональных карточек дня. Вместе с суточным дайджестом
 * в 21:00 это ровно пять записей на стене сообщества за сутки.
 *
 * <p>Планировщик тикает на главном потоке раз в минуту: сбор никнеймов и
 * балансов требует Bukkit API. Сама сеть ({@code wall.post} текстом и PNG в
 * беседу) уходит в асинхронную задачу и не блокирует тики. Владелец стены
 * по-прежнему один — {@code CHRDK_VK}; GitHub Actions и внешний ledger в
 * рантайме не участвуют.</p>
 */
public final class DailyCardsService {

    private final VkPlugin plugin;
    private final WallDigestService digest;
    private final DailyCardsLedger ledger;
    private volatile DailyCardsConfig config;
    private volatile VkWallPublisher publisher;

    private BukkitTask task;
    private volatile boolean stopped;

    public DailyCardsService(VkPlugin plugin, WallDigestService digest,
                             DailyCardsConfig config, DailyCardsStore store) {
        this.plugin = plugin;
        this.digest = digest;
        this.config = config == null ? DailyCardsConfig.DEFAULT : config;
        this.ledger = new DailyCardsLedger(store, store.load());
        refreshClients();
    }

    public void start() {
        List<DailyCardKind> skipped = ledger.takeUnresolvedPosting();
        if (!skipped.isEmpty()) {
            plugin.getLogger().warning("[daily-cards] прерванные публикации за "
                    + ledger.day() + " пропущены во избежание дубля: " + skipped);
            ledger.save();
        }
        if (!config.enabled()) {
            plugin.getLogger().info("[daily-cards] автопостинг выключен: wall-daily-cards.enabled: false");
            return;
        }
        if (!plugin.config().apiConfigured()) {
            plugin.getLogger().warning("[daily-cards] нет community.group-id/access-token — стена недоступна");
            return;
        }
        if (plugin.config().textOnlyWall()) {
            plugin.getLogger().info("[daily-cards] текстовый режим стены: wall.post без PNG, "
                    + "карточки уходят в беседу ВК");
        }
        schedule();
        plugin.getLogger().info("[daily-cards] четыре карточки игроков включены: " + config.scheduleLabel());
    }

    /** Живая перезагрузка конфига плагина без рестарта сервера. */
    public void applyConfig(DailyCardsConfig next) {
        if (next == null || stopped) {
            return;
        }
        this.config = next;
        cancel();
        if (!next.enabled()) {
            plugin.getLogger().info("[daily-cards] автопостинг выключен перезагрузкой конфига");
            return;
        }
        if (plugin.config().textOnlyWall()) {
            plugin.getLogger().info("[daily-cards] текстовый режим стены после перезагрузки конфига");
        }
        refreshClients();
        schedule();
        plugin.getLogger().info("[daily-cards] расписание обновлено: " + next.scheduleLabel());
    }

    public void shutdown() {
        stopped = true;
        cancel();
        ledger.save();
    }

    private void refreshClients() {
        VkConfig cfg = plugin.config();
        this.publisher = cfg.photoWallEnabled()
                ? new VkWallPublisher(plugin.communityApiClient(), plugin.photoApiClient(), cfg.groupId())
                : new VkWallPublisher(plugin.communityApiClient(), cfg.groupId());
    }

    private void schedule() {
        task = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::checkDue, 20L * 30L, 20L * 60L);
    }

    private void cancel() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Главный поток: собрать победителей и поставить сетевую часть в очередь. */
    private void checkDue() {
        if (stopped || !config.enabled()) {
            return;
        }
        Instant now = Instant.now();
        String day = config.dayKey(now);
        for (DailyCardKind kind : ledger.due(config, now)) {
            DailyNomination nomination;
            try {
                nomination = nomination(kind);
            } catch (RuntimeException | LinkageError failure) {
                plugin.getLogger().log(Level.WARNING,
                        "[daily-cards] не удалось собрать номинацию " + kind.id(), failure);
                continue;
            }
            if (nomination == null || !nomination.valid()) {
                plugin.getLogger().info("[daily-cards] нет данных для «" + kind.title()
                        + "» за " + day + " — карточка пропущена");
                ledger.markAttempt(kind);
                ledger.save();
                continue;
            }
            ledger.markAttempt(kind);
            ledger.markPosting(kind);
            ledger.save();
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                    () -> publish(kind, nomination, day));
        }
    }

    /** Сетевая часть: текст на стену + PNG в беседу + локальный ledger. */
    private void publish(DailyCardKind kind, DailyNomination nomination, String day) {
        try {
            VkConfig cfg = plugin.config();
            byte[] png = DailyPlayerCard.render(nomination, config, day);
            String marker = "#chrdk_" + shortHash(day + kind.id());
            String baseText = DailyPlayerCard.message(nomination, config, day, marker);
            String guid = "chrdk-card-" + kind.id() + "-" + day;
            String wallText = cfg.withDiscuss(baseText);
            long postId = cfg.photoWallEnabled()
                    ? publisher.publish(png, wallText, guid)
                    : publisher.publishText(wallText, guid);
            ledger.markPosted(kind, postId);
            plugin.getLogger().info("[daily-cards] «" + kind.title() + "» → "
                    + nomination.playerName() + ": " + cfg.wallPostUrl(postId));
            sendChatCard(cfg, png, baseText, guid, kind.title());
            if (plugin.socialService() != null) {
                plugin.socialService().audit().write("social.wall.card", "", "vk",
                        "kind=" + kind.id() + " post=" + postId);
            }
        } catch (IOException | InterruptedException | RuntimeException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            ledger.abandonPosting(kind);
            plugin.getLogger().log(Level.WARNING,
                    "[daily-cards] публикация «" + kind.title() + "» не удалась: "
                            + failure.getMessage(), failure);
        } finally {
            ledger.save();
        }
    }

    /** Ошибка беседы не отменяет уже опубликованную запись стены. */
    private void sendChatCard(VkConfig cfg, byte[] png, String text, String guid, String title) {
        if (!cfg.chatCardsEnabled() || plugin.service() == null) {
            return;
        }
        try {
            boolean sent = plugin.service().publishChatCard(png, text, guid);
            if (sent) {
                plugin.getLogger().info("[daily-cards] PNG-карточка «" + title + "» отправлена в беседу");
            } else {
                plugin.getLogger().warning("[daily-cards] PNG-карточка «" + title
                        + "» не отправлена: беседа ВК не настроена");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("[daily-cards] отправка PNG в беседу прервана");
        } catch (IOException | RuntimeException chatFailure) {
            plugin.getLogger().log(Level.WARNING,
                    "[daily-cards] не удалось отправить PNG в беседу: " + chatFailure.getMessage(),
                    chatFailure);
        }
    }

    private DailyNomination nomination(DailyCardKind kind) {
        return switch (kind) {
            case RICHEST -> richest();
            case PRESTIGE -> fromLeader(kind, digest.collector().topPrestige());
            case HUNTER -> fromLeader(kind, digest.collector().topMobKills());
            case MINER -> miner();
        };
    }

    /** Самый богатый: обезличенный баланс из CHRDK_Core по всем известным никам. */
    private DailyNomination richest() {
        ChrdkRegisterApi api = plugin.coreApi();
        if (api == null) {
            return null;
        }
        String best = null;
        long bestBalance = 0L;
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            String name = offline.getName();
            if (name == null || name.isBlank()) {
                continue;
            }
            long balance;
            try {
                balance = api.bankBalance(name);
            } catch (RuntimeException | LinkageError onePlayer) {
                continue;
            }
            if (balance <= 0L) {
                continue;
            }
            if (balance > bestBalance
                    || (balance == bestBalance && best != null
                    && name.compareToIgnoreCase(best) < 0)) {
                best = name;
                bestBalance = balance;
            }
        }
        return best == null ? null
                : new DailyNomination(DailyCardKind.RICHEST, best, bestBalance, 0L);
    }

    /** Шахтёр дня: лидер по блокам, алмазы того же игрока идут вторым числом. */
    private DailyNomination miner() {
        WallDigestCollector.Leader leader = digest.collector().topBlocks();
        if (leader == null) {
            return null;
        }
        long diamonds = digest.collector().diamondsOf(leader.name());
        return new DailyNomination(DailyCardKind.MINER, leader.name(), leader.value(), diamonds);
    }

    private static DailyNomination fromLeader(DailyCardKind kind, WallDigestCollector.Leader leader) {
        return leader == null ? null
                : new DailyNomination(kind, leader.name(), leader.value(), 0L);
    }

    private static String shortHash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes).substring(0, 12);
        } catch (RuntimeException | java.security.NoSuchAlgorithmException bad) {
            return "dedup";
        }
    }
}
