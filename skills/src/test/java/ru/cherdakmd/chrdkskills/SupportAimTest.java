package ru.cherdakmd.chrdkskills;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

class SupportAimTest {
    private static final class Scene {
        World world;
        Player caster, target;
        RayTraceResult hit;
        boolean visible = true, line = true, targetAlive = true, targetOnline = true;
        Scene() {
            world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                    (p, method, args) -> {
                        if (method.getName().equals("rayTrace") && args.length == 7) {
                            assertEquals(8.0, (double) args[2]);
                            assertEquals(FluidCollisionMode.NEVER, args[3]);
                            assertEquals(false, args[4]);
                            assertEquals(0.0, (double) args[5]);
                            @SuppressWarnings("unchecked") var filter = (Predicate<Entity>) args[6];
                            assertFalse(filter.test(caster)); assertTrue(filter.test(target));
                            return hit;
                        }
                        return defaultValue(method.getReturnType());
                    });
            target = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (p, method, args) -> switch (method.getName()) {
                        case "isOnline" -> targetOnline;
                        case "isDead" -> !targetAlive;
                        case "getWorld" -> world;
                        default -> defaultValue(method.getReturnType());
                    });
            caster = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (p, method, args) -> switch (method.getName()) {
                        case "isOnline" -> true;
                        case "isDead" -> false;
                        case "getWorld" -> world;
                        case "getEyeLocation" -> new Location(world, 0, 64, 0);
                        case "canSee" -> visible;
                        case "hasLineOfSight" -> line;
                        default -> defaultValue(method.getReturnType());
                    });
        }
    }
    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        if (type == long.class) return 0L;
        if (type == int.class) return 0;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return null;
    }
    @Test void acceptsFirstVisiblePlayerHitWithinEightBlocks() {
        var scene = new Scene(); scene.hit = new RayTraceResult(new Vector(0, 64, 8), scene.target);
        var result = SupportAim.find(scene.caster);
        assertNotNull(result); assertSame(scene.target, result.player()); assertEquals(64.0, result.hitDistanceSquared());
    }
    @Test void noHitWallInvisibleDeadAndOutOfRangeFailClosed() {
        var s = new Scene(); assertNull(SupportAim.find(s.caster));
        s.hit = new RayTraceResult(new Vector(0,64,3)); assertNull(SupportAim.find(s.caster));
        s.hit = new RayTraceResult(new Vector(0,64,8.01),s.target); assertNull(SupportAim.find(s.caster));
        s.hit = new RayTraceResult(new Vector(0,64,2),s.target);
        s.visible = false; assertNull(SupportAim.find(s.caster)); s.visible = true;
        s.line = false; assertNull(SupportAim.find(s.caster)); s.line = true;
        s.targetAlive = false; assertNull(SupportAim.find(s.caster)); s.targetAlive = true;
        s.targetOnline = false; assertNull(SupportAim.find(s.caster));
    }
}
