package ru.cherdakmd.chrdkclasses;

/** Замороженная арифметика 1.4.255; не зависит от будущего каталога/RankPerks. */
record LegacyClassRules(int version, int levelsPerLevel, int startingPoints, int pointsPerLevel,
                         int achievementEvery, int achievementMax, boolean ranksEnabled,
                         boolean enabled) {
    static final int VERSION = 1;

    LegacyClassRules {
        if (version != VERSION || levelsPerLevel < 5 || levelsPerLevel > 100
                || startingPoints < 0 || startingPoints > 10 || pointsPerLevel < 0 || pointsPerLevel > 5
                || achievementEvery < 0 || achievementEvery > 100
                || achievementMax < 0 || achievementMax > 2) {
            throw new IllegalArgumentException("Некорректные исходные правила классов");
        }
    }

    static LegacyClassRules capture(ClassConfig config) {
        return new LegacyClassRules(VERSION, config.levelsPerLevel(), config.startingPoints(),
                config.pointsPerLevel(), config.achievementEvery(), config.achievementMaxBonus(),
                config.ranksEnabled(), config.enabled());
    }

    int levelStep(int rank) {
        if (rank < 0 || rank > 5) {
            throw new IllegalArgumentException("Некорректный исходный ранг");
        }
        return Math.max(5, levelsPerLevel - (ranksEnabled ? rank : 0));
    }

    int level(int skills, int rank) {
        if (skills < 0) {
            throw new IllegalArgumentException("Некорректная сумма навыков");
        }
        return Math.min(34, 1 + skills / levelStep(rank));
    }

    int levelPoints(int skills, int rank) {
        return startingPoints + (level(skills, rank) - 1) * pointsPerLevel;
    }

    boolean needsAchievements() {
        return enabled && achievementEvery > 0 && achievementMax > 0;
    }

    int achievementPoints(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Некорректное число достижений");
        }
        return needsAchievements() ? Math.min(achievementMax, count / achievementEvery) : 0;
    }
}
