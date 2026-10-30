package ru.cherdakmd.chrdkclasses;

import java.util.Set;

/** Неизменяемый отчёт переноса; цена старого выбора не зависит от нового дерева. */
record LegacyClassMigration(String classId, int oldLevel, int earnedPoints,
                            Set<String> talents, long capturedAt, LegacyClassBasis basis) {
    // Максимум старых допустимых настроек: 10 + 33 × 5 + 2.
    static final int MAX_OLD_POINTS = 177;

    LegacyClassMigration(String classId, int oldLevel, int earnedPoints, Set<String> talents, long capturedAt) {
        this(classId, oldLevel, earnedPoints, talents, capturedAt, null);
    }

    static LegacyClassMigration capture(LegacyClassBasis basis) {
        return new LegacyClassMigration(basis.classId(), basis.level(), basis.earnedPoints(),
                basis.talents(), basis.capturedAt(), basis);
    }

    LegacyClassMigration {
        if (PlayerClass.parse(classId) == null || !PlayerClass.parse(classId).id().equals(classId)
                || oldLevel < 1 || oldLevel > 34 || earnedPoints < 0
                || earnedPoints > MAX_OLD_POINTS || capturedAt <= 0) {
            throw new IllegalArgumentException("Некорректная основа переноса класса");
        }
        talents = Set.copyOf(talents);
        LegacyClassTalents.spent(classId, talents);
        if (basis != null && (!classId.equals(basis.classId()) || oldLevel != basis.level()
                || earnedPoints != basis.earnedPoints() || !talents.equals(basis.talents())
                || capturedAt != basis.capturedAt())) {
            throw new IllegalArgumentException("Отчёт не соответствует сохранённой основе");
        }
    }

    int spentPoints() {
        return LegacyClassTalents.spent(classId, talents);
    }

    int freePoints() {
        return Math.max(0, earnedPoints - spentPoints());
    }

    /** Старое легальное вложение не отнимается из-за истёкшего к моменту переноса ранга. */
    int preservedPoints() {
        return Math.max(earnedPoints, spentPoints());
    }
}
