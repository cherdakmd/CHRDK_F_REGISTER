package ru.cherdakmd.chrdkregister.api;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Exact hundredths of a diamond. Legacy APIs continue to use whole diamonds. */
public final class DiamondMoney {
    public static final long SCALE = 100;
    public static long whole(long minor) { return Math.floorDiv(minor, SCALE); }
    public static int fraction(long minor) { return (int) Math.floorMod(minor, SCALE); }
    public static long ceilWhole(long minor) {
        if (minor < 0) throw new IllegalArgumentException("negative money");
        return Math.addExact(whole(minor), fraction(minor) == 0 ? 0 : 1);
    }
    public static long minor(double whole) {
        if (!Double.isFinite(whole) || whole < 0) throw new IllegalArgumentException("money");
        return BigDecimal.valueOf(whole).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
    public static String format(long minor) {
        if (minor < 0) return "—";
        long whole = whole(minor); int fraction = fraction(minor);
        return fraction == 0 ? Long.toString(whole) : String.format(java.util.Locale.ROOT, "%d,%02d", whole, fraction);
    }
    private DiamondMoney() { }
}
