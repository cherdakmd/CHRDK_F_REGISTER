package ru.cherdakmd.chrdkenchants;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Реальные методы tracker с подставленным Bukkit-хранилищем, без запущенного мира. */
class MiningOriginTest {
    private final NamespacedKey positions = new NamespacedKey("test", "positions"), tainted = new NamespacedKey("test", "tainted");
    private record Value(Object type, Object value) { }
    private final Map<String,Map<NamespacedKey,Value>> chunks = new HashMap<>();
    private final Set<String> unloaded = new HashSet<>();
    private final World world = proxy(World.class, (method, args) -> switch (method) {
        case "isChunkLoaded" -> !unloaded.contains(args[0]+":"+args[1]);
        case "getMinHeight" -> -64; case "getMaxHeight" -> 320;
        case "getBlockAt" -> block((int) args[0], (int) args[1], (int) args[2]);
        default -> throw new UnsupportedOperationException(method);
    });
    private MiningOrigin tracker() { return new MiningOrigin(positions, tainted); }
    private Map<NamespacedKey,Value> data(int x, int z) { return chunks.computeIfAbsent(x+":"+z, key -> new HashMap<>()); }
    private PersistentDataContainer pdc(int x, int z) {
        var values = data(x,z);
        return proxy(PersistentDataContainer.class, (method,args) -> switch (method) {
            case "has" -> values.containsKey(args[0]) && (args.length == 1 || values.get(args[0]).type == args[1]);
            case "get" -> values.containsKey(args[0]) && values.get(args[0]).type == args[1] ? values.get(args[0]).value : null;
            case "set" -> { values.put((NamespacedKey)args[0],new Value(args[1],args[2])); yield null; }
            case "remove" -> { values.remove(args[0]); yield null; }
            default -> throw new UnsupportedOperationException(method);
        });
    }
    private Block block(int x, int y, int z) {
        return proxy(Block.class, (method,args) -> switch (method) {
            case "getX" -> x; case "getY" -> y; case "getZ" -> z; case "getWorld" -> world;
            case "getType" -> Material.DIAMOND_ORE;
            case "getChunk" -> proxy(Chunk.class, (name,unused) -> {
                if (name.equals("getPersistentDataContainer")) return pdc(x >> 4,z >> 4);
                throw new UnsupportedOperationException(name);
            });
            case "getRelative" -> {
                BlockFace face = (BlockFace) args[0]; yield block(x+face.getModX(), y+face.getModY(), z+face.getModZ());
            }
            default -> throw new UnsupportedOperationException(method);
        });
    }
    @Test void placementMarksPersistAcrossListenerReplacementAndRepeatedBreakQueries() {
        Block placed = block(-1,-64,-1), neighbor = block(-2,-64,-1); var origin = tracker();
        assertFalse(origin.denied(placed)); origin.mark(placed);
        for (int i = 0; i < 10; i++) assertTrue(origin.denied(placed));
        assertTrue(tracker().denied(placed)); assertFalse(tracker().denied(neighbor));
        origin.mark(placed); assertEquals(1,((long[])data(-1,-1).get(positions).value).length);
        assertFalse(origin.denied(block(15,-64,15))); // Тот же local position в другом чанке не совпадает.
    }
    @Test void overflowDisablesBonusForTheChunkButDoesNotTouchOrdinaryBreaks() {
        long[] full = new long[4096]; for (int i = 0; i < full.length; i++) full[i] = i;
        data(0,0).put(positions, new Value(PersistentDataType.LONG_ARRAY, full));
        var origin = tracker(); origin.mark(block(0,16,0));
        assertTrue(data(0,0).containsKey(tainted)); assertFalse(data(0,0).containsKey(positions));
        assertTrue(origin.denied(block(15,319,15))); assertTrue(tracker().denied(block(1,0,1)));
        assertFalse(origin.denied(block(16,0,0)));
    }
    @Test void wrongTypesInvalidHeightsAndDuplicatePositionsFailClosed() {
        for (Value invalid : List.of(new Value(PersistentDataType.STRING, "invalid"),
                new Value(PersistentDataType.LONG_ARRAY, new long[]{Long.MAX_VALUE}),
                new Value(PersistentDataType.LONG_ARRAY, new long[]{1,1}))) {
            chunks.clear(); data(0,0).put(positions, invalid);
            var origin = tracker(); assertTrue(origin.denied(block(0,0,0)));
            origin.mark(block(1,0,0)); assertTrue(data(0,0).containsKey(tainted));
        }
    }
    @Test void noReadOrWriteLoadsAnUnloadedChunk() {
        unloaded.add("1:0"); var origin = tracker();
        assertTrue(origin.denied(block(16,0,0))); origin.mark(block(16,0,0)); assertTrue(chunks.isEmpty());
    }
    @Test void bothPistonDirectionsMarkSourceAndDestinationsWithoutConsumingMarks() {
        var origin = tracker(); Block ore = block(15,0,0);
        var extend = new BlockPistonExtendEvent(block(14,0,0),List.of(ore),BlockFace.EAST);
        origin.extend(extend); assertFalse(extend.isCancelled());
        for (int x : new int[]{14,15,16}) assertTrue(tracker().denied(block(x,0,0)));
        chunks.clear(); var retract = new BlockPistonRetractEvent(block(14,0,0),List.of(ore),BlockFace.WEST);
        origin.retract(retract); assertFalse(retract.isCancelled());
        for (int x : new int[]{14,15,16}) assertTrue(tracker().denied(block(x,0,0)));
    }
    @Test void pistonCannotMoveOreIntoUnloadedUntrackedDestination() {
        unloaded.add("1:0"); var event = new BlockPistonExtendEvent(block(14,0,0),List.of(block(15,0,0)),BlockFace.EAST);
        tracker().extend(event); assertTrue(event.isCancelled()); assertTrue(chunks.isEmpty());
    }
    @FunctionalInterface private interface Call { Object run(String method, Object[] args); }
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self,method,args) -> {
            if (method.getName().equals("hashCode")) return System.identityHashCode(self);
            if (method.getName().equals("equals")) return self == args[0];
            if (method.getName().equals("toString")) return type.getSimpleName();
            return call.run(method.getName(), args == null ? new Object[0] : args);
        }));
    }
}
