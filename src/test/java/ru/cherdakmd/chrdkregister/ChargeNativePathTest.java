package ru.cherdakmd.chrdkregister;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class ChargeNativePathTest {
    @Test void nativeCommitChecksAuthPermissionModeNpcStorageAndTenderBeforeDurableDebit() throws Exception {
        String code=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/ChrdkRegisterApiImpl.java"));
        String commit=code.substring(code.indexOf("public boolean commitBankCharge"),code.indexOf("public boolean personalBankAvailable"));
        for (String guard:new String[]{"auctionBankReady()","payment.valid()","payer.isOnline()","payer.isDead()","payer.hasMetadata(\"NPC\")","isAuthenticated(payer)","GameMode.SURVIVAL","GameMode.ADVENTURE","payer.hasPermission(permission)","expected.length != 36","getStorageContents()","ChargeInventory.plan","plan.debit()","plan.cash()","plan.change()","inventoryHash()","BankCharge.permission(payment.source())"})
            assertTrue(commit.indexOf(guard)>=0 && commit.indexOf(guard)<commit.indexOf("bank.settleCharge"),guard);
        assertFalse(commit.contains("setStorageContents")); assertFalse(commit.contains("dropItem"));
    }
    @Test void minorPaidActionsAreVerifiedWithTheSameMinorTenderAndFrozenRate() throws Exception {
        String code=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/ChrdkRegisterApiImpl.java"));
        String commit=code.substring(code.indexOf("public boolean commitBankCharge"),code.indexOf("public boolean personalBankAvailable"));
        assertTrue(commit.contains("payment.minor()"));
        assertTrue(commit.contains("ChargeInventory.planMinor(expected, payment.price()"));
        assertTrue(commit.contains("bank.spendableBalanceMinor(payer.getUniqueId()), payment.rateBps()"));
        assertTrue(commit.indexOf("planMinor")<commit.indexOf("bank.settleCharge"));
    }
    @Test void claimsTeleportNamespaceUsesBankOnlyPlannerAndCoreRevalidationWithoutChangingOtherServices() throws Exception {
        String actions=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/api/PaidActions.java"));
        String claims=Files.readString(Path.of("claims/src/main/java/ru/cherdakmd/chrdkclaims/ClaimsPlugin.java"));
        String core=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/ChrdkRegisterApiImpl.java"));
        String commit=core.substring(core.indexOf("public boolean commitBankCharge"),core.indexOf("public boolean personalBankAvailable"));
        String inventory=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/api/ChargeInventory.java"));
        String bankCharge=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/api/BankCharge.java"));
        assertTrue(actions.contains("planMinorBankOnly"));
        assertTrue(actions.contains("planBankOnly"));
        assertTrue(claims.contains("\"claim-teleports\",true"));
        assertTrue(actions.contains("this(plugin,api,messages,source,false)"),"other service namespaces keep their prior payment policy");
        assertTrue(actions.contains("this.bankOnly=bankOnly||BankCharge.bankOnlySource(source)"),
                "the namespace policy cannot be disabled by an accidentally legacy constructor call");
        assertTrue(core.contains("BankCharge.bankOnlySource(payment.source())"));
        assertTrue(commit.contains("bankOnly&&(payment.debit()!=payment.price()||payment.cash()!=0||payment.change()!=0)"),
                "uncommitted legacy item tenders cannot be replayed through the new Core path");
        assertTrue(commit.contains("ChargeInventory.planMinorBankOnly(expected, payment.price()"));
        assertTrue(inventory.contains("c.debit()==c.price()&&c.cash()==0&&c.change()==0"),
                "new bank-only intents are validated without inventory tender");
        assertTrue(bankCharge.contains("bankOnlySource(String source)"));
    }
    @Test void recoverySavesPlayerdataBeforeSourceAndNeverAutomaticallyRefundsOrDrops() throws Exception {
        String code=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/api/ChargeInventory.java"));
        String apply=code.substring(code.indexOf("public void apply("),code.indexOf("public void checkpoint()"));
        assertTrue(apply.indexOf("setStorageContents")<apply.indexOf("getPersistentDataContainer().set"));
        assertTrue(apply.indexOf("getPersistentDataContainer().set")<apply.indexOf("checkpoint();"));
        assertTrue(code.contains("public void checkpoint() { player.saveData(); }"));
        assertTrue(code.contains("pending.payment().cash()==0?null:inventory("));
        String executor=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/api/ChargeRecovery.java"));
        assertTrue(executor.indexOf("InventoryTransfer.recover(")<executor.indexOf("source.publish("));
        assertTrue(executor.indexOf("source.publish(")<executor.indexOf("journal.complete("));
        String permission=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/api/BankCharge.java"));
        for(String source:new String[]{"common-goals","teleports","claim-teleports","guilds"}) assertTrue(permission.contains("case \""+source+"\""));
        assertFalse(code.contains("deposit(")); assertFalse(code.contains("dropItem"));
    }
}
