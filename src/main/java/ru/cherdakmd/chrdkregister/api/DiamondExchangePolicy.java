package ru.cherdakmd.chrdkregister.api;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Deterministic, bounded market quotation. One physical diamond is quoted in hundredths. */
public final class DiamondExchangePolicy {
    public static final int BASE_BPS = 10_000;
    public static final int MIN_BPS = 5_000;
    public static final int MAX_BPS = 20_000;
    public static final int MAX_STEP_BPS = 250;

    /** All volumes and balances are exact minor units; targets are configurable by the host. */
    public record Snapshot(long circulation, long minted, long burned, long shopTurnover,
                           long auctionTurnover, long activeAccounts, long onlinePlayers,
                           long targetCirculation, long targetActiveAccounts) {
        public Snapshot {
            circulation = nonNegative(circulation); minted = nonNegative(minted);
            burned = nonNegative(burned); shopTurnover = nonNegative(shopTurnover);
            auctionTurnover = nonNegative(auctionTurnover); activeAccounts = nonNegative(activeAccounts);
            onlinePlayers = nonNegative(onlinePlayers);
            targetCirculation = Math.max(100_000L, targetCirculation);
            targetActiveAccounts = Math.max(1L, targetActiveAccounts);
        }
        private static long nonNegative(long value) { return Math.max(0, value); }
    }

    public record Quote(int rateBps, int demandBps, int inflationBps, int emissionBps,
                        int activityBps, int turnoverBps) {
        public Quote {
            if (rateBps < MIN_BPS || rateBps > MAX_BPS) throw new IllegalArgumentException("rate bounds");
        }
        public long creditMinor(long diamonds) {
            if (diamonds < 0) throw new IllegalArgumentException("negative diamonds");
            return Math.multiplyExact(diamonds, rateBps);
        }
        public long scaleMinor(long baseMinor) {
            if (baseMinor < 0) throw new IllegalArgumentException("negative money");
            return BigDecimal.valueOf(baseMinor).multiply(BigDecimal.valueOf(rateBps))
                    .divide(BigDecimal.valueOf(BASE_BPS), 0, RoundingMode.HALF_UP).longValueExact();
        }
        public String text() { return DiamondMoney.format(rateBps); }
    }

    /** previousRateBps makes each observation period bounded; there is no wall-clock or random input. */
    public static Quote quote(Snapshot s, int previousRateBps) {
        int previous = Math.clamp(previousRateBps, MIN_BPS, MAX_BPS);
        long trades = Math.addExact(s.shopTurnover(), s.auctionTurnover());
        long demandVolume = Math.max(100L, Math.addExact(s.shopTurnover(), s.targetCirculation() / 100));
        // Positive trade pressure increases the value of the physical reserve.
        int demand = boundedRatio(trades, demandVolume, 3_500);
        int inflation = -boundedRatio(Math.max(0, s.circulation() - s.targetCirculation()),
                s.targetCirculation(), 2_000);
        long netEmission = s.minted() - s.burned();
        int emission = -boundedRatio(netEmission, Math.max(100_000L, s.circulation()), 2_000);
        long activePressure = s.activeAccounts() + s.onlinePlayers() - s.targetActiveAccounts();
        int activity = boundedRatio(activePressure, s.targetActiveAccounts(), 1_000);
        int turnover = boundedRatio(trades, Math.max(100_000L, s.circulation()), 1_500);
        long raw = (long) BASE_BPS + demand + inflation + emission + activity + turnover;
        int bounded = (int) Math.clamp(raw, previous - MAX_STEP_BPS, previous + MAX_STEP_BPS);
        return new Quote(Math.clamp(bounded, MIN_BPS, MAX_BPS), demand, inflation, emission, activity, turnover);
    }

    private static int boundedRatio(long numerator, long denominator, int weight) {
        if (numerator == 0 || denominator <= 0) return 0;
        BigDecimal ratio = BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 8, RoundingMode.HALF_UP);
        return ratio.multiply(BigDecimal.valueOf(weight)).setScale(0, RoundingMode.HALF_UP).intValue();
    }
    private DiamondExchangePolicy() { }
}
