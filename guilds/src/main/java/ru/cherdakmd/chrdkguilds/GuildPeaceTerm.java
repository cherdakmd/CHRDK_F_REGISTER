package ru.cherdakmd.chrdkguilds;

/** Долговечные сутки предоплаты и начало дополнительного суточного срока. */
record GuildPeaceTerm(long paidUntil, long graceSince, long activatesAt) {
    static final long DAY = 86_400_000L;
    GuildPeaceTerm {
        if (paidUntil <= 0 || graceSince < 0 || activatesAt < 0 || activatesAt >= paidUntil
                || paidUntil > Long.MAX_VALUE - DAY || graceSince > Long.MAX_VALUE - DAY) throw new IllegalArgumentException("peace term");
    }
    long deadline() { return Math.addExact(graceSince == 0 ? paidUntil : graceSince, DAY); }
    boolean protectedAt(long now) { return now >= activatesAt && now < deadline(); }
    GuildPeaceTerm paid(int days, long now) {
        long end = Math.addExact(paidUntil, Math.multiplyExact(days, DAY));
        return new GuildPeaceTerm(end, end > now ? 0 : graceSince, activatesAt);
    }
    GuildPeaceTerm unpaid(long now) { return graceSince == 0 ? new GuildPeaceTerm(paidUntil, now, activatesAt) : this; }
}
