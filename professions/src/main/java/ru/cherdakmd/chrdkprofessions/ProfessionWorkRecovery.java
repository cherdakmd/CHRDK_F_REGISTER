package ru.cherdakmd.chrdkprofessions;

/** Чистое решение WAL. Завершённый commit не даёт права заново заменить рюкзак. */
final class ProfessionWorkRecovery {
    enum Decision { FINISH_COMMITTED, APPLY_BEFORE, SAVE_AFTER, HOLD }
    static Decision decide(boolean committed,boolean matchesBefore,boolean matchesAfter){
        if(committed)return Decision.FINISH_COMMITTED;
        if(matchesAfter)return Decision.SAVE_AFTER;
        return matchesBefore?Decision.APPLY_BEFORE:Decision.HOLD;
    }
    private ProfessionWorkRecovery(){}
}
