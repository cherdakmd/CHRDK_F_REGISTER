package ru.cherdakmd.chrdkregister;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.BankCharge;
import ru.cherdakmd.chrdkregister.api.ChargeInventory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Предметы не могут ни покрыть банковскую нехватку, ни считаться валютой Claims.
 * Конструкция ItemStack требует живого Bukkit, поэтому семантика планировщиков
 * проверяется кодом (как остальные native-пути) и на пустых snapshot без предметов.
 */
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
        // Пустой storage: план обязан работать только со счётом и не изымать предметы.
        ItemStack[] inventory=new ItemStack[36];

        var enough=ChargeInventory.planMinorBankOnly(inventory,10_000,10_000,10_000);
        assertNotNull(enough);
        assertEquals(10_000,enough.debit());
        assertEquals(0,enough.cash());
        assertEquals(0,enough.change());
        for(ItemStack item:enough.after()) assertNull(item);

        // Нехватка банковского баланса: план запрещён до какого-либо учёта инвентаря.
        assertNull(ChargeInventory.planMinorBankOnly(inventory,10_000,9_999,10_000),
                "having items in inventory must not cover a one-minor-unit bank shortage");
        assertNull(ChargeInventory.planBankOnly(inventory,20,19),
                "the legacy whole-unit service path is also bank-only");
    }

    @Test void bankOnlyPlannersIgnoreInventoryWhenDecidingTheDebit() throws Exception {
        String code=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/api/ChargeInventory.java"));
        String minor=code.substring(code.indexOf("public static Plan planMinorBankOnly"),code.indexOf("public static boolean valid"));
        String whole=code.substring(code.indexOf("public static Plan planBankOnly"),code.indexOf("public static Plan planMinorBankOnly"));
        // Оба планировщика отклоняют нехватку счёта до обхода storage и возвращают cash=0.
        assertTrue(minor.contains("spendableBalanceMinor<price"));
        assertTrue(whole.contains("spendableBalance<price"));
        for(String planner:new String[]{minor,whole}) {
            assertTrue(planner.contains("new Plan(copy(before),price,0,0)"),planner);
            assertFalse(planner.contains("cashValue"),planner);
            assertFalse(planner.contains("AuctionTender"),planner);
            assertFalse(planner.contains("setAmount"),planner);
            assertFalse(planner.contains("DiamondMoney"),planner);
        }
        // Отдельная ветка старых pending-receipts сверяет только legacy item tender, не выдаёт его.
        String valid=code.substring(code.indexOf("public static boolean valid"));
        assertTrue(valid.contains("c.debit()==c.price()&&c.cash()==0&&c.change()==0"));
        assertFalse(valid.contains("deposit("));
    }
}
