package ru.cherdakmd.chrdkgoals;

import ru.cherdakmd.chrdkregister.api.ChargeJournal;

/** Доказательство отсутствия любых банковских обязательств общих целей. */
final class GoalRegistration {
    static boolean canRestore(GoalStore store,ChargeJournal journal) {
        return store.healthy()&&journal!=null&&journal.healthy()&&journal.pending()==null
                &&journal.nextSequence()==1&&store.lifetimeEarned()==0&&store.entitlements().isEmpty();
    }
    private GoalRegistration() { }
}
