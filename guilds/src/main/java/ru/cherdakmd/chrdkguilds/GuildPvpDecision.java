package ru.cherdakmd.chrdkguilds;

/** Чистая таблица PvP: отсутствие клана не освобождает нападающего от ответственности. */
enum GuildPvpDecision {
    ALLOW, BLOCK, BLOCK_AND_FINE;
    static GuildPvpDecision decide(boolean samePlayer,boolean attackerPeaceful,boolean peacefulLaunch,boolean victimProtected,boolean deliberate) {
        if(samePlayer)return ALLOW;
        if(attackerPeaceful||peacefulLaunch)return BLOCK;
        if(victimProtected)return deliberate?BLOCK_AND_FINE:BLOCK;
        return ALLOW;
    }
}
