package ru.cherdakmd.diamondshop;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Целые партии/алмазы. Double используется только старым движком котировок, не балансом. */
final class WholeTrades {
    static final long MAX = 1_000_000_000L;
    static int batch(double minimumBid) {
        if (!Double.isFinite(minimumBid) || minimumBid <= 0) return 0;
        double units = Math.ceil(1 / minimumBid);
        if (units > 2304) return 0;
        for (int size : new int[]{1,2,4,8,16,32,64,128,256,512,1024,2304}) if (size >= units) return size;
        return 0;
    }
    static long price(double unitPrice, int batch, boolean buy) {
        if (!Double.isFinite(unitPrice) || unitPrice <= 0 || batch <= 0) return -1;
        var value = BigDecimal.valueOf(unitPrice).multiply(BigDecimal.valueOf(batch))
                .setScale(0, buy ? RoundingMode.CEILING : RoundingMode.FLOOR);
        return value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(MAX)) > 0 ? -1 : value.longValueExact();
    }
    static long legacy(double amount) {
        if (!Double.isFinite(amount) || amount < 0 || amount > MAX) return -1;
        return BigDecimal.valueOf(amount).setScale(0, RoundingMode.CEILING).longValueExact();
    }
    record Payment(long account, long physical, long change) { }
    static Payment payment(long cost, long balance, long physical) {
        if (cost <= 0 || cost > MAX || balance < 0 || balance > MAX || physical < 0) return null;
        long account = Math.min(cost, balance), needed = cost - account;
        return physical < needed || balance - account > MAX - (physical - needed)
                ? null : new Payment(account, physical, physical - needed);
    }
    private WholeTrades() { }
}
