package ru.cherdakmd.chrdkguilds;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.BankStore;
import ru.cherdakmd.chrdkregister.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Новый путь вступления использует те же durable whole-domain/bank-only контракты. */
class GuildRecruitment280FinanceTest {
    @TempDir Path dir;
    final UUID leader=UUID.randomUUID(), guest=UUID.randomUUID();
    final long now=1_800_000_000_000L;
    GuildStore fresh() { return new GuildStore(dir,Logger.getAnonymousLogger()); }
    GuildStore setup() {
        var store=fresh();store.add(new Guild("one","Первый","ONE",leader,now));store.flush();return store;
    }
    GuildStore bind(PaidActionStore source) throws Exception { var store=fresh();store.bind(source);return store; }
    GuildFinance.Preview plan(GuildStore store,GuildJoinTerms terms,long expires,long at) {
        return GuildFinance.preview(store,GuildConfig.defaults(),(svc,q)->GuildFinance.confirmedJoin(svc,q,guest,terms,expires,at));
    }
    @Test void liveOpenStatePersistsAndFakePurseCannotBypassBank() throws Exception {
        setup();var source=new PaidActionStore(dir);var live=bind(source);
        var service=new GuildService(GuildConfig.defaults(),live);
        assertTrue(service.recruitment(leader,true).ok());assertTrue(bind(new PaidActionStore(dir)).guild("one").recruitmentOpen());
        GuildPurse forbidden=(id,amount)->{throw new AssertionError("native purse");};
        assertEquals("dues-join-required",service.joinOpen(forbidden,guest,"one",now).key());assertNull(live.of(guest));
    }
    @Test void staleOrExpiredConsentNeverPlansChargeAndTreasuryChangeDoesNotInvalidate() {
        var store=setup();store.guild("one").recruitment(true);var t=GuildJoinTerms.capture(store,guest,"one",now);
        assertEquals(0,plan(store,t,now+30_000,now+30_000).debit());
        assertFalse(plan(store,t,now+30_000,now+30_000).result().ok());
        store.guild("one").deposit(25);assertTrue(plan(store,t,now+30_000,now+1).result().ok());
        store.guild("one").recruitment(false);var refused=plan(store,t,now+30_000,now+1);
        assertEquals("recruit-changed",refused.result().key());assertEquals(0,refused.debit());assertNull(store.of(guest));
    }
    @Test void realBankOnlyJoinRecoversAfterCrashWithoutDoubleChargeOrDoubleMember() throws Exception {
        setup();var source=new PaidActionStore(dir);var live=bind(source);source.markRegistered();
        assertTrue(new GuildService(GuildConfig.defaults(),live).recruitment(leader,true).ok());
        var terms=GuildJoinTerms.capture(live,guest,"one",now);var preview=plan(live,terms,now+30_000,now+1);
        assertTrue(preview.result().ok());assertEquals(10,preview.debit());assertNull(live.of(guest));
        var bank=new BankStore(dir.resolve("core"),Logger.getAnonymousLogger());bank.load();
        assertTrue(bank.registerIncomeSource("guilds",source.lineage()));bank.deposit(guest,"Guest",50);bank.saveNow();
        String inventory=new com.google.gson.Gson().toJson(new String[36]);
        var journal=new ChargeJournal(dir,"guilds",source.lineage());
        var charge=journal.prepare(guest,10,10,0,0,inventory,inventory,source.snapshot(),source.previewValue(GuildStore.FINANCE_KEY,preview.domain()));
        assertTrue(bank.settleCharge(charge));assertEquals(40,bank.balance(guest));assertNull(live.of(guest));
        var api=(ChrdkRegisterApi)Proxy.newProxyInstance(ChrdkRegisterApi.class.getClassLoader(),new Class<?>[]{ChrdkRegisterApi.class},(p,m,a)->{
            if(m.getName().equals("bankChargeStatus"))return bank.bankChargeStatus((BankCharge)a[0]);
            throw new AssertionError("offline bank-only recovery accessed "+m.getName());
        });
        var rebootSource=new PaidActionStore(dir);var rebootJournal=new ChargeJournal(dir,"guilds",rebootSource.lineage());
        assertEquals(ChargeRecovery.Decision.COMPLETE,ChargeInventory.recover(rebootJournal,api,null,rebootSource));
        assertEquals(ChargeRecovery.Decision.COMPLETE,ChargeInventory.recover(rebootJournal,api,null,rebootSource));
        var reboot=bind(new PaidActionStore(dir));assertEquals(2,reboot.guild("one").size());assertEquals(10,reboot.guild("one").treasury());
        assertEquals(40,bank.balance(guest));assertEquals(now+GuildPeaceTerm.DAY+1,reboot.of(guest).duesTerm(guest).dueAt());
        assertEquals("already-in-guild",plan(reboot,terms,now+30_000,now+2).result().key());
        assertEquals(0,plan(reboot,terms,now+30_000,now+2).debit());
    }
}
