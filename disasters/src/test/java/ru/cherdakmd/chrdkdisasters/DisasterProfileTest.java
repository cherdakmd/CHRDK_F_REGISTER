package ru.cherdakmd.chrdkdisasters;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DisasterProfileTest {

    @Test
    void dailyEarningsAreUnlimitedAndStatisticsResetNextMoscowDay() {
        DisasterProfile profile = new DisasterProfile();
        assertEquals(10, profile.earn(10, 120, "2026-11-07"));
        assertEquals(200, profile.earn(200, 120, "2026-11-07"));
        assertEquals(5, profile.earn(5, 0, "2026-11-07"));
        assertEquals(215, profile.shards());
        assertEquals(215, profile.dailyEarned());
        assertEquals(7, profile.earn(7, 120, "2026-11-08"));
        assertEquals(7, profile.dailyEarned());
        assertEquals(222, profile.shards());
        assertEquals(222, profile.earned());
    }

    @Test void technicalOverflowDoesNotWrapCurrency() {
        DisasterProfile profile = DisasterProfile.fromMap(Map.of("shards", Long.MAX_VALUE - 2, "earned", Long.MAX_VALUE - 2));
        assertEquals(2, profile.earn(10, 0, "today"));
        assertEquals(Long.MAX_VALUE, profile.shards());
        assertEquals(0, profile.earn(1, 0, "today"));
    }

    @Test
    void shopSpendsAndRefundsShards() {
        DisasterProfile profile = new DisasterProfile();
        profile.earn(50, 120, "2026-11-07");
        assertFalse(profile.spend(51));
        assertTrue(profile.spend(20));
        assertEquals(30, profile.shards());
        assertEquals(20, profile.spent());
        profile.refund(20);
        assertEquals(50, profile.shards());
        assertEquals(0, profile.spent());
        assertFalse(profile.spend(0));
    }

    @Test
    void kindsAreCountedOnceAndLowercased() {
        DisasterProfile profile = new DisasterProfile();
        assertTrue(profile.addKind("TORNADO"));
        assertFalse(profile.addKind("tornado"));
        assertTrue(profile.addKind("flood"));
        assertFalse(profile.addKind(null));
        assertEquals(2, profile.kindCount());
        assertTrue(profile.kinds().contains("tornado"));
    }

    @Test
    void wardExpiresAndExperienceAccumulates() {
        DisasterProfile profile = new DisasterProfile();
        profile.grantWard(10_000L, 30_000L);
        assertTrue(profile.isWardActive(20_000L));
        assertFalse(profile.isWardActive(40_000L));
        profile.addXp(100);
        profile.addXp(-50);
        assertEquals(100, profile.xp());
    }

    @Test
    void jsonSnapshotRoundTripKeepsProgress() {
        DisasterProfile profile = new DisasterProfile();
        profile.earn(42, 120, "2026-11-07");
        profile.addXp(350);
        profile.addKind("blizzard");
        profile.grantWard(1_000L, 60_000L);
        profile.spend(12);

        Map<String, Object> raw = profile.toMap();
        DisasterProfile restored = DisasterProfile.fromMap(raw);
        assertEquals(profile.shards(), restored.shards());
        assertEquals(profile.earned(), restored.earned());
        assertEquals(profile.spent(), restored.spent());
        assertEquals(profile.xp(), restored.xp());
        assertEquals(profile.day(), restored.day());
        assertEquals(profile.wardUntil(), restored.wardUntil());
        assertEquals(profile.kinds(), restored.kinds());

        Map<String, DisasterProfile> players = new LinkedHashMap<>();
        players.put(DisasterProgressStore.key(
                java.util.UUID.fromString("11111111-2222-3333-4444-555555555555")), profile);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        root.put("players", DisasterProfile.writePlayers(players));
        Map<String, DisasterProfile> reread = DisasterProfile.readPlayers(root.get("players"));
        assertEquals(1, reread.size());
        assertEquals(42 + 0 - 12, reread.values().iterator().next().shards());
        assertEquals(1, reread.values().iterator().next().kindCount());
    }

    @Test
    void gsonLikeDoubleNumbersAreReadAsLongs() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("shards", 12.0);
        raw.put("xp", 300.0);
        raw.put("day", "2026-11-07");
        raw.put("kinds", java.util.List.of("tornado", "flood"));
        DisasterProfile profile = DisasterProfile.fromMap(raw);
        assertEquals(12, profile.shards());
        assertEquals(300, profile.xp());
        assertEquals(2, profile.kindCount());
        assertEquals(0, DisasterProfile.fromMap(null).shards());
    }
}
