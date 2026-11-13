package ru.cherdakmd.chrdkregister;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.BankCharge;
import ru.cherdakmd.chrdkregister.api.ChargeInventory;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChargeInventoryBankOnlyTest {
    @Test void claimTeleportPolicyIsBankOnlyWithoutRejectingLegacyPendingReceipts() {
        String hash="a".repeat(64);
        var bankOnly=new BankCharge("claim-teleports",UUID.randomUUID(),1,UUID.randomUUID(),UUID.randomUUID(),
                10_000,10_000,0,0,hash,hash,true,10_000);
        assertTrue(bankOnly.valid());
        var withPhysical=new BankCharge("claim-teleports",UUID.randomUUID(),1,UUID.randomUUID(),UUID.randomUUID(),
                10_000,9_900,100,0,hash,hash,true,10_000);
        assertTrue(BankCharge.bankOnlySource(withPhysical.source()));
        assertTrue(withPhysical.valid(),"legacy pending receipts must remain parseable so recovery can reconcile them");
    }

    @Test void bankOnlyPlansNeverTenderPhysicalDiamonds() {
        ItemStack[] inventory=new ItemStack[36];
        inventory[0]=new ItemStack(Material.DIAMOND,64);
        inventory[1]=new ItemStack(Material.DIAMOND_BLOCK,2);

        var enough=ChargeInventory.planMinorBankOnly(inventory,10_000,10_000,10_000);
        assertNotNull(enough);
        assertEquals(10_000,enough.debit());
        assertEquals(0,enough.cash());
        assertEquals(64,enough.after()[0].getAmount());
        assertEquals(2,enough.after()[1].getAmount());

        assertNull(ChargeInventory.planMinorBankOnly(inventory,10_000,9_999,10_000),
                "having diamonds in inventory must not cover a one-minor-unit bank shortage");
        assertNull(ChargeInventory.planBankOnly(inventory,20,19),
                "the legacy whole-unit service path is also bank-only");
    }
}
