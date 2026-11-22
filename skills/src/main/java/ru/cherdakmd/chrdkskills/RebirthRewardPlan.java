package ru.cherdakmd.chrdkskills;

/** Замораживает денежную часть наград до сброса прогресса. */
final class RebirthRewardPlan {
    private RebirthRewardPlan() { }

    static long amount(int baseConfigured, int milestoneConfigured, boolean milestone, long freshUniqueIds) {
        if (freshUniqueIds < 0) throw new IllegalArgumentException("negative unique count");
        int base = RebirthMath.rebirthDiamonds(baseConfigured);
        int slots = milestone ? 2 : 1;
        long fallbackSlots = Math.max(0L, slots - Math.min(freshUniqueIds, slots));
        return base + (milestone ? RebirthMath.milestoneDiamonds(milestoneConfigured) : 0L)
                + fallbackSlots * 2L * base;
    }
}
