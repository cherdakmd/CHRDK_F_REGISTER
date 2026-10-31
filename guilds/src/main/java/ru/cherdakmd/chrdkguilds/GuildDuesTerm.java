package ru.cherdakmd.chrdkguilds;

/** Следующий объявленный взнос и неизменяемый срок устранения неоплаты. */
record GuildDuesTerm(long dueAt, long graceSince, int amount) {
    GuildDuesTerm {
        if(dueAt<=0 || dueAt>Long.MAX_VALUE-GuildPeaceTerm.DAY || graceSince<0
                || graceSince>Long.MAX_VALUE-GuildPeaceTerm.DAY || amount<10 || amount>1_000_000)
            throw new IllegalArgumentException("dues term");
    }
    static GuildDuesTerm start(long now,int rate) { return new GuildDuesTerm(Math.addExact(now,GuildPeaceTerm.DAY),0,rate); }
    long deadline() { return Math.addExact(graceSince==0?dueAt:graceSince,GuildPeaceTerm.DAY); }
    GuildDuesTerm unpaid(long now) { return graceSince==0?new GuildDuesTerm(dueAt,now,amount):this; }
}
