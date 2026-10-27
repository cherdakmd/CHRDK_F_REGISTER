package ru.cherdakmd.chrdkhunt;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class HuntHistoryTest {
    @TempDir Path dir;
    private HuntHistory.Entry entry(int n) {
        return new HuntHistory.Entry(new UUID(0, n), 1_800_000_000_000L + n, "ZOMBIE", 5, 0, "world");
    }
    @Test void historyIsCappedNewestFirstImmutableAndPersonal() {
        var store = new HuntStore(dir, Logger.getAnonymousLogger()); UUID owner = UUID.randomUUID();
        for (int i = 1; i <= 40; i++) assertTrue(store.recordHistory(owner, "Hunter", entry(i)));
        var rows = store.history(owner);
        assertEquals(20, rows.size()); assertEquals(entry(40), rows.getFirst()); assertEquals(entry(21), rows.getLast());
        assertThrows(UnsupportedOperationException.class, () -> rows.clear());
        assertTrue(store.history(UUID.randomUUID()).isEmpty());
        assertEquals(0, store.killsTotal(owner)); assertEquals(0, store.player(owner).coins());
        assertEquals(0, store.player(owner).earned()); assertTrue(store.player(owner).bestiary().isEmpty());
        assertTrue(store.player(owner).trophies().isEmpty()); assertTrue(store.player(owner).shardMap().isEmpty());
        assertTrue(store.player(owner).keyMap().isEmpty());
    }
    @Test void duplicatesDoNotDirtyOrCreateAnotherRow() {
        var store = new HuntStore(dir, Logger.getAnonymousLogger()); UUID owner = UUID.randomUUID();
        assertTrue(store.recordHistory(owner, "Hunter", entry(1))); store.snapshotJson();
        assertFalse(store.recordHistory(owner, "Hunter", entry(1))); assertFalse(store.isDirty());
        assertEquals(1, store.history(owner).size());
        assertFalse(store.recordHistory(null, "Hunter", entry(2)));
        assertFalse(store.recordHistory(owner, "Hunter", null));
    }
    @Test void emptyReadDoesNotCreateProfileOrDirtyState() {
        var store = new HuntStore(dir, Logger.getAnonymousLogger()); UUID owner = UUID.randomUUID();
        assertTrue(store.history(owner).isEmpty()); assertNull(store.player(owner)); assertFalse(store.isDirty());
    }
    @Test void snapshotAndReloadPreserveHistoryWalletAndHud() throws Exception {
        var store = new HuntStore(dir, Logger.getAnonymousLogger()); UUID owner = UUID.randomUUID();
        store.addCoins(owner, "Hunter", 123); store.addShards(owner, "Hunter", "undead", 7);
        store.registerKill(owner, "Hunter", "undead", 1_800_000_000_000L);
        store.mobHud(owner, "Hunter", false);
        store.recordHistory(owner, "Hunter", entry(1)); store.recordHistory(owner, "Hunter", entry(2));
        String snapshot = store.snapshotJson(); store.recordHistory(owner, "Hunter", entry(3));
        Files.writeString(store.file(), snapshot);
        var loaded = new HuntStore(dir, Logger.getAnonymousLogger()); loaded.load();
        assertEquals(List.of(entry(2), entry(1)), loaded.history(owner));
        assertEquals(123, loaded.player(owner).coins()); assertEquals(7, loaded.player(owner).shards("undead"));
        assertEquals(1, loaded.killsTotal(owner)); assertFalse(loaded.mobHud(owner)); assertFalse(loaded.isDirty());
    }
    @Test void legacyDataWithoutHistoryRemainsValidAndResetClearsJournal() throws Exception {
        UUID owner = UUID.randomUUID();
        Files.writeString(dir.resolve("hunt.json"), "{\"version\":1,\"players\":{\""+owner+"\":{\"name\":\"Hunter\",\"coins\":44,\"kills-total\":3}}}");
        var store = new HuntStore(dir, Logger.getAnonymousLogger()); store.load();
        assertTrue(store.history(owner).isEmpty()); assertEquals(44, store.player(owner).coins());
        assertEquals(3, store.killsTotal(owner)); store.recordHistory(owner, "Hunter", entry(1));
        assertTrue(store.reset(owner)); assertTrue(store.history(owner).isEmpty());
    }
    @Test void malformedRowsDoNotDamageWalletAndLoaderOnlyReadsTail() throws Exception {
        UUID owner = UUID.randomUUID();
        List<Object> rows = new ArrayList<>(HuntHistory.write(List.of(entry(1))));
        rows.add(Map.of("victim", "bad", "time", 12)); rows.add("bad");
        Map<String, Object> invalid = new HashMap<>(HuntHistory.write(List.of(entry(2))).getFirst());
        invalid.put("level", 1.5); rows.add(invalid);
        Map<String, Object> player = Map.of("name", "Hunter", "coins", 42, "history", rows);
        Files.writeString(dir.resolve("hunt.json"), new Gson().toJson(Map.of("version", 1, "players", Map.of(owner.toString(), player))));
        var store = new HuntStore(dir, Logger.getAnonymousLogger()); store.load();
        assertEquals(List.of(entry(1)), store.history(owner)); assertEquals(42, store.player(owner).coins());
        List<Object> large = new ArrayList<>();
        for (int i = 1; i <= 100; i++) large.add(HuntHistory.write(List.of(entry(i))).getFirst());
        var tail = HuntHistory.read(large); assertEquals(20, tail.size()); assertEquals(entry(81), tail.getFirst());
        assertTrue(HuntHistory.read("bad").isEmpty());
    }
    @Test void strictNumbersUuidTimestampAndTextRejectPoisonedRows() {
        Map<String, Object> valid = HuntHistory.write(List.of(entry(1))).getFirst();
        for (Object value : new Object[]{Double.NaN, Double.POSITIVE_INFINITY, -1, 1.5, Long.MAX_VALUE, "5"}) {
            var row = new HashMap<>(valid); row.put("time", value); assertTrue(HuntHistory.read(List.of(row)).isEmpty());
        }
        for (String field : List.of("level", "elite")) {
            var row = new HashMap<>(valid); row.put(field, 999); assertTrue(HuntHistory.read(List.of(row)).isEmpty());
        }
        for (var mutation : List.of(Map.entry("victim", "1-1-1-1-1"), Map.entry("type", "<red>"), Map.entry("world", "bad\nworld"))) {
            var row = new HashMap<>(valid); row.put(mutation.getKey(), mutation.getValue());
            assertTrue(HuntHistory.read(List.of(row)).isEmpty());
        }
        var duplicate = new ArrayList<Object>(); duplicate.add(valid); duplicate.add(valid);
        assertEquals(1, HuntHistory.read(duplicate).size());
    }
    @Test void paginationIsStrictBoundedAndReadOnly() {
        assertEquals(1, HuntHistory.page(null, 0)); assertEquals(1, HuntHistory.pages(0));
        assertEquals(4, HuntHistory.page("4", 20)); assertEquals(2, HuntHistory.pages(6));
        for (String bad : List.of("0", "-1", "01", "5", "999999999999999", "Hunter", "1.5", ""))
            assertEquals(0, HuntHistory.page(bad, 20));
        assertEquals(0, HuntHistory.page("2", 5));
    }
    @Test void commandChecksAuthAndOnlyReadsOwnJournal() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkhunt/MobReport.java"));
        assertTrue(source.indexOf("isAuthenticated(player)") < source.indexOf("return history(player, args)"));
        assertTrue(source.indexOf("hasPermission(\"chrdkhunt.use\")") < source.indexOf("return history(player, args)"));
        String command = source.substring(source.indexOf("private boolean history("), source.indexOf("private static final java.time"));
        assertTrue(command.contains("history(player.getUniqueId())"));
        assertTrue(command.contains("args.length <= 2")); assertTrue(command.contains("HuntHistory.page("));
        assertFalse(command.contains("recordHistory(")); assertFalse(command.contains("ensure("));
        assertFalse(command.contains("requestSave(")); assertFalse(command.contains("getPlayerExact("));
    }
}
