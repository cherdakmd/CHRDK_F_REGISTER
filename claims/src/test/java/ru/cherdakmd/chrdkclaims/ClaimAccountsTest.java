package ru.cherdakmd.chrdkclaims;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class ClaimAccountsTest {
    @TempDir Path dir;
    static final class Accounts implements ClaimAccounts {
        final Map<String,Long> money=new HashMap<>(); boolean available=true, reject=false; String payer;
        public boolean ready(String n) { return available && money.containsKey(n.toLowerCase(Locale.ROOT)); }
        public long balance(String n) { return money.getOrDefault(n.toLowerCase(Locale.ROOT),0L); }
        public boolean exchange(Map<String,Long> changes,String inventoryPayer) {
            payer=inventoryPayer; if (reject) return false;
            for(var e:changes.entrySet()) if(!ready(e.getKey()) || balance(e.getKey())+e.getValue()<0 || balance(e.getKey())+e.getValue()>1_000_000_000L) return false;
            changes.forEach((n,d)->money.put(n.toLowerCase(Locale.ROOT),balance(n)+d)); return true;
        }
        void set(String n,long value) { money.put(n.toLowerCase(Locale.ROOT),value); }
    }
    ClaimStore store() { return new ClaimStore(dir,Logger.getAnonymousLogger()); }
    Claim claim(ClaimStore s,long now) { var c=new Claim("world",0,0,"Owner",now); s.add(c); return c; }
    Accounts wire(ClaimStore s) { var a=new Accounts(); a.set("Owner",0); a.set("Tenant",100); s.accounts(a); return a; }
    @Test void externalBankIsNotAddedToFrozenTreasuryAndTransferDoesNotMoveSource() {
        var s=store(); s.addTreasury("Owner",70); var c=claim(s,System.currentTimeMillis());
        var a=wire(s); a.set("Owner",11); a.set("NewOwner",30);
        assertEquals(11,s.treasuryOf("Owner")); assertEquals(70,s.legacyTreasuryOf("Owner"));
        var moved=s.transferAll("Owner","NewOwner");
        assertEquals(0,moved.treasury()); assertEquals("NewOwner",c.owner);
        assertEquals(11,a.balance("Owner")); assertEquals(30,a.balance("NewOwner"));
        assertEquals(70,s.legacyTreasuryOf("Owner")); assertEquals(0,s.legacyTreasuryOf("NewOwner"));
    }
    @Test void leaseUsesManualPayerOnlyOnAcceptanceAndBankOnlyOfflineRenewal() {
        var s=store(); long now=System.currentTimeMillis(); var c=claim(s,now); var a=wire(s);
        assertNull(ClaimRentals.offer(s,c,"Owner",10,now));
        assertNull(ClaimRentals.take(s,c,"Tenant",10,now,2)); assertEquals("Tenant",a.payer);
        assertEquals(90,a.balance("Tenant")); assertEquals(8,a.balance("Owner"));
        long end=c.rentUntil;
        assertEquals(1,ClaimRentals.settle(s,c,end,2)); assertNull(a.payer);
        assertEquals(80,a.balance("Tenant")); assertEquals(16,a.balance("Owner"));
        assertTrue(c.paidUntil>=c.rentUntil);
    }
    @Test void unavailableOrRejectedBankCannotEndRentalOrAdvanceUnpaidUpkeep() {
        var s=store(); long now=System.currentTimeMillis(); var c=claim(s,now); var a=wire(s);
        assertNull(ClaimRentals.offer(s,c,"Owner",10,now)); assertNull(ClaimRentals.take(s,c,"Tenant",10,now,2));
        long end=c.rentUntil, paid=c.paidUntil; int debt=c.debtDays;
        a.available=false; assertEquals(0,ClaimRentals.settle(s,c,end,2)); assertEquals("Tenant",c.tenant);
        assertNull(ClaimUpkeep.day(s,c,2,3)); assertEquals(paid,c.paidUntil); assertEquals(debt,c.debtDays);
        a.available=true; a.reject=true;
        assertEquals(0,ClaimRentals.settle(s,c,end,2)); assertEquals(end,c.rentUntil); assertEquals("Tenant",c.tenant);
        assertNull(ClaimUpkeep.day(s,c,2,3)); assertEquals(paid,c.paidUntil); assertEquals(debt,c.debtDays);
        assertEquals(90,a.balance("Tenant"));
    }
    @Test void realEmptyBankFollowsGraceAndPaidLeaseIsNeverCutShort() {
        var s=store(); long now=System.currentTimeMillis(); var c=claim(s,now); var a=wire(s);
        assertNull(ClaimRentals.offer(s,c,"Owner",10,now)); assertNull(ClaimRentals.take(s,c,"Tenant",10,now,2));
        long end=c.rentUntil; a.set("Tenant",0); a.set("Owner",0);
        assertNull(ClaimRentals.offer(s,c,"Owner",0,now+1));
        assertEquals(0,ClaimRentals.settle(s,c,end-1,2)); assertEquals("Tenant",c.tenant); assertEquals(end,c.rentUntil);
        assertEquals(-1,ClaimRentals.settle(s,c,end,2)); assertEquals("",c.tenant);
        var day=ClaimUpkeep.day(s,c,2,3); assertNotNull(day); assertEquals(1,c.debtDays); assertFalse(day.released());
    }
    @Test void recipientCapAndUnresolvedIdentityDoNotChargeTenant() {
        var s=store(); long now=System.currentTimeMillis(); var c=claim(s,now); var a=wire(s);
        ClaimRentals.offer(s,c,"Owner",10,now); a.set("Owner",1_000_000_000L);
        assertEquals("rent-owner-funds",ClaimRentals.take(s,c,"Tenant",10,now,0));
        assertEquals(100,a.balance("Tenant")); assertEquals("",c.tenant);
        a.money.remove("owner"); assertEquals("finance.unavailable",ClaimRentals.take(s,c,"Tenant",10,now,0));
        assertEquals(100,a.balance("Tenant"));
    }
    @Test void corruptedLegacyMoneyIsNotSilentlyClampedOrImportedAsZero() throws Exception {
        Files.writeString(dir.resolve("claims-data.json"),"{\"version\":3,\"claims\":[],\"treasury\":{\"Owner\":4294967297}}");
        var s=store(); s.load(); assertFalse(s.moneyAvailable()); assertFalse(s.fundsReady("Owner"));
        try(var files=Files.list(dir)) { assertTrue(files.anyMatch(p->p.getFileName().toString().contains("corrupt"))); }
        assertTrue(Files.isRegularFile(dir.resolve("claims-data.json")));
        s.markDirty(); s.save(); assertTrue(Files.readString(dir.resolve("claims-data.json")).contains("4294967297"));
        var restarted=store(); restarted.load(); assertFalse(restarted.moneyAvailable());
    }
    @Test void financialWiringKeepsDefaultsFailClosedAndMessagesVersioned() throws Exception {
        String base="src/main/java/ru/cherdakmd/chrdkclaims/";
        String service=Files.readString(Path.of(base+"ClaimsService.java"));
        String bridge=Files.readString(Path.of(base+"ClaimBankBridge.java"));
        String plugin=Files.readString(Path.of(base+"ClaimsPlugin.java"));
        assertFalse(service.contains("DiamondItems.give(")); assertFalse(service.contains("DiamondItems.collect("));
        assertTrue(plugin.indexOf("store.accounts(new ClaimBankBridge")<plugin.indexOf("service.processUpkeep"));
        assertTrue(bridge.contains("getStorageContents()")); assertTrue(bridge.contains("DiamondItems.plain"));
        assertTrue(bridge.contains("exchangeClaimAccounts(changes, null, null, null)"));
        assertTrue(service.contains("ClaimUpkeep.day")); assertTrue(service.contains("finance.bank-commands"));
        var userFile=dir.resolve("messages.yml");
        Files.writeString(userFile,"claims:\n  profile-treasury: 'Старая пользовательская казна'\n");
        ru.cherdakmd.chrdkregister.Messages messages;
        try(var in=Files.newInputStream(Path.of("src/main/resources/messages.yml"))) {
            messages=ru.cherdakmd.chrdkregister.Messages.from(in,userFile,Logger.getAnonymousLogger());
        }
        assertTrue(messages.raw("profile-treasury").contains("Старая пользовательская"));
        assertTrue(messages.raw("finance.balance-line").contains("/bal"));
        assertTrue(messages.raw("finance.balance-line").contains("%physical%"));
        assertFalse(messages.raw("finance.profile-treasury").contains("Казна"));
        assertTrue(messages.raw("finance.bank-commands").contains("/bal deposit"));
    }
}
