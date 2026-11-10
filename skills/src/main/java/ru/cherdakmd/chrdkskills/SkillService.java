package ru.cherdakmd.chrdkskills;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import ru.cherdakmd.chrdkregister.api.EconomyConfirmations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Прокачка навыков: начисление опыта, уровни, вехи и активные перки.
 *
 * <p>Единственная точка входа для событий: {@link #award(Player, SkillType, long)}.
 * Аргумент — не опыт, а «единицы действия» (один блок, одна рыба, пункт урона):
 * так конфиг может менять цену действия, не трогая код событий. Итог считается
 * как {@code единицы × опыт за действие × множители}; цена единицы
 * ограничена {@code limits.max-xp-per-action}, а число единиц — кодом события
 * (не больше 10 за удар) или самим внешним аддоном.</p>
 *
 * <p>Уровень всегда выводится из опыта, поэтому «повышение» — это сравнение
 * старого и нового уровня. При переходе через уровень-веху выдаются алмазы
 * ровно один раз: отметка живёт в {@link SkillStore}.</p>
 */
public final class SkillService {

    /** Итог одного начисления. */
    public record Award(SkillType skill, long xp, int oldLevel, int newLevel,
                        List<Integer> milestones) {

        public static Award none(SkillType skill) {
            return new Award(skill, 0L, 1, 1, List.of());
        }
    }

    private final SkillsPlugin plugin;
    private final SkillStore store;
    /** Двухшаговое подтверждение сброса навыка: TTL и одноразовость из ядра. */
    private final EconomyConfirmations confirmations = new EconomyConfirmations();
    /** Кулдаун actionbar-подсказки: не дёргаем полосу на каждый блок. */
    private final Map<UUID, Long> balanceBars = new ConcurrentHashMap<>();
    /** Последнее объявленное состояние «навык → множитель»: чат без повторов. */
    private final Map<String, BalanceState> balanceStates = new ConcurrentHashMap<>();

    /** Снимок правила баланса для одного начисления. */
    private record BalanceView(Map<SkillType, Integer> prestige,
                               Collection<SkillType> tracked, int allowance,
                               int hardCap, double softStep, double minFactor,
                               int rankLevel, int baseMaxLead, int rankBonus) {
    }

    /** Состояние, о котором уже написали в чат (только память, без skills.json). */
    record BalanceState(double factor, int allowance) {
        boolean same(double otherFactor, int otherAllowance) {
            return Double.compare(factor, otherFactor) == 0 && allowance == otherAllowance;
        }
    }

    /** Отчёт для {@code /skills balance}: сводка плюс строка на каждый навык. */
    public record BalanceReport(int allowance, int hardCap, int minimum,
                                int rankLevel, int baseMaxLead, int rankBonus,
                                List<BalanceRow> rows) {
    }

    /** Одна строка отчёта. */
    public record BalanceRow(SkillType skill, int step, int level, int lead,
                             double factor, boolean tracked) {
    }

    /** Результат обращения к {@link #requestPrestige}. */
    public enum PrestigeResult { DISABLED, NOT_MAX, AT_CAP, PENDING, DONE }

    /** Строка топа престижа. */
    public record PrestigeEntry(String name, int total) {
    }

    public SkillService(SkillsPlugin plugin, SkillStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    /* --------------------------------- прогресс -------------------------------- */

    public SkillCurve.Params curve() {
        return plugin.config().curve();
    }

    public long xp(Player player, SkillType skill) {
        return player == null || skill == null ? 0L : store.xp(key(player), skill);
    }

    public int level(Player player, SkillType skill) {
        return SkillCurve.levelFor(xp(player, skill), curve());
    }

    public SkillCurve.LevelProgress progress(Player player, SkillType skill) {
        return SkillCurve.progress(xp(player, skill), curve());
    }

    /** Уровни всех навыков (для перков и витрины). */
    public Map<SkillType, Integer> levels(Player player) {
        Map<SkillType, Integer> out = new EnumMap<>(SkillType.class);
        for (SkillType skill : SkillType.values()) {
            out.put(skill, level(player, skill));
        }
        return out;
    }

    /** Суммарный уровень — «сила» игрока для топа. */
    public int totalLevel(Player player) {
        return SkillPerks.totalLevel(levels(player));
    }

    public long totalXp(Player player) {
        if (player == null) {
            return 0L;
        }
        long total = 0L;
        for (long value : store.allXp(key(player)).values()) {
            total += value;
        }
        return total;
    }

    /**
     * Перки одного навыка. События спрашивают именно так: шанс двойной добычи
     * горного дела не должен срабатывать на брёвнах, хотя вид эффекта общий.
     */
    public SkillPerks.Active perksFor(Player player, SkillType skill) {
        if (player == null || skill == null) {
            return new SkillPerks.Active(Map.of(), List.of());
        }
        return SkillPerks.active(
                Map.of(skill, level(player, skill)),
                Map.of(skill, prestigeLevel(player, skill)),
                plugin.config().prestigePerkPerStep());
    }

    /* -------------------------------- престиж -------------------------------- */

    /** Ступени престижа одного навыка (0 — ещё не сбрасывали). */
    public int prestigeLevel(Player player, SkillType skill) {
        return player == null || skill == null ? 0 : store.prestige(key(player), skill);
    }

    /** Сумма ступеней по всем навыкам — значок «✦ Престиж N». */
    public int totalPrestige(Player player) {
        return player == null ? 0 : store.totalPrestige(key(player));
    }

    /** Текущее звание серверного уровня престижа. */
    public PrestigeMath.Tier prestigeTier(Player player) {
        return PrestigeMath.tierFor(totalPrestige(player));
    }

    /** Следующее звание или {@code null} на потолке. */
    public PrestigeMath.Tier nextPrestigeTier(Player player) {
        return PrestigeMath.nextTier(totalPrestige(player));
    }

    /** Топ игроков по общему уровню престижа. */
    public List<PrestigeEntry> prestigeTop() {
        List<PrestigeEntry> out = new ArrayList<>();
        for (Map.Entry<String, Integer> row
                : store.prestigeTop(plugin.config().leaderboardSize())) {
            String name = store.name(row.getKey());
            out.add(new PrestigeEntry(name.isBlank() ? shortKey(row.getKey()) : name,
                    row.getValue()));
        }
        return List.copyOf(out);
    }

    /**
     * Добровольный сброс одного навыка с потолка. Первый вызов выдаёт
     * подтверждение, повторный в течение TTL реально сбрасывает опыт.
     */
    public PrestigeResult requestPrestige(Player player, SkillType skill) {
        if (!prestigeReady(player) || skill == null) {
            return PrestigeResult.DISABLED;
        }
        if (store.prestige(key(player), skill) >= PrestigeMath.MAX_PER_SKILL) {
            return PrestigeResult.AT_CAP;
        }
        if (level(player, skill) < curve().maxLevel()) {
            return PrestigeResult.NOT_MAX;
        }
        return applyPrestige(player, skill.id(), List.of(skill));
    }

    /** Сброс всех навыков, достигших потолка, одним подтверждением. */
    public PrestigeResult requestPrestigeAll(Player player) {
        if (!prestigeReady(player)) {
            return PrestigeResult.DISABLED;
        }
        List<SkillType> ready = new ArrayList<>();
        boolean anyMaxed = false;
        for (SkillType skill : SkillRegistry.all()) {
            boolean maxed = level(player, skill) >= curve().maxLevel();
            anyMaxed |= maxed;
            if (maxed && store.prestige(key(player), skill) < PrestigeMath.MAX_PER_SKILL) {
                ready.add(skill);
            }
        }
        if (ready.isEmpty()) {
            return anyMaxed ? PrestigeResult.AT_CAP : PrestigeResult.NOT_MAX;
        }
        return applyPrestige(player, "all", ready);
    }

    private boolean prestigeReady(Player player) {
        return player != null && plugin.config().enabled()
                && plugin.config().prestigeEnabled();
    }

    /**
     * Общий исполнитель: двухшаговое подтверждение, сброс, физические алмазы
     * за ступени и за новые звания. Разовые вехи в {@code /bal} не
     * перевыдаются — у нового цикла свой предметный источник наград.
     */
    private PrestigeResult applyPrestige(Player player, String quote,
                                         List<SkillType> targets) {
        long now = System.nanoTime();
        if (!confirmations.consume(player.getUniqueId(), "prestige", quote, now)) {
            confirmations.offer(player.getUniqueId(), "prestige", quote, now);
            return PrestigeResult.PENDING;
        }
        int steps = 0;
        for (SkillType skill : targets) {
            // Сначала фиксируем ступень: если потолок достигнут, опыт не трогаем.
            if (!store.addPrestige(key(player), skill)) {
                continue;
            }
            store.setXp(key(player), skill, 0L);
            steps++;
        }
        if (steps == 0) {
            return PrestigeResult.AT_CAP;
        }
        giveDiamonds(player, PrestigeMath.stepDiamonds(
                plugin.config().prestigeDiamondsPerStep()) * steps);
        grantTierRewards(player);
        return PrestigeResult.DONE;
    }

    /**
     * Разовые алмазы за каждое новое звание, один раз на порог. При сбросе
     * всех навыков игрок может пересечь несколько порогов сразу: в чат уходит
     * один анонс старшего звания с суммарной наградой, а не пачка сообщений.
     */
    private void grantTierRewards(Player player) {
        int total = totalPrestige(player);
        int diamonds = 0;
        int crossed = 0;
        PrestigeMath.Tier highest = null;
        for (int threshold : PrestigeMath.rewardThresholds()) {
            if (total < threshold || !store.markTier(key(player), threshold)) {
                continue;
            }
            PrestigeMath.Tier tier = PrestigeMath.tierAt(threshold);
            diamonds += PrestigeMath.tierDiamonds(tier);
            crossed++;
            highest = tier == null ? highest : tier;
            plugin.getLogger().info("[CHRDK_Skills] " + player.getName()
                    + " достиг звания «" + (tier == null ? threshold : tier.title())
                    + "» (престиж " + total + ")");
        }
        if (crossed == 0) {
            return;
        }
        giveDiamonds(player, diamonds);
        if (highest != null && plugin.config().prestigeAnnounceTiers()) {
            Bukkit.broadcast(plugin.messages().get("prestige-tier-announce",
                    "player", player.getName(),
                    "title", Messages.trusted(highest.title()),
                    "count", String.valueOf(total),
                    "diamonds", String.valueOf(diamonds)));
        }
    }

    /** Алмазы предметом: в инвентарь, остаток — под ноги. */
    private void giveDiamonds(Player player, int amount) {
        if (player == null || !player.isOnline() || amount <= 0) {
            return;
        }
        ItemStack stack = new ItemStack(Material.DIAMOND, Math.min(64 * 36, amount));
        try {
            player.getInventory().addItem(stack).values().forEach(rest ->
                    player.getWorld().dropItemNaturally(player.getLocation(), rest));
        } catch (RuntimeException error) {
            player.getWorld().dropItemNaturally(player.getLocation(), stack);
        }
    }

    /** Сколько физических алмазов уже выпало игроку сегодня (МСК). */
    public int diamondDropsToday(Player player) {
        return player == null ? 0 : store.diamondDropsToday(key(player), DiamondDrops.today());
    }

    /** Учесть выпавшие алмазы в дневном бюджете. */
    public void noteDiamondDrops(Player player, int amount) {
        if (player != null && amount > 0) {
            store.noteDiamondDrops(key(player), DiamondDrops.today(), amount);
        }
    }

    /* -------------------------------- начисление ------------------------------- */

    /**
     * Начисляет опыт за действие.
     *
     * @param units сколько действий засчитать (блоки, рыбы, пункты урона)
     */
    public Award award(Player player, SkillType skill, long units) {
        if (player == null || skill == null || units <= 0L || !plugin.config().enabled()) {
            return Award.none(skill);
        }
        if (!plugin.config().skill(skill).enabled()) {
            return Award.none(skill);
        }
        // Баланс престижа: мягкая зона урезает XP, жёсткий потолок его ставит.
        // Считаем до начисления, но применяем после бустеров ядра и престижа.
        BalanceView balance = balanceView(player);
        double balanceFactor = 1.0;
        int lead = 0;
        if (balance != null) {
            lead = PrestigeXpGate.lead(balance.prestige(), balance.tracked(), skill);
            balanceFactor = PrestigeXpGate.factor(balance.prestige(), balance.tracked(), skill,
                    balance.allowance(), balance.hardCap(), balance.softStep(), balance.minFactor());
            if (balanceFactor >= 1.0) {
                clearBalanceState(player, skill);
            } else {
                notifyBalance(player, skill, lead, balance.allowance(), balanceFactor,
                        balance.hardCap());
            }
            if (balanceFactor <= 0.0) {
                return Award.none(skill);
            }
        }
        long perUnit = plugin.config().xpFor(skill);
        if (perUnit <= 0L) {
            return Award.none(skill);
        }
        // Внешний аддон может передать любое число — ограничиваем разумным
        // максимумом, чтобы не поймать переполнение long.
        long safeUnits = Math.min(1000L, units);
        long gained = perUnit * safeUnits;
        if (plugin.coreApi() != null) gained = plugin.coreApi().boostExperience(player.getName(), "skills", gained, gained);
        // Престиж ускоряет повторную прокачку, но не пробивает потолок одного
        // действия: иначе ветеран получал бы экспоненциальный опыт.
        gained = Math.round(gained * PrestigeMath.xpFactor(
                prestigeLevel(player, skill), plugin.config().prestigeXpPerStep()));
        // Мягкая зона: хотя бы 1 XP за реальное действие (scaledXp).
        gained = PrestigeXpGate.scaledXp(gained, balanceFactor);
        gained = Math.max(0L, Math.min(plugin.config().maxXpPerAction(), gained));
        int oldLevel = level(player, skill);
        if (store.xp(key(player), skill) > Long.MAX_VALUE - gained) return Award.none(skill);
        long next = store.xp(key(player), skill) + gained;
        int newLevel = SkillCurve.levelFor(next, curve());
        List<Integer> granted = grantMilestones(player, oldLevel, newLevel);
        if (granted == null) return Award.none(skill);
        long total = store.addXp(key(player), skill, gained);
        announceLevels(player, skill, oldLevel, newLevel);
        return new Award(skill, gained, oldLevel, newLevel, granted);
    }

    /** {@code null} — баланс выключен или престиж отключён полностью. */
    private BalanceView balanceView(Player player) {
        SkillsConfig cfg = plugin.config();
        if (!cfg.prestigeEnabled() || !cfg.prestigeBalanceEnabled()) {
            return null;
        }
        Set<SkillType> tracked = EnumSet.noneOf(SkillType.class);
        for (SkillType type : SkillType.values()) {
            if (cfg.skill(type).enabled()) {
                tracked.add(type);
            }
        }
        if (tracked.isEmpty()) {
            return null;
        }
        // Эффективная ступень ранга (включая особые звания), мягко: старое ядро
        // или аддон без API просто не дают послабления, а не роняют прокачку.
        int rankLevel = effectiveRankLevel(plugin.coreApi(), player.getName());
        int rankBonus = PrestigeXpGate.rankBonus(cfg.prestigeBalanceRankMaxLead(), rankLevel);
        int allowance = PrestigeXpGate.allowance(cfg.prestigeBalanceBaseMaxLead(),
                cfg.prestigeBalanceRankMaxLead(), rankLevel, cfg.prestigeBalanceHardCap());
        return new BalanceView(store.allPrestige(key(player)), tracked, allowance,
                cfg.prestigeBalanceHardCap(), cfg.prestigeBalanceSoftStep(),
                cfg.prestigeBalanceMinXpFactor(), rankLevel,
                cfg.prestigeBalanceBaseMaxLead(), rankBonus);
    }

    /**
     * Эффективная ступень ранга без жёсткой зависимости от свежего Core.
     * Проверяется юнит-тестами без Bukkit: null, ошибка ядра и бинарно
     * несовместимая сборка дают 0, XP при этом продолжает начисляться.
     */
    static int effectiveRankLevel(ChrdkRegisterApi api, String playerName) {
        if (api == null) {
            return 0;
        }
        try {
            return Math.max(0, api.rankLevel(playerName));
        } catch (RuntimeException | LinkageError outdated) {
            return 0;
        }
    }

    /** Отчёт для {@code /skills balance}; {@code null}, если правило выключено. */
    public BalanceReport balanceReport(Player player) {
        BalanceView view = balanceView(player);
        if (view == null) {
            return null;
        }
        int minimum = PrestigeXpGate.minimum(view.prestige(), view.tracked());
        List<BalanceRow> rows = new ArrayList<>();
        for (SkillType type : SkillType.values()) {
            boolean tracked = view.tracked().contains(type);
            rows.add(new BalanceRow(type,
                    PrestigeXpGate.step(view.prestige(), type), level(player, type),
                    tracked ? PrestigeXpGate.lead(view.prestige(), view.tracked(), type) : 0,
                    tracked ? PrestigeXpGate.factor(view.prestige(), view.tracked(), type,
                            view.allowance(), view.hardCap(), view.softStep(), view.minFactor()) : 1.0,
                    tracked));
        }
        return new BalanceReport(view.allowance(), view.hardCap(), minimum, view.rankLevel(),
                view.baseMaxLead(), view.rankBonus(), List.copyOf(rows));
    }

    /**
     * Actionbar — с кулдауном, чат — только при смене состояния. Ранг истёк или
     * куплен: allowance пересчитывается на следующем действии без отката данных.
     */
    private void notifyBalance(Player player, SkillType skill, int lead, int allowance,
                               double factor, int hardCap) {
        long now = System.currentTimeMillis();
        long window = TimeUnit.SECONDS.toMillis(plugin.config().prestigeBalanceNoticeSeconds());
        Long lastBar = balanceBars.putIfAbsent(player.getUniqueId(), now);
        if (lastBar == null || now - lastBar >= window) {
            balanceBars.put(player.getUniqueId(), now);
            String name = plugin.messages().raw("skill." + skill.id() + ".name");
            if (plugin.config().actionbar()) {
                if (factor <= 0.0) {
                    player.sendActionBar(plugin.messages().get("prestige-balance-bar-stop",
                            "skill", Messages.trusted(name)));
                } else {
                    player.sendActionBar(plugin.messages().get("prestige-balance-bar",
                            "skill", Messages.trusted(name),
                            "factor", PrestigeXpGate.factorText(factor)));
                }
            }
        }
        String key = player.getUniqueId() + ":" + skill.id();
        BalanceState previous = balanceStates.put(key, new BalanceState(factor, allowance));
        if (previous != null && previous.same(factor, allowance)) {
            return;
        }
        String name = plugin.messages().raw("skill." + skill.id() + ".name");
        if (factor <= 0.0) {
            plugin.messages().send(player, "prestige-balance-state-stop",
                    "skill", Messages.trusted(name),
                    "lead", Integer.toString(lead),
                    "hard_cap", Integer.toString(hardCap));
        } else {
            plugin.messages().send(player, "prestige-balance-state",
                    "skill", Messages.trusted(name),
                    "factor", PrestigeXpGate.factorText(factor),
                    "lead", Integer.toString(lead),
                    "allowance", Integer.toString(allowance));
        }
    }

    /** Вернулись к полному опыту: сбрасываем состояние, чтобы новый отрыв снова объявился. */
    private void clearBalanceState(Player player, SkillType skill) {
        balanceStates.remove(player.getUniqueId() + ":" + skill.id());
    }

    /** Админское начисление: то же, но без ограничения «единиц» и с проверкой прав. */
    public Award grantXp(Player player, SkillType skill, long xp) {
        if (player == null || skill == null || xp <= 0L) {
            return Award.none(skill);
        }
        int oldLevel = level(player, skill);
        if (store.xp(key(player), skill) > Long.MAX_VALUE - xp) return Award.none(skill);
        long next = store.xp(key(player), skill) + xp;
        int newLevel = SkillCurve.levelFor(next, curve());
        if (grantMilestones(player, oldLevel, newLevel) == null) return Award.none(skill);
        long total = store.addXp(key(player), skill, xp);
        announceLevels(player, skill, oldLevel, newLevel);
        return new Award(skill, xp, oldLevel, newLevel, List.of());
    }

    /** Админская установка уровня: опыт ставится на начало уровня. */
    public void setLevel(Player player, SkillType skill, int level) {
        if (player == null || skill == null) {
            return;
        }
        int clamped = Math.max(1, Math.min(curve().maxLevel(), level));
        store.setXp(key(player), skill, SkillCurve.totalForLevel(clamped, curve()));
    }

    public void reset(Player player) {
        if (player == null) {
            return;
        }
        for (SkillType skill : SkillType.values()) {
            store.setXp(key(player), skill, 0L);
        }
    }

    /**
     * Поздравление с новым уровнем: чат, экшнбар и звук. Имя навыка берётся из
     * messages.yml — цвет уже в шаблоне, поэтому подстановка идёт как trusted.
     */
    private void announceLevels(Player player, SkillType skill, int from, int to) {
        if (to <= from) {
            return;
        }
        String name = plugin.messages().raw("skill." + skill.id() + ".name");
        if (plugin.config().announce()) {
            plugin.messages().send(player, "level-up",
                    "skill", Messages.trusted(name),
                    "level", String.valueOf(to));
        }
        if (plugin.config().actionbar()) {
            player.sendActionBar(plugin.messages().get("actionbar-level",
                    "skill", Messages.trusted(name),
                    "level", String.valueOf(to)));
        }
        if (plugin.config().sounds()) {
            player.playSound(player.getLocation(),
                    org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
        }
    }

    private List<Integer> grantMilestones(Player player, int oldLevel, int newLevel) {
        if (newLevel <= oldLevel) {
            return List.of();
        }
        var pending = new java.util.LinkedHashMap<String,Long>();
        for (var entry : plugin.config().milestones().entrySet()) {
            int level=entry.getKey();
            if (level>oldLevel && level<=newLevel && !store.milestones(key(player)).contains(level))
                pending.put("level:"+level,(long)entry.getValue().diamonds());
        }
        if (!pending.isEmpty() && !plugin.awards().reserveAll(player,pending)) return null;
        List<Integer> granted = new ArrayList<>();
        for (var entry : plugin.config().milestones().entrySet()) {
            int level=entry.getKey();
            if (!pending.containsKey("level:"+level)) continue;
            store.markMilestone(key(player),level); granted.add(level);
            plugin.messages().send(player,"milestone","level",level,"diamonds",plugin.awards().amount(player,"level:"+level));
        }
        return List.copyOf(granted);
    }

    /* ---------------------------------- топ ----------------------------------- */

    /** Топ игроков по суммарному опыту: имя и уровень. */
    public List<Entry> top() {
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, Long> row
                : store.topByTotalXp(plugin.config().leaderboardSize())) {
            String name = store.name(row.getKey());
            if (name.isBlank()) {
                name = shortKey(row.getKey());
            }
            out.add(new Entry(name, SkillCurve.levelFor(row.getValue(), curve()),
                    row.getValue()));
        }
        return List.copyOf(out);
    }

    public record Entry(String name, int level, long xp) {
    }

    /** Обновляет ник в профиле (вызывается при входе). */
    public void rememberName(Player player) {
        if (player != null) {
            store.setName(key(player), player.getName());
        }
    }

    private static String shortKey(String key) {
        return key == null || key.length() < 8 ? "?" : key.substring(0, 8);
    }

    public SkillStore store() {
        return store;
    }

    private static String key(Player player) {
        return player.getUniqueId().toString();
    }
}
