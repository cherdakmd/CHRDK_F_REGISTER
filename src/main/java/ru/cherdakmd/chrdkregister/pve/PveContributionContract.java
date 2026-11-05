package ru.cherdakmd.chrdkregister.pve;

/**
 * Профиль получателя {@link PveRecipient#SKILL_XP} в едином контракте
 * подтверждённого вклада.
 *
 * <p><b>Это не седьмой валидатор.</b> Проверка происхождения, режима, PvP и
 * порога вклада делегируется единому {@link PveAwardContract#verify}; квитанция
 * формируется {@link PveAwardContract#receipt}, а уникальность и общий дневной
 * бюджет ведёт {@link PveAwardLedger}. Класс лишь фиксирует, что опыт навыков
 * получает подтверждение ровно по тем же правилам, что и остальные пять
 * получателей, и не вводит второй death-листенер.</p>
 *
 * <p>Начисление численного XP остаётся в {@code skills} на событиях урона и
 * здесь <b>не вызывается и не меняется</b>: контракт регистрирует только
 * квитанцию подтверждённой смерти моба. Так один удар не превращается в
 * фиктивную death-квитанцию, а XP не дублируется.</p>
 */
public final class PveContributionContract {

    public static final PveRecipient RECIPIENT = PveRecipient.SKILL_XP;

    private PveContributionContract() {
    }

    /** Единая проверка; отдельного набора флагов и порогов здесь нет. */
    public static PveVerdict verify(PveDeathEvidence evidence) {
        return PveAwardContract.verify(evidence);
    }

    /** Квитанция в формате общего реестра: {@code deathId|SKILL_XP|день}. */
    public static PveReceipt receipt(PveDeathEvidence evidence) {
        if (evidence == null) {
            return null;
        }
        return PveAwardContract.receipt(evidence.deathId(), RECIPIENT, evidence.dayKey());
    }

    /**
     * Подтверждённый вклад → награда/квитанция. Возвращает {@code true}, только
     * если вердикт положительный и общий реестр принял уникальную квитанцию.
     * Повторная смерть того же моба и отказ валидатора ничего не регистрируют.
     */
    public static boolean register(PveDeathEvidence evidence, PveVerdict verdict) {
        if (evidence == null || verdict == null || !verdict.allowed()) {
            return false;
        }
        return PveAwardLedger.shared().register(evidence.playerId(), receipt(evidence));
    }
}
