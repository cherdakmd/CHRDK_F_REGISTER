package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MiningSafetyTest {
    @Test void geometryIsUniqueAndNeverRebreaksTheCenter() {
        for (double[] direction : new double[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1},{.3,.2,-.8}})
            for (int level = 1; level <= 2; level++) {
                var offsets = MiningPolicy.area(direction[0], direction[1], direction[2], level);
                assertEquals(level == 1 ? 8 : 17, offsets.size());
                assertEquals(offsets.size(), new HashSet<>(offsets).size());
                assertFalse(offsets.contains(new MiningPolicy.Offset(0,0,0)));
            }
    }
    @Test void secondLayerFollowsTheSignOfTheActualLookAxis() {
        assertTrue(MiningPolicy.area(-1,0,0,2).stream().allMatch(o -> o.x() <= 0));
        assertTrue(MiningPolicy.area(0,0,-1,2).stream().allMatch(o -> o.z() <= 0));
        assertTrue(MiningPolicy.area(0,-1,0,2).stream().allMatch(o -> o.y() <= 0));
        assertTrue(MiningPolicy.area(1,0,0,2).stream().anyMatch(o -> o.x() == 1));
        assertTrue(MiningPolicy.area(0,0,1,2).stream().anyMatch(o -> o.z() == 1));
        assertTrue(MiningPolicy.area(0,1,0,2).stream().anyMatch(o -> o.y() == 1));
        assertTrue(MiningPolicy.area(Double.NaN, 0, 1, 2).isEmpty());
        assertTrue(MiningPolicy.area(0, Double.POSITIVE_INFINITY, 1, 2).isEmpty());
    }
    @Test void mineralWhitelistDoesNotTreatOreBlocksOrScrapAsMineralBonuses() {
        assertEquals("RAW_IRON", MiningPolicy.mineral("IRON_ORE"));
        assertEquals("RAW_COPPER", MiningPolicy.mineral("DEEPSLATE_COPPER_ORE"));
        assertEquals("DIAMOND", MiningPolicy.mineral("DEEPSLATE_DIAMOND_ORE"));
        assertEquals("GOLD_NUGGET", MiningPolicy.mineral("NETHER_GOLD_ORE"));
        assertEquals("QUARTZ", MiningPolicy.mineral("NETHER_QUARTZ_ORE"));
        for (String material : new String[]{null,"STONE","RAW_IRON_BLOCK","ANCIENT_DEBRIS","DIAMOND_BLOCK","RAW_GOLD"}) {
            assertFalse(MiningPolicy.ore(material)); assertNull(MiningPolicy.mineral(material));
        }
    }
    @Test void smeltingIsOneToOneAndSilkTouchAlwaysWins() {
        for (String[] mapping : new String[][]{{"IRON_ORE","RAW_IRON","IRON_INGOT"},
                {"DEEPSLATE_GOLD_ORE","RAW_GOLD","GOLD_INGOT"},{"COPPER_ORE","RAW_COPPER","COPPER_INGOT"},
                {"ANCIENT_DEBRIS","ANCIENT_DEBRIS","NETHERITE_SCRAP"}}) {
            assertEquals(mapping[2], MiningPolicy.smelt(mapping[0],mapping[1],false));
            assertEquals(mapping[1], MiningPolicy.smelt(mapping[0],mapping[1],true));
            assertEquals(mapping[0], MiningPolicy.smelt(mapping[0],mapping[0],true));
        }
        assertEquals("IRON_ORE", MiningPolicy.smelt("IRON_ORE","IRON_ORE",false));
        assertEquals("DIAMOND", MiningPolicy.smelt("DIAMOND_ORE","DIAMOND",false));
        assertEquals("RAW_GOLD", MiningPolicy.smelt("IRON_ORE","RAW_GOLD",false));
        assertEquals("GOLD_NUGGET", MiningPolicy.smelt("NETHER_GOLD_ORE","GOLD_NUGGET",false));
    }
    @Test void packedMarksCoverNegativeHeightsAndNeverAliasWithinAChunk() {
        Set<Long> positions = new HashSet<>();
        for (int y : new int[]{-64,-1,0,1,255,319}) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
            assertTrue(positions.add(MiningPolicy.position(x,y,z)));
        assertEquals(MiningPolicy.position(15,-64,15),MiningPolicy.position(-1,-64,-1));
        assertTrue(MiningPolicy.validPositions(positions.stream().mapToLong(Long::longValue).toArray(), -64, 320));
        assertFalse(MiningPolicy.validPositions(new long[]{MiningPolicy.position(1,-65,1)}, -64, 320));
        assertFalse(MiningPolicy.validPositions(new long[]{MiningPolicy.position(1,320,1)}, -64, 320));
        assertFalse(MiningPolicy.validPositions(new long[]{Long.MAX_VALUE}, -64, 320));
        assertFalse(MiningPolicy.validPositions(new long[]{1,1}, -64, 320));
        assertFalse(MiningPolicy.validPositions(null, -64, 320));
    }
    @Test void marksAreBoundedIdempotentAndOverflowIsFailClosed() {
        long[] marks = new long[0];
        for (int i = 0; i < 4096; i++) marks = MiningPolicy.mark(marks, i);
        assertNotNull(marks); assertEquals(4096, marks.length);
        assertSame(marks, MiningPolicy.mark(marks, 17)); assertNull(MiningPolicy.mark(marks, 4096));
        assertNull(MiningPolicy.mark(null, 1)); assertNull(MiningPolicy.mark(new long[4097], 0));
        assertFalse(MiningPolicy.validPositions(new long[4097], -64, 320));
    }
    private static final class Scheduled implements MiningTasks.Handle {
        final Runnable action; boolean cancelled;
        Scheduled(Runnable action) { this.action = action; }
        public void cancel() { cancelled = true; }
    }
    @Test void deferredQueueIsBoundedAndRejectsDuplicateKeys() {
        List<Scheduled> scheduled = new ArrayList<>(); UUID owner = UUID.randomUUID();
        var queue = new MiningTasks(action -> { var task = new Scheduled(action); scheduled.add(task); return task; });
        for (int i = 0; i < 4096; i++) assertTrue(queue.submit(owner, i, () -> { }));
        assertFalse(queue.submit(owner, 4096, () -> fail("overflow")));
        assertFalse(queue.submit(owner, 1, () -> fail("duplicate"))); assertEquals(4096, queue.size());
        scheduled.getFirst().action.run(); assertEquals(4095, queue.size());
        assertTrue(queue.submit(owner, 4096, () -> { }));
        queue.clear(); assertEquals(0, queue.size()); assertTrue(scheduled.getLast().cancelled);
    }
    @Test void reloadCancellationCannotExecuteOldTicketsOrDeleteTheirReplacements() {
        List<Scheduled> scheduled = new ArrayList<>(); AtomicInteger count = new AtomicInteger(); UUID owner = UUID.randomUUID();
        var queue = new MiningTasks(action -> { var task = new Scheduled(action); scheduled.add(task); return task; });
        queue.submit(owner, "same", () -> count.addAndGet(10)); var old = scheduled.getFirst();
        queue.clear(); assertTrue(old.cancelled);
        queue.submit(owner, "same", count::incrementAndGet); old.action.run();
        assertEquals(1, queue.size()); assertEquals(0, count.get());
        scheduled.getLast().action.run(); scheduled.getLast().action.run();
        assertEquals(1, count.get()); assertEquals(0, queue.size());
    }
    @Test void quitCancellationIsScopedAndSchedulingFailureDoesNotLeakCapacity() {
        List<Scheduled> scheduled = new ArrayList<>(); AtomicInteger count = new AtomicInteger();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var queue = new MiningTasks(action -> { var task = new Scheduled(action); scheduled.add(task); return task; });
        queue.submit(first, "first", () -> count.addAndGet(10)); queue.submit(second, "second", count::incrementAndGet);
        queue.cancel(first); assertEquals(1, queue.size()); assertTrue(scheduled.getFirst().cancelled);
        scheduled.forEach(task -> task.action.run()); assertEquals(1, count.get());
        var broken = new MiningTasks(action -> { throw new IllegalStateException("scheduler stopped"); });
        assertThrows(IllegalStateException.class, () -> broken.submit(first, "key", () -> { })); assertEquals(0, broken.size());
    }
    @Test void newUtilitiesHaveStrictBalanceAndRespectRegistryAndMasterSwitch() {
        assertEquals(25,UtilityEnchant.FROST_RESISTANCE.freezePercent(1));
        assertEquals(50,UtilityEnchant.FROST_RESISTANCE.freezePercent(2));
        assertEquals(50,UtilityEnchant.FROST_RESISTANCE.freezePercent(Integer.MAX_VALUE));
        assertEquals(25,UtilityEnchant.FROST_RESISTANCE.freezePercent(Integer.MIN_VALUE));
        assertTrue(UtilityEnchant.unsafe(100,98)); assertTrue(UtilityEnchant.unsafe(100,99));
        assertTrue(UtilityEnchant.unsafe(100,100)); assertFalse(UtilityEnchant.unsafe(100,97));
        assertFalse(UtilityEnchant.unsafe(0,0)); assertFalse(UtilityEnchant.unsafe(Integer.MAX_VALUE,0));
        assertEquals(1, EnchantRegistry.byId("tool_safeguard").maxLevel());
        assertTrue(EnchantRegistry.byId("tool_safeguard").supports("IRON_HOE"));
        assertFalse(EnchantRegistry.byId("tool_safeguard").supports("IRON_SWORD"));
        assertTrue(EnchantRegistry.byId("frost_resistance").supports("IRON_BOOTS"));
        assertFalse(EnchantRegistry.byId("frost_resistance").supports("IRON_LEGGINGS"));
        var yaml = new org.bukkit.configuration.file.YamlConfiguration(); yaml.set("utility-effects.enabled", false);
        var disabled = EnchantsConfig.parse(yaml, java.util.logging.Logger.getLogger("MiningSafetyTest"));
        assertFalse(disabled.isEnabled("tool_safeguard")); assertFalse(disabled.isEnabled("frost_resistance"));
    }
    @Test void bonusesWaitForBothFinalEventsAndDoNotCloneOtherPluginsRewards() throws Exception {
        String source = source("ToolEffectsListener");
        for (String guard : List.of("source.event.isCancelled()", "source.event.isDropItems()", "event.isCancelled()",
                "event.getItems().contains(yield.entity)", "yield.entity.isValid()", "yield.entity.isDead()",
                "source.natural && !source.secondary && !silk", "MiningPolicy.mineral(source.type.name())",
                "yield.entity.getItemStack().getAmount() >= yield.amount", "int remaining = 64",
                "stack.getAmount(), before", "new ItemStack(yield.entity.getItemStack().getType(), amount)")) assertTrue(source.contains(guard), guard);
        assertTrue(source.contains("tasks.submit(event.getPlayer().getUniqueId(), yields"));
        assertFalse(source.contains("getItemStack().clone()")); assertFalse(source.contains("setExpToDrop"));
        assertTrue(source.contains("converted.setType(after)"));
        assertEquals(10, EnchantParams.chancePercent("midas", 1));
        assertEquals(10, EnchantParams.percent("fortunate", 1)); assertEquals(18, EnchantParams.percent("fortunate", 2));
        assertFalse(source("PolarityEffectsListener").contains("dropItemNaturally"));
    }
    @Test void expansionsUseNativeBreaksAndCannotTraverseDeniedOreOrReloadChunks() throws Exception {
        String source = source("ToolEffectsListener");
        for (String guard : List.of("player.breakBlock(block)", "if (breakOne(player, block, source)) { count++; queue.add(block); }",
                "secondary.add", "finally { secondary.remove", "source.secondary", "source.sneaking",
                "source.generation == plugin.sourceGeneration()", "getHeldItemSlot() != source.slot", "distanceSquared",
                "isChunkLoaded", "instanceof org.bukkit.block.TileState", "PveEquipment.same(source.tool",
                "sources.size() >= 4096", "tasks.cancel(player.getUniqueId())", "coreApi().isAuthenticated(player)")) assertTrue(source.contains(guard), guard);
        for (String forbidden : List.of("breakNaturally", "setType(Material.AIR)", "callEvent(", "setDamage(")) assertFalse(source.contains(forbidden), forbidden);
        String plugin = source("EnchantsPlugin"); assertTrue(plugin.contains("if (tools != null) tools.clear()"));
    }
    @Test void originNeverDeletesMarksOrUsesEphemeralPerPlayerProvenance() throws Exception {
        String source = source("MiningOrigin");
        for (String guard : List.of("PersistentDataType.LONG_ARRAY", "data.has(tainted)", "MiningPolicy.validPositions",
                "BlockPlaceEvent", "BlockMultiPlaceEvent", "BlockPistonExtendEvent", "BlockPistonRetractEvent", "isChunkLoaded")) assertTrue(source.contains(guard),guard);
        for (String forbidden : List.of("BlockBreakEvent", "System.currentTimeMillis", "getUniqueId()", "getGameMode()")) assertFalse(source.contains(forbidden), forbidden);
    }
    @Test void safeguardIsBeforeDropsAndFreezeCannotReduceAnEntityAttack() throws Exception {
        String source = source("MiningUtilityListener");
        for (String guard : List.of("priority = EventPriority.LOWEST", "event.setCancelled(true)", "damage.isUnbreakable()",
                "damage.hasMaxDamage()", "notified.size() >= 4096", "instanceof EntityDamageByEntityEvent",
                "EntityDamageEvent.DamageCause.FREEZE", "getCausingEntity() != null", "getDirectEntity() != null",
                "Material.POWDER_SNOW", "coreApi().isAuthenticated(player)")) assertTrue(source.contains(guard), guard);
        for (String forbidden : List.of("setDamage(0", "setType(", "dropItem", "addItem(", "setHealth(", "setExpToDrop", "setUnbreakable(")) assertFalse(source.contains(forbidden), forbidden);
    }
    private String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/" + name + ".java"));
    }
}
