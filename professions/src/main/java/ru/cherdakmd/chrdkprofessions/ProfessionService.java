package ru.cherdakmd.chrdkprofessions;

import ru.cherdakmd.chrdkregister.api.RankBonus;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Function;

/**
 * Правила профессий (1.4.165): выбор, опыт, суточный потолок и прибавки.
 *
 * <p>Сервис не знает ни о событиях, ни о сообщениях: слушатель приносит ему
 * «столько-то опыта такой-то профессии», а он решает, положено ли это, не
 * превышен ли суточный потолок и не вырос ли уровень. Поэтому правила
 * проверяются без сервера, а команда, меню и события ведут себя одинаково.</p>
 */
public final class ProfessionService {

    private ProfessionConfig config;
    private final ProfessionStore store;
    /** Бонусы донатного ранга: подставляются ядром после загрузки API (1.4.167). */
    private volatile Function<UUID, RankBonus> rankBonus = player -> RankBonus.NONE;

    @FunctionalInterface
    public interface RewardScaler { long apply(UUID player, long base, long current); }
    private volatile RewardScaler rewardScaler = (player, base, current) -> current;
    public void setRewardScaler(RewardScaler scaler) {
        rewardScaler = scaler == null ? (player, base, current) -> current : scaler;
    }

    public ProfessionService(ProfessionConfig config, ProfessionStore store) {
        this.config = config == null ? ProfessionConfig.defaults() : config;
        this.store = store;
    }

    /**
     * Подключает источник бонусов ранга.
     *
     * <p>Сервис не держит ссылку на ядро: аддон может работать без него, а тесты
     * обходятся без Bukkit. Провайдер возвращает {@link RankBonus#NONE}, если
     * игрок без ранга или ядро старше API v10.</p>
     */
    public void setRankBonus(Function<UUID, RankBonus> provider) {
        this.rankBonus = provider == null ? player -> RankBonus.NONE : provider;
    }

    /** Бонусы ранга игрока; без провайдера — нули, а не {@code null}. */
    public RankBonus bonus(UUID player) {
        if (player == null) {
            return RankBonus.NONE;
        }
        RankBonus value = rankBonus.apply(player);
        return value == null ? RankBonus.NONE : value;
    }

    /** Шкала ремесла едина для всех; исторические прибавки ограничены powerCap. */
    public int maxLevel(UUID player) {
        return ProfessionMath.PROGRESSION_CAP;
    }

    public int powerCap(UUID player){return ProfessionMath.maxLevel(bonus(player).extraSkill());}

    /** Перечитывает конфиг после {@code /profadmin reload}. */
    public void applyConfig(ProfessionConfig updated) {
        if (updated != null) {
            this.config = updated;
        }
    }

    public ProfessionConfig config() {
        return config;
    }

    public ProfessionStore store() {
        return store;
    }

    /* --------------------------------- выбор ---------------------------------- */

    /**
     * Все восемь ремёсел активны у каждого игрока (1.4.282): выбор отменён,
     * опыт и заработок идут сразу по всем подходящим профессиям.
     */
    public boolean isActive(UUID player, Profession profession) {
        return config.enabled() && profession != null;
    }

    /**
     * Ранее выбранная профессия из старого файла прогресса. Оставлена только
     * для обратной совместимости отображения: геймплей её не использует.
     *
     * @deprecated выбор профессии отменён
     */
    @Deprecated
    public Profession active(UUID player) {
        return player == null ? null : store.active(player);
    }

    /** Смены профессии больше нет: кулдаун всегда нулевой. */
    public long chooseCooldownLeft(UUID player, long now) {
        return 0L;
    }

    /** @deprecated выбор профессии отменён; всегда отказ. */
    @Deprecated
    public ProfessionResult checkChoose(UUID player, String professionId, long now) {
        return ProfessionResult.fail("choose-removed");
    }

    /** @deprecated выбор профессии отменён; всегда отказ. */
    @Deprecated
    public ProfessionResult choose(UUID player, String professionId, long now) {
        return ProfessionResult.fail("choose-removed");
    }

    /** Специализация конкретного ремесла: выбор ремесла отменён, ветка — нет. */
    public ProfessionMastery mastery(UUID player, Profession profession) {
        return player == null || profession == null ? null : store.mastery(player, profession);
    }

    /** @deprecated укажите ремесло явно. */
    @Deprecated
    public ProfessionMastery mastery(UUID player) {
        return mastery(player, active(player));
    }

    public long masteryCooldownLeft(UUID player, Profession profession, long now) {
        if (store.mastery(player, profession) == null) return 0;
        long changed = store.masteryChanged(player, profession);
        long until = changed > Long.MAX_VALUE - ProfessionMastery.CHANGE_MILLIS
                ? Long.MAX_VALUE : changed + ProfessionMastery.CHANGE_MILLIS;
        return remaining(until, now);
    }

    public long workCooldownLeft(UUID player, Profession profession, long now) {
        return player == null || profession == null ? 0 : remaining(store.workUntil(player, profession), now);
    }

    /** @deprecated укажите ремесло явно. */
    @Deprecated
    public long workCooldownLeft(UUID player, long now) {
        return workCooldownLeft(player, active(player), now);
    }

    private static long remaining(long until, long now) {
        return until <= now ? 0 : (until - Math.max(0, now)) / 1000 + ((until - Math.max(0, now)) % 1000 > 0 ? 1 : 0);
    }

    public ProfessionResult checkMastery(UUID player, Profession profession) {
        if (!config.enabled() || !config.perksEnabled() || !config.masteryEnabled()) return ProfessionResult.fail("mastery-disabled");
        if (!store.canTrack(player)) return ProfessionResult.fail("mastery-storage-full");
        if (profession == null) return ProfessionResult.fail("mastery-wrong");
        if (level(player, profession) < ProfessionMastery.UNLOCK_LEVEL)
            return ProfessionResult.fail("mastery-locked", "level", String.valueOf(ProfessionMastery.UNLOCK_LEVEL));
        return ProfessionResult.ok("mastery-ready");
    }

    public ProfessionResult chooseMastery(UUID player, String id, long now) {
        ProfessionMastery target = ProfessionMastery.parse(id);
        if (target == null) return ProfessionResult.fail("mastery-wrong");
        Profession profession = target.profession();
        ProfessionResult check = checkMastery(player, profession);
        if (!check.ok()) return check;
        if (target == mastery(player, profession)) return ProfessionResult.fail("mastery-same");
        long left = masteryCooldownLeft(player, profession, now);
        if (left > 0) return ProfessionResult.fail("mastery-change-cooldown", "time", ProfessionMath.clock(left));
        if (!store.setMastery(player, target, now)) return ProfessionResult.fail("mastery-storage-full");
        return ProfessionResult.ok("mastery-chosen", "specialization", target.id());
    }

    /* --------------------------------- опыт ----------------------------------- */

    public int xp(UUID player, Profession profession) {
        return player == null ? 0 : store.xp(player, profession);
    }

    public int totalXp(UUID player) {
        return player == null ? 0 : store.totalXp(player);
    }

    public int level(UUID player, Profession profession) {
        return ProfessionMath.levelFor(xp(player, profession), maxLevel(player));
    }

    /** Лучший уровень среди всех ремёсел: «главной» профессии больше нет. */
    public int level(UUID player) {
        int best = 1;
        for (Profession profession : Profession.values()) {
            best = Math.max(best, level(player, profession));
        }
        return best;
    }

    /**
     * Ремесло с наибольшим опытом — только фокус меню/плейсхолдеров, не выбор
     * и не ограничение: опыт и заработок идут по всем восьми всегда.
     */
    public Profession primary(UUID player) {
        Profession best = Profession.SMITH;
        int bestXp = -1;
        for (Profession profession : Profession.values()) {
            int xp = xp(player, profession);
            if (xp > bestXp) { bestXp = xp; best = profession; }
        }
        return best;
    }

    public int progress(UUID player, Profession profession) {
        return ProfessionMath.progress(xp(player, profession), maxLevel(player));
    }

    public int needed(UUID player, Profession profession) {
        int level = level(player, profession);
        return ProfessionMath.needed(level, maxLevel(player));
    }

    public int progressPercent(UUID player, Profession profession) {
        return ProfessionMath.progressPercent(xp(player, profession), maxLevel(player));
    }

    /* ------------------------------- суточный лимит ---------------------------- */

    /** Дата суточного счётчика в UTC: смена суток не зависит от часового пояса. */
    public static String utcDay(long now) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC).toString();
    }

    /** Сколько опыта уже получено сегодня. */
    public int dailyUsed(UUID player, Profession profession, long now) {
        if (player == null || profession == null) {
            return 0;
        }
        String today = utcDay(now);
        return today.equals(store.dailyDate(player, profession))
                ? store.dailyAmount(player, profession) : 0;
    }

    /**
     * Сколько опыта ещё можно получить сегодня.
     *
     * @return {@code -1}, если суточный потолок выключен ({@code daily-xp-cap: 0})
     */
    public int dailyLeft(UUID player, Profession profession, long now) {
        int cap = config.dailyXpCap();
        if (cap <= 0) {
            return -1;
        }
        return Math.max(0, cap - dailyUsed(player, profession, now));
    }

    /**
     * Начисляет опыт, если профессия активна и суточный потолок не исчерпан.
     *
     * <p>Возвращает и то, сколько реально добавилось: по этому числу видно,
     * был ли упор в потолок, и стоит ли один раз предупредить игрока.</p>
     */
    public Award award(UUID player, Profession profession, int rawXp, long now) {
        if (!isActive(player, profession) || rawXp <= 0) {
            return Award.none();
        }
        int cap = maxLevel(player);
        int before = store.xp(player, profession);
        int levelBefore = ProfessionMath.levelFor(before, cap);
        // Базовый опыт и опыт с бонусом ранга считаем отдельно: суточный потолок
        // ограничивает только базу, так что донатный ранг реально получает
        // больше опыта за день, а не просто быстрее упирается в тот же лимит.
        double factor = config.xpMultiplier() * bonus(player).xpFactor();
        long base = Math.max(1L, Math.round(rawXp * config.xpMultiplier()));
        long boosted = Math.max(1L, Math.round(rawXp * factor));
        long current = Math.max(boosted, rewardScaler.apply(player, base, boosted));
        int left = dailyLeft(player, profession, now);
        long grantedBase = left < 0 ? base : Math.min(base, (long) Math.max(0, left));
        long granted = Math.min(Integer.MAX_VALUE, grantedBase + Math.max(0L, current - base));
        boolean capped = left >= 0 && grantedBase < base;
        if (granted <= 0) {
            return new Award(0, levelBefore, levelBefore, Math.max(0, left), true);
        }
        int after = store.addXp(player, profession, (int) granted);
        int added = Math.max(0, after - before);
        int levelAfter = ProfessionMath.levelFor(after, cap);
        if (left >= 0 && grantedBase > 0) {
            store.setDaily(player, profession, utcDay(now),
                    (int) Math.min(Integer.MAX_VALUE, dailyUsed(player, profession, now) + grantedBase));
        }
        // Заработок считается из фактически начисленного опыта: xpPercent ранга
        // автоматически усиливает и алмазы, без второго умножения.
        long diamonds = accrueIncome(player, grantedBase, granted, now);
        return new Award(added, levelBefore, levelAfter, dailyLeft(player, profession, now), capped, diamonds);
    }

    /**
     * Дробное накопление алмазов. Один алмаз стоит {@code xpPerDiamond} опыта,
     * остаток хранится в тысячных. {@code store.creditIncome} фиксирует
     * кумулятивный total ДО обращения к банку — повтор доставки не удваивает
     * выплату. Потолок 9999 алмазов в сутки — защита от ферм, а не цель темпа.
     */
    private long accrueIncome(UUID player, long baseXp, long grantedXp, long now) {
        if (!config.incomeEnabled() || grantedXp <= 0) return 0L;
        long xp = config.incomeRankBonus() ? grantedXp : baseXp;
        if (xp <= 0) return 0L;
        long micro = store.incomeMicro(player) + xp * 1000L / config.xpPerDiamond();
        long whole = micro / 1000L;
        if (whole <= 0) {
            store.setIncomeMicro(player, micro % 1000L);
            return 0L;
        }
        int cap = config.dailyIncomeCap();
        String today = utcDay(now);
        long paid = store.incomeToday(player, today);
        if (cap > 0 && paid + whole > cap) whole = Math.max(0L, cap - paid);
        if (whole <= 0) {
            store.setIncomeMicro(player, micro % 1000L);
            return 0L;
        }
        store.setIncomeMicro(player, micro % 1000L);
        store.creditIncome(player, whole, today);
        return whole;
    }

    /** Сколько алмазов заработано за сегодня (для /prof и профиля). */
    public long incomeToday(UUID player, long now) {
        return player == null ? 0L : store.incomeToday(player, utcDay(now));
    }

    /** Кумулятивный total, уже переданный в банк. */
    public long incomeTotal(UUID player) {
        return player == null ? 0L : store.incomeTotal(player);
    }

    /** Дробный остаток в тысячных алмаза (0..999). */
    public long incomeMicro(UUID player) {
        return player == null ? 0L : store.incomeMicro(player);
    }

    /**
     * Итог начисления опыта.
     *
     * @param added       сколько опыта реально добавилось
     * @param levelBefore уровень до начисления
     * @param levelAfter  уровень после начисления
     * @param dailyLeft   сколько ещё можно получить сегодня ({@code -1} — без потолка)
     * @param capped      упёрлись ли в суточный потолок
     */
    public record Award(int added, int levelBefore, int levelAfter, int dailyLeft,
                        boolean capped, long diamonds) {

        /** Старые вызывающие места, которым доход не нужен. */
        public Award(int added, int levelBefore, int levelAfter, int dailyLeft, boolean capped) {
            this(added, levelBefore, levelAfter, dailyLeft, capped, 0L);
        }

        static Award none() {
            return new Award(0, 1, 1, -1, false);
        }

        /** Вырос ли уровень: по этому признаку показывается поздравление. */
        public boolean leveled() {
            return levelAfter > levelBefore;
        }
    }

    public Award awardSource(UUID player,Profession profession,String action,org.bukkit.Material material,int units,int xp,long now){
        if(!config.enabled()||player==null||profession==null||material==null||units<=0||xp<=0||!isActive(player,profession))return Award.none();
        int before=store.xp(player,profession),daily=dailyUsed(player,profession,now);
        Award award=award(player,profession,(int)Math.min(Integer.MAX_VALUE,(long)units*xp),now);
        if(award.added()>0||(before>=ProfessionStore.MAX_XP&&(config.dailyXpCap()==0||dailyUsed(player,profession,now)>daily)))store.practice(player,profession,action+":"+material.name().toLowerCase(java.util.Locale.ROOT),units);
        return award;
    }
    public ProfessionTrials trials(){return new ProfessionTrials(this,store);}

    /* --------------------------------- прибавки -------------------------------- */

    /** Скидка кузнеца на наковальню, %: 0, если профессия не активна. */
    public int smithDiscount(UUID player) {
        return perk(player, Profession.SMITH, config.perkSmithAnvil());
    }

    /** Шанс алхимика на лишнюю склянку, %. */
    public int alchemistChance(UUID player) {
        return perk(player, Profession.ALCHEMIST, config.perkAlchemistBottle());
    }

    /** Прибавка повара к насыщению, очки. */
    public int cookSaturation(UUID player) {
        return perk(player, Profession.COOK, config.perkCookSaturation());
    }

    /** Шанс камнереза на лишний блок при плавке и распиле, %. */
    public int masonChance(UUID player) {
        return perk(player, Profession.MASON, config.perkMasonBlock());
    }

    private int perk(UUID player, Profession profession, boolean enabled) {
        if (!config.enabled() || !enabled || player == null) {
            return 0;
        }
        if (!isActive(player, profession)) {
            return 0;
        }
        // 1.4.282: прибавки всех ремёсел работают одновременно — выбор отменён.
        return ProfessionMath.perkValue(profession, Math.min(level(player, profession),powerCap(player)), powerCap(player));
    }

    /** Включена ли прибавка профессии в конфиге (для подсказок в меню). */
    public boolean perkEnabled(Profession profession) {
        return config.perkEnabled(profession);
    }

    /* -------------------------------- служебное -------------------------------- */

    /** @deprecated выбор профессии отменён; всегда отказ. */
    @Deprecated
    public ProfessionResult setByAdmin(UUID player, String professionId, long now) {
        return ProfessionResult.fail("choose-removed");
    }

    /** Админская выдача опыта: суточный потолок не действует, потолок файла — да. */
    public ProfessionResult grantByAdmin(UUID player, String professionId, int amount) {
        Profession target = Profession.parse(professionId);
        if (target == null) {
            return ProfessionResult.fail("profession-unknown",
                    "profession", professionId == null ? "?" : professionId);
        }
        if (amount <= 0) {
            return ProfessionResult.fail("admin-xp-amount",
                    "max", String.valueOf(ProfessionStore.MAX_XP));
        }
        int before = store.xp(player, target);
        int after = store.addXp(player, target, amount);
        return ProfessionResult.ok("admin-xp-done", "profession", target.id(),
                "amount", String.valueOf(Math.max(0, after - before)),
                "level", String.valueOf(ProfessionMath.levelFor(after, maxLevel(player))));
    }

    /** Полный сброс игрока. */
    public ProfessionResult resetByAdmin(UUID player) {
        if (!store.reset(player)) {
            return ProfessionResult.fail("admin-reset-none");
        }
        return ProfessionResult.ok("admin-reset");
    }
}
