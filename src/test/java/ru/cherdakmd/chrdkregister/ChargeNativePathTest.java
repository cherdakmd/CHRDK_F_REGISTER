package ru.cherdakmd.chrdkregister;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class ChargeNativePathTest {
    @Test void nativeCommitChecksAuthPermissionModeNpcStorageAndTenderBeforeDurableDebit() throws Exception {
        String code=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/ChrdkRegisterApiImpl.java"));
        String commit=code.substring(code.indexOf("public boolean commitBankCharge"),code.indexOf("public boolean personalBankAvailable"));
        for (String guard:new String[]{"auctionBankReady()","payment.valid()","payer.isOnline()","payer.isDead()","payer.hasMetadata(\"NPC\")","isAuthenticated(payer)","GameMode.SURVIVAL","GameMode.ADVENTURE","payer.hasPermission(permission)","expected.length != 36","getStorageContents()","ChargeInventory.plan","plan.debit()","plan.cash()","plan.change()","inventoryHash()","case \"lottery\"","case \"common-goals\""})
            assertTrue(commit.indexOf(guard)>=0 && commit.indexOf(guard)<commit.indexOf("bank.settleCharge"),guard);
        assertFalse(commit.contains("setStorageContents")); assertFalse(commit.contains("dropItem"));
    }
    @Test void recoverySavesPlayerdataBeforeSourceAndNeverAutomaticallyRefundsOrDrops() throws Exception {
        String code=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/api/ChargeInventory.java"));
        String apply=code.substring(code.indexOf("case APPLY_AFTER"),code.indexOf("case COMPLETE"));
        assertTrue(apply.indexOf("setStorageContents")<apply.indexOf("saveData")); assertTrue(apply.indexOf("saveData")<apply.indexOf("source.publish")); assertTrue(apply.indexOf("source.publish")<apply.indexOf("journal.complete"));
        assertTrue(code.contains("c.cash() == 0 ? null : player.getInventory()")); assertFalse(code.contains("deposit(")); assertFalse(code.contains("dropItem"));
    }
}
