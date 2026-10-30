package ru.cherdakmd.chrdkclaims;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.ClanMembershipIndex;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Производный доступ, геометрия, аренда и неизменность личных приглашений без Bukkit. */
class ClaimClanSharingTest {
    @TempDir Path dir;
    private final Map<String, ClanMembershipIndex.Membership> members = new HashMap<>();
    private final ClanMembershipIndex.Membership clan = new ClanMembershipIndex.Membership("stable-1", "Первый", "ONE", "member");
    Claim claim(int x) {
        Claim c = new Claim("world", x, 0, "Owner", 1_792_000_000_000L);
        members.put("Owner", clan); members.put("Guest", clan);
        c.membershipIndex(members::get); return c;
    }
    ClaimStore store() { return new ClaimStore(dir, Logger.getAnonymousLogger()); }
    @Test void membershipAloneNeverGrantsLandAndAllThreeRolesAreExact() {
        var c=claim(0); assertNull(c.roleOf("Guest"));
        for (var role:Claim.Role.values()) {
            c.shareClan(clan.id(),role); assertEquals(role,c.roleOf("Guest"));
            assertEquals(role==Claim.Role.BUILD,c.canBuild("Guest"));
            assertEquals(role!=Claim.Role.ACCESS,c.canOpen("Guest"));
            assertTrue(c.canUse("Guest")); assertEquals(Claim.Role.BUILD,c.roleOf("Owner"));
        }
    }
    @Test void exitKickOwnerMoveDissolutionAndNewClanWithSameNameRevokeImmediately() {
        var c=claim(0); c.shareClan(clan.id(),Claim.Role.BUILD); assertTrue(c.canBuild("Guest"));
        members.remove("Guest"); assertNull(c.roleOf("Guest")); members.put("Guest",clan);
        members.put("Owner",new ClanMembershipIndex.Membership("new-id",clan.name(),clan.tag(),"leader"));
        assertNull(c.roleOf("Guest")); members.clear(); assertNull(c.roleOf("Guest"));
    }
    @Test void personalRoleWinsAndSurvivesMissingProviderAndTurningSharingOff() {
        var c=claim(0); c.members.add("Guest"); c.setRole("Guest",Claim.Role.ACCESS); c.shareClan(clan.id(),Claim.Role.BUILD);
        assertEquals(Claim.Role.ACCESS,c.roleOf("Guest")); c.membershipIndex(null); assertEquals(Claim.Role.ACCESS,c.roleOf("Guest"));
        c.clearClanSharing(); assertEquals(List.of("Guest"),c.members); assertEquals(Claim.Role.ACCESS,c.roleOf("Guest"));
    }
    @Test void providerExceptionsNeverOpenLand() {
        var c=claim(0); c.shareClan(clan.id(),Claim.Role.BUILD);
        c.membershipIndex(name->{throw new IllegalStateException("offline");}); assertNull(c.roleOf("Guest"));
        c.membershipIndex(name->{throw new NoClassDefFoundError("unloaded");}); assertNull(c.roleOf("Guest"));
    }
    @Test void officerAndLeaderHaveOnlyOwnersChosenRole() {
        var c=claim(0); c.shareClan(clan.id(),Claim.Role.ACCESS);
        members.put("Guest",new ClanMembershipIndex.Membership(clan.id(),"Renamed","NEW","leader"));
        assertEquals(Claim.Role.ACCESS,c.roleOf("Guest")); assertFalse(c.isMember("Guest"));
    }
    @Test void paidRentalBlocksClanAndPersonalButPreservesOwnerAndTenant() {
        var c=claim(0); c.shareClan(clan.id(),Claim.Role.BUILD); c.members.add("Guest");
        c.tenant="Tenant"; c.rentUntil=2_000;
        assertNull(c.roleAt("Guest",1_000)); assertEquals(Claim.Role.BUILD,c.roleAt("Tenant",1_000));
        assertEquals(Claim.Role.BUILD,c.roleAt("Owner",1_000)); assertEquals(Claim.Role.BUILD,c.roleAt("Guest",2_000));
        c.clearClanSharing(); assertEquals(2_000,c.rentUntil); assertEquals("Tenant",c.tenant);
    }
    @Test void actualOwnerChangeAndTransferResetBindingWithoutErasingPersonalInvitations() {
        var s=store(); var c=claim(0); c.members.add("Friend"); c.shareClan(clan.id(),Claim.Role.BUILD); s.add(c);
        s.transferAll("Owner","NewOwner"); assertEquals("",c.clanId()); assertTrue(c.isMember("Friend"));
        c.shareClan(clan.id(),Claim.Role.BUILD); c.owner="Different"; assertNull(c.roleOf("Guest"));
        c.clearMembers(); assertEquals("",c.clanId());
    }
    @Test void settingIsOwnerOnlyAndAppliesToWholeConnectedTerritoryNotOtherLand() {
        var s=store(); s.add(claim(0)); s.add(claim(1)); s.add(claim(4));
        assertFalse(ClaimClanSharing.set(s,"world",0,0,"Guest",clan.id(),Claim.Role.BUILD));
        assertTrue(ClaimClanSharing.set(s,"world",0,0,"Owner",clan.id(),Claim.Role.CONTAINER));
        assertEquals(clan.id(),s.claimAt("world",1,0).clanId()); assertEquals("",s.claimAt("world",4,0).clanId());
        assertTrue(ClaimClanSharing.set(s,"world",1,0,"Owner","",null)); assertEquals("",s.claimAt("world",0,0).clanId());
    }
    @Test void mergeOfConflictingBindingsDisablesClanInsteadOfEscalating() {
        var a=claim(0); var b=claim(1); a.shareClan(clan.id(),Claim.Role.ACCESS); b.shareClan(clan.id(),Claim.Role.BUILD);
        ClaimTerritories.normalize(List.of(a,b),List.of(a,b),true); assertEquals("",a.clanId()); assertEquals("",b.clanId());
        a.shareClan(clan.id(),Claim.Role.ACCESS); b.shareClan(clan.id(),Claim.Role.ACCESS);
        ClaimTerritories.normalize(List.of(a,b),List.of(a,b),false); assertEquals(clan.id(),b.clanId());
    }
    @Test void optionalFieldsRoundTripAndLegacyDomainRemainsByteExact() throws Exception {
        var s=store(); s.add(claim(0)); String old=s.domainJson(); assertFalse(old.contains("\"cg\""));
        s.save(); var read=store(); read.load(); assertEquals(old,read.domainJson());
        s.claimAt("world",0,0).shareClan(clan.id(),Claim.Role.CONTAINER); s.markDirty(); s.save();
        read=store(); read.load(); assertTrue(read.moneyAvailable()); var c=read.claimAt("world",0,0);
        assertEquals(clan.id(),c.clanId()); assertEquals(Claim.Role.CONTAINER,c.clanRole()); assertNull(c.roleOf("Guest"));
        read.membershipIndex(members::get); assertEquals(Claim.Role.CONTAINER,c.roleOf("Guest"));
        assertEquals(s.domainJson(),read.domainJson());
    }
    @Test void accessibleMenuRechecksMembershipAndSkipsLeasedRepresentative() {
        var a=claim(0); var b=claim(1); a.shareClan(clan.id(),Claim.Role.BUILD); b.shareClan(clan.id(),Claim.Role.BUILD);
        a.tenant="Tenant"; a.rentUntil=Long.MAX_VALUE;
        var list=ClaimMenuModel.view(List.of(a,b),"Guest",ClaimMenuModel.Tab.ACCESSIBLE);
        assertEquals(1,list.size()); assertEquals(1,list.getFirst().x()); assertFalse(list.getFirst().mine());
        members.remove("Guest"); assertTrue(ClaimMenuModel.view(List.of(a,b),"Guest",ClaimMenuModel.Tab.ACCESSIBLE).isEmpty());
    }
    @Test void malformedBindingFailsClosedAndOriginalIsNeverOverwritten() throws Exception {
        var s=store(); var c=claim(0); c.shareClan(clan.id(),Claim.Role.BUILD); s.add(c); s.save();
        var file=dir.resolve("claims-data.json"); String bad=Files.readString(file).replace("\"cgr\": \"BUILD\"","\"cgr\": \"ADMIN\"");
        Files.writeString(file,bad); var read=store(); read.load(); assertFalse(read.moneyAvailable()); read.save(); assertEquals(bad,Files.readString(file));
    }
}
