package ru.cherdakmd.chrdkenchants;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class WorkshopPolicyTest {
    private EnchantDef light() { return EnchantRegistry.light().stream().filter(d -> d.maxLevel() > 1).findFirst().orElseThrow(); }
    @Test void cleansingRemovesExactlySelectedCurseAndNeverMutatesInput() {
        var curse = EnchantRegistry.curses().getFirst(); var other = EnchantRegistry.curses().get(1);
        var data = Map.of(curse.id(), 1, other.id(), 1, light().id(), 1);
        var clean = WorkshopPolicy.cleanse(data, curse.id());
        assertEquals(Map.of(other.id(), 1, light().id(), 1), clean); assertEquals(3, data.size());
        assertNull(WorkshopPolicy.cleanse(data, light().id())); assertNull(WorkshopPolicy.cleanse(data, null));
        assertEquals(Map.of(), WorkshopPolicy.cleanse(Map.of(curse.id(), 1), curse.id()));
    }
    @Test void combineEveryCatalogEntryExactlyOneLevelBelowItsOwnCeiling() {
        for (var def : EnchantRegistry.all()) {
            for (int level = 1; level <= def.maxLevel(); level++) {
                var data = Map.of(def.id(), level);
                if (level == def.maxLevel()) assertNull(WorkshopPolicy.combine(data, data));
                else assertEquals(Map.of(def.id(), level + 1), WorkshopPolicy.combine(data, data));
                assertEquals(level, data.get(def.id()));
            }
        }
        assertNull(WorkshopPolicy.combine(Map.of(light().id(), 1), Map.of(light().id(), 2)));
        var mixed = Map.of(light().id(), 1, EnchantRegistry.curses().getFirst().id(), 1);
        assertNull(WorkshopPolicy.combine(mixed, mixed));
    }
    @Test void splitEveryEntryConservesAllLevelsAndHasDisjointResults() {
        var data = new LinkedHashMap<String, Integer>();
        EnchantRegistry.all().forEach(d -> data.put(d.id(), d.maxLevel()));
        for (String id : data.keySet()) {
            var split = WorkshopPolicy.split(data, id, false);
            assertNotNull(split); assertEquals(Map.of(id, data.get(id)), split.extracted());
            assertFalse(split.remainder().containsKey(id));
            var joined = new HashMap<>(split.remainder()); joined.putAll(split.extracted()); assertEquals(data, joined);
        }
        var single = Map.of(light().id(), 1);
        assertNull(WorkshopPolicy.split(single, light().id(), false));
        assertTrue(WorkshopPolicy.split(single, light().id(), true).remainder().isEmpty());
        assertNull(WorkshopPolicy.split(single, null, true));
    }
    @Test void malformedOrNonCanonicalInputFailsClosed() {
        for (var data : List.of(Map.of("unknown", 1), Map.of(light().id(), 0), Map.of(light().id(), 1000), Map.of(light().id().toUpperCase(), 1))) {
            assertFalse(WorkshopPolicy.valid(data)); assertNull(WorkshopPolicy.combine(data, data));
            assertNull(WorkshopPolicy.split(data, light().id(), true));
        }
        assertFalse(WorkshopPolicy.valid(null)); assertFalse(WorkshopPolicy.valid(Map.of()));
    }
    @Test void debitPlansAreExactBoundedAndNonMutating() {
        int[] original = {2, 0, 5, 2};
        assertArrayEquals(new int[]{0, 0, 0, 1}, MaterialDebit.plan(original, 8));
        assertArrayEquals(new int[]{2, 0, 5, 2}, original);
        assertNull(MaterialDebit.plan(original, 10)); assertArrayEquals(new int[]{2, 0, 5, 2}, original);
        assertNull(MaterialDebit.plan(new int[]{-1}, 1)); assertNull(MaterialDebit.plan(new int[]{65}, 1));
        assertNull(MaterialDebit.plan(new int[37], 1)); assertNull(MaterialDebit.plan(null, 1));
        assertNull(MaterialDebit.plan(original, 0)); assertNull(MaterialDebit.plan(original, 65));
        for (int a=0; a<=64; a++) for(int b=0; b<=64; b++) for(int cost=1; cost<=9; cost++) {
            var next = MaterialDebit.plan(new int[]{a,b}, cost);
            if(a+b < cost) assertNull(next); else { assertNotNull(next); assertEquals(a+b-cost, next[0]+next[1]); }
        }
    }
    @Test void productionCommitsOnlyConfirmedCompleteSnapshotAndKeepsForeignDataOnOriginal() throws Exception {
        var source = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/EnchantWorkshop.java"));
        assertTrue(source.indexOf("pending.take(") < source.indexOf("setStorageContents(copy(plan.after()))"));
        for (String guard : List.of("Arrays.equals(plan.before()", "Objects.equals(plan.off()", "plan.generation() !=", "plan.world().equals", "getHeldItemSlot()", "main.isSimilar(off)", "meta.hasStoredEnchants() || meta.hasEnchants()", "!item.hasItemMeta()", "MaterialDebit.plan", "result.remainder()", "EnchantBookSource.WORKSHOP")) assertTrue(source.contains(guard), guard);
        assertFalse(source.contains("dispatchCommand")); assertFalse(source.contains("dropItem"));
    }
}
