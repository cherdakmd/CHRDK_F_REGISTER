package ru.cherdakmd.chrdkclasses;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/** Читает каждый нужный источник ровно один раз. Ничего не мигрирует и не выдаёт. */
final class LegacyClassSnapshotService {
    enum Source { SKILLS, RANKS, ACHIEVEMENTS }

    record Result(Map<Source, ClassDataRead> sources, LegacyClassBasis basis) {
        Result {
            sources = Map.copyOf(sources);
            if (sources.size() != Source.values().length
                    || (basis != null && sources.values().stream().anyMatch(read -> !read.usable()))) {
                throw new IllegalArgumentException("Несогласованный снимок исходных данных");
            }
        }
        boolean ready() { return basis != null; }
    }

    private final ClassStore store;
    private final LegacyClassRules rules;
    private final SkillSource skills;
    private final RankSource ranks;
    private final AchievementSource achievements;

    LegacyClassSnapshotService(ClassStore store, LegacyClassRules rules, SkillSource skills,
                               RankSource ranks, AchievementSource achievements) {
        this.store = store;
        this.rules = rules;
        this.skills = skills;
        this.ranks = ranks;
        this.achievements = achievements;
    }

    Result read(UUID id, long now) {
        if (id == null || now <= 0) {
            throw new IllegalArgumentException("Для снимка нужны игрок и время");
        }
        Map<Source, ClassDataRead> reads = new EnumMap<>(Source.class);
        reads.put(Source.SKILLS, skills == null ? unavailable() : safe(() -> skills.snapshotTotal(id)));
        reads.put(Source.RANKS, !rules.ranksEnabled() ? notRequired()
                : ranks == null ? unavailable() : safe(() -> ranks.snapshotRank(id)).atMost(5));
        reads.put(Source.ACHIEVEMENTS, !rules.needsAchievements() ? notRequired()
                : achievements == null ? unavailable() : safe(() -> achievements.snapshotCount(id)));
        String classId = store.classOf(id);
        if (classId.isEmpty() || reads.values().stream().anyMatch(read -> !read.usable())) {
            return new Result(reads, null);
        }
        LegacyClassBasis basis = new LegacyClassBasis(id, classId, store.talents(id), store.changedAt(id),
                store.respecAt(id), rules, reads.get(Source.SKILLS).value(), reads.get(Source.RANKS).value(),
                reads.get(Source.ACHIEVEMENTS).value(), now);
        return new Result(reads, basis);
    }

    private static ClassDataRead safe(java.util.function.Supplier<ClassDataRead> supplier) {
        try {
            ClassDataRead result = supplier.get();
            return result == null ? ClassDataRead.absent(ClassDataRead.Status.ERROR) : result;
        } catch (RuntimeException | LinkageError failure) {
            return ClassDataRead.absent(ClassDataRead.Status.ERROR);
        }
    }

    private static ClassDataRead unavailable() { return ClassDataRead.absent(ClassDataRead.Status.UNAVAILABLE); }
    private static ClassDataRead notRequired() { return ClassDataRead.absent(ClassDataRead.Status.NOT_REQUIRED); }
}
