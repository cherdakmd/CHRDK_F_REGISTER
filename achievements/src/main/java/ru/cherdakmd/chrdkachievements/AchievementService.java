package ru.cherdakmd.chrdkachievements;

import org.bukkit.entity.Player;
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
        // The monetary journal is authoritative when the async progress snapshot lagged.
        plugin.money().awards(player).forEach((id, award) -> {
            if (award.state() == ru.cherdakmd.chrdkregister.api.FixedMoneyMilestones.State.MONEY) {
                store.unlock(key, id);
                store.markClaimed(key, id);
            }
        });
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
        if (player == null || def == null || store.isUnlocked(key(player),def.id())) return false;
        return unlock(player,def);
    }

    private boolean unlock(Player player, AchievementDef def) {
        String key = key(player);
        var reward=plugin.config().rewardOf(def);
        var accepted=plugin.money().accept(player,def.id(),reward.diamonds(),reward.passHours(),
                def.badge()!=null && plugin.config().badges()?100:0);
        if(accepted==null || accepted.state()!=ru.cherdakmd.chrdkregister.api.FixedMoneyMilestones.State.MONEY) return false;
        if(!store.unlock(key,def.id()))return false;
        store.markClaimed(key,def.id());
        store.raise(key, AchievementMetric.ACHIEVEMENTS, store.unlocked(key).size());
        announce(player,def);
        return true;
    }

    private void announce(Player player, AchievementDef def) {
        String name = plugin.messages().raw("ach." + def.id() + ".name");
        if (name.isBlank()) {
            name = ArtifactTextFallback.humanize(def.id());
        }
        // Категория — разметка из messages.yml, а не текст игрока: без Trusted
        // MiniMessage показал бы в чате литералы <aqua>…</aqua>.
        plugin.messages().send(player, "achievement-unlocked",
                "name", Messages.trusted(name),
                "category", plugin.messages().mini("category." + def.category().id()));
        var accepted=plugin.money().award(player,def.id());
        if(accepted!=null && accepted.state()==ru.cherdakmd.chrdkregister.api.FixedMoneyMilestones.State.MONEY && accepted.minor()>0) {
            plugin.messages().send(player, "achievement-reward",
                    "reward", Messages.trusted(ru.cherdakmd.chrdkregister.api.DiamondMoney.format(accepted.minor())+" алмазов в /bal (ожидает зачисления)"));
        }
        if (plugin.config().actionbar()) {
            player.sendActionBar(plugin.messages().get("actionbar-unlocked",
                    "name", Messages.trusted(name)));
        }
        player.playSound(player.getLocation(),
                org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
    }

    /** Примерная сумма до принятия; на момент открытия сохраняется точная цена. */
    public String rewardText(AchievementDef def) {
        var reward=plugin.config().rewardOf(def);
        return plugin.money().preview(reward.diamonds(),reward.passHours(),def.badge()!=null && plugin.config().badges()?100:0);
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
        // Исторические записи сохраняются для уже выданных наград и мета-прогресса,
        // но не должны увеличивать счётчик активного каталога выше его размера.
        return (int) store.unlocked(key(player)).stream().filter(id -> AchievementRegistry.byId(id) != null).count();
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
        var old=plugin.money().award(player,def.id());
        if (!store.isUnlocked(key(player), def.id()) || old == null
                || old.state()==ru.cherdakmd.chrdkregister.api.FixedMoneyMilestones.State.MONEY) {
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
