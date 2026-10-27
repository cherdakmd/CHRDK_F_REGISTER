package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.EnchantBookSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class BookProgressionTest {
    @TempDir Path directory;
    private static final class Death { boolean cancelled; }
    @Test void cancelledDeathNeverConsumesThePermanentClaim() {
        var claims = new DeathBookClaims<Death>(event -> event.cancelled); var id = UUID.randomUUID(); var event = new Death();
        assertTrue(claims.begin(id,event)); event.cancelled = true;
        assertFalse(claims.finish(id,event)); assertEquals(0,claims.size());
        var actual = new Death(); assertTrue(claims.begin(id,actual)); assertTrue(claims.finish(id,actual));
    }
    @Test void duplicateAndRecursiveEventsCannotClaimTwice() {
        var claims = new DeathBookClaims<Death>(event -> event.cancelled); var id = UUID.randomUUID(); var first = new Death();
        assertTrue(claims.begin(id,first)); assertFalse(claims.begin(id,first)); assertFalse(claims.begin(id,new Death()));
        assertTrue(claims.finish(id,first)); assertFalse(claims.finish(id,first));
    }
    @Test void cancelledEventCanBeReplacedInTheSameTickAndOldCallbackCannotRemoveReplacement() {
        var claims = new DeathBookClaims<Death>(event -> event.cancelled); var id = UUID.randomUUID();
        var first = new Death(); claims.begin(id,first); first.cancelled = true; var next = new Death();
        assertTrue(claims.begin(id,next)); assertFalse(claims.finish(id,first)); assertEquals(1,claims.size());
        claims.discard(id,first); assertEquals(1,claims.size()); assertTrue(claims.finish(id,next));
    }
    @Test void claimCapacityIsBoundedAndCancelledReplacementStillFitsAtCapacity() {
        var claims = new DeathBookClaims<Death>(event -> event.cancelled); UUID id = null; Death last = null;
        for (int i=0;i<4096;i++) { id=UUID.randomUUID(); last=new Death(); assertTrue(claims.begin(id,last)); }
        assertFalse(claims.begin(UUID.randomUUID(),new Death())); last.cancelled=true;
        var next=new Death(); assertTrue(claims.begin(id,next)); assertEquals(4096,claims.size());
        claims.discard(id,next); assertEquals(4095,claims.size()); assertTrue(claims.begin(UUID.randomUUID(),new Death()));
    }
    @Test void reloadDiscardsOldEventsWithoutMarkingCancelledOrNewDeathsClaimed() {
        var claims = new DeathBookClaims<Death>(event -> event.cancelled); var id=UUID.randomUUID(); var first=new Death();
        claims.begin(id,first); claims.clear(); assertFalse(claims.finish(id,first));
        var next=new Death(); claims.begin(id,next); assertFalse(claims.finish(id,first)); assertTrue(claims.finish(id,next));
    }
    @Test void sourceEncodingIsFixedVersionedAndNeverAcceptsArbitraryPlayerText() {
        var codes = new HashSet<String>();
        for (var source:EnchantBookSource.values()) {
            assertTrue(codes.add(source.encoded())); assertEquals(source,EnchantBookSource.decode(source.encoded()));
            assertTrue(source.id().matches("[a-z]+"));
        }
        for (String value:List.of("mob","MOB","v1:MOB","v2:mob","v1:unknown","<red>mob</red>","a".repeat(4097),""))
            assertNull(EnchantBookSource.decode(value));
        assertNull(EnchantBookSource.decode(null)); assertEquals(7,codes.size());
    }
    @Test void newSourceOverloadDelegatesToLegacyApiWithoutBreakingOldImplementations() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var api = (ru.cherdakmd.chrdkregister.api.ChrdkEnchantsApi) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ru.cherdakmd.chrdkregister.api.ChrdkEnchantsApi.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("book") && method.getParameterCount() == 2) {
                        assertEquals("seer", args[0]); assertEquals(1, args[1]); calls.incrementAndGet(); return null;
                    }
                    if (method.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                    throw new UnsupportedOperationException(method.getName());
                });
        assertNull(api.book("seer", 1, EnchantBookSource.TREASURE)); assertEquals(1,calls.get());
    }
    @Test void oldBooksRemainValidWithoutSourceAndSourceIsNotPartOfTransferValidation() throws Exception {
        assertEquals(Map.of("seer",1),EnchantBookValidation.read("seer:1"));
        assertFalse(source("EnchantBookValidation").contains("bookSource"));
        String items=source("EnchantItems");
        assertTrue(items.contains("if (!data.has(bookSource)) return \"book-source-legacy\""));
        assertTrue(items.contains("\"book-source-unknown\""));
        String renderer=items.substring(items.indexOf("private void refreshLore"),items.indexOf("private void applyAttributes"));
        assertFalse(renderer.contains("data.set(")); assertTrue(renderer.contains("sourceKey(meta)"));
    }
    @Test void everyNewBookSourceIsStampedAtItsOwnCommitBoundary() throws Exception {
        assertTrue(source("TableListener").contains("stampBook(result, ru.cherdakmd.chrdkregister.api.EnchantBookSource.TABLE)"));
        assertTrue(source("VillagerListener").contains("EnchantBookSource.VILLAGER"));
        assertTrue(source("MobBookListener").contains("EnchantBookSource.MOB"));
        assertTrue(source("EnchantCommandHandler").contains("EnchantBookSource.ADMIN"));
        assertTrue(source("EnchantsApiBridge").contains("EnchantBookSource.API"));
        Path root=Path.of("..");
        String treasures=Files.readString(root.resolve("treasures/src/main/java/ru/cherdakmd/chrdktreasures/TreasureSpecials.java"));
        assertTrue(treasures.contains("EnchantBookSource.SHARDS")); assertTrue(treasures.contains("EnchantBookSource.TREASURE"));
        String api=Files.readString(root.resolve("src/main/java/ru/cherdakmd/chrdkregister/api/ChrdkEnchantsApi.java"));
        assertTrue(api.contains("default ItemStack book(String id, int level, EnchantBookSource source)"));
        assertTrue(api.contains("return book(id, level);"));
    }
    @Test void mobClaimIsScheduledBeforeRollButCommittedOnlyAfterFinalCancellation() throws Exception {
        String source=source("MobBookListener");
        assertTrue(source.contains("DeathBookClaims<>(EntityDeathEvent::isCancelled)"));
        assertTrue(source.contains("if (!claims.begin(id, event)) return"));
        assertTrue(source.indexOf("pending.submit(id, event")<source.indexOf("nextDouble()"));
        assertTrue(source.contains("if (claims.finish(id, event)) data.set(claimed"));
        assertTrue(source.contains("pending.cancel(id)")); assertTrue(source.contains("claims.discard(id, event)"));
        assertTrue(source.contains("event.getDrops().add(book)"));
        for (String forbidden:List.of("dropItem(","addItem(","setDroppedExp(","setHealth(")) assertFalse(source.contains(forbidden));
    }
    @Test void libraryRoundTripsTheEntireCatalogWithoutIncreasingLevelsOrInventingIds() {
        Map<String,Integer> all=new TreeMap<>(); EnchantRegistry.all().forEach(def->all.put(def.id(),def.maxLevel()));
        String encoded=EnchantLibrary.encode(all); assertTrue(encoded.length()<4099);
        assertEquals(all,EnchantLibrary.read(encoded)); assertEquals(76,EnchantLibrary.read(encoded).size());
        assertEquals(Map.of(),EnchantLibrary.read(null)); assertEquals(Map.of(),EnchantLibrary.read("v1|"));
    }
    @Test void libraryKeepsHighestDiscoveredLevelAndDoesNotMutateEitherInput() {
        var before=Map.of("scholar",2,"seer",1); var book=Map.of("scholar",1,"air_reserve",2);
        var result=EnchantLibrary.merge(before,book);
        assertEquals(Map.of("scholar",2,"seer",1,"air_reserve",2),result);
        assertEquals(Map.of("scholar",2,"seer",1),before); assertEquals(Map.of("scholar",1,"air_reserve",2),book);
        assertEquals(3,EnchantLibrary.merge(result,Map.of("scholar",3)).get("scholar"));
        assertEquals(result,EnchantLibrary.merge(result,Map.of()));
    }
    @Test void invalidOrFutureLibraryDataIsRejectedAsAWhole() {
        for (String raw:List.of("", "v2|seer:1","v1|seer:1;unknown:1","v1|seer:1;seer:1","v1|scholar:0",
                "v1|air_reserve:9999999999","v1|seer:1;","v1|"+"a".repeat(4097))) assertNull(EnchantLibrary.read(raw),raw);
        assertNull(EnchantLibrary.merge(Map.of(),Map.of("unknown",1)));
        assertNull(EnchantLibrary.merge(Map.of(),Map.of("seer",10)));
        assertNull(EnchantLibrary.merge(null,Map.of("seer",1)));
    }
    @Test void libraryPagesAreStableBoundedImmutableAndDoNotLeakMoreThanTenEntries() {
        Map<String,Integer> all=new LinkedHashMap<>(); EnchantRegistry.all().forEach(def->all.put(def.id(),1));
        var reached=new HashSet<String>();
        for(int i=1;i<=EnchantLibrary.pages(all.size());i++) {
            var page=EnchantLibrary.page(all,i); assertTrue(page.size()<=10);
            page.forEach(entry->assertTrue(reached.add(entry.getKey())));
            assertThrows(UnsupportedOperationException.class,()->page.clear());
            assertThrows(UnsupportedOperationException.class,()->page.getFirst().setValue(99));
        }
        assertEquals(all.keySet(),reached); assertEquals(8,EnchantLibrary.pages(all.size()));
        assertEquals(EnchantLibrary.page(all,1),EnchantLibrary.page(all,Integer.MIN_VALUE));
        assertEquals(EnchantLibrary.page(all,8),EnchantLibrary.page(all,Integer.MAX_VALUE));
        assertEquals(1,EnchantLibrary.pages(0)); assertEquals(1,EnchantLibrary.pages(-1));
        assertTrue(EnchantLibrary.pages(Integer.MAX_VALUE)>0); assertTrue(EnchantLibrary.page(Map.of(),1).isEmpty());
    }
    @Test void libraryOnlyObservesOwnedStrictBooksAfterNativeInventoryCommit() throws Exception {
        String source=source("EnchantLibraryListener");
        for(String guard:List.of("player.getInventory().getContents()", "Material.ENCHANTED_BOOK", "plugin.items().validated(item)",
                "pending.submit(player.getUniqueId(), player.getUniqueId()", "generation != plugin.sourceGeneration()",
                "world.equals(player.getWorld().getUID())", "GameMode.SURVIVAL", "coreApi().isAuthenticated(player)",
                "before == null", "!before.equals(found)", "PlayerAuthCompletedEvent", "pending.cancel(")) assertTrue(source.contains(guard),guard);
        assertFalse(source.contains("event.getItem().getItemStack()")); assertFalse(source.contains("getTopInventory()"));
        for(String forbidden:List.of("dropItem", "addItem(", "award(", "setExp(", "setLevel(", "setItem(")) assertFalse(source.contains(forbidden),forbidden);
    }
    @Test void libraryQueryIsReadOnlyAndBothServicesHaveLifecycleCleanup() throws Exception {
        String source=source("EnchantLibraryListener");
        String show=source.substring(source.indexOf("void show("),source.indexOf("@EventHandler(priority"));
        assertFalse(show.contains("scan(")); assertFalse(show.contains(".set(")); assertFalse(show.contains("queue("));
        String plugin=source("EnchantsPlugin");
        assertEquals(2,plugin.split("if \\(mobBooks != null\\) mobBooks.clear\\(\\);",-1).length-1);
        assertEquals(2,plugin.split("if \\(library != null\\) library.clear\\(\\);",-1).length-1);
        assertTrue(source("EnchantCommandHandler").contains("case \"library\""));
    }
    @Test void sourceLabelsAndLibraryMessagesAreRealMiniMessageEntries() throws Exception {
        try(var stream=getClass().getResourceAsStream("/messages.yml")) {
            var messages=Messages.from(stream,directory.resolve("messages.yml"),Logger.getLogger("BookProgressionTest"));
            for(var source:EnchantBookSource.values()) assertTrue(messages.has("book-source-"+source.id()));
            for(String key:List.of("book-source","book-source-legacy","book-source-unknown","library-header","library-line",
                    "library-empty","library-disabled","library-invalid")) {
                assertTrue(messages.has(key),key); assertFalse(messages.plain(key).isBlank());
            }
        }
    }
    private String source(String name) throws Exception { return Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/"+name+".java")); }
}
