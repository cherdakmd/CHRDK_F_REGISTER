package ru.cherdakmd.chrdkfishing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FishAlbumTest {
    @TempDir Path dir;
    final byte[] key = new byte[32];
    final UUID owner = UUID.randomUUID();
    Path path() { return dir.resolve("album.db"); }
    FishToken.Token fish(int length, FishingModel.Size size) {
        return FishToken.issue(key, UUID.randomUUID(), new FishingModel.Catch("pike", size, length), 70);
    }
    @Test void idempotentCatchRecordsAndPersonalBestsPersist() throws Exception {
        var small = fish(20, FishingModel.Size.SMALL); var big = fish(100, FishingModel.Size.TROPHY);
        try (var album = new FishAlbum(path(), true, key)) {
            assertEquals(FishAlbum.Result.RECORDED, album.record(owner, small, 100));
            assertEquals(FishAlbum.Result.ALREADY_RECORDED, album.record(owner, small, 101));
            assertEquals(FishAlbum.Result.CONFLICT, album.record(UUID.randomUUID(), small, 102));
            assertEquals(FishAlbum.Result.RECORDED, album.record(owner, big, 103));
            assertEquals(new FishAlbum.Record(2, 100, FishingModel.Size.TROPHY), album.records(owner).get("pike"));
        }
        try (var album = new FishAlbum(path(), false, key)) {
            assertEquals(new FishAlbum.Record(2, 100, FishingModel.Size.TROPHY), album.records(owner).get("pike"));
            assertTrue(album.records(UUID.randomUUID()).isEmpty());
        }
    }
    @Test void forgedOrAlteredRecordsNeverDisappearSilently() throws Exception {
        var valid = fish(40, FishingModel.Size.NORMAL);
        try (var album = new FishAlbum(path(), true, key)) {
            var forged = new FishToken.Token(valid.id(), valid.fish(), 500, valid.mac());
            assertEquals(FishAlbum.Result.CONFLICT, album.record(owner, forged, 100));
            assertTrue(album.records(owner).isEmpty());
            assertEquals(FishAlbum.Result.RECORDED, album.record(owner, valid, 100));
        }
        try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path()); var statement = db.createStatement()) {
            statement.execute("UPDATE catches SET quoted_price=999");
        }
        try (var album = new FishAlbum(path(), false, key)) {
            assertThrows(java.sql.SQLException.class, () -> album.records(owner));
            assertThrows(java.sql.SQLException.class, () -> album.record(owner, valid, 100));
        }
    }
    @Test void absentOrInvalidExistingAlbumIsNeverRecreated() throws Exception {
        assertThrows(java.sql.SQLException.class, () -> new FishAlbum(path(), false, key));
        Files.writeString(path(), "broken");
        assertThrows(java.sql.SQLException.class, () -> new FishAlbum(path(), true, key));
        assertEquals("broken", Files.readString(path()));
    }
    @Test void wrongKeyDoesNotReadOldRecords() throws Exception {
        try (var album = new FishAlbum(path(), true, key)) { album.record(owner, fish(40, FishingModel.Size.NORMAL), 1); }
        var wrong = key.clone(); wrong[0] = 42;
        try (var album = new FishAlbum(path(), false, wrong)) {
            assertThrows(java.sql.SQLException.class, () -> album.records(owner));
        }
    }
}
