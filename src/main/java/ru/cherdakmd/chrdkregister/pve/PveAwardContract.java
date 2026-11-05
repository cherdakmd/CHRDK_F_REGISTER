package ru.cherdakmd.chrdkregister.pve;

import java.util.Collection;
import java.util.UUID;

/**
 * Единый контракт подтверждённого PvE-события и общего бюджета наград.
 *
 * <p>Живёт в ядре, но <b>не</b> в публичном {@code api}: это служебный протокол
 * между модулями CHRDK, поэтому {@code ChrdkRegisterApi.API_VERSION} не
 * повышается и новый модуль/JAR не появляется. Все PvE-модули уже зависят от
 * {@code project(':')} и видят эти классы.</p>
 *
 * <p>Контракт не хранит состояние и не знает Bukkit: он проверяет снимок
 * признаков и квитанции, которые каждый модуль хранит у себя. Это позволяет
 * тестировать его обычными JVM-тестами и не создавать пятый листенер на удар.</p>
 */
public final class PveAwardContract {

    /** Минимальный вклад: 20 % здоровья моба, но не более 20 единиц. */
    public static final double CONTRIBUTION_FRACTION = 0.20;
    public static final double CONTRIBUTION_FLAT_CAP = 20.0;
    /** Общий дневной бюджет начислений на игрока по всем получателям. */
    public static final int DAILY_AWARD_CAP = 2_000;

    private PveAwardContract() {
    }

    public static double requiredContribution(double maxHealth) {
        return Math.min(CONTRIBUTION_FLAT_CAP, Math.max(0.0, maxHealth) * CONTRIBUTION_FRACTION);
    }

    /** Все проверки происхождения и вклада в одном месте, в фиксированном порядке. */
    public static PveVerdict verify(PveDeathEvidence evidence) {
        if (evidence == null) {
            return PveVerdict.no(PveVerdict.Reason.PLUGIN_NOT_READY);
        }
        if (!evidence.authenticated()) {
            return PveVerdict.no(PveVerdict.Reason.NOT_AUTHENTICATED);
        }
        if (!evidence.survivalOrAdventure()) {
            return PveVerdict.no(PveVerdict.Reason.WRONG_GAME_MODE);
        }
        if (evidence.temporaryRaidMob()) {
            return PveVerdict.no(PveVerdict.Reason.TEMPORARY_RAID_MOB);
        }
        if (!evidence.naturalProvenance()) {
            return PveVerdict.no(PveVerdict.Reason.NOT_NATURAL);
        }
        if (evidence.spawnerOrSummoned()) {
            return PveVerdict.no(PveVerdict.Reason.SPAWNER_OR_SUMMONED);
        }
        if (evidence.pvpRecent()) {
            return PveVerdict.no(PveVerdict.Reason.PVP_RECENT);
        }
        if (!evidence.pluginReady()) {
            return PveVerdict.no(PveVerdict.Reason.PLUGIN_NOT_READY);
        }
        double required = requiredContribution(evidence.maxHealth());
        if (evidence.contribution() < required) {
            return PveVerdict.insufficient(required);
        }
        return PveVerdict.yes();
    }

    public static PveReceipt receipt(UUID deathId, PveRecipient recipient, String dayKey) {
        return new PveReceipt(deathId, recipient, dayKey, 1);
    }

    /**
     * Можно ли начислить ещё одну награду: квитанция уникальна и общий дневной
     * бюджет не исчерпан.
     */
    public static boolean canAward(Collection<PveReceipt> today, PveReceipt candidate, int dailyCap) {
        if (candidate == null || today == null || dailyCap <= 0) {
            return false;
        }
        int spent = 0;
        for (PveReceipt existing : today) {
            if (candidate.key().equals(existing.key())) {
                return false;
            }
            spent += Math.max(0, existing.units());
        }
        return spent + candidate.units() <= dailyCap;
    }

    /** Сколько пыли ещё можно зачислить сегодня игроку. */
    public static long remainingDailyPowder(long dailyCap, long creditedToday) {
        if (dailyCap <= 0) {
            return 0L;
        }
        return Math.max(0L, dailyCap - Math.max(0L, creditedToday));
    }
}
