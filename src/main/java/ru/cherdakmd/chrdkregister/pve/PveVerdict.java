package ru.cherdakmd.chrdkregister.pve;

/**
 * Решение общего валидатора PvE-события.
 *
 * @param allowed  можно ли начислять награды
 * @param reason   точная причина (в том числе при отказе)
 * @param required требуемый вклад при отказе по недостатку вклада, иначе 0
 */
public record PveVerdict(boolean allowed, Reason reason, double required) {

    public enum Reason {
        OK,
        NOT_AUTHENTICATED,
        WRONG_GAME_MODE,
        TEMPORARY_RAID_MOB,
        NOT_NATURAL,
        SPAWNER_OR_SUMMONED,
        PVP_RECENT,
        PLUGIN_NOT_READY,
        DUPLICATE_DEATH,
        INSUFFICIENT_CONTRIBUTION
    }

    public static PveVerdict yes() {
        return new PveVerdict(true, Reason.OK, 0.0);
    }

    public static PveVerdict no(Reason reason) {
        return new PveVerdict(false, reason, 0.0);
    }

    public static PveVerdict insufficient(double required) {
        return new PveVerdict(false, Reason.INSUFFICIENT_CONTRIBUTION, required);
    }
}
