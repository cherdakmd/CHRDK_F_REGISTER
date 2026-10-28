package ru.cherdakmd.chrdkskills;

import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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
        return SkillPerks.active(Map.of(skill, level(player, skill)));
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
        long perUnit = plugin.config().xpFor(skill);
        if (perUnit <= 0L) {
            return Award.none(skill);
        }
        // Внешний аддон может передать любое число — ограничиваем разумным
        // максимумом, чтобы не поймать переполнение long.
        long safeUnits = Math.min(1000L, units);
        long gained = perUnit * safeUnits;
        if (plugin.coreApi() != null) gained = plugin.coreApi().boostExperience(player.getName(), "skills", gained, gained);
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
