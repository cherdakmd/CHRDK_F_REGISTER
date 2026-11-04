package ru.cherdakmd.chrdkclaims;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.HomeClaimPolicy;

import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.4.282: точка дома разрешена владельцу и действующему арендатору, но не гостям. */
class ClaimsHomePolicyTest {

    @TempDir Path dir;

    private ClaimStore store() {
        ClaimStore store = new ClaimStore(dir, Logger.getAnonymousLogger());
        store.load();
        return store;
    }

    private HomeClaimPolicy policy(ClaimStore store) {
        return new ClaimsHomePolicy(() -> store);
    }

    private static Claim claim(int chunkX, int chunkZ, String owner) {
        return new Claim("world", chunkX, chunkZ, owner, System.currentTimeMillis() + 3_600_000L);
    }

    @Test void freeChunkIsAlwaysAllowed() {
        var store = store();
        var policy = policy(store);
        assertTrue(policy.canUseHome("Steve", "world", 100, 100));
        assertTrue(policy.canUseHome("Steve", "another_world", 0, 0));
    }

    @Test void ownerAndActiveTenantAreAllowed() {
        var store = store();
        Claim claim = claim(1, 1, "Owner");
        claim.tenant = "Renter";
        claim.rentUntil = System.currentTimeMillis() + 60_000L;
        store.add(claim);
        var policy = policy(store);
        assertTrue(policy.canUseHome("owner", "world", 16, 16), "владелец, регистр ника не важен");
        assertTrue(policy.canUseHome("Renter", "world", 31, 31), "арендатор в другом конце того же чанка");
        assertFalse(policy.canUseHome("Stranger", "world", 16, 16));
    }

    @Test void membersAndBuildRoleAreStillGuests() {
        var store = store();
        Claim claim = claim(2, 2, "Owner");
        claim.members.add("Builder");
        claim.setRole("Builder", Claim.Role.BUILD);
        store.add(claim);
        var policy = policy(store);
        assertTrue(claim.canBuild("Builder"), "право стройки само по себе не делает приват своим");
        assertFalse(policy.canUseHome("Builder", "world", 32, 32));
        assertFalse(policy.canUseHome("OwnerGuest", "world", 32, 32));
    }

    @Test void expiredRentalFallsBackToOwner() {
        var store = store();
        Claim claim = claim(3, 3, "Owner");
        claim.tenant = "Renter";
        claim.rentUntil = System.currentTimeMillis() - 1L;
        store.add(claim);
        var policy = policy(store);
        assertFalse(policy.canUseHome("Renter", "world", 48, 48));
        assertTrue(policy.canUseHome("Owner", "world", 48, 48));
    }

    @Test void missingStoreOrNamesNeverBlockHomes() {
        assertTrue(policy(null).canUseHome("Steve", "world", 0, 0), "нет данных приватов — не запрещаем");
        var store = store();
        store.add(claim(0, 0, "Owner"));
        assertTrue(policy(store).canUseHome("Steve", null, 0, 0));
        assertTrue(policy(store).canUseHome(null, "world", 0, 0));
    }

    @Test void negativeBlockCoordinatesResolveTheRightChunk() {
        var store = store();
        store.add(claim(-1, -1, "Owner"));
        var policy = policy(store);
        assertFalse(policy.canUseHome("Stranger", "world", -1, -1));
        assertFalse(policy.canUseHome("Stranger", "world", -16, -16));
        assertTrue(policy.canUseHome("Stranger", "world", 0, 0));
    }
}
