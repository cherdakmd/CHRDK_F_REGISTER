package ru.cherdakmd.chrdkclasses;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Неизменяемый согласованный снимок прогресса. Сам по себе не подтверждает
 * происхождение игрового действия: это обязанность будущего PvE-обработчика.
 * Никакие методы здесь не читают платный ранг и не пересчитывают прошлый XP.
 */
record ClassDevelopment(int version, long xp, Map<Source, Long> xpSources,
                        Set<String> trialAwards, LegacyClassMigration migration,
                        boolean firstBuildAvailable, long revision) {
    static final int VERSION = 1;
    private static final Pattern TRIAL_ID = Pattern.compile("[a-z][a-z0-9_-]{0,63}");

    enum Source {
        COMBAT, SUPPORT, EXPLORATION, TRIAL, MIGRATION
    }

    ClassDevelopment {
        if (version != VERSION || xp < 0 || xp > ClassDevelopmentRules.MAX_XP || revision < 0) {
            throw new IllegalArgumentException("Некорректная версия, XP или ревизия прогресса");
        }
        xpSources = Map.copyOf(xpSources);
        long total = 0;
        for (long value : xpSources.values()) {
            if (value <= 0 || value > ClassDevelopmentRules.MAX_XP) {
                throw new IllegalArgumentException("Некорректный источник XP");
            }
            total = Math.addExact(total, value);
        }
        if (total != xp) {
            throw new IllegalArgumentException("Сумма источников XP не совпадает с общим XP");
        }
        trialAwards = Set.copyOf(trialAwards);
        if (trialAwards.size() > ClassDevelopmentRules.MAX_TRIAL_POINTS
                || trialAwards.stream().anyMatch(id -> !validTrial(id))) {
            throw new IllegalArgumentException("Некорректный реестр наград испытаний");
        }
        long expectedMigrationXp = migration == null ? 0
                : ClassDevelopmentRules.xpForLevel(migration.oldLevel());
        if (xpSources.getOrDefault(Source.MIGRATION, 0L) != expectedMigrationXp
                || (migration == null && firstBuildAvailable)) {
            throw new IllegalArgumentException("Несогласованный отчёт переноса");
        }
    }

    static ClassDevelopment fresh() {
        return new ClassDevelopment(VERSION, 0, Map.of(), Set.of(), null, false, 0);
    }

    static ClassDevelopment migrate(LegacyClassMigration report) {
        if (report == null) {
            throw new IllegalArgumentException("Для переноса нужен отчёт");
        }
        long xp = ClassDevelopmentRules.xpForLevel(report.oldLevel());
        return new ClassDevelopment(VERSION, xp,
                xp == 0 ? Map.of() : Map.of(Source.MIGRATION, xp), Set.of(), report, true, 1);
    }

    int level() {
        return ClassDevelopmentRules.level(xp);
    }

    int levelPoints() {
        return level();
    }

    int trialPoints() {
        return trialAwards.size();
    }

    /** Это остаток сохранённого права, а не повторная выдача потраченных очков. */
    int legacyCredit() {
        return migration == null ? 0
                : Math.max(0, migration.preservedPoints() - levelPoints() - trialPoints());
    }

    int earnedPoints() {
        return levelPoints() + trialPoints() + legacyCredit();
    }

    ClassDevelopment awardXp(Source source, long amount) {
        if (source == null || source == Source.MIGRATION || amount < 0) {
            throw new IllegalArgumentException("Некорректное начисление XP класса");
        }
        long accepted = Math.min(amount, ClassDevelopmentRules.MAX_XP - xp);
        if (accepted == 0) {
            return this;
        }
        Map<Source, Long> sources = new EnumMap<>(Source.class);
        sources.putAll(xpSources);
        sources.merge(source, accepted, Math::addExact);
        return new ClassDevelopment(version, xp + accepted, sources, trialAwards, migration,
                firstBuildAvailable, Math.incrementExact(revision));
    }

    /** Идентификатор и право на испытание должен предварительно проверить сервер. */
    ClassDevelopment completeTrial(String id, long xpReward) {
        if (!validTrial(id) || xpReward < 0) {
            throw new IllegalArgumentException("Некорректная награда испытания");
        }
        if (trialAwards.contains(id) || trialAwards.size() >= ClassDevelopmentRules.MAX_TRIAL_POINTS) {
            return this;
        }
        ClassDevelopment withXp = awardXp(Source.TRIAL, xpReward);
        Set<String> awards = new LinkedHashSet<>(trialAwards);
        awards.add(id);
        return new ClassDevelopment(version, withXp.xp, withXp.xpSources, awards, migration,
                firstBuildAvailable, Math.incrementExact(revision));
    }

    ClassDevelopment consumeFirstBuild() {
        return firstBuildAvailable
                ? new ClassDevelopment(version, xp, xpSources, trialAwards, migration, false,
                        Math.incrementExact(revision)) : this;
    }

    private static boolean validTrial(String id) {
        return id != null && TRIAL_ID.matcher(id).matches();
    }
}
