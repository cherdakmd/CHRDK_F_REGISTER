package ru.cherdakmd.chrdkenchants;

/** A cancelled event must be checked separately; zero, NaN and infinity are not evidence of damage. */
final class DamageEvidence {
    private DamageEvidence() { }
    static boolean positive(double damage) { return Double.isFinite(damage) && damage > 0.0; }
}
