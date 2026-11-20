package ru.cherdakmd.chrdkskills;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/** Read-only ray candidate, not a grant of permission and not connected to abilities. */
final class SupportAim {
    private static final double MAX_DISTANCE = 8.0;
    record Candidate(Player player, double hitDistanceSquared) { }

    /** Must run on the server thread. Treat all invalid results or failures as no target. */
    static Candidate find(Player caster) {
        if (caster == null) return null;
        try {
            if (!caster.isOnline() || caster.isDead()) return null;
            Location eye = caster.getEyeLocation();
            World world = caster.getWorld();
            if (eye == null || world == null || eye.getWorld() != world) return null;
            Vector start = eye.toVector(), direction = eye.getDirection();
            if (!finite(start) || !finite(direction) || direction.lengthSquared() < 1e-12) return null;
            RayTraceResult hit = world.rayTrace(eye, direction, MAX_DISTANCE, FluidCollisionMode.NEVER,
                    false, 0.0, entity -> entity != caster);
            if (hit == null || hit.getHitBlock() != null || !(hit.getHitEntity() instanceof Player target)
                    || target == caster || !target.isOnline() || target.isDead()
                    || target.getWorld() != world || !caster.canSee(target) || !caster.hasLineOfSight(target)) return null;
            Vector position = hit.getHitPosition();
            if (!finite(position)) return null;
            double distanceSquared = position.distanceSquared(start);
            if (!Double.isFinite(distanceSquared) || distanceSquared > SupportTargetRules.MAX_DISTANCE_SQUARED) return null;
            return new Candidate(target, distanceSquared);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static boolean finite(Vector vector) {
        return vector != null && Double.isFinite(vector.getX()) && Double.isFinite(vector.getY()) && Double.isFinite(vector.getZ());
    }

    private SupportAim() { }
}
