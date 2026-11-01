package ru.cherdakmd.chrdkguilds;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GuildRecruitment280Test {
    @TempDir Path folder;
    final UUID leader=UUID.randomUUID(), officer=UUID.randomUUID(), member=UUID.randomUUID(), guest=UUID.randomUUID();
    final long now=1_800_000_000_000L;
    GuildStore store() {
        GuildStore s=new GuildStore(folder,Logger.getAnonymousLogger());
        Guild g=new Guild("alpha","Alpha","ALP",leader,now);
        g.addMember(officer,GuildRank.OFFICER);g.addMember(member,GuildRank.MEMBER);s.add(g);return s;
    }
    GuildService service(GuildStore s) { return new GuildService(GuildConfig.defaults(),s); }
    @Test void closedByDefaultOnlyAdministrationCanOpenAndSettingSurvivesCopy() {
        var s=store();var svc=service(s);assertFalse(s.guild("alpha").recruitmentOpen());
        assertFalse(svc.recruitment(member,true).ok());assertFalse(svc.recruitment(guest,true).ok());
        assertTrue(svc.recruitment(officer,true).ok());assertTrue(s.guild("alpha").recruitmentOpen());
        assertTrue(s.detached(s.snapshotJson()).guild("alpha").recruitmentOpen());
        assertTrue(svc.recruitment(leader,false).ok());assertEquals(2,s.guild("alpha").recruitmentRevision());
        assertFalse(svc.recruitment(leader,false).ok());assertEquals(2,s.guild("alpha").recruitmentRevision());
    }
    @Test void previewNeverSpendsOrCreatesAnInviteAndSuccessfulJoinPreservesDues() {
        var s=store();service(s).recruitment(leader,true);String before=s.snapshotJson();
        var plan=GuildFinance.preview(s,GuildConfig.defaults(),(svc,purse)->svc.joinOpen(purse,guest,"alpha",now));
        assertTrue(plan.result().ok());assertEquals(10,plan.debit());assertEquals(before,s.snapshotJson());assertNull(s.inviteOf(guest));
        var after=s.detached(plan.domain());assertTrue(after.guild("alpha").isMember(guest));
        assertEquals(10,after.guild("alpha").treasury());
        assertEquals(now+GuildPeaceTerm.DAY,after.guild("alpha").duesTerm(guest).dueAt());
        assertFalse(service(after).joinOpen(new GuildFinance.Quote(),guest,"alpha",now).ok());
    }
    @Test void closedJoinRequiresRealInviteAndExistingInviteWorksInClosedClan() {
        var s=store();assertEquals("recruit-closed",service(s).joinOpen(new GuildFinance.Quote(),guest,"alpha",now).key());
        assertNull(s.inviteOf(guest));assertNull(GuildJoinTerms.capture(s,guest,"alpha",now));
        assertTrue(service(s).invite(officer,guest,now).ok());var ticket=GuildJoinTerms.capture(s,guest,"alpha",now);
        assertNotNull(ticket);assertNotNull(ticket.invitation());
        assertTrue(service(s).accept(new GuildFinance.Quote(),guest,now).ok());
    }
    @Test void closingReopeningAndChangingFeeInvalidateConsentButUnrelatedTreasuryDoesNot() {
        var s=store();service(s).recruitment(leader,true);var t=GuildJoinTerms.capture(s,guest,"alpha",now);
        assertTrue(t.matches(s,guest,now));assertFalse(t.matches(s,UUID.randomUUID(),now));
        s.guild("alpha").deposit(20);assertTrue(t.matches(s,guest,now));
        service(s).recruitment(officer,false);service(s).recruitment(officer,true);assertFalse(t.matches(s,guest,now));
        t=GuildJoinTerms.capture(s,guest,"alpha",now);s.guild("alpha").duesRate(11,now+1);assertFalse(t.matches(s,guest,now+1));
    }
    @Test void expiredOrReplacedInviteAndRecreatedGuildCannotReuseConsent() {
        var s=store();service(s).invite(officer,guest,now);var t=GuildJoinTerms.capture(s,guest,"alpha",now);
        assertFalse(t.matches(s,guest,now+GuildConfig.defaults().inviteSeconds()*1000L));
        service(s).invite(officer,guest,now+1);assertFalse(t.matches(s,guest,now+1));
        s.remove(s.guild("alpha"));s.add(new Guild("beta","Alpha","ALP",leader,now+2));assertFalse(t.matches(s,guest,now+2));
    }
    @Test void peacefulJoinKeepsFeeWarmupDebtBrandAndCombatChecks() {
        var s=store();var g=s.guild("alpha");g.mode(GuildMode.PEACEFUL,now);g.recruitment(true);
        for(UUID id:g.memberIds())g.peaceTerm(id,new GuildPeaceTerm(now+GuildPeaceTerm.DAY,0,now));
        var plan=GuildFinance.preview(s,GuildConfig.defaults(),(svc,purse)->svc.joinOpen(purse,guest,"alpha",now));
        assertTrue(plan.result().ok());assertEquals(20,plan.debit());
        assertEquals(now+GuildPeaceService.WARMUP,s.detached(plan.domain()).guild("alpha").peaceTerm(guest).activatesAt());
        assertEquals(20,GuildJoinTerms.capture(s,guest,"alpha",now).fee());
        s.combatGuard(id->true);
        assertEquals("peace-combat",service(s).joinOpen(new GuildFinance.Quote(),guest,"alpha",now).key());
        s.combatGuard(id->false);s.sanction(guest,new GuildSanction(1,0));
        assertEquals("peace-debt-mode",service(s).joinOpen(new GuildFinance.Quote(),guest,"alpha",now).key());
        s.sanction(guest,new GuildSanction(0,0).brand(now,"test"));
        assertEquals("discipline-brand-block",service(s).joinOpen(new GuildFinance.Quote(),guest,"alpha",now).key());
    }
    @Test void invitationInOpenClanDoesNotFallBackToOpenAfterExpiryAndModeChangeNeedsNewConsent() {
        var s=store();var g=s.guild("alpha");g.recruitment(true);service(s).invite(officer,guest,now);
        var terms=GuildJoinTerms.capture(s,guest,"alpha",now);assertNotNull(terms.invitation());
        long expired=terms.invitation().expiresAt();
        assertNull(GuildJoinTerms.capture(s,guest,"alpha",expired).invitation());
        assertFalse(terms.matches(s,guest,expired));
        var denied=GuildFinance.preview(s,GuildConfig.defaults(),(svc,q)->GuildFinance.confirmedJoin(svc,q,guest,terms,expired+1,expired));
        assertEquals("recruit-changed",denied.result().key());assertEquals(0,denied.debit());
        g.mode(GuildMode.PEACEFUL,now+1);
        assertFalse(terms.matches(s,guest,now+1));
    }
    @Test void flagPairMustBeCompleteTypedAndIntegral() {
        String original=store().snapshotJson();
        for(int kind=0;kind<5;kind++) {
            var json=com.google.gson.JsonParser.parseString(original).getAsJsonObject();
            var guild=json.getAsJsonArray("guilds").get(0).getAsJsonObject();
            switch(kind) {
                case 0->guild.remove("recruitment-revision");
                case 1->guild.remove("recruitment-open");
                case 2->guild.addProperty("recruitment-revision",-1);
                case 3->guild.addProperty("recruitment-revision",0.5);
                case 4->guild.addProperty("recruitment-revision","1");
            }
            assertThrows(RuntimeException.class,()->store().detached(json.toString()));
        }
    }
    @Test void directorySortsOpenFirstAndThenByNameWithStableIds() {
        var s=store();var z=new Guild("z","Zeta","ZZZ",UUID.randomUUID(),now);z.recruitment(true);
        var b=new Guild("b","Beta","BBB",UUID.randomUUID(),now);b.recruitment(true);
        assertEquals(List.of("b","z","alpha"),GuildRecruitmentGui.ordered(List.of(s.guild("alpha"),z,b)).stream().map(Guild::id).toList());
    }
    @Test void fullClanInsufficientFundsAndNoPurseNeverJoin() {
        var s=store();var g=s.guild("alpha");g.recruitment(true);
        assertFalse(service(s).joinOpen(null,guest,"alpha",now).ok());
        GuildPurse poor=new GuildPurse(){public long take(UUID id,long amount){return 0;}public long give(UUID id,long amount){return 0;}};
        assertEquals("dues-bank",service(s).joinOpen(poor,guest,"alpha",now).key());assertFalse(g.isMember(guest));
        while(g.size()<GuildMath.memberCap(g.level()))g.addMember(UUID.randomUUID(),GuildRank.MEMBER);
        assertEquals("guild-full",service(s).joinOpen(new GuildFinance.Quote(),guest,"alpha",now).key());
    }
    @Test void oneWindowCanBeConsumedOnlyOnceAndDeadlineIsExclusive() {
        var s=store();s.guild("alpha").recruitment(true);var terms=GuildJoinTerms.capture(s,guest,"alpha",now);
        var holder=new GuildRecruitmentGui.Holder(guest,0,terms,now+30_000);
        assertTrue(holder.consume(now));assertFalse(holder.consume(now));
        assertFalse(new GuildRecruitmentGui.Holder(guest,0,terms,now+30_000).consume(now+30_000));
        assertEquals(28,Arrays.stream(GuildRecruitmentGui.SLOTS).distinct().count());
    }
    @Test void legacyAbsentFlagsAreClosedAndMalformedFlagFailsClosed() {
        var s=store();String old=s.snapshotJson().replaceAll("(?m)^\\s*\"recruitment-(open|revision)\": [^\\n]+\\n", "");
        assertFalse(s.detached(old).guild("alpha").recruitmentOpen());
        String invalid=s.snapshotJson().replace("\"recruitment-open\": false", "\"recruitment-open\": \"false\"");
        assertThrows(RuntimeException.class,()->s.detached(invalid));
    }
}
