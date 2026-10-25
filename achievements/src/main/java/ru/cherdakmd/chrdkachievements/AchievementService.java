package ru.cherdakmd.chrdkachievements;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Учёт достижений и выдача наград.
 *
 * <p>Единая точка входа для событий: {@link #addProgress(Player, AchievementMetric, long)}
 * и {@link #setFlag(Player, AchievementMetric)}. Оба метода накапливают счётчик,
 * затем проверяют все достижения — так одно и то же событие может закрыть сразу
 * несколько порогов (например, 100-й моб и 1 000-й открывают разные записи).</p>
 *
 * <p>Награда выдаётся ровно один раз: помимо {@code unlocked} есть отметка
 * {@code claimed}, поэтому сбой между открытием и выплатой не приведёт к
 * повторной выдаче после перезапуска. Дни ранга идут через API ядра,
 * алмазы — предметами стаками по 64.</p>
 *
 * <p>Бейджи: выбранный бейдж хранится в профиле и добавляется к нику через
 * {@code ChrdkRegisterApi.registerNameTag}. Провайдер читает кэш
 * {@link UUID} → строка бейджа, поэтому не зависит от файла и потока чата.</p>
 */
public final class AchievementService {

    private final AchievementsPlugin plugin;
    private final AchievementStore store;
    /** Кэш оформления для провайдера ников: uuid → MiniMessage-бейдж. */
    private final Map<UUID, String> badgeCache = new ConcurrentHashMap<>();

    public AchievementService(AchievementsPlugin plugin, AchievementStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    /* ------------------------------ учёт прогресса ----------------------------- */

    /**
     * Прибавляет прогресс и проверяет достижения.
     *
     * @return сколько достижений открылось в результате
     */
    public int addProgress(Player player, AchievementMetric metric, long amount) {
        if (player == null || metric == null || amount <= 0L || !enabled()) {
            return 0;
        }
        store.add(key(player), metric, amount);
        return check(player);
    }

    /** Ставит флаг «сделано» (ранг, регистрация, 2FA). */
    public int setFlag(Player player, AchievementMetric metric) {
        if (player == null || metric == null || !enabled()) {
            return 0;
        }
        store.raise(key(player), metric, 1L);
        return check(player);
    }

    /**
     * Поднимает счётчик до значения (максимум), а не прибавляет: нужно для
     * «пиковых» метрик — например, максимального баланса банка.
     *
     * @return сколько достижений открылось
     */
    public int raise(Player player, AchievementMetric metric, long value) {
        if (player == null || metric == null || value <= 0L || !enabled()) {
            return 0;
        }
        store.raise(key(player), metric, value);
        return check(player);
    }

    /**
     * Проверяет все достижения игрока и выдаёт награды за новые.
     *
     * <p>Мета-достижение зависит от числа уже открытых, поэтому после каждой
     * пачки открытий проход повторяется — но не больше трёх раз: рекурсия
     * «легенда открывает легенду» невозможна по построению.</p>
     */
    public int check(Player player) {
        if (player == null || !enabled()) {
            return 0;
        }
        String key = key(player);
        int opened = 0;
        for (int pass = 0; pass < 3; pass++) {
            List<AchievementDef> fresh = AchievementRules.newlyCompleted(
                    AchievementRegistry.all(), store.unlocked(key), metric -> value(key, metric));
            if (fresh.isEmpty()) {
                break;
            }
            for (AchievementDef def : fresh) {
                if (unlock(player, def)) {
                    opened++;
                }
            }
            // После открытий обновляем служебный счётчик для «Легенды».
            store.raise(key, AchievementMetric.ACHIEVEMENTS, store.unlocked(key).size());
        }
        return opened;
    }

    /** Админское открытие достижения без проверки порога. */
    public boolean grant(Player player, AchievementDef def) {
        if (player == null || def == null) {
            return false;
        }
        boolean fresh = store.unlock(key(player), def.id());
        store.raise(key(player), AchievementMetric.ACHIEVEMENTS, store.unlocked(key(player)).size());
        if (fresh) {
            announce(player, def);
            pay(player, def);
        }
        return fresh;
    }

    private boolean unlock(Player player, AchievementDef def) {
        String key = key(player);
        if (!store.unlock(key, def.id())) {
            return false;
        }
        announce(player, def);
        pay(player, def);
        return true;
    }

    private void announce(Player player, AchievementDef def) {
        String name = plugin.messages().raw("ach." + def.id() + ".name");
        if (name.isBlank()) {
            name = ArtifactTextFallback.humanize(def.id());
        }
        plugin.messages().send(player, "achievement-unlocked",
                "name", Messages.trusted(name),
                "category", plugin.messages().raw("category." + def.category().id()));
        AchievementsConfig.Reward reward = plugin.config().rewardOf(def);
        if (reward.passDays() > 0 || reward.diamonds() > 0) {
            plugin.messages().send(player, "achievement-reward",
                    "reward", Messages.trusted(rewardText(reward)));
        }
        if (plugin.config().actionbar()) {
            player.sendActionBar(plugin.messages().get("actionbar-unlocked",
                    "name", Messages.trusted(name)));
        }
        if (plugin.config().announce()) {
            plugin.messages().send(player, "achievement-hint-badges");
        }
        player.playSound(player.getLocation(),
                org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
    }

    private void pay(Player player, AchievementDef def) {
        String key = key(player);
        if (!store.markClaimed(key, def.id())) {
            // Награда уже была выплачена ранее — не дублируем.
            return;
        }
        AchievementsConfig.Reward reward = plugin.config().rewardOf(def);
        if (reward.passDays() > 0) {
            if (plugin.coreApi() != null) {
                plugin.coreApi().grantPass(player.getName(), reward.passDays(),
                        "achievements:" + def.id());
            } else {
                plugin.getLogger().warning("Ядро недоступно: награда достижения "
                        + def.id() + " не выдана игроку " + player.getName());
            }
        }
        int remaining = reward.diamonds();
        while (remaining > 0) {
            int amount = Math.min(64, remaining);
            remaining -= amount;
            Map<Integer, ItemStack> leftover = player.getInventory()
                    .addItem(new ItemStack(Material.DIAMOND, amount));
            for (ItemStack rest : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
    }

    /** Текст награды для чата и GUI. */
    public String rewardText(AchievementsConfig.Reward reward) {
        List<String> parts = new ArrayList<>(2);
        if (reward.passDays() > 0) {
            parts.add(plugin.messages().raw("reward-days",
                    "days", String.valueOf(reward.passDays())));
        }
        if (reward.diamonds() > 0) {
            parts.add(plugin.messages().raw("reward-diamonds",
                    "count", String.valueOf(reward.diamonds())));
        }
        if (parts.isEmpty()) {
            return plugin.messages().raw("reward-none");
        }
        return String.join(plugin.messages().raw("reward-join"), parts);
    }

    /* --------------------------------- прогресс -------------------------------- */

    public long value(Player player, AchievementMetric metric) {
        return value(key(player), metric);
    }

    private long value(String key, AchievementMetric metric) {
        return store.value(key, metric);
    }

    public int percent(AchievementDef def, Player player) {
        return AchievementRules.percent(value(player, def.metric()), def.goal());
    }

    public boolean isUnlocked(Player player, String achievementId) {
        return store.isUnlocked(key(player), achievementId);
    }

    public Set<String> unlocked(Player player) {
        return store.unlocked(key(player));
    }

    public int unlockedCount(Player player) {
        return store.unlocked(key(player)).size();
    }

    public int total() {
        return AchievementRegistry.size();
    }

    /* ---------------------------------- бейджи --------------------------------- */

    /**
     * Надевает бейдж открытого достижения или снимает его ({@code null}/«off»).
     *
     * @return текст ошибки (ключ messages.yml) или {@code null} при успехе
     */
    public String setBadge(Player player, String badgeId) {
        if (player == null) {
            return "player-only";
        }
        if (!plugin.config().badges()) {
            return "badges-disabled";
        }
        if (badgeId == null || badgeId.isBlank() || badgeId.equalsIgnoreCase("off")) {
            store.setBadge(key(player), null);
            badgeCache.remove(player.getUniqueId());
            return null;
        }
        AchievementDef def = AchievementRegistry.byId(badgeId);
        if (def == null || def.badge() == null) {
            return "badge-unknown";
        }
        if (!store.isUnlocked(key(player), def.id())) {
            return "badge-locked";
        }
        store.setBadge(key(player), def.badge());
        badgeCache.put(player.getUniqueId(), plugin.messages().raw("badge." + def.badge()));
        return null;
    }

    /** Бейдж игрока для GUI и сообщений ({@code null} — не выбран). */
    public String badgeOf(Player player) {
        return store.badge(key(player));
    }

    /** Строка бейджа с разметкой или {@code null}. */
    public String badgeText(Player player) {
        String badge = badgeOf(player);
        return badge == null ? null : plugin.messages().raw("badge." + badge);
    }

    /** Кэш для провайдера ников (может вызываться асинхронно из чата). */
    public String cachedBadge(UUID playerId) {
        return playerId == null ? null : badgeCache.get(playerId);
    }

    /** Подхватывает сохранённый бейдж после входа. */
    public void refreshBadge(Player player) {
        if (player == null) {
            return;
        }
        String badge = store.badge(key(player));
        if (badge == null) {
            badgeCache.remove(player.getUniqueId());
        } else {
            badgeCache.put(player.getUniqueId(), plugin.messages().raw("badge." + badge));
        }
    }

    public void forget(UUID playerId) {
        if (playerId != null) {
            badgeCache.remove(playerId);
        }
    }

    /* -------------------------------- состояние -------------------------------- */

    public boolean enabled() {
        return plugin.config().enabled();
    }

    public AchievementStore store() {
        return store;
    }

    /** Список достижений раздела — для GUI. */
    public List<AchievementDef> byCategory(AchievementDef.Category category) {
        return category == null ? AchievementRegistry.all() : AchievementRegistry.byCategory(category);
    }

    /** Ближайшее незакрытое достижение (для подсказки в GUI). */
    public AchievementDef nextGoal(Player player) {
        AchievementDef best = null;
        int bestPercent = -1;
        for (AchievementDef def : AchievementRegistry.all()) {
            if (isUnlocked(player, def.id())) {
                continue;
            }
            int percent = percent(def, player);
            if (percent > bestPercent) {
                bestPercent = percent;
                best = def;
            }
        }
        return best;
    }

    private static String key(Player player) {
        return player == null ? "" : player.getUniqueId().toString();
    }

    /** Локальный фолбэк имени: «pervyj-shag» → «Pervyj Shag». */
    static final class ArtifactTextFallback {

        private ArtifactTextFallback() {
        }

        static String humanize(String id) {
            if (id == null || id.isBlank()) {
                return "";
            }
            StringBuilder out = new StringBuilder();
            for (String part : id.trim().split("[-_\\s]+")) {
                if (part.isEmpty()) {
                    continue;
                }
                if (!out.isEmpty()) {
                    out.append(' ');
                }
                out.append(Character.toUpperCase(part.charAt(0)))
                        .append(part.substring(1).toLowerCase(Locale.ROOT));
            }
            return out.toString();
        }
    }
}
