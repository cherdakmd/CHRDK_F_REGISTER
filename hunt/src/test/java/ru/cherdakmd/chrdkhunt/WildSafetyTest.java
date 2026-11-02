package ru.cherdakmd.chrdkhunt;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Ни LOS, ни поиск точки не должны читать незагруженный промежуточный чанк. */
class WildSafetyTest {
    private World world(BiPredicate<Integer, Integer> loaded, AtomicInteger probes) {
        UUID id = UUID.randomUUID();
        return (World) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{World.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getUID" -> id;
            case "getName" -> "test";
            case "equals" -> proxy == args[0];
            case "hashCode" -> id.hashCode();
            case "isChunkLoaded" -> { probes.incrementAndGet(); yield loaded.test((Integer) args[0], (Integer) args[1]); }
            default -> throw new AssertionError("Нельзя загружать/читать мир: " + method.getName());
        });
    }
    @Test void loadedEndpointsAreNotEnoughWhenMiddleChunkIsMissing() {
        AtomicInteger probes = new AtomicInteger(); World w = world((x, z) -> x == 0 || x == 3, probes);
        assertFalse(WildSafety.loadedArea(new Location(w, 1, 64, 1), new Location(w, 49, 64, 1)));
        assertTrue(probes.get() <= 4);
    }
    @Test void diagonalCornerDoesNotSneakAcrossUnloadedNeighbour() {
        World w = world((x, z) -> x.equals(z), new AtomicInteger());
        assertFalse(WildSafety.loadedArea(new Location(w, 15.9, 64, 15.9), new Location(w, 16.1, 64, 16.1)));
    }
    @Test void smallLoadedAreaHasHardBoundAndDoesNotAccessBlocksOrRequestChunks() {
        AtomicInteger probes = new AtomicInteger(); World w = world((x, z) -> true, probes);
        assertTrue(WildSafety.loadedArea(new Location(w, 15.5, 64, 15.5), new Location(w, 95.5, 70, 95.5)));
        assertEquals(36, probes.get());
    }
    @Test void differentWorldsNonFiniteCoordinatesAndLongRaysAreRejectedBeforeTraversal() {
        AtomicInteger probes = new AtomicInteger(); World w = world((x, z) -> true, probes), other = world((x, z) -> true, probes);
        Location from = new Location(w, 0, 64, 0);
        assertFalse(WildSafety.loadedArea(from, new Location(other, 0, 64, 0)));
        assertFalse(WildSafety.loadedArea(from, new Location(w, Double.NaN, 64, 0)));
        assertFalse(WildSafety.loadedArea(from, new Location(w, 10000000, 64, 0)));
        assertFalse(WildSafety.loadedArea(from, null)); assertEquals(0, probes.get());
    }
}
