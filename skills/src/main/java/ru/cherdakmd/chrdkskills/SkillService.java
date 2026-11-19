package ru.cherdakmd.chrdkskills;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
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
    /** Двухшаговое подтверждение перерождения: TTL и одноразовость из ядра. */
    private final EconomyConfirmations confirmations = new EconomyConfirmations();

    /** Результат обращения к {@link #requestRebirth}. */
    public enum RebirthResult { DISABLED, NOT_READY, PENDING, DONE }

    /** Строка топа перерождений. */
    public record RebirthEntry(String name, int count) {
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

    /* -------------------------------- атрибуты -------------------------------- */

    /** Всего очков атрибутов за прокачку: уровни навыков и ступени престижа. */
    public int attributePoints(Player player) {
        return AttributeMath.points(totalLevel(player), rebirths(player));
    }

    /**
     * Свободные очки общего пула: атрибуты и ветки билда делят один бюджет
     * (уровни навыков + престиж), честный PvE-вклад добавляет бонусные очки.
     */
    public int freeAttributePoints(Player player) {
        return Math.max(0, attributePoints(player) + bonusPoints(player)
                - store.spentAttributes(key(player)) - store.spentBranches(key(player)));
    }

    /** Вложенные очки атрибута. */
    public int attribute(Player player, PlayerAttribute attribute) {
        return player == null || attribute == null ? 0 : store.attribute(key(player), attribute);
    }

    /** Сумма вложенных очков — «ранг атрибутов» для порогов тиров. */
    public int attributeSum(Player player) {
        return player == null ? 0 : store.spentAttributes(key(player));
    }

    /** Стойкость — компенсация пробивания защиты высшими тирами. */
    public int stamina(Player player) {
        return attribute(player, PlayerAttribute.STAMINA);
    }

    /**
     * Вкладывает очки; {@code false} — не хватает свободных. После вклада
     * вызывающий обновляет эффекты ({@code plugin.effects().apply(player)}).
     */
    public boolean spendAttribute(Player player, PlayerAttribute attribute, int points) {
        if (player == null || attribute == null || points <= 0
                || points > freeAttributePoints(player)) {
            return false;
        }
        return store.addAttribute(key(player), attribute, points);
    }

    /** Полный сброс распределения; все очки возвращаются в свободный пул. */
    public void resetAttributes(Player player) {
        if (player == null) {
            return;
        }
        store.resetAttributes(key(player));
    }

    /** Бонусные очки общего пула за честный PvE-вклад. */
    public int bonusPoints(Player player) {
        return player == null ? 0 : store.bonusPoints(key(player));
    }

    /** Уровень ветки билда. */
    public int branchLevel(Player player, BuildBranch branch) {
        return player == null || branch == null ? 0 : store.branch(key(player), branch);
    }

    /** Уровень ветки по строковому id — мягкий мост для других аддонов. */
    public int branchLevel(Player player, String branchId) {
        return branchLevel(player, BuildBranch.byId(branchId));
    }

    /**
     * Вкладывает очки общего пула в ветку (уровень ветки = очко);
     * {@code false} — не хватает свободных или ветка на потолке.
     */
    public boolean spendBranch(Player player, BuildBranch branch, int points) {
        if (player == null || branch == null || points <= 0
                || points > freeAttributePoints(player)) {
            return false;
        }
        int level = branchLevel(player, branch);
        if (level + points > BuildBranch.MAX_LEVEL) {
            return false;
        }
        return store.setBranch(key(player), branch, level + points);
    }

    /** Сброс веток; очки возвращаются в общий пул. Оплата сброса — у вызывающего. */
    public void resetBranches(Player player) {
        if (player == null) {
            return;
        }
        store.resetBranches(key(player));
    }

    /** Суммарный честный PvE-вклад (журнал). */
    public long pveContribution(Player player) {
        return player == null ? 0L : store.pve(key(player));
    }

    /**
     * Прибавляет единицы честного PvE-вклада и возвращает число выданных
     * бонусных очков пула ({@link BranchMath#PVE_PER_POINT}). Дневной потолок
     * очков и честность события проверяет вызывающий слушатель.
     */
    public int addPve(Player player, long units) {
        if (player == null || units <= 0) return 0;
        long before = store.pve(key(player));
        long after = store.addPve(key(player), units);
        return (int) (after / BranchMath.PVE_PER_POINT - before / BranchMath.PVE_PER_POINT);
    }

    /** Выдаёт бонусные очки общего пула (награда за вклад). */
    public void grantBonusPoints(Player player, int points) {
        if (player == null || points <= 0) {
            return;
        }
        store.addBonusPoints(key(player), points);
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

    /* ------------------------------ перерождения ------------------------------ */

    /** Сколько перерождений сделал игрок (0 — ещё не перерождался). */
    public int rebirths(Player player) {
        return player == null ? 0 : store.rebirths(key(player));
    }

    /** Старая шкала ступеней (перерождение = 10 ступеней) — контракт аддонов. */
    public int totalPrestige(Player player) {
        return rebirths(player) * 10;
    }

    /** Текущий титул перерождения. */
    public String rebirthTitle(Player player) {
        return RebirthMath.title(rebirths(player));
    }

    /** Топ игроков по числу перерождений. */
    public List<RebirthEntry> rebirthTop() {
        List<RebirthEntry> out = new ArrayList<>();
        for (Map.Entry<String, Integer> row
                : store.rebirthTop(plugin.config().leaderboardSize())) {
            String name = store.name(row.getKey());
            out.add(new RebirthEntry(name.isBlank() ? shortKey(row.getKey()) : name,
                    row.getValue()));
        }
        return List.copyOf(out);
    }

    /**
     * Перерождение (п.30): полный сброс персонажа, когда все десять навыков на
     * потолке. Первый вызов выдаёт подтверждение, повторный в течение TTL
     * реально сбрасывает уровни, атрибуты и ветки и выдаёт награды.
     */
    public RebirthResult requestRebirth(Player player) {
        if (player == null || !plugin.config().enabled() || !plugin.config().rebirthEnabled()) {
            return RebirthResult.DISABLED;
        }
        List<Integer> levels = new ArrayList<>();
        for (SkillType skill : SkillRegistry.all()) {
            levels.add(level(player, skill));
        }
        if (!RebirthMath.ready(levels, curve().maxLevel())) {
            return RebirthResult.NOT_READY;
        }
        return applyRebirth(player);
    }

    /**
     * Общий исполнитель: двухшаговое подтверждение, полный сброс персонажа и
     * награды — алмазы предметом, уникальный предмет из пула без повторов и
     * особые награды на вехах.
     */
    private RebirthResult applyRebirth(Player player) {
        long now = System.nanoTime();
        if (!confirmations.consume(player.getUniqueId(), "rebirth", "all", now)) {
            confirmations.offer(player.getUniqueId(), "rebirth", "all", now);
            return RebirthResult.PENDING;
        }
        store.resetForRebirth(key(player));
        store.addRebirth(key(player));
        int count = store.rebirths(key(player));
        double bonus = RebirthMath.xpBonusPercent(count,
                plugin.config().rebirthXpPercentPerRebirth(),
                plugin.config().rebirthXpDecay(),
                plugin.config().rebirthXpBonusCapPercent());
        giveDiamonds(player, RebirthMath.rebirthDiamonds(plugin.config().rebirthDiamondsPerRebirth()));
        giveUniqueReward(player);
        plugin.messages().send(player, "rebirth-done",
                "count", String.valueOf(count),
                "title", Messages.trusted(RebirthMath.title(count)),
                "bonus", String.format(java.util.Locale.ROOT, "%.1f", bonus));
        player.showTitle(net.kyori.adventure.title.Title.title(
                plugin.messages().get("rebirth-title", "count", String.valueOf(count)),
                plugin.messages().get("rebirth-subtitle",
                        "title", Messages.trusted(RebirthMath.title(count)))));
        var location = player.getLocation().add(0, 1, 0);
        player.getWorld().spawnParticle(org.bukkit.Particle.TOTEM_OF_UNDYING, location, 160, 1.0, 1.2, 1.0, 0.18);
        player.getWorld().playSound(location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
        grantMilestoneRewards(player, count);
        // Аддоны со своим «перерождением» (генератор п.31) сбрасываются по событию.
        Bukkit.getPluginManager().callEvent(
                new ru.cherdakmd.chrdkregister.api.events.RebirthEvent(player, count));
        return RebirthResult.DONE;
    }

    /**
     * Уникальный предмет из пула: случайный из ещё не выданных. Пул исчерпан —
     * утешительные алмазы двойной нормы.
     */
    private void giveUniqueReward(Player player) {
        List<SkillsConfig.UniqueItem> fresh = plugin.config().rebirthUniqueItems().stream()
                .filter(item -> !store.rebirthUniques(key(player)).contains(item.id()))
                .toList();
        if (fresh.isEmpty()) {
            giveDiamonds(player, RebirthMath.rebirthDiamonds(
                    plugin.config().rebirthDiamondsPerRebirth()) * 2);
            return;
        }
        SkillsConfig.UniqueItem pick = fresh.get(
                java.util.concurrent.ThreadLocalRandom.current().nextInt(fresh.size()));
        store.grantRebirthUnique(key(player), pick.id());
        giveItem(player, buildUnique(pick));
        plugin.getLogger().info("[CHRDK_Skills] " + player.getName()
                + " получил уникальный предмет «" + pick.name() + "» за перерождение");
    }

    private ItemStack buildUnique(SkillsConfig.UniqueItem item) {
        Material material = Material.matchMaterial(item.material());
        if (material == null || !material.isItem()) {
            material = Material.NETHER_STAR;
        }
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plugin.messages().markup(item.name()));
        if (!item.lore().isEmpty()) {
            meta.lore(item.lore().stream().map(plugin.messages()::markup).toList());
        }
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * Особые награды на вехах (5, 10, 25, 50, далее каждые 25): доплата
     * алмазами, второй уникальный предмет и анонс в общий чат.
     */
    private void grantMilestoneRewards(Player player, int count) {
        if (!RebirthMath.milestone(count) || !store.markRebirthMilestone(key(player), count)) {
            return;
        }
        giveDiamonds(player, RebirthMath.milestoneDiamonds(plugin.config().rebirthMilestoneDiamonds()));
        giveUniqueReward(player);
        plugin.getLogger().info("[CHRDK_Skills] " + player.getName()
                + " достиг вехи перерождения " + count);
        if (plugin.config().rebirthAnnounceMilestones()) {
            Bukkit.broadcast(plugin.messages().get("rebirth-milestone-announce",
                    "player", player.getName(),
                    "count", String.valueOf(count),
                    "title", Messages.trusted(RebirthMath.title(count))));
        }
    }

    /** Алмазы предметом: в инвентарь, остаток — под ноги. */
    private void giveDiamonds(Player player, int amount) {
        if (player == null || !player.isOnline() || amount <= 0) {
            return;
        }
        giveItem(player, new ItemStack(Material.DIAMOND, Math.min(64 * 36, amount)));
    }

    /** Предмет в инвентарь, остаток — под ноги. */
    private void giveItem(Player player, ItemStack stack) {
        if (player == null || !player.isOnline() || stack == null) {
            return;
        }
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
        long perUnit = plugin.config().xpFor(skill);
        if (perUnit <= 0L) {
            return Award.none(skill);
        }
        // Внешний аддон может передать любое число — ограничиваем разумным
        // максимумом, чтобы не поймать переполнение long.
        long safeUnits = Math.min(1000L, units);
        long gained = perUnit * safeUnits;
        if (plugin.coreApi() != null) gained = plugin.coreApi().boostExperience(player.getName(), "skills", gained, gained);
        // Перерождение ускоряет прокачку навсегда: убывающий прирост, потолок
        // +50%, один множитель на все навыки.
        gained = Math.round(gained * RebirthMath.xpFactor(rebirths(player),
                plugin.config().rebirthXpPercentPerRebirth(),
                plugin.config().rebirthXpDecay(),
                plugin.config().rebirthXpBonusCapPercent()));
        // Мудрость ускоряет прокачку навыков (атрибуты персонажа).
        gained = Math.round(gained * AttributeMath.xpFactor(
                attribute(player, PlayerAttribute.WISDOM)));
        // Маг: интеллект внутри ветки билда — ещё +2% опыта за уровень.
        gained = Math.round(gained * BranchMath.xpMult(
                branchLevel(player, BuildBranch.MAGE)));
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

    /** Отчёт для {@code /skills balance} удалён вместе с правилом баланса (п.30). */



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
     * Поздравление с новым уровнем: акшнбар (без спама в чате) и звук. Чат —
     * запасной путь, когда акшнбар выключен (feedback.actionbar: false) и
     * включён feedback.announce. Имя навыка берётся из messages.yml — цвет уже
     * в шаблоне, поэтому подстановка идёт как trusted.
     */
    private void announceLevels(Player player, SkillType skill, int from, int to) {
        if (to <= from) {
            return;
        }
        String name = plugin.messages().raw("skill." + skill.id() + ".name");
        if (plugin.config().actionbar()) {
            player.sendActionBar(plugin.messages().get("actionbar-level",
                    "skill", Messages.trusted(name),
                    "level", String.valueOf(to)));
        } else if (plugin.config().announce()) {
            plugin.messages().send(player, "level-up",
                    "skill", Messages.trusted(name),
                    "level", String.valueOf(to));
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
