package ru.cherdakmd.chrdkclasses;

import java.util.*;

/** Единственный валидатор изучения, экипировки, сброса и сохранённых сборок. */
final class DevelopmentService {
    private final TalentService parent;
    private final ClassStore store;
    private final LegacyClassSnapshotService legacy;
    record Quote(UUID owner, ClassBuild target, long buildRevision, long progressRevision,
                 long expiresAt, long cooldownSeconds) { }
    DevelopmentService(TalentService parent, LegacyClassSnapshotService legacy) {
        this.parent = parent; this.store = parent.store(); this.legacy = legacy;
    }
    boolean prepare(UUID id, long now) {
        if (store.development(id) == null) {
            if (!store.hasClass(id)) store.startDevelopment(id);
            else {
                var read = legacy.read(id, now);
                if (!read.ready()) return false;
                store.migrateDevelopment(id, read.basis());
            }
        }
        store.initializeBuild(id);
        return true;
    }
    ClassAbility equipped(UUID id, int slot) {
        ClassBuild build = store.build(id);
        return build == null ? null : ClassAbility.byKey(build.abilities().get(slot == 2 ? 1 : 0));
    }
    List<ClassResult> problems(UUID id, ClassBuild build) {
        List<ClassResult> problems = new ArrayList<>();
        ClassDevelopment progress = store.development(id);
        if (!parent.config().enabled()) problems.add(ClassResult.fail("disabled"));
        if (progress == null || store.build(id) == null) {
            problems.add(ClassResult.fail("development-pending")); return problems;
        }
        if (!build.classId().equals(store.classOf(id))) problems.add(ClassResult.fail("development-foreign"));
        if (build.spent() > progress.earnedPoints()) problems.add(ClassResult.fail("talent-points",
                "cost", String.valueOf(build.spent()), "points", String.valueOf(progress.earnedPoints())));
        Set<String> groups = new HashSet<>();
        for (var entry : build.ranks().entrySet()) {
            Talent node = ClassTrees.talent(entry.getKey());
            if (node.minLevel() > progress.level()) problems.add(ClassResult.fail("development-level", "level", String.valueOf(node.minLevel())));
            if (node.hasParent() && !build.ranks().containsKey(node.requires())) problems.add(ClassResult.fail("talent-locked", "talent", node.requires()));
            if (!node.exclusiveGroup().isEmpty() && !groups.add(node.exclusiveGroup())) problems.add(ClassResult.fail("development-exclusive"));
            if (node.needsSkill()) {
                ClassDataRead skill = parent.skillSnapshot(id, node.skill());
                ClassDataRead rank = parent.rankSnapshot(id);
                if (!skill.usable() || !rank.usable()) problems.add(ClassResult.fail("passport-skill-unavailable"));
                else {
                    int required = RankPerks.of(rank.value()).skillRequirement(node.skillLevel());
                    if (skill.value() < required) problems.add(ClassResult.fail("talent-skill", "skill", node.skill(),
                            "level", String.valueOf(required), "have", String.valueOf(skill.value())));
                }
            }
        }
        for (String key : build.abilities()) if (ClassAbility.byKey(key).requiredLevel() > progress.level())
            problems.add(ClassResult.fail("development-level", "level", String.valueOf(ClassAbility.byKey(key).requiredLevel())));
        return problems.stream().distinct().toList();
    }
    List<ClassResult> learnProblems(UUID id, String key) {
        Talent node = ClassTrees.talent(key);
        if (node == null) return List.of(ClassResult.fail("talent-unknown", "talent", key == null ? "?" : key));
        ClassBuild current = store.build(id);
        if (current == null) return List.of(ClassResult.fail("class-required"));
        if (!node.classId().equals(current.classId())) return List.of(ClassResult.fail("development-foreign"));
        int rank = current.ranks().getOrDefault(key, 0);
        if (rank == node.maxRank()) return List.of(ClassResult.fail("talent-known"));
        return problems(id, current.rank(key, rank+1));
    }
    ClassResult learn(UUID id, String key, long now) {
        var errors = learnProblems(id, key);
        if (!errors.isEmpty()) return errors.getFirst();
        ClassBuild current = store.build(id);
        store.installBuild(id, current.rank(key, current.ranks().getOrDefault(key,0)+1), now, false);
        return ClassResult.ok("talent-learned", "talent", key, "points", String.valueOf(parent.availablePoints(id)));
    }
    long cooldown(UUID id) { return parent.perks(id).respecCooldownSeconds(parent.config().respecCooldownSeconds()); }
    long cooldownLeft(UUID id, long now) {
        ClassDevelopment progress = store.development(id);
        if (progress != null && progress.firstBuildAvailable()) return 0;
        return (ClassAbility.remaining(store.respecAt(id), now, cooldown(id)*1000L) + 999L) / 1000L;
    }
    Quote quote(UUID id, ClassBuild target, long now) {
        var errors = problems(id,target);
        if (!errors.isEmpty()) throw new IllegalArgumentException(errors.getFirst().key());
        return new Quote(id,target,store.buildRevision(id),store.development(id).revision(),
                Math.addExact(now,30_000), cooldown(id));
    }
    ClassResult apply(UUID id, Quote quote, long now) {
        if (quote == null || !id.equals(quote.owner()) || now >= quote.expiresAt() || now < quote.expiresAt()-30_000
                || quote.buildRevision() != store.buildRevision(id) || store.development(id) == null
                || quote.progressRevision() != store.development(id).revision() || quote.cooldownSeconds() != cooldown(id))
            return ClassResult.fail("respec-preview-stale");
        var errors = problems(id,quote.target());
        if (!errors.isEmpty()) return errors.getFirst();
        if (quote.target().equals(store.build(id))) return ClassResult.fail("development-same");
        long left = cooldownLeft(id,now);
        if (left > 0) return ClassResult.fail("respec-cooldown", "time", ClassMath.clock(left));
        store.installBuild(id,quote.target(),now,true);
        return ClassResult.ok("development-applied");
    }
}
