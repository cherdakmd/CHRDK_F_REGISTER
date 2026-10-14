package ru.cherdakmd.chrdkauction;

/** Never guess ownership when neither (or both) of the durable snapshots match. */
enum RecoveryDecision {
    ROLLBACK, COMPLETE, CONFLICT;
    static RecoveryDecision of(boolean before, boolean after) {
        if (before == after) return CONFLICT;
        return after ? COMPLETE : ROLLBACK;
    }
}
