package ru.cherdakmd.chrdkguilds;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class GuildStyle278Test {
    private final UUID owner=UUID.randomUUID();
    private final Logger logger=Logger.getAnonymousLogger();
    @Test void choicesPersistButNeverContainPaidRights(@TempDir Path folder) throws Exception {
        var store=new GuildStyleStore(folder,logger);
        assertEquals(GuildStyleStore.Result.OK,store.select(owner,"Steve","clan-a","name","aurora",1000));
        assertEquals(GuildStyleStore.Result.OK,store.select(owner,"Steve","clan-a","emblem","crown",2000));
        var restored=new GuildStyleStore(folder,logger); assertTrue(restored.healthy());
        assertEquals(2,restored.of(owner).choices().size()); assertEquals(1000,restored.of(owner).movedAt());
        String json=Files.readString(folder.resolve("clan-styles.json"));
        assertFalse(json.contains("entitlement")); assertFalse(json.contains("paidUntil"));
    }
    @Test void transferMovesWholePersonalSelectionAndOffCannotResetCooldown(@TempDir Path folder) {
        var store=new GuildStyleStore(folder,logger);
        store.select(owner,"Steve","clan-a","name","aurora",1000);
        store.select(owner,"Steve","clan-a","emblem","crown",1001);
        assertEquals(GuildStyleStore.Result.COOLDOWN,store.select(owner,"Steve","clan-b","frame","gold",1002));
        assertTrue(store.remove(owner,"name")); assertTrue(store.remove(owner,"emblem"));
        store=new GuildStyleStore(folder,logger);
        assertEquals(GuildStyleStore.Result.COOLDOWN,store.select(owner,"Steve","clan-b","frame","gold",1002));
        assertEquals(GuildStyleStore.Result.OK,store.select(owner,"Steve","clan-b","frame","gold",86_401_000));
        assertEquals("clan-b",store.of(owner).guild()); assertTrue(store.forGuild("clan-a").isEmpty());
        assertEquals(GuildStyleStore.Result.COOLDOWN,store.select(owner,"Steve","clan-a","frame","gold",86_401_001));
    }
    @Test void licenseExpiryMembershipAndOwnerIdentityAreCheckedOnEachRender(@TempDir Path folder) {
        var store=new GuildStyleStore(folder,logger);store.select(owner,"Steve","clan-a","name","gold",1000);
        var guild=new Guild("clan-a","Alpha","ABC",owner,1000);
        var rows=store.forGuild("clan-a");
        assertEquals("gold",GuildStylePolicy.active(rows,guild,"name",2000,row->3000,row->true));
        assertNull(GuildStylePolicy.active(rows,guild,"name",3000,row->3000,row->true));
        assertNull(GuildStylePolicy.active(rows,guild,"name",2000,row->3000,row->false));
        var other=new Guild("clan-a","Alpha","ABC",UUID.randomUUID(),1000);
        assertNull(GuildStylePolicy.active(rows,other,"name",2000,row->3000,row->true));
        var recreated=new Guild("clan-b","Alpha","ABC",owner,1000);
        assertNull(GuildStylePolicy.active(rows,recreated,"name",2000,row->3000,row->true));
    }
    @Test void removingEffectsDoesNotErasePersonalBinding(@TempDir Path folder) {
        var store=new GuildStyleStore(folder,logger);store.select(owner,"Steve","clan-a","name","gold",1000);
        assertTrue(store.clear("clan-a","name")); assertTrue(store.of(owner).choices().isEmpty());
        assertEquals(1000,store.of(owner).movedAt());
        assertEquals(GuildStyleStore.Result.COOLDOWN,store.select(owner,"Steve","clan-b","name","gold",1002));
    }
    @Test void corruptOrUnknownStyleDisablesWithoutOverwriting(@TempDir Path folder) throws Exception {
        Path file=folder.resolve("clan-styles.json"); Files.writeString(file,"broken");
        var store=new GuildStyleStore(folder,logger); assertFalse(store.healthy());
        assertEquals(GuildStyleStore.Result.FAILED,store.select(owner,"Steve","clan-a","name","gold",1000));
        assertEquals("broken",Files.readString(file));
        assertFalse(GuildStyleDefs.valid("name","<click:run_command:'/op Steve'>"));
        assertFalse(GuildStyleDefs.valid("rank","gold"));
        assertTrue(GuildStyleDefs.name("<red>Bad", "gold", "crown").contains("\\<red>"));
    }
    @Test void diskFailureKeepsOldChoiceAndDoesNotBlockReaders(@TempDir Path folder) throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var store=new GuildStyleStore(folder,logger,(file,json)->{
            entered.countDown();try {release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return false;
        });
        try(var worker=Executors.newSingleThreadExecutor()) {
            var future=worker.submit(()->store.select(owner,"Steve","clan-a","name","gold",1000));
            try {assertTrue(entered.await(2,TimeUnit.SECONDS));assertTimeoutPreemptively(Duration.ofSeconds(1),()->assertNull(store.of(owner)));}
            finally {release.countDown();}
            assertEquals(GuildStyleStore.Result.FAILED,future.get(2,TimeUnit.SECONDS));
        }
        assertNull(store.of(owner));
    }
    @Test void tabModeKeysRemainIndependentFromPaidStyle() throws Exception {
        assertEquals("tab-battle-prefix",GuildNameTag.tabKey(false,false));
        assertEquals("tab-peace-prefix",GuildNameTag.tabKey(true,true));
        String source=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkguilds/GuildNameTag.java"));
        String tab=source.substring(source.indexOf("if (tab) return"),source.indexOf("return plugin.messages().raw(guild.peaceful()"));
        assertFalse(tab.contains("styles()"));
    }
}
