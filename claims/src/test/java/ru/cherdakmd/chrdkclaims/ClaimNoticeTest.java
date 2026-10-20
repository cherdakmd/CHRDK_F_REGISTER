package ru.cherdakmd.chrdkclaims;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ключи уведомлений о границах приватов — без Bukkit. */
class ClaimNoticeTest {

    private static ClaimNotice.Spot spot(String owner, int salePrice, Claim.Role role,
                                         String greeting) {
        return new ClaimNotice.Spot("world", 3, -7, owner, salePrice, role, 1_700_000_000_000L,
                greeting);
    }

    @Test
    void ownerWinsOverMembership() {
        ClaimNotice.Spot spot = spot("Steve", 0, Claim.Role.BUILD, "");
        assertEquals(ClaimNotice.Audience.OWNER, spot.audience("steve"));
        assertEquals(ClaimNotice.Audience.OWNER, spot.audience("STEVE"));
        assertEquals(ClaimNotice.Audience.MEMBER, spot.audience("Alex"));
        assertEquals(ClaimNotice.Audience.VISITOR, spot.audience("Herobrine"));
    }

    @Test
    void memberIsDetectedByRole() {
        ClaimNotice.Spot spot = spot("Steve", 0, Claim.Role.ACCESS, "");
        assertEquals(ClaimNotice.Audience.MEMBER, spot.audience("Alex"));
        assertEquals(ClaimNotice.Audience.MEMBER, spot.audience(null));
        assertTrue(spot.audience("Alex") != ClaimNotice.Audience.OWNER);
    }

    @Test
    void enterKeysCoverAllAudiences() {
        assertEquals("enter-own", ClaimNotice.key(ClaimNotice.Kind.ENTER,
                ClaimNotice.Audience.OWNER, false));
        assertEquals("enter-member", ClaimNotice.key(ClaimNotice.Kind.ENTER,
                ClaimNotice.Audience.MEMBER, false));
        assertEquals("enter-foreign", ClaimNotice.key(ClaimNotice.Kind.ENTER,
                ClaimNotice.Audience.VISITOR, false));
        assertEquals("enter-for-sale", ClaimNotice.key(ClaimNotice.Kind.ENTER,
                ClaimNotice.Audience.VISITOR, true));
    }

    @Test
    void leaveKeysMirrorEnterAndKeepOwnerPriceHidden() {
        assertEquals("leave-own", ClaimNotice.key(ClaimNotice.Kind.LEAVE,
                ClaimNotice.Audience.OWNER, true));
        assertEquals("leave-member", ClaimNotice.key(ClaimNotice.Kind.LEAVE,
                ClaimNotice.Audience.MEMBER, true));
        assertEquals("leave-foreign", ClaimNotice.key(ClaimNotice.Kind.LEAVE,
                ClaimNotice.Audience.VISITOR, false));
        assertEquals("leave-for-sale", ClaimNotice.key(ClaimNotice.Kind.LEAVE,
                ClaimNotice.Audience.VISITOR, true));
    }

    @Test
    void spotSanitizesNullsAndDetectsSale() {
        ClaimNotice.Spot spot = new ClaimNotice.Spot(null, 0, 0, null, 0, null, 0L, null);
        assertEquals("", spot.world());
        assertEquals("", spot.owner());
        assertEquals("", spot.greeting());
        assertFalse(spot.forSale());
        assertFalse(spot.ownedBy(null));
        assertFalse(spot.ownedBy(""));
        assertEquals(ClaimNotice.Audience.VISITOR, spot.audience("Steve"));
        assertTrue(spot("Steve", 16, null, "").forSale());
    }

    @Test
    void whereIsStableAndCaseInsensitive() {
        ClaimNotice.Spot lower = new ClaimNotice.Spot("world", 3, -7, "Steve", 0, null, 0L, "");
        ClaimNotice.Spot upper = new ClaimNotice.Spot("WORLD", 3, -7, "Alex", 9, null, 0L, "");
        assertEquals(lower.where(), upper.where());
        assertEquals("world:3:-7", lower.where());
    }
}
