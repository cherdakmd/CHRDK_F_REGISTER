package ru.cherdakmd.chrdkclaims;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class ClaimRentalsTest {
    @TempDir Path dir;
    private final long now = System.currentTimeMillis();
    private ClaimStore store() { return new ClaimStore(dir, Logger.getAnonymousLogger()); }
    private Claim claim(ClaimStore store) {
        var c = new Claim("world", 0, 0, "Owner", now);
        c.members.add("Friend"); store.add(c); return c;
    }
    private void lease(ClaimStore s, Claim c, int price, int serverRent) {
        assertNull(ClaimRentals.offer(s, c, "Owner", price, now));
        assertNull(ClaimRentals.take(s, c, "Tenant", c.rentOffer, now, serverRent));
    }
    @Test void paymentComesOnlyFromTreasuryAndPrepayCoversServerUpkeep() {
        var s = store(); var c = claim(s); s.addTreasury("Tenant", 30); lease(s, c, 10, 2);
        assertEquals(20, s.treasuryOf("Tenant")); assertEquals(8, s.treasuryOf("Owner"));
        assertEquals(now + ClaimMath.DAY_MILLIS, c.rentUntil);
        assertTrue(c.paidUntil >= c.rentUntil); assertEquals(0, c.debtDays);
        assertEquals(Claim.Role.BUILD, c.roleAt("Tenant", now));
        assertEquals(Claim.Role.BUILD, c.roleAt("Owner", now)); assertNull(c.roleAt("Friend", now));
        assertTrue(c.isOwner("Owner")); assertFalse(c.isOwner("Tenant"));
    }
    @Test void tenantAccessIsOnlyForLeasedChunkAndExpiresAtExactBoundary() {
        var s = store(); var c = claim(s); var b = new Claim("world", 1, 0, "Owner", now); s.add(b);
        s.addTreasury("Tenant", 10); lease(s, c, 10, 0);
        assertNull(b.roleAt("Tenant", now)); assertEquals(Claim.Role.BUILD, b.roleAt("Friend", now));
        assertNull(c.roleAt("Tenant", c.rentUntil)); assertEquals(Claim.Role.BUILD, c.roleAt("Friend", c.rentUntil));
    }
    @Test void automationDomainsSeparateLeaseFromRestOfSameOwnersTerritory() {
        var s = store(); var c = claim(s); var b = new Claim("world", 1, 0, "Owner", now); s.add(b);
        assertEquals(c.accessDomain(now), b.accessDomain(now));
        s.addTreasury("Tenant", 10); lease(s, c, 10, 0);
        assertNotEquals(c.accessDomain(now), b.accessDomain(now));
        assertEquals(c.accessDomain(c.rentUntil), b.accessDomain(c.rentUntil));
    }
    @Test void insufficientTenantOrOwnerFundsNeverMutateBalancesOrContract() {
        var s = store(); var c = claim(s); assertNull(ClaimRentals.offer(s, c, "Owner", 10, now));
        assertEquals("rent-funds", ClaimRentals.take(s, c, "Tenant", c.rentOffer, now, 0));
        s.addTreasury("Tenant", 10);
        assertEquals("rent-owner-funds", ClaimRentals.take(s, c, "Tenant", c.rentOffer, now, 12));
        assertEquals(10, s.treasuryOf("Tenant")); assertEquals(0, s.treasuryOf("Owner"));
        assertEquals("", c.tenant); assertEquals(now, c.paidUntil);
    }
    @Test void priorServerDebtMustBeCoveredBeforeTakingLease() {
        var s = store(); var c = claim(s); c.debtDays = 2;
        s.addTreasury("Tenant", 10); s.addTreasury("Owner", 26); lease(s, c, 10, 12);
        assertEquals(0, s.treasuryOf("Owner")); assertEquals(0, c.debtDays);
        assertTrue(c.paidUntil >= c.rentUntil);
    }
    @Test void renewalChargesExactlyOnceAndKeepsAgreedPriceDespiteNewOffer() {
        var s = store(); var c = claim(s); s.addTreasury("Tenant", 100); lease(s, c, 10, 0);
        assertNull(ClaimRentals.offer(s, c, "Owner", 90, now));
        long due = c.rentUntil;
        assertEquals(0, ClaimRentals.settle(s, c, due - 1, 0));
        assertEquals(1, ClaimRentals.settle(s, c, due, 0));
        assertEquals(0, ClaimRentals.settle(s, c, due, 0));
        assertEquals(80, s.treasuryOf("Tenant")); assertEquals(20, s.treasuryOf("Owner"));
        assertEquals(10, c.rentPrice);
    }
    @Test void ownerCannotEndPaidTimeAndOffOnlyStopsFutureRenewal() {
        var s = store(); var c = claim(s); s.addTreasury("Tenant", 20); lease(s, c, 10, 0);
        long until = c.rentUntil;
        assertNull(ClaimRentals.offer(s, c, "Owner", 0, now));
        assertTrue(c.rentedAt(now)); assertEquals(until, c.rentUntil);
        assertEquals(0, ClaimRentals.settle(s, c, until - 1, 0));
        assertEquals(-1, ClaimRentals.settle(s, c, until, 0));
        assertEquals(10, s.treasuryOf("Tenant")); assertEquals("", c.tenant);
    }
    @Test void renterStopAndInsufficientRenewalFundsEndWithoutDebtOrPartialCharge() {
        var s = store(); var c = claim(s); s.addTreasury("Tenant", 10); lease(s, c, 10, 0);
        assertEquals(-1, ClaimRentals.settle(s, c, c.rentUntil, 0)); assertEquals("", c.tenant);
        s.addTreasury("Tenant", 20); lease(s, c, 10, 0);
        assertEquals("rent-not-tenant", ClaimRentals.stop(s, c, "Other"));
        assertNull(ClaimRentals.stop(s, c, "tenant"));
        assertTrue(c.rentedAt(now)); assertEquals(-1, ClaimRentals.settle(s, c, c.rentUntil, 0));
        assertEquals(10, s.treasuryOf("Tenant"));
    }
    @Test void serverDowntimeIsNotChargedAsManyRentalDays() {
        var s = store(); var c = claim(s); s.addTreasury("Tenant", 100); lease(s, c, 10, 0);
        assertEquals(1, ClaimRentals.settle(s, c, now + 20 * ClaimMath.DAY_MILLIS, 0));
        assertEquals(80, s.treasuryOf("Tenant"));
        assertEquals(now + 21 * ClaimMath.DAY_MILLIS, c.rentUntil);
    }
    @Test void staleReferenceDuplicateTakeSelfAndForeignOfferAreRejected() {
        var s = store(); var c = claim(s); s.addTreasury("Tenant", 40);
        assertEquals("rent-not-owner", ClaimRentals.offer(s, c, "Tenant", 10, now));
        assertNull(ClaimRentals.offer(s, c, "Owner", 10, now));
        assertEquals("rent-price-changed", ClaimRentals.take(s, c, "Tenant", 9, now, 0));
        assertEquals(40, s.treasuryOf("Tenant"));
        assertEquals("rent-self", ClaimRentals.take(s, c, "owner", c.rentOffer, now, 0));
        lease(s, c, 10, 0);
        assertEquals("rent-busy", ClaimRentals.take(s, c, "Tenant", c.rentOffer, now, 0));
        var stale = new Claim("world", 0, 0, "Owner", now); stale.rentOffer = 10;
        assertEquals("rent-no-offer", ClaimRentals.take(s, stale, "Other", stale.rentOffer, now, 0));
        assertEquals(30, s.treasuryOf("Tenant"));
    }
    @Test void paidChunkCannotBeRemovedReplacedPurgedTransferredOrRestored() {
        var s = store(); var c = claim(s); s.addTreasury("Tenant", 10); lease(s, c, 10, 0);
        assertThrows(IllegalStateException.class, () -> s.remove(c));
        assertThrows(IllegalStateException.class, () -> s.removeWorld("world"));
        assertThrows(IllegalStateException.class, () -> s.add(new Claim("world", 0, 0, "Other", now)));
        assertThrows(IllegalStateException.class, () -> s.transferAll("Owner", "Other"));
        assertEquals(ClaimStore.RestoreResult.PROTECTED, s.restoreFrom("claims-data.json.bak"));
        assertEquals("Owner", c.owner); assertSame(c, s.claimAt("world", 0, 0));
    }
    @Test void rentalOfferContractMoneyAndConsentSurviveRestartWithoutSecondCharge() {
        var s = store(); var c = claim(s); s.addTreasury("Tenant", 40); lease(s, c, 10, 2); s.save();
        var loaded = store(); loaded.load(); var back = loaded.claimAt("world", 0, 0);
        assertEquals("Tenant", back.tenant); assertEquals(10, back.rentOffer); assertEquals(10, back.rentPrice);
        assertEquals(c.rentUntil, back.rentUntil); assertTrue(back.rentRenew);
        assertEquals(0, ClaimRentals.settle(loaded, back, now + 1, 2)); assertEquals(30, loaded.treasuryOf("Tenant"));
    }
    @Test void territoryExpansionKeepsLeaseAndNormalPermissionsIndependently() {
        var s = store(); var c = claim(s); s.addTreasury("Tenant", 10); lease(s, c, 10, 0);
        var b = new Claim("world", 1, 0, "Owner", now); s.add(b);
        assertEquals(Claim.Role.BUILD, b.memberRole("Friend")); assertNull(b.roleAt("Tenant", now));
        assertEquals("Tenant", c.tenant); assertEquals(2, s.territory(c).size());
    }
    @Test void saleDisabledWorldAndTreasuryCeilingAreNotPaymentBypasses() {
        var s = store(); var c = claim(s); c.salePrice = 50;
        assertEquals("rent-sale", ClaimRentals.offer(s, c, "Owner", 10, now));
        c.salePrice = 0; s.addTreasury("Tenant", 40); lease(s, c, 10, 0);
        assertEquals(-1, ClaimRentals.settle(s, c, c.rentUntil, -1));
        s.addTreasury("Owner", Integer.MAX_VALUE);
        assertEquals("rent-owner-funds", ClaimRentals.take(s, c, "Tenant", c.rentOffer, now, 0));
        assertEquals(30, s.treasuryOf("Tenant"));
    }
    @Test void nativeIntegrationGuardsAuthTransfersAutomationsAndScheduledBilling() throws Exception {
        String service = source("ClaimsService"), commands = source("ClaimsCommandHandler");
        assertTrue(service.contains("if (!isAllowed(player))"));
        assertTrue(commands.contains("if (!ready(player)) return;"));
        assertTrue(service.contains("+ region.size() > limit"));
        assertTrue(service.contains("foreignNearby")); assertTrue(service.contains("rentalLocked"));
        assertTrue(source("ClaimProtectionListener").contains("accessDomain(now)"));
        assertTrue(source("ClaimTeleportService").contains("claim.canUse(player.getName())"));
        assertTrue(source("ClaimsPlugin").contains("service.processRentals"));
        assertTrue(source("ClaimsPlugin").contains("rentalTask.cancel()"));
    }
    private String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkclaims/" + name + ".java"));
    }
}
