package ru.cherdakmd.chrdkhunt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Локальная группа при spawn — не клан и не долговечная группа, никаких долговечных списков игроков. */
final class SpawnGroup {
    record Settings(boolean enabled, double radius, int limit) {
        static Settings bounded(boolean enabled, double radius, int limit) {
            return new Settings(enabled, Double.isFinite(radius) ? Math.clamp(radius, 4, 32) : 24,
                    Math.clamp(limit, 2, 8));
        }
        boolean inRange(double squared) {
            return enabled && Double.isFinite(squared) && squared >= 0 && squared <= radius * radius;
        }
    }
    private record Member(UUID id, double squared, int skills) { }
    private static final Comparator<Member> ORDER = Comparator.comparingDouble(Member::squared).thenComparing(Member::id);
    private final Settings settings;
    private final List<Member> members = new ArrayList<>();
    SpawnGroup(Settings settings) { this.settings = settings; }
    void offer(UUID id, double squared, int skills) {
        if (id == null || !settings.inRange(squared) || members.stream().anyMatch(m -> m.id.equals(id))) return;
        members.add(new Member(id, squared, Math.clamp(skills, 10, 1000)));
        members.sort(ORDER);
        if (members.size() > settings.limit) members.removeLast();
    }
    int effectiveSkills(int nearestSkills) {
        if (!settings.enabled || members.size() < 2) return nearestSkills;
        return members.stream().mapToInt(Member::skills).sum() / members.size();
    }
    int size() { return members.size(); }
}
