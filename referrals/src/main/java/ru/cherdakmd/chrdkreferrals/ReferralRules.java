package ru.cherdakmd.chrdkreferrals;

import java.util.Locale;

/**
 * Чистые правила реферальной привязки (без Bukkit — покрыто юнит-тестами).
 */
public final class ReferralRules {

    private ReferralRules() {
    }

    /** Ключ данных по игроку — ник в нижнем регистре. */
    public static String canonical(String playerName) {
        return playerName == null ? "" : playerName.toLowerCase(Locale.ROOT);
    }

    /** Возможные причины отказа в привязке. */
    public enum LinkError {
        OK,
        ALREADY_LINKED,
        SELF,
        TOO_LATE,
        INVITER_UNREGISTERED
    }

    /**
     * Можно ли привязать {@code invitee} к {@code inviter}.
     *
     * @param inviteePassUntil    unix-мс окончания ранга приглашённого (0 — ранга не было никогда)
     * @param alreadyLinked       была ли у приглашённого привязка ранее
     * @param inviterRegistered   прошёл ли пригласивший регистрацию пароля
     * @param requireRegistered   требуемость регистрации пригласившего из конфига
     */
    public static LinkError canLink(String inviter, String invitee,
                                    long inviteePassUntil, boolean alreadyLinked,
                                    boolean inviterRegistered, boolean requireRegistered) {
        if (canonical(inviter).equals(canonical(invitee))) return LinkError.SELF;
        if (alreadyLinked) return LinkError.ALREADY_LINKED;
        if (inviteePassUntil > 0) return LinkError.TOO_LATE; // привязка только до первой подписки
        if (requireRegistered && !inviterRegistered) return LinkError.INVITER_UNREGISTERED;
        return LinkError.OK;
    }

    /** Trial grants must never consume a pending link or mint referral hours. */
    public static boolean triggersBonus(String source) { return !"trial".equals(source); }

    /** Соответствующий ключ сообщения для ошибки. */
    public static String messageKeyOf(LinkError error) {
        return switch (error) {
            case ALREADY_LINKED -> "ref.use-already-linked";
            case SELF -> "ref.use-self";
            case TOO_LATE -> "ref.use-too-late";
            case INVITER_UNREGISTERED -> "ref.use-inviter-unregistered";
            case OK -> "ref.use-ok";
        };
    }
}
