package ru.cherdakmd.chrdkclasses;

/** Версия собственной кривой XP: 50 уровней и до шести уникальных очков испытаний. */
final class ClassDevelopmentRules {
    static final int MAX_LEVEL = 50;
    static final int MAX_TRIAL_POINTS = 6;
    static final int MAX_STANDARD_POINTS = MAX_LEVEL + MAX_TRIAL_POINTS;
    private static final long[] THRESHOLDS = thresholds();
    static final long MAX_XP = THRESHOLDS[MAX_LEVEL];

    private ClassDevelopmentRules() {
    }

    private static long[] thresholds() {
        long[] result = new long[MAX_LEVEL + 1];
        for (int level = 1; level < MAX_LEVEL; level++) {
            long offset = level - 1L;
            result[level + 1] = result[level] + 100L + 40L * offset + 8L * offset * offset;
        }
        return result;
    }

    static long xpForLevel(int level) {
        if (level < 1 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("Уровень вне собственной кривой класса");
        }
        return THRESHOLDS[level];
    }

    static int level(long xp) {
        if (xp < 0 || xp > MAX_XP) {
            throw new IllegalArgumentException("XP вне собственной кривой класса");
        }
        int low = 1;
        int high = MAX_LEVEL;
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (THRESHOLDS[middle] <= xp) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    static int progressSegments(long xp,int width) {
        if(width<1||width>100)throw new IllegalArgumentException("Некорректная ширина шкалы");
        int level=level(xp);
        return level==MAX_LEVEL?width:(int)((xp-THRESHOLDS[level])*width/(THRESHOLDS[level+1]-THRESHOLDS[level]));
    }

    static long nextLevelIn(long xp) {
        int level = level(xp);
        return level == MAX_LEVEL ? 0L : THRESHOLDS[level + 1] - xp;
    }
}
