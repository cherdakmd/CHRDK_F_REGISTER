package ru.cherdakmd.chrdkclasses;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Общая арифметика классов. В игровом плагине включён DevelopmentService:
 * собственный XP, ранги, сборки и Skills только как требования.
 * Явно не включённый development сохраняет прежнюю модель для проверки
 * совместимости и замороженной миграции; игровой плагин её не использует.
 */
public final class TalentService {

    private volatile ClassConfig config;
    private final ClassStore store;
    private final SkillSource skills;
    private final RankSource ranks;
    private final AchievementSource achievements;
    private DevelopmentService development;
    void enableDevelopment(LegacyClassRules rules) {
        development = new DevelopmentService(this, new LegacyClassSnapshotService(store, rules, skills, ranks, achievements));
    }
    DevelopmentService development() { return development; }
    ClassDataRead rankSnapshot(UUID id) {
        if (!config.ranksEnabled()) return ClassDataRead.absent(ClassDataRead.Status.NOT_REQUIRED);
        return ranks == null ? ClassDataRead.absent(ClassDataRead.Status.UNAVAILABLE) : ranks.snapshotRank(id);
    }


    /** Сервис без рангов и достижений: базовые правила (так его видят тесты). */
    public TalentService(ClassConfig config, ClassStore store, SkillSource skills) {
        this(config, store, skills, null, null);
    }

    public TalentService(ClassConfig config, ClassStore store, SkillSource skills,
                         RankSource ranks, AchievementSource achievements) {
        this.config = config;
        this.store = store;
        this.skills = skills;
        this.ranks = ranks;
        this.achievements = achievements;
    }

    /** Новый конфиг без потери прогресса (перезагрузка). */
    public void applyConfig(ClassConfig config) {
        if (config != null) {
            this.config = config;
        }
    }

    public ClassConfig config() {
        return config;
    }

    public ClassStore store() {
        return store;
    }

    /* --------------------------------- расчёты -------------------------------- */

    /** Сумма уровней навыков игрока (0 без аддона навыков). */
    public int totalSkill(UUID player) {
        return skills == null || player == null ? 0 : Math.max(0, skills.totalLevel(player));
    }

    /** Уровень навыка — для проверки требований талантов. */
    public int skillLevel(UUID player, String skillId) {
        return skills == null || player == null || skillId == null || skillId.isBlank()
                ? 0 : Math.max(0, skills.level(player, skillId));
    }

    ClassDataRead skillSnapshot(UUID player, String skillId) {
        if (skills == null) {
            return ClassDataRead.absent(ClassDataRead.Status.UNAVAILABLE);
        }
        try {
            ClassDataRead result = skills.snapshotLevel(player, skillId);
            return result == null ? ClassDataRead.absent(ClassDataRead.Status.ERROR) : result;
        } catch (RuntimeException | LinkageError error) {
            return ClassDataRead.absent(ClassDataRead.Status.ERROR);
        }
    }

    /* ------------------------------- ранги (1.4.175) ------------------------------- */

    /** Ступень донатного ранга: 0 — ранга нет или ядро не отвечает. */
    public int rankLevel(UUID player) {
        if (ranks == null || player == null) {
            return 0;
        }
        return Math.clamp(ranks.rankLevel(player), 0, RankPerks.MAX_RANK);
    }

    /**
     * Плюшки ранга игрока; при выключенной секции {@code ranks} — никаких.
     *
     * <p>Выключатель стоит здесь, а не у каждого вызова: тогда «плюшки
     * выключены» значит ровно одно — все числа как без ранга.</p>
     */
    RankPerks perks(UUID player) {
        return config.ranksEnabled() ? RankPerks.of(rankLevel(player)) : RankPerks.NONE;
    }

    /** Шаг уровня класса с учётом ранга — для подписей в меню. */
    public int levelStep(UUID player) {
        return perks(player).levelStep(config.levelsPerLevel());
    }

    /** Сколько уровней навыков нужно до следующего уровня класса с учётом ранга. */
    public int nextLevelIn(UUID player) {
        if (development != null) return store.development(player) == null ? 0 : Math.toIntExact(ClassDevelopmentRules.nextLevelIn(store.development(player).xp()));
        return ClassMath.nextLevelIn(totalSkill(player), levelStep(player));
    }

    /* --------------------------------- очки ---------------------------------- */

    /** Сколько очков талантов игрок получил за достижения. */
    public int achievementBonus(UUID player) {
        if (achievements == null || player == null || !config.enabled()) {
            return 0;
        }
        return ClassMath.achievementBonus(achievements.unlockedCount(player),
                config.achievementEvery(), config.achievementMaxBonus());
    }

    /** Сколько достижений открыто; 0 — аддона нет. */
    public int unlockedAchievements(UUID player) {
        return achievements == null || player == null ? 0
                : Math.max(0, achievements.unlockedCount(player));
    }

    /**
     * Порог навыка для узла с учётом ранга.
     *
     * <p>Меню и проверка зовут именно его: иначе кнопка показывала бы 75, а
     * сервис требовал 70 — и игрок считал бы, что талант «не учится».</p>
     */
    public int requiredSkill(UUID player, Talent talent) {
        return talent == null || !talent.needsSkill() ? 0
                : perks(player).skillRequirement(talent.skillLevel());
    }

    /** Уровень класса, выведенный из навыков (шаг уменьшен рангом). */
    public int level(UUID player) {
        if (development != null) return store.development(player) == null ? 1 : store.development(player).level();
        return ClassMath.level(totalSkill(player), levelStep(player));
    }

    /** Сколько очков игрок заработал за всё время: уровни плюс достижения. */
    public int earnedPoints(UUID player) {
        if (development != null) return store.development(player) == null ? 0 : store.development(player).earnedPoints();
        return ClassMath.points(level(player), config.startingPoints(),
                config.pointsPerLevel()) + achievementBonus(player);
    }

    /** Сколько очков стоит изученное дерево. */
    public int spentPoints(UUID player) {
        if (development != null) return store.build(player) == null ? 0 : store.build(player).spent();
        int spent = 0;
        for (String id : store.talents(player)) {
            Talent talent = ClassCatalog.talent(id);
            if (talent != null) {
                spent += talent.cost();
            }
        }
        return spent;
    }

    /** Сколько очков можно потратить прямо сейчас. */
    public int availablePoints(UUID player) {
        return ClassMath.available(earnedPoints(player), spentPoints(player));
    }

    /** Выбранный класс или {@code null}. */
    public PlayerClass classOf(UUID player) {
        return ClassCatalog.playerClass(store.classOf(player));
    }

    /** Снимок текущих исходных правил; сам перенос здесь не запускается. */
    LegacyClassSnapshotService.Result progressSnapshot(UUID player, long now) {
        return new LegacyClassSnapshotService(store, LegacyClassRules.capture(config), skills, ranks,
                achievements).read(player, now);
    }

    Map<EffectKind, ClassEffectView> effectDetails(UUID player) {
        return effectDetails(player, development == null ? null : store.build(player));
    }
    Map<EffectKind, ClassEffectView> effectDetails(UUID player, ClassBuild selected) {
        Map<EffectKind, Double> passives = new EnumMap<>(EffectKind.class);
        Map<EffectKind, Double> talents = new EnumMap<>(EffectKind.class);
        PlayerClass playerClass = classOf(player);
        if (playerClass != null && (development == null || store.development(player) != null)) {
            passives.putAll(ClassCatalog.passive(playerClass.id()));
            for (String id : development == null ? store.talents(player) : selected == null ? java.util.Set.<String>of() : selected.ranks().keySet()) {
                Talent talent = development == null ? ClassCatalog.talent(id) : ClassTrees.talent(id);
                if (talent != null && talent.classId().equals(playerClass.id())) {
                    talents.merge(talent.kind(), talent.value() * (development == null ? 1 : selected.ranks().get(id)), Double::sum);
                }
            }
        }
        Map<EffectKind, ClassEffectView> result = new EnumMap<>(EffectKind.class);
        for (EffectKind kind : EffectKind.values()) {
            result.put(kind, new ClassEffectView(kind, passives.getOrDefault(kind, 0.0),
                    talents.getOrDefault(kind, 0.0), development == null || playerClass == null ? kind.cap() : ClassTrees.ceiling(playerClass.id(), kind)));
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    /** Итоговые эффекты берутся из того же расчёта, который показывает паспорт. */
    public Map<EffectKind, Double> effects(UUID player) {
        Map<EffectKind, Double> out = new EnumMap<>(EffectKind.class);
        for (ClassEffectView view : effectDetails(player).values()) {
            if (view.effective() > 0.0) {
                out.put(view.kind(), view.effective());
            }
        }
        return out;
    }

    /** Величина одного эффекта (0, если эффекта нет). */
    public double effect(UUID player, EffectKind kind) {
        return kind == null ? 0.0 : effects(player).getOrDefault(kind, 0.0);
    }

    /* -------------------------------- кулдауны -------------------------------- */

    /** Сколько секунд осталось до смены класса (0 — можно менять). */
    public long changeCooldownLeft(UUID player, long now) {
        long base = perks(player).changeCooldownSeconds(config.changeCooldownSeconds());
        long until = store.changedAt(player) + base * 1_000L;
        return until <= now ? 0L : (until - now + 999) / 1_000L;
    }

    /** Сколько секунд осталось до пересборки дерева. */
    public long respecCooldownLeft(UUID player, long now) {
        if (development != null) return development.cooldownLeft(player,now);
        long base = perks(player).respecCooldownSeconds(config.respecCooldownSeconds());
        long until = store.respecAt(player) + base * 1_000L;
        return until <= now ? 0L : (until - now + 999) / 1_000L;
    }

    /* ---------------------------------- выбор --------------------------------- */

    /** Проверка выбора класса без записи — для меню и подтверждения. */
    public ClassResult checkChoose(UUID player, String classId, long now) {
        if (development != null && store.development(player) == null) return ClassResult.fail("development-pending");
        if (!config.enabled()) {
            return ClassResult.fail("disabled");
        }
        PlayerClass target = ClassCatalog.playerClass(classId);
        if (target == null) {
            return ClassResult.fail("class-unknown", "class", classId == null ? "?" : classId);
        }
        PlayerClass current = classOf(player);
        if (current == target) {
            return ClassResult.fail("class-same", "class", target.id());
        }
        if (current != null && spentPoints(player) > earnedPoints(player)) {
            return ClassResult.fail("class-budget-unconfirmed");
        }
        long left = changeCooldownLeft(player, now);
        if (left > 0) {
            return ClassResult.fail("class-cooldown", "time", ClassMath.clock(left));
        }
        if (current == null) {
            return ClassResult.ok("class-choose-ready", "class", target.id());
        }
        return ClassResult.ok("class-change-ready", "from", current.id(), "to", target.id());
    }

    /**
     * Выбирает класс; при смене стирает прежние таланты.
     *
     * <p>Стирание — не наказание, а неизбежность: таланты воина бессмысленны
     * для жреца, а оставлять их «на будущее» значит разрешить собрать два
     * дерева подряд за одну прокачку.</p>
     */
    public ClassResult choose(UUID player, String classId, long now) {
        ClassResult check = checkChoose(player, classId, now);
        if (!check.ok()) {
            return check;
        }
        PlayerClass target = ClassCatalog.playerClass(classId);
        boolean changing = classOf(player) != null;
        int lost = store.classOf(player).isEmpty() ? 0 : store.talentCount(player);
        store.setClass(player, target.id(), now);
        if (development != null) store.initializeBuild(player);
        return changing
                ? ClassResult.ok("class-changed", "class", target.id(),
                        "talents", String.valueOf(lost))
                : ClassResult.ok("class-chosen", "class", target.id());
    }

    /* --------------------------------- таланты -------------------------------- */

    /** Проверка изучения таланта без записи. */
    public ClassResult checkLearn(UUID player, String talentId) {
        if (development != null) {
            var errors = development.learnProblems(player,talentId);
            return errors.isEmpty() ? ClassResult.ok("talent-ready") : errors.getFirst();
        }
        if (!config.enabled()) {
            return ClassResult.fail("disabled");
        }
        PlayerClass playerClass = classOf(player);
        if (playerClass == null) {
            return ClassResult.fail("class-required");
        }
        Talent talent = ClassCatalog.talent(talentId);
        if (talent == null) {
            return ClassResult.fail("talent-unknown",
                    "talent", talentId == null ? "?" : talentId);
        }
        if (!talent.classId().equals(playerClass.id())) {
            return ClassResult.fail("talent-foreign", "talent", talent.id(),
                    "class", playerClass.id());
        }
        if (store.hasTalent(player, talent.id())) {
            return ClassResult.fail("talent-known", "talent", talent.id());
        }
        if (talent.hasParent() && !store.hasTalent(player, talent.requires())) {
            return ClassResult.fail("talent-locked", "talent", talent.requires());
        }
        if (talent.needsSkill()) {
            int have = skillLevel(player, talent.skill());
            int required = perks(player).skillRequirement(talent.skillLevel());
            if (have < required) {
                return ClassResult.fail("talent-skill", "skill", talent.skill(),
                        "level", String.valueOf(required),
                        "have", String.valueOf(have));
            }
        }
        int available = availablePoints(player);
        if (available < talent.cost()) {
            return ClassResult.fail("talent-points", "cost", String.valueOf(talent.cost()),
                    "points", String.valueOf(available));
        }
        return ClassResult.ok("talent-ready", "talent", talent.id(),
                "cost", String.valueOf(talent.cost()));
    }

    /** Учит талант, если пройдены все проверки. */
    public ClassResult learn(UUID player, String talentId) {
        if (development != null) return development.learn(player,talentId,System.currentTimeMillis());
        ClassResult check = checkLearn(player, talentId);
        if (!check.ok()) {
            return check;
        }
        Talent talent = ClassCatalog.talent(talentId);
        store.learnTalent(player, talent.id());
        return ClassResult.ok("talent-learned", "talent", talent.id(),
                "points", String.valueOf(availablePoints(player)));
    }

    /**
     * Пересборка дерева: таланты стираются, очки возвращаются в котёл.
     *
     * <p>Кулдаун нужен, чтобы «пересобрал под каждого моба» не стало
     * единственным способом играть. Класс при этом не меняется.</p>
     */
    public ClassResult respec(UUID player, long now) {
        if (development != null) return ClassResult.fail("development-confirm-required");
        if (!config.enabled()) {
            return ClassResult.fail("disabled");
        }
        if (classOf(player) == null) {
            return ClassResult.fail("class-required");
        }
        if (store.talentCount(player) == 0) {
            return ClassResult.fail("respec-empty");
        }
        long left = respecCooldownLeft(player, now);
        if (left > 0) {
            return ClassResult.fail("respec-cooldown", "time", ClassMath.clock(left));
        }
        if (spentPoints(player) > earnedPoints(player)) {
            return ClassResult.fail("respec-budget-unconfirmed");
        }
        int cleared = store.clearTalents(player, now);
        return ClassResult.ok("respec-done", "talents", String.valueOf(cleared),
                "points", String.valueOf(availablePoints(player)));
    }

    /* -------------------------------- служебное -------------------------------- */

    /** Админская выдача класса в обход кулдауна. */
    public ClassResult setByAdmin(UUID player, String classId, long now) {
        if (development != null && !development.prepare(player,now)) return ClassResult.fail("development-pending");
        PlayerClass target = ClassCatalog.playerClass(classId);
        if (target == null) {
            return ClassResult.fail("class-unknown", "class", classId == null ? "?" : classId);
        }
        store.setClass(player, target.id(), now);
        if (development != null) store.initializeBuild(player);
        return ClassResult.ok("admin-class-set", "class", target.id());
    }

    /** Админский полный сброс: класс, таланты, моменты. */
    public ClassResult resetByAdmin(UUID player) {
        if (development != null && !development.prepare(player,System.currentTimeMillis())) return ClassResult.fail("development-pending");
        if (!store.reset(player)) {
            return ClassResult.fail("admin-reset-none");
        }
        return ClassResult.ok("admin-reset");
    }
}
