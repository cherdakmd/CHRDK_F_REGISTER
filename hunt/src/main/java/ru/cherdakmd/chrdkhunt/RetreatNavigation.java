package ru.cherdakmd.chrdkhunt;

import com.destroystokyo.paper.entity.Pathfinder;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import org.bukkit.Location;
import org.bukkit.entity.Mob;
import java.util.EnumSet;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Только временный MOVE-goal. Отмена останавливает лишь всё ещё совпадающий путь, не чужой новый. */
final class RetreatNavigation implements Goal<Mob> {
    private final Mob mob;
    private final GoalKey<Mob> key;
    private final Pathfinder.PathResult path;
    private final List<Location> points;
    private final BooleanSupplier valid;
    private final Consumer<Throwable> fault;
    boolean started, finished;
    RetreatNavigation(Mob mob, GoalKey<Mob> key, Pathfinder.PathResult path, List<Location> points,
                      BooleanSupplier valid, Consumer<Throwable> fault) {
        this.mob = mob; this.key = key; this.path = path;
        this.points = points.stream().map(Location::clone).toList(); this.valid = valid; this.fault = fault;
    }
    boolean routeUnchanged() { return path.getPoints().equals(points); }
    boolean ownsPath() {
        if (!started) return false;
        var current = mob.getPathfinder().getCurrentPath();
        return current != null && current.getPoints().equals(path.getPoints());
    }
    private void failed(Throwable failure) { finished = true; fault.accept(failure); }
    @Override public boolean shouldActivate() {
        try { return !started && !finished && valid.getAsBoolean(); }
        catch (RuntimeException | LinkageError failure) { failed(failure); return false; }
    }
    @Override public boolean shouldStayActive() {
        try { return started && !finished && valid.getAsBoolean() && routeUnchanged() && ownsPath(); }
        catch (RuntimeException | LinkageError failure) { failed(failure); return false; }
    }
    @Override public void start() {
        try {
            if (finished || started) return;
            if (!valid.getAsBoolean()) { finished = true; return; }
            started = true;
            if (!mob.getPathfinder().moveTo(path, 1.0) || !routeUnchanged()) stop();
        } catch (RuntimeException | LinkageError failure) { failed(failure); }
    }
    @Override public void stop() {
        finished = true;
        try { if (ownsPath()) mob.getPathfinder().stopPathfinding(); }
        catch (RuntimeException | LinkageError failure) { failed(failure); }
        finally { started = false; }
    }
    @Override public GoalKey<Mob> getKey() { return key; }
    @Override public EnumSet<GoalType> getTypes() { return EnumSet.of(GoalType.MOVE); }
}
