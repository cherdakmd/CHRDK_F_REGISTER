package ru.cherdakmd.chrdktierforge;

import java.util.List;
import java.util.Random;

/** Взвешенный бросок тира по снимкам лестницы (нулевые веса не выпадают). */
public final class TierRoller {

    private TierRoller() {
    }

    public static TierDef.Snapshot roll(List<TierDef.Snapshot> tiers, Random random) {
        int total = tiers.stream().mapToInt(TierDef.Snapshot::weight).sum();
        if (total <= 0) {
            return null;
        }
        int ticket = random.nextInt(total);
        for (TierDef.Snapshot tier : tiers) {
            ticket -= tier.weight();
            if (ticket < 0) {
                return tier;
            }
        }
        return tiers.get(tiers.size() - 1);
    }
}
