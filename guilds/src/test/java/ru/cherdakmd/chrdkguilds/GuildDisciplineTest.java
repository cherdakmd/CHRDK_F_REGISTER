package ru.cherdakmd.chrdkguilds;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.PaidActionStore;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Чистый production-домен и настоящее durable-хранилище; не клиентский Bukkit smoke. */
class GuildDisciplineTest {
    @TempDir Path dir;
    final UUID leader=UUID.randomUUID(),member=UUID.randomUUID(),victim=UUID.randomUUID();
    final long now=1_900_000_000_000L,minute=60_000L;
    GuildStore store() {
        var s=new GuildStore(dir,Logger.getAnonymousLogger());
        var g=new Guild("one","Первый","ONE",leader,now);
        g.addMember(member,GuildRank.MEMBER);g.deposit(500);s.add(g);
        assertTrue(new GuildPeaceService(s).mode(new GuildFinance.Quote(),leader,GuildMode.PEACEFUL,now).ok());
        return s;
    }
    GuildResult incident(GuildStore s,UUID owner,int index) {
        return new GuildPeaceService(s).fine(owner,victim,UUID.randomUUID(),"melee",true,now+index*minute);
    }
    void incidents(GuildStore s,UUID owner,int count) { for(int i=0;i<count;i++)assertTrue(incident(s,owner,i).ok()); }
    @Test void tenthIncidentAtomicallyExpelsBrandsAndKeepsPersonalDebt() {
        var s=store();long treasury=s.of(leader).treasury();incidents(s,member,9);
        assertNotNull(s.of(member));assertEquals(9,s.sanction(member).violations(now+8*minute));
        assertEquals("discipline-expelled",incident(s,member,9).key());
        assertNull(s.of(member));assertTrue(s.sanction(member).branded(now+9*minute));
        assertEquals(now+9*minute+GuildSanction.BRAND,s.sanction(member).brandUntil());
        assertEquals(1000,s.sanction(member).debt());assertEquals(treasury,s.of(leader).treasury());
        assertEquals(1,s.sanction(member).audit().size());assertFalse(s.peaceNotices(leader).isEmpty());
    }
    @Test void ninthIsExplicitFinalWarning() {var s=store();incidents(s,member,8);assertEquals("discipline-last-warning",incident(s,member,8).key());}
    @Test void seventhAndEighthHaveEscalation() {var s=store();incidents(s,member,6);assertEquals("discipline-warning",incident(s,member,6).key());assertEquals("discipline-warning",incident(s,member,7).key());}
    @Test void manyClicksOrVictimsWithinMinuteAreOneIncident() {
        var s=store();assertTrue(incident(s,member,0).ok());
        for(int i=1;i<100;i++)assertFalse(new GuildPeaceService(s).fine(member,UUID.randomUUID(),UUID.randomUUID(),"projectile",true,now+i).ok());
        assertEquals(1,s.sanction(member).violations(now+minute-1));assertEquals(100,s.sanction(member).debt());
        assertTrue(incident(s,member,1).ok());
    }
    @Test void sameLingeringOrPiercingSourceNeverCreatesAnotherIncidentEvenAfterRestart() {
        var s=store();var source=UUID.randomUUID();var rules=new GuildPeaceService(s);
        assertTrue(rules.fine(member,victim,source,"potion",true,now).ok());s.saveNow();
        var boot=new GuildStore(dir,Logger.getAnonymousLogger());boot.load();
        assertFalse(new GuildPeaceService(boot).fine(member,UUID.randomUUID(),source,"potion",true,now+10*minute).ok());
        assertEquals(1,boot.sanction(member).violations(now+10*minute));
    }
    @Test void thirtyDayBoundaryIsExclusiveWithoutPermanentAccumulation() {
        var s=store();incident(s,member,0);var sanction=s.sanction(member);
        assertEquals(1,sanction.violations(now+GuildSanction.WINDOW-1));
        assertEquals(0,sanction.violations(now+GuildSanction.WINDOW));
        assertTrue(new GuildPeaceService(s).fine(member,victim,UUID.randomUUID(),"melee",true,now+GuildSanction.WINDOW).ok());
        assertEquals(1,s.sanction(member).incidents().size());
    }
    @Test void leaderIsNeverAutomaticallyBrandedOrExpelledButPaysEveryIncident() {
        var s=store();incidents(s,leader,12);assertNotNull(s.of(leader));assertEquals(leader,s.of(leader).leader());
        assertFalse(s.sanction(leader).branded(now+12*minute));assertEquals(12,s.sanction(leader).violations(now+12*minute));
        assertEquals(1200,s.sanction(leader).debt());
        assertEquals(1,s.sanction(leader).audit().size());assertEquals(1,s.peaceNotices(member).size());
    }
    @Test void leadershipTransferEnforcesFormerLeadersAccumulatedViolations() {
        var s=store();incidents(s,leader,10);
        assertTrue(new GuildService(GuildConfig.defaults(),s).transfer(leader,member,now+10*minute).ok());
        assertEquals(member,s.of(member).leader());assertNull(s.of(leader));assertTrue(s.sanction(leader).branded(now+10*minute));
    }
    @Test void promotionToLeaderPreservesHistoryAndFormerLeaderIsNotPunishedBelowThreshold() {
        var s=store();incidents(s,member,9);incidents(s,leader,3);
        assertTrue(new GuildService(GuildConfig.defaults(),s).transfer(leader,member,now+10*minute).ok());
        assertNotNull(s.of(leader));assertEquals(9,s.sanction(member).violations(now+10*minute));
        assertEquals("discipline-leader-warning",incident(s,member,11).key());assertNotNull(s.of(member));
    }
    @Test void disbandCannotLaunderTenLeaderViolations() {
        var s=store();incidents(s,leader,10);
        assertTrue(new GuildService(GuildConfig.defaults(),s).disband(leader,now+10*minute).ok());
        assertEquals(0,s.count());assertTrue(s.sanction(leader).branded(now+10*minute));
    }
    @Test void switchingClanOrLeavingDoesNotResetNineViolations() {
        var s=store();incidents(s,member,9);var svc=new GuildService(GuildConfig.defaults(),s);
        assertTrue(svc.leave(member,now+9*minute).ok());assertEquals(9,s.sanction(member).violations(now+9*minute));
        assertEquals("discipline-expelled",incident(s,member,9).key());assertTrue(s.sanction(member).branded(now+9*minute));
    }
    @Test void peacefulProjectileAfterLeavingStillCountsButBattleAggressionDoesNot() {
        var s=store();assertTrue(new GuildService(GuildConfig.defaults(),s).leave(member,now).ok());
        assertTrue(new GuildPeaceService(s).fine(member,victim,UUID.randomUUID(),"projectile",true,now).ok());
        assertTrue(new GuildPeaceService(s).fine(member,victim,UUID.randomUUID(),"projectile",false,now+minute).ok());
        assertEquals(1,s.sanction(member).violations(now+minute));assertEquals(200,s.sanction(member).debt());
    }
    @Test void brandExpiresAtExactSevenDayBoundaryAndOldCycleCannotImmediatelyExpelAgain() {
        var s=store();incidents(s,member,10);var sanction=s.sanction(member);long until=sanction.brandUntil();
        assertTrue(sanction.branded(until-1));assertFalse(sanction.branded(until));assertEquals(0,sanction.violations(until));
        assertTrue(new GuildPeaceService(s).fine(member,victim,UUID.randomUUID(),"projectile",true,until).ok());
        assertFalse(s.sanction(member).branded(until));assertEquals(1,s.sanction(member).violations(until));assertEquals(1100,s.sanction(member).debt());
    }
    @Test void oldProjectilesDuringBrandDoNotExtendBrandOrCreateANewCycle() {
        var s=store();incidents(s,member,10);long until=s.sanction(member).brandUntil();
        for(int i=10;i<20;i++)incident(s,member,i);
        assertEquals(until,s.sanction(member).brandUntil());assertEquals(0,s.sanction(member).violations(now+20*minute));assertEquals(2000,s.sanction(member).debt());
    }
    @Test void paymentNeverErasesViolationsOrBrand() {
        var s=store();incidents(s,member,9);assertEquals(900,new GuildPeaceService(s).collect(member,900).debit());
        assertEquals(9,s.sanction(member).violations(now+9*minute));incident(s,member,9);
        long until=s.sanction(member).brandUntil();new GuildPeaceService(s).collect(member,100);
        assertEquals(0,s.sanction(member).debt());assertEquals(until,s.sanction(member).brandUntil());
    }
    @Test void brandedPlayerCannotJoinPeaceButCanJoinBattleAndDebtStillBlocksReturnAfterExpiry() {
        var s=store();incidents(s,member,10);var svc=new GuildService(GuildConfig.defaults(),s);
        assertTrue(svc.invite(leader,member,now+10*minute).ok());
        assertEquals("discipline-brand-block",svc.accept(new GuildFinance.Quote(),member,now+10*minute).key());
        s.of(leader).mode(GuildMode.BATTLE,now+10*minute);
        assertTrue(svc.accept(new GuildFinance.Quote(),member,now+10*minute).ok());
        assertTrue(svc.leave(member,now+11*minute).ok());
        long until=s.sanction(member).brandUntil();
        s.of(leader).mode(GuildMode.PEACEFUL,until);
        // Прямое переключение модели в тесте требует полного срока защиты состава.
        s.of(leader).peaceTerm(leader,new GuildPeaceTerm(until+GuildPeaceTerm.DAY,0,until));
        assertTrue(svc.invite(leader,member,until).ok());
        assertEquals("peace-debt-mode",svc.accept(new GuildFinance.Quote(),member,until).key());
    }
    @Test void brandBlocksWholeRosterPeaceTransitionIncludingNewlyCreatedBattleClan() {
        var s=store();incidents(s,member,10);
        var g=new Guild("other","Второй","TWO",member,now+10*minute);g.deposit(100);s.add(g);
        assertEquals("discipline-brand-block",new GuildPeaceService(s).mode(new GuildFinance.Quote(),member,GuildMode.PEACEFUL,now+10*minute).key());
    }
    @Test void adminForgivenessIsAddressedAuditedAndDoesNotRefundOrUnbrand() {
        var s=store();incidents(s,member,10);var before=s.sanction(member);var id=before.incidents().getFirst().id();
        assertTrue(new GuildPeaceService(s).forgive(member,id,now+10*minute,"console","Подтверждена ошибка атрибуции").ok());
        var after=s.sanction(member);assertEquals(1000,after.debt());assertEquals(before.brandUntil(),after.brandUntil());
        assertTrue(after.incidents().getFirst().forgiven());assertEquals(2,after.audit().size());
        assertFalse(new GuildPeaceService(s).forgive(member,id,now+11*minute,"console","Повтор").ok());
    }
    @Test void adminForgivenessBelowThresholdChangesCountButCannotReuseOldSource() {
        var s=store();incidents(s,member,9);var event=s.sanction(member).incidents().getFirst();
        assertTrue(new GuildPeaceService(s).forgive(member,event.id(),now+9*minute,"console","Ошибка").ok());
        assertEquals(8,s.sanction(member).violations(now+9*minute));
        assertFalse(new GuildPeaceService(s).fine(member,victim,event.source(),"melee",true,now+9*minute).ok());
    }
    @Test void unbrandStartsNewCycleWithoutRestoringMembershipOrMoney() {
        var s=store();incidents(s,member,10);
        assertTrue(new GuildPeaceService(s).unbrand(member,now+10*minute,"console","Апелляция удовлетворена").ok());
        assertFalse(s.sanction(member).branded(now+10*minute));assertEquals(0,s.sanction(member).violations(now+10*minute));
        assertNull(s.of(member));assertEquals(1000,s.sanction(member).debt());assertEquals(2,s.sanction(member).audit().size());
    }
    @Test void restartPreservesFullEvidenceBrandAndExclusion() {
        var s=store();incidents(s,member,10);s.saveNow();var boot=new GuildStore(dir,Logger.getAnonymousLogger());boot.load();
        assertNull(boot.of(member));assertEquals(s.sanction(member),boot.sanction(member));assertEquals(s.peaceNotices(member),boot.peaceNotices(member));
    }
    @Test void schemaThreeMigrationKeepsDebtAndIncidentBoundaryWithoutInventingViolations() {
        var s=store();new GuildPeaceService(s).fine(member,now);
        var json=JsonParser.parseString(s.snapshotJson()).getAsJsonObject();json.addProperty("version",3);
        var row=json.getAsJsonObject("sanctions").getAsJsonObject(member.toString());
        for(String key:List.of("incidents","brand-until","cycle-started-at","discipline-audit"))row.remove(key);
        var boot=s.detached(json.toString());assertEquals(100,boot.sanction(member).debt());
        assertEquals(now+minute,boot.sanction(member).nextIncidentAt());assertEquals(0,boot.sanction(member).violations(now));assertEquals(0,boot.sanction(member).brandUntil());
    }
    @Test void downgradedOrMissingDisciplineSchemaFailsClosed() {
        var s=store();incident(s,member,0);var json=JsonParser.parseString(s.snapshotJson()).getAsJsonObject();
        json.addProperty("version",3);assertThrows(RuntimeException.class,()->s.detached(json.toString()));
        json.addProperty("version",4);json.getAsJsonObject("sanctions").getAsJsonObject(member.toString()).remove("incidents");
        assertThrows(RuntimeException.class,()->s.detached(json.toString()));
    }
    @Test void corruptEvidenceDuplicateIdsAndNegativeBrandAreRejected() {
        var s=store();incident(s,member,0);var json=JsonParser.parseString(s.snapshotJson()).getAsJsonObject();
        var row=json.getAsJsonObject("sanctions").getAsJsonObject(member.toString());
        row.getAsJsonArray("incidents").add(row.getAsJsonArray("incidents").get(0).deepCopy());
        assertThrows(RuntimeException.class,()->s.detached(json.toString()));
        row.getAsJsonArray("incidents").remove(1);row.addProperty("brand-until",-1);
        assertThrows(RuntimeException.class,()->s.detached(json.toString()));
    }
    @Test void durableTransactionCommitsExclusionBrandDebtAndNoticesTogetherExactlyOnce() throws Exception {
        var s=store();incidents(s,member,9);s.saveNow();var paid=new PaidActionStore(dir);s.bind(paid);
        UUID action=UUID.randomUUID();
        assertTrue(s.transaction(copy->new GuildPeaceService(copy).fine(member,victim,action,"projectile",true,now+9*minute),GuildResult.fail("failed")).ok());
        var boot=new GuildStore(dir,Logger.getAnonymousLogger());boot.bind(new PaidActionStore(dir));
        assertNull(boot.of(member));assertEquals(1000,boot.sanction(member).debt());assertTrue(boot.sanction(member).branded(now+9*minute));
        assertFalse(boot.transaction(copy->new GuildPeaceService(copy).fine(member,victim,action,"projectile",true,now+11*minute),GuildResult.fail("failed")).ok());
        assertEquals(1000,boot.sanction(member).debt());assertFalse(boot.peaceNotices(member).isEmpty());
    }
    @Test void uncommittedDetachedPreviewCannotLeakExclusionOrBrand() throws Exception {
        var s=store();incidents(s,member,9);s.saveNow();s.bind(new PaidActionStore(dir));
        var copy=s.copy();incident(copy,member,9);
        assertNull(copy.of(member));assertNotNull(s.of(member));assertFalse(s.sanction(member).branded(now+9*minute));assertEquals(900,s.sanction(member).debt());
    }
    @Test void adminAuditIsBoundedWithoutDroppingDebtOrEvidence() {
        var sanction=new GuildSanction(100,0);
        for(int i=0;i<100;i++)sanction=sanction.log(now+i,"console","review","Проверено");
        assertEquals(64,sanction.audit().size());assertEquals(now+36,sanction.audit().getFirst().at());assertEquals(100,sanction.debt());
    }
    @Test void failedWriteBeforeReplaceCannotPublishPartialPunishment() throws Exception {
        var s=store();incidents(s,member,9);s.saveNow();s.bind(new PaidActionStore(dir));
        java.nio.file.Files.createDirectory(dir.resolve("paid-actions.json.tmp"));
        var result=s.transaction(copy->new GuildPeaceService(copy).fine(member,victim,UUID.randomUUID(),"melee",true,now+9*minute),GuildResult.fail("failed"));
        assertFalse(result.ok());assertNotNull(s.of(member));assertEquals(900,s.sanction(member).debt());
        var boot=new GuildStore(dir,Logger.getAnonymousLogger());boot.bind(new PaidActionStore(dir));
        assertNotNull(boot.of(member));assertFalse(boot.sanction(member).branded(now+9*minute));assertEquals(900,boot.sanction(member).debt());
    }
    @Test void crashAfterAtomicReplaceRecoversWholePunishmentWithoutDoubleFine() throws Exception {
        var s=store();incidents(s,member,9);s.saveNow();
        var crash=new java.util.concurrent.atomic.AtomicBoolean();
        var paid=new PaidActionStore(dir,()->{if(crash.get())throw new java.io.IOException("crash after replace");});s.bind(paid);
        crash.set(true);UUID action=UUID.randomUUID();
        var result=s.transaction(copy->new GuildPeaceService(copy).fine(member,victim,action,"melee",true,now+9*minute),GuildResult.fail("failed"));
        assertFalse(result.ok()); // Клиенту не объявляем неподтверждённый успех.
        var boot=new GuildStore(dir,Logger.getAnonymousLogger());boot.bind(new PaidActionStore(dir));
        assertNull(boot.of(member));assertTrue(boot.sanction(member).branded(now+9*minute));assertEquals(1000,boot.sanction(member).debt());
        assertFalse(boot.transaction(copy->new GuildPeaceService(copy).fine(member,victim,action,"melee",true,now+11*minute),GuildResult.fail("failed")).ok());
        assertEquals(1000,boot.sanction(member).debt());assertFalse(boot.peaceNotices(member).isEmpty());
    }

}
