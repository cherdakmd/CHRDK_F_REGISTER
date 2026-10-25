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

    /** Потолок уровня профессии игрока: 25 плюс уровни навыка от ранга. */
    public int maxLevel(UUID player) {
        return ProfessionMath.maxLevel(bonus(player).extraSkill());
    }

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

    /** Выбранная профессия или {@code null}. */
    public Profession active(UUID player) {
        return player == null ? null : store.active(player);
    }

    /** Сколько секунд осталось до смены профессии (0 — можно менять). */
    public long chooseCooldownLeft(UUID player, long now) {
        if (player == null || store.active(player) == null) {
            return 0L;
        }
        long until = store.changedAt(player) + config.chooseCooldownMillis();
        return until <= now ? 0L : (until - now + 999) / 1_000L;
    }

    /** Проверка выбора без записи. */
    public ProfessionResult checkChoose(UUID player, String professionId, long now) {
        if (!config.enabled()) {
            return ProfessionResult.fail("disabled");
        }
        Profession target = Profession.parse(professionId);
        if (target == null) {
            return ProfessionResult.fail("profession-unknown",
                    "profession", professionId == null ? "?" : professionId);
        }
        Profession current = active(player);
        if (current == target) {
            return ProfessionResult.fail("profession-same", "profession", target.id());
        }
        long left = chooseCooldownLeft(player, now);
        if (left > 0) {
            return ProfessionResult.fail("profession-cooldown",
                    "time", ProfessionMath.clock(left));
        }
        return current == null
                ? ProfessionResult.ok("profession-choose-ready", "profession", target.id())
                : ProfessionResult.ok("profession-change-ready", "from", current.id(),
                        "to", target.id());
    }

    /**
     * Выбирает профессию. Опыт прежней сохраняется: возвращение к ремеслу
     * продолжает его, а не начинает заново.
     */
    public ProfessionResult choose(UUID player, String professionId, long now) {
        ProfessionResult check = checkChoose(player, professionId, now);
        if (!check.ok()) {
            return check;
        }
        Profession target = Profession.parse(professionId);
        Profession current = active(player);
        store.setActive(player, target.id(), now);
        return current == null
                ? ProfessionResult.ok("profession-chosen", "profession", target.id())
                : ProfessionResult.ok("profession-changed", "from", current.id(),
                        "to", target.id());
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

    public int level(UUID player) {
        Profession active = active(player);
        return active == null ? 1 : level(player, active);
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
        if (!config.enabled() || player == null || profession == null || rawXp <= 0) {
            return Award.none();
        }
        if (store.active(player) != profession) {
            return Award.none();
        }
        int cap = maxLevel(player);
        int before = store.xp(player, profession);
        int levelBefore = ProfessionMath.levelFor(before, cap);
        // Опыт: множитель сервера × бонус ранга (1.4.167). Оба множителя
        // умножаются до округления, чтобы 10 % ранга не терялись на мелких наградах.
        double factor = config.xpMultiplier() * bonus(player).xpFactor();
        int scaled = Math.max(1, (int) Math.round(rawXp * factor));
        int left = dailyLeft(player, profession, now);
        int granted = left < 0 ? scaled : Math.min(scaled, left);
        boolean capped = left >= 0 && granted < scaled;
        if (granted <= 0) {
            return new Award(0, levelBefore, levelBefore, Math.max(0, left), true);
        }
        int after = store.addXp(player, profession, granted);
        int added = Math.max(0, after - before);
        int levelAfter = ProfessionMath.levelFor(after, cap);
        if (left >= 0 && added > 0) {
            store.setDaily(player, profession, utcDay(now), dailyUsed(player, profession, now) + added);
        }
        return new Award(added, levelBefore, levelAfter, dailyLeft(player, profession, now), capped);
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
    public record Award(int added, int levelBefore, int levelAfter, int dailyLeft, boolean capped) {

        static Award none() {
            return new Award(0, 1, 1, -1, false);
        }

        /** Вырос ли уровень: по этому признаку показывается поздравление. */
        public boolean leveled() {
            return levelAfter > levelBefore;
        }
    }

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

    /** Шанс камнереза на второй блок, %. */
    public int masonChance(UUID player) {
        return perk(player, Profession.MASON, config.perkMasonBlock());
    }

    private int perk(UUID player, Profession profession, boolean enabled) {
        if (!config.enabled() || !enabled || player == null) {
            return 0;
        }
        if (store.active(player) != profession) {
            return 0;
        }
        return ProfessionMath.perkValue(profession, level(player, profession), maxLevel(player));
    }

    /** Включена ли прибавка профессии в конфиге (для подсказок в меню). */
    public boolean perkEnabled(Profession profession) {
        return config.perkEnabled(profession);
    }

    /* -------------------------------- служебное -------------------------------- */

    /** Админская выдача профессии в обход кулдауна. */
    public ProfessionResult setByAdmin(UUID player, String professionId, long now) {
        Profession target = Profession.parse(professionId);
        if (target == null) {
            return ProfessionResult.fail("profession-unknown",
                    "profession", professionId == null ? "?" : professionId);
        }
        store.setActive(player, target.id(), now);
        return ProfessionResult.ok("admin-profession-set", "profession", target.id());
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
