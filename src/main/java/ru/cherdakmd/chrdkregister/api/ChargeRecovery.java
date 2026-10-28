package ru.cherdakmd.chrdkregister.api;

/** Чистая матрица: платёж подтверждается банком, предметы и источник — каждый своим снимком. */
public final class ChargeRecovery {
    public enum Decision { APPLY_AFTER, COMPLETE, ROLLBACK, WAIT, CONFLICT }
    public static Decision decide(BankCharge.Status status, long cash, boolean stateBefore, boolean stateAfter,
                                  boolean inventoryBefore, boolean inventoryAfter) {
        if (status == null || status == BankCharge.Status.UNAVAILABLE) return Decision.WAIT;
        if (status == BankCharge.Status.CONFLICT || stateBefore == stateAfter) return Decision.CONFLICT;
        if (status == BankCharge.Status.MISSING)
            return stateBefore && !stateAfter && (cash == 0 || inventoryBefore && !inventoryAfter) ? Decision.ROLLBACK : Decision.CONFLICT;
        if (cash == 0) return Decision.COMPLETE;
        // Источник публикуется после saveData: published + before не исправляем догадкой об откате playerdata.
        if (stateAfter && !inventoryAfter) return Decision.CONFLICT;
        if (inventoryAfter && !inventoryBefore) return Decision.COMPLETE;
        return inventoryBefore && !inventoryAfter ? Decision.APPLY_AFTER : Decision.CONFLICT;
    }
    private ChargeRecovery() { }
}
