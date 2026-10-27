package ru.cherdakmd.chrdkregister;
import ru.cherdakmd.chrdkregister.api.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class HarvestWindowsTest {
    @Test void sameActualHarvestFromTwoProvidersCreatesOnePlantingWindow() {
        var windows = new HarvestWindows<String,Object,Object>(); Object event=new Object(), first=new Object();
        var initial=windows.reserve("crop",event,first); assertTrue(initial.fresh());
        var second=windows.reserve("crop",event,new Object()); assertFalse(second.fresh()); assertSame(first,second.value());
        assertNull(windows.reserve("crop",new Object(),new Object())); assertEquals(1,windows.size());
    }
    @Test void staleCallbackCannotRemoveReplacementAndIdentityNotEqualsIsUsed() {
        var windows = new HarvestWindows<String,Object,Object>(); Object event=new String("event"), old=new String("value");
        windows.reserve("crop",event,old);
        assertNull(windows.reserve("crop",new String("event"),new Object()));
        assertFalse(windows.remove("crop",new String("value"))); assertTrue(windows.remove("crop",old));
        Object next=new Object(); windows.reserve("crop",new Object(),next);
        assertFalse(windows.remove("crop",old)); assertSame(next,windows.get("crop"));
        var snapshot=windows.snapshot(); windows.remove("crop",next); assertEquals(1,snapshot.size());
    }
    @Test void hardCapacityAndNullInputsFailClosedWithoutEviction() {
        var windows = new HarvestWindows<Integer,Object,Object>(); Object event=new Object(),value=new Object();
        assertNull(windows.reserve(null,event,value)); assertNull(windows.reserve(1,null,value)); assertNull(windows.reserve(1,event,null));
        for(int i=0;i<HarvestWindows.LIMIT;i++) assertTrue(windows.reserve(i,event,value).fresh());
        assertNull(windows.reserve(HarvestWindows.LIMIT,event,value)); assertFalse(windows.reserve(0,event,new Object()).fresh());
        assertEquals(4096,windows.size()); assertTrue(windows.remove(0,value)); assertTrue(windows.reserve(4096,event,value).fresh());
    }
    @Test void onlySixRipeCropsHaveRealSeeds() {
        var expected=Map.of("WHEAT","WHEAT_SEEDS","CARROTS","CARROT","POTATOES","POTATO","BEETROOTS","BEETROOT_SEEDS","NETHER_WART","NETHER_WART","COCOA","COCOA_BEANS");
        expected.forEach((crop,seed)->assertEquals(seed,CropPolicy.seed(crop)));
        assertNull(CropPolicy.seed("SUGAR_CANE")); assertNull(CropPolicy.seed(null));
        for(int max=1;max<=7;max++) for(int age=-1;age<=max+1;age++) assertEquals(age==max,CropPolicy.ripe(age,max));
        assertFalse(CropPolicy.ripe(0,0));
    }
    @Test void productionDebitsBeforePlacementAndRechecksNativeCancellationAndIdentity() throws Exception {
        var s=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/api/CropReplanting.java"));
        assertTrue(s.indexOf("getInventory().setItem(slot, paid")<s.indexOf("block.setBlockData(entry.crop"));
        assertTrue(s.indexOf("block.setBlockData(entry.crop")<s.indexOf("callEvent(event)"));
        for(String guard:List.of("pending.reserve", "!reserved.fresh()", "pending.get(key) == entry", "!entry.source.isDropItems()", "entry.source.isCancelled()", "event.canBuild()", "!event.isCancelled()", "entry.crop.isSupported(block)", "!item.hasItemMeta()", "pending.remove(key, entry)", "old.update(true, false)", "addItem(new ItemStack(seed, 1))")) assertTrue(s.contains(guard),guard);
        assertFalse(s.contains("setExpToDrop")); assertFalse(s.contains("setAge(1)"));
        var skills=Files.readString(Path.of("skills/src/main/java/ru/cherdakmd/chrdkskills/SkillListener.java"));
        assertTrue(skills.contains("CropReplanting.request(plugin, event")); assertTrue(skills.contains("CropReplanting.isPlacement(event)")); assertTrue(s.contains("finally { currentPlacement = previousPlacement; }")); assertFalse(skills.contains("block.setType(replant)"));
    }
}
