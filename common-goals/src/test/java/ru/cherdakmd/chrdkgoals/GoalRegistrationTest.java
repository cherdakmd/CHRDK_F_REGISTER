package ru.cherdakmd.chrdkgoals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.BankStore;
import ru.cherdakmd.chrdkregister.api.ChargeJournal;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class GoalRegistrationTest {
    @TempDir Path dir;
    @Test void pristineGoalsRestoreSameLineageButRewardsRemainFailClosed() throws Exception {
        var log=Logger.getAnonymousLogger(); var store=new GoalStore(dir,log);store.enableBank();
        var journal=new ChargeJournal(dir,"common-goals",store.incomeId());
        store.markBankRegistered(); UUID lineage=store.incomeId();
        assertTrue(GoalRegistration.canRestore(store,journal));
        var bank=new BankStore(dir.resolve("empty-core"),log);bank.load();
        assertTrue(bank.registerIncomeSource("common-goals",lineage));
        assertTrue(bank.incomeSourceAvailable("common-goals",lineage));
        store.addPendingReward("alex",10);store.saveStrict();
        assertFalse(GoalRegistration.canRestore(store,journal));
    }
    @Test void chargeHistoryOrPendingPreventsAutomaticRegistration() throws Exception {
        var log=Logger.getAnonymousLogger();var store=new GoalStore(dir,log);store.enableBank();
        var journal=new ChargeJournal(dir,"common-goals",store.incomeId());
        store.enableBank();store.markBankRegistered();store.replaceGoals(List.of(new GoalState("donate-diamonds",5,10)),1000);store.saveStrict();
        String before=store.snapshot(),after=store.previewDonation("alex",5,0),empty=new com.google.gson.Gson().toJson(new String[36]);
        journal.prepare(java.util.UUID.randomUUID(),5,5,0,0,empty,empty,before,after);
        assertFalse(GoalRegistration.canRestore(store,journal));
    }
}
