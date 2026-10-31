package ru.cherdakmd.chrdkguilds;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class GuildPeacePreviewTest {
    @TempDir Path dir;
    final UUID leader=UUID.randomUUID(),member=UUID.randomUUID();
    final long now=1_800_000_000_000L;
    GuildStore store(long balance,long returns) {
        var store=new GuildStore(dir,java.util.logging.Logger.getAnonymousLogger());
        var guild=new Guild("test","TestGuild","TAG",leader,now-1000);
        guild.addMember(member,GuildRank.MEMBER);guild.deposit(balance);guild.returnWarFunds(returns);store.add(guild);return store;
    }
    @Test void normalTreasuryPreviewIncludesWholeCost() {
        var store=store(100,0);var before=store.of(leader);
        var plan=GuildFinance.preview(store,GuildConfig.defaults(),(service,purse)->new GuildPeaceService(service.store()).mode(purse,leader,GuildMode.PEACEFUL,now));
        assertTrue(plan.result().ok());assertEquals(20,GuildFinance.treasurySpent(before,store.detached(plan.domain()).of(leader)));
    }
    @Test void fullTreasuryDoesNotHideConsumptionOfWarReturns() {
        var store=store(Guild.MAX_TREASURY,800);var before=store.of(leader);
        var plan=GuildFinance.preview(store,GuildConfig.defaults(),(service,purse)->new GuildPeaceService(service.store()).mode(purse,leader,GuildMode.PEACEFUL,now));
        var after=store.detached(plan.domain()).of(leader);
        assertTrue(plan.result().ok());assertEquals(before.treasury(),after.treasury());assertEquals(780,after.warReturns());assertEquals(20,GuildFinance.treasurySpent(before,after));assertEquals(0,plan.debit());
    }
    @Test void partialRefillStillReportsWholeCostNotOnlyFreeBalanceDifference() {
        var store=store(Guild.MAX_TREASURY,5);var before=store.of(leader);
        var plan=GuildFinance.preview(store,GuildConfig.defaults(),(service,purse)->new GuildPeaceService(service.store()).mode(purse,leader,GuildMode.PEACEFUL,now));
        var after=store.detached(plan.domain()).of(leader);
        assertEquals(15,before.treasury()-after.treasury());assertEquals(20,GuildFinance.treasurySpent(before,after));
    }
    @Test void PrepayIncludesAllMembersAndWarReserveWithoutMutatingPreviewSource() {
        var store=store(Guild.MAX_TREASURY,800);
        var activated=GuildFinance.preview(store,GuildConfig.defaults(),(service,purse)->new GuildPeaceService(service.store()).mode(purse,leader,GuildMode.PEACEFUL,now));
        store=store.detached(activated.domain());var before=store.of(leader);String original=store.snapshotJson();
        var plan=GuildFinance.preview(store,GuildConfig.defaults(),(service,purse)->new GuildPeaceService(service.store()).prepay(leader,5,now));
        var after=store.detached(plan.domain()).of(leader);
        assertTrue(plan.result().ok());assertEquals(100,GuildFinance.treasurySpent(before,after));assertEquals(0,plan.debit());assertEquals(original,store.snapshotJson());
    }
}
