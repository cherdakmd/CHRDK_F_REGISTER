package ru.cherdakmd.chrdkskills;

/**
 * Pure, fail-closed decision for a single already ray-selected external target.
 * This is not a ray tracer, a protection integration, or permission to apply
 * effects: callers must obtain fresh evidence on the server thread. Not yet
 * connected to live abilities.
 */
final class SupportTargetRules {
    static final double MAX_DISTANCE_SQUARED = 64.0;

    enum GuildRelation { SAME_GUILD, ACTIVE_WAR, OTHER, UNKNOWN }
    enum GroupRelation { SAME_GROUP, DIFFERENT, UNKNOWN }
    enum Protection { ALLOW, DENY, UNKNOWN }
    enum Result { EXTERNAL_TARGET, SELF }

    record Evidence(boolean targetIsFirstHit, boolean sameWorld, boolean targetOnlineAndAlive,
                    boolean visible, boolean clearLineOfSight, double distanceSquared,
                    GuildRelation guild, GroupRelation group, Protection protection) { }

    static Result decide(Evidence e) {
        if (e == null || !e.targetIsFirstHit() || !e.sameWorld() || !e.targetOnlineAndAlive()
                || !e.visible() || !e.clearLineOfSight() || !Double.isFinite(e.distanceSquared())
                || e.distanceSquared() < 0 || e.distanceSquared() > MAX_DISTANCE_SQUARED
                || e.guild() == null || e.guild() == GuildRelation.UNKNOWN
                || e.guild() == GuildRelation.ACTIVE_WAR || e.protection() != Protection.ALLOW) {
            return Result.SELF;
        }
        if (e.guild() == GuildRelation.SAME_GUILD || e.group() == GroupRelation.SAME_GROUP)
            return Result.EXTERNAL_TARGET;
        return Result.SELF;
    }

    private SupportTargetRules() { }
}
