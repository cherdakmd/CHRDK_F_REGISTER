package ru.cherdakmd.chrdkclasses;

import java.util.Set;
import java.util.UUID;

/** Снимок доказуемой основы: правила, прочитанные источники и старый выбор. */
record LegacyClassBasis(UUID playerId, String classId, Set<String> talents, long changedAt, long respecAt,
                         LegacyClassRules rules, int skillTotal, int rank, int achievements,
                         long capturedAt) {
    LegacyClassBasis {
        if (playerId == null || PlayerClass.parse(classId) == null || !PlayerClass.parse(classId).id().equals(classId)
                || changedAt < 0 || respecAt < 0 || rules == null || skillTotal < 0
                || rank < 0 || rank > 5 || achievements < 0 || capturedAt <= 0
                || (!rules.ranksEnabled() && rank != 0)
                || (!rules.needsAchievements() && achievements != 0)) {
            throw new IllegalArgumentException("Некорректный исходный снимок класса");
        }
        talents = Set.copyOf(talents);
        LegacyClassTalents.spent(classId, talents);
    }

    boolean sameState(LegacyClassBasis other) {
        return other != null && playerId.equals(other.playerId) && classId.equals(other.classId)
                && talents.equals(other.talents) && changedAt == other.changedAt && respecAt == other.respecAt
                && rules.equals(other.rules) && skillTotal == other.skillTotal
                && rank == other.rank && achievements == other.achievements;
    }

    int level() { return rules.level(skillTotal, rank); }
    int levelPoints() { return rules.levelPoints(skillTotal, rank); }
    int achievementPoints() { return rules.achievementPoints(achievements); }
    int earnedPoints() { return levelPoints() + achievementPoints(); }
    int spentPoints() { return LegacyClassTalents.spent(classId, talents); }
    int freePoints() { return Math.max(0, earnedPoints() - spentPoints()); }
    int deficit() { return Math.max(0, spentPoints() - earnedPoints()); }
    int nextLevelIn() {
        return level() == 34 ? 0 : level() * rules.levelStep(rank) - skillTotal;
    }
}
