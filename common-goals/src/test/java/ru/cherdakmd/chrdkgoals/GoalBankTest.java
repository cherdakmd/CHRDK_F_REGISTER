package ru.cherdakmd.chrdkgoals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.BankStore;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
class GoalBankTest {
    @TempDir Path dir;
    GoalStore source() { var s=new GoalStore(dir,Logger.getAnonymousLogger()); s.load(); return s; }
    BankStore bank() { var b=new BankStore(dir.resolve("core"),Logger.getAnonymousLogger()); b.load(); return b; }
    @Test void legacyPendingBecomesImmutableTotalAndCannotBePhysicallyClaimed() throws Exception {
        var s=source(); s.addPendingReward("Alex",70); s.save(); s=source(); s.enableBank();
        var id=s.incomeId(); assertEquals(id,source().incomeId()); var locked=s;
        assertThrows(IllegalStateException.class,()->locked.claim("Alex")); assertEquals(70,source().entitlementOf("Alex"));
        var b=bank(); var owner=UUID.randomUUID(); assertTrue(b.registerIncomeSource("common-goals",id));
        assertTrue(b.creditEntitlement("common-goals",id,"alex",owner,s.entitlementOf("Alex")));
        assertTrue(bank().creditEntitlement("common-goals",id,"alex",owner,source().entitlementOf("Alex"))); assertEquals(70,bank().balance(owner));
        s.addPendingReward("Alex",5); s.saveStrict();
        assertTrue(bank().creditEntitlement("common-goals",id,"alex",owner,source().entitlementOf("Alex"))); assertEquals(75,bank().balance(owner));
    }
    @Test void completionAndIncomePersistTogetherBeforeCreditAndOnlyOnce() throws Exception {
        var s=source(); s.enableBank(); s.replaceGoals(List.of(new GoalState("hostile-slay",10,50)),1000);
        assertTrue(s.progressAndRewards("hostile-slay","Alex",10,0));
        s=source(); assertTrue(s.goal("hostile-slay").orElseThrow().done); assertEquals(50,s.entitlementOf("Alex"));
        assertFalse(s.progressAndRewards("hostile-slay","Alex",10,0)); assertEquals(50,source().entitlementOf("Alex"));
    }
    @Test void corruptSourcesAreNotResetOrSaved() throws Exception {
        for (String json:List.of("broken","{}","null")) {
            Files.writeString(dir.resolve("goals-data.json"),json); var s=source(); assertFalse(s.healthy());
            s.markDirty(); s.save(); assertEquals(json,Files.readString(dir.resolve("goals-data.json")));
            assertThrows(IllegalStateException.class,s::enableBank);
        }
    }
    @Test void failedCompletionNeverPublishesSpendableIncomeAndQuarantines() throws Exception {
        var s=source(); s.enableBank(); s.replaceGoals(List.of(new GoalState("hostile-slay",1,50)),1000); s.saveStrict();
        Files.createDirectory(dir.resolve("goals-data.json.tmp"));
        assertThrows(java.io.IOException.class,()->s.progressAndRewards("hostile-slay","Alex",1,0));
        assertFalse(s.healthy()); assertEquals(0,source().entitlementOf("Alex")); assertFalse(source().goal("hostile-slay").orElseThrow().done);
    }
    @Test void markerAndSourceIdentityAreRequiredAfterUpgrade() throws Exception {
        var s=source(); s.enableBank(); String original=Files.readString(dir.resolve("goals-data.json"));
        for (String field:List.of("incomeId","bankRegistered")) {
            var json=com.google.gson.JsonParser.parseString(original).getAsJsonObject(); json.remove(field);
            Files.writeString(dir.resolve("goals-data.json"),json.toString()); assertFalse(source().healthy());
        }
    }
}
