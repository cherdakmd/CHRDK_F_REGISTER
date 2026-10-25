package ru.cherdakmd.chrdkclaims;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Роли участников привата: уровни доступа, наследование и очистка. */
class ClaimRoleTest {

    private static Claim claim() {
        Claim claim = new Claim("world", 0, 0, "Owner", 1);
        claim.members.add("Builder");
        claim.members.add("Container");
        claim.members.add("Guest");
        claim.setRole("Container", Claim.Role.CONTAINER);
        claim.setRole("Guest", Claim.Role.ACCESS);
        return claim;
    }

    @Test
    void ownerAlwaysHasFullAccess() {
        Claim claim = claim();
        assertEquals(Claim.Role.BUILD, claim.roleOf("Owner"));
        assertTrue(claim.canBuild("Owner"));
        assertTrue(claim.canOpen("Owner"));
        assertTrue(claim.canUse("Owner"));
    }

    @Test
    void memberWithoutExplicitRoleKeepsFullAccess() {
        Claim claim = claim();
        assertEquals(Claim.Role.BUILD, claim.roleOf("Builder"), "старые участники не теряют доступ");
        assertTrue(claim.canBuild("Builder"));
    }

    @Test
    void rolesAreOrderedAccessContainerBuild() {
        Claim claim = claim();
        assertEquals(Claim.Role.CONTAINER, claim.roleOf("Container"));
        assertFalse(claim.canBuild("Container"));
        assertTrue(claim.canOpen("Container"));
        assertTrue(claim.canUse("Container"));

        assertEquals(Claim.Role.ACCESS, claim.roleOf("Guest"));
        assertFalse(claim.canBuild("Guest"));
        assertFalse(claim.canOpen("Guest"));
        assertTrue(claim.canUse("Guest"));
    }

    @Test
    void strangersHaveNoRole() {
        Claim claim = claim();
        assertNull(claim.roleOf("Stranger"));
        assertFalse(claim.canUse("Stranger"));
        assertFalse(claim.canOpen("Stranger"));
        assertFalse(claim.canBuild("Stranger"));
        assertNull(claim.roleOf(null));
    }

    @Test
    void roleLookupIgnoresCase() {
        Claim claim = claim();
        assertEquals(Claim.Role.ACCESS, claim.roleOf("gUeSt"));
        assertEquals(Claim.Role.CONTAINER, claim.roleOf("CONTAINER"));
    }

    @Test
    void settingBuildDropsExplicitRole() {
        Claim claim = claim();
        claim.setRole("Guest", Claim.Role.BUILD);
        assertEquals(Claim.Role.BUILD, claim.roleOf("Guest"));
        assertTrue(claim.explicitRoles().keySet().stream().noneMatch("guest"::equals),
                "полный доступ не хранится отдельно");
    }

    @Test
    void rolesCannotBeGivenToStrangers() {
        Claim claim = claim();
        claim.setRole("Stranger", Claim.Role.ACCESS);
        assertNull(claim.roleOf("Stranger"));
        claim.applyRole("Stranger", Claim.Role.ACCESS);
        assertNull(claim.roleOf("Stranger"));
    }

    @Test
    void removingMemberRemovesRole() {
        Claim claim = claim();
        assertTrue(claim.removeMember("Guest"));
        assertFalse(claim.isMember("Guest"));
        assertNull(claim.roleOf("Guest"));
        assertFalse(claim.removeMember("Guest"), "повторное удаление ничего не делает");
    }

    @Test
    void clearMembersWipesRolesToo() {
        Claim claim = claim();
        claim.clearMembers();
        assertTrue(claim.members.isEmpty());
        assertTrue(claim.explicitRoles().isEmpty());
        assertNull(claim.roleOf("Guest"));
    }

    @Test
    void explicitRolesContainOnlyNonBuildEntries() {
        Claim claim = claim();
        assertEquals(2, claim.explicitRoles().size());
        assertEquals(Claim.Role.ACCESS, claim.explicitRoles().get("guest"));
        assertEquals(Claim.Role.CONTAINER, claim.explicitRoles().get("container"));
    }
}
