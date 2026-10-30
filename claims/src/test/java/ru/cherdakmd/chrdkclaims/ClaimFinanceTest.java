package ru.cherdakmd.chrdkclaims;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.BankStore;
import ru.cherdakmd.chrdkregister.api.ClaimExchange;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Real canonical source + real canonical Core. Only native funding/availability is a test port. */
class ClaimFinanceTest {
    @TempDir Path dir;
    final Logger log=Logger.getAnonymousLogger();
    final UUID owner=UUID.randomUUID(),buyer=UUID.randomUUID();
    final long now=System.currentTimeMillis();
    ClaimStore store; Bank bank;
    class Bank implements ClaimFinance.Bank {
        BankStore root=new BankStore(dir.resolve("root"),log);
        boolean hide,hideAfterCommit,refuse; int commits,fundCalls; long physical; Runnable afterFunding=()->{};
        Bank() {root.load();root.open(owner,"Owner");root.open(buyer,"Buyer");root.deposit(buyer,"Buyer",100);root.flush();}
        UUID id(String name) {return name.equalsIgnoreCase("Owner")?owner:name.equalsIgnoreCase("Buyer")?buyer:null;}
        public boolean ready(String name) {return !hide&&id(name)!=null&&root.importLegacyClaims(id(name),name,store.legacyTreasuryOf(name));}
        public long balance(String name) {return root.balance(id(name));}
        public long spendable(String name) {return balance(name)+(name.equalsIgnoreCase("Buyer")?physical:0);}
        public boolean exchange(Map<String,Long> deltas,String payer) {throw new AssertionError("legacy callback forbidden");}
        public boolean registered(UUID id) {return !hide&&root.claimSourceRegistered(id);}
        public boolean register(UUID id) {return !hide&&root.registerClaimSource(id);}
        public Map<UUID,Long> resolve(Map<String,Long> changes,String payer) {
            var result=new LinkedHashMap<UUID,Long>();for(var e:changes.entrySet()) {if(!ready(e.getKey()))return null;result.merge(id(e.getKey()),e.getValue(),Long::sum);}
            if(payer!=null) {long cost=-changes.getOrDefault(payer.toLowerCase(Locale.ROOT),0L);if(balance(payer)<cost){long amount=cost-balance(payer);if(amount>physical)return null;fundCalls++;physical-=amount;root.deposit(id(payer),payer,amount);root.flush();afterFunding.run();}}
            return result;
        }
        public ClaimExchange.Status status(ClaimExchange t) {return hide?ClaimExchange.Status.UNAVAILABLE:root.claimStatus(t);}
        public ClaimExchange.Status commit(ClaimExchange t) {commits++;if(refuse)return ClaimExchange.Status.MISSING;var result=root.commitClaim(t);if(hideAfterCommit){hide=true;return ClaimExchange.Status.UNAVAILABLE;}return result;}
    }
    void start() {store=new ClaimStore(dir.resolve("claims"),log);store.load();bank=new Bank();attach();}
    void attach() {store.accounts(bank);store.finance=new ClaimFinance(store,bank);assertTrue(store.finance.recover());}
    void restart() {store=new ClaimStore(dir.resolve("claims"),log);store.load();bank.hide=false;bank.hideAfterCommit=false;bank.root.load();attach();}
    Path file() {return dir.resolve("claims/claims-data.json");}
    Claim claim() {var c=new Claim("world",0,0,"Owner",now);c.members.add("Friend");c.setRole("Friend",Claim.Role.ACCESS);store.add(c);return c;}
    boolean buy() {return ClaimLand.buy(store,"world",0,0,"Buyer",30,now+ClaimMath.DAY_MILLIS);}
    @Test void newLandAndStatisticsAppearOnlyWithDurableDebit() {start();assertTrue(buy());assertEquals(70,bank.balance("Buyer"));assertEquals("Buyer",store.claimAt("world",0,0).owner);assertEquals(30,store.diamondsSpent());assertEquals(1,store.financialSequence());restart();assertEquals(30,store.diamondsSpent());assertEquals(70,bank.balance("Buyer"));}
    @Test void unknownCommitKeepsBeforeLiveAndProtectsPendingNewChunk() {start();bank.hideAfterCommit=true;assertFalse(buy());assertNull(store.claimAt("world",0,0));assertNotNull(store.protectedClaimAt("world",0,0));assertEquals(0,store.diamondsSpent());assertEquals(70,bank.balance("Buyer"));assertTrue(store.finance.busy());restart();assertEquals("Buyer",store.claimAt("world",0,0).owner);assertEquals(70,bank.balance("Buyer"));assertEquals(30,store.diamondsSpent());}
    @Test void pendingPreventsAnotherPurchaseEvenForAnotherChunk() {start();bank.hideAfterCommit=true;buy();assertFalse(ClaimLand.buy(store,"world",1,0,"Buyer",30,now+1));assertEquals(70,bank.balance("Buyer"));assertNull(store.claimAt("world",1,0));}
    @Test void knownBankRefusalCancelsWithoutDomainOrMoney() {start();bank.refuse=true;assertFalse(buy());assertFalse(store.finance.busy());assertNull(store.financialPending());assertNull(store.claimAt("world",0,0));assertEquals(100,bank.balance("Buyer"));}
    @Test void sourcePrepareFailureDoesNotCommitCore() throws Exception {start();Files.createDirectory(dir.resolve("claims/claims-data.json.tmp"));assertFalse(buy());assertEquals(0,bank.commits);assertEquals(100,bank.balance("Buyer"));assertNull(store.claimAt("world",0,0));}
    @Test void sourcePrepareAfterRenameAmbiguityRecoversCanonicalIntent() {start();var original=store;store=new ClaimStore(dir.resolve("claims"),log,()->{throw new IOException("prepare uncertain");});store.load();store.accounts(bank);store.finance=new ClaimFinance(store,bank);assertFalse(buy());assertFalse(store.moneyAvailable());assertEquals(100,bank.balance("Buyer"));restart();assertEquals("Buyer",store.claimAt("world",0,0).owner);assertEquals(70,bank.balance("Buyer"));assertNotEquals(original,store);}
    @Test void ackAfterRenameFailureDoesNotOverwriteCommittedDomainAtShutdown() throws Exception {start();final int[] writes={0};store=new ClaimStore(dir.resolve("claims"),log,()->{if(++writes[0]==2)throw new IOException("ack uncertain");});store.load();store.accounts(bank);store.finance=new ClaimFinance(store,bank);assertFalse(buy());assertEquals(70,bank.balance("Buyer"));String ack=Files.readString(file());store.markDirty();store.save();assertEquals(ack,Files.readString(file()));restart();assertEquals("Buyer",store.claimAt("world",0,0).owner);assertEquals(1,store.financialSequence());assertEquals(70,bank.balance("Buyer"));}
    @Test void registrationAckDoesNotRecreateMissingCoreBinding() {start();UUID lineage=store.financialLineage();bank.root=new BankStore(dir.resolve("empty-root"),log);bank.root.load();assertFalse(store.finance.recover());assertFalse(bank.root.claimSourceRegistered(lineage));assertFalse(buy());}
    @Test void firstRegistrationPersistsSourceBeforeCallingCore() {start();assertTrue(Files.isRegularFile(file()));UUID lineage=store.financialLineage();assertTrue(store.financialRegistered());assertTrue(bank.root.claimSourceRegistered(lineage));restart();assertEquals(lineage,store.financialLineage());}
    @Test void missingRegistrationAckRetriesSamePersistedLineage() throws Exception {start();UUID lineage=store.financialLineage();var json=JsonParser.parseString(Files.readString(file())).getAsJsonObject();json.getAsJsonObject("finance").addProperty("registered",false);Files.writeString(file(),json.toString());restart();assertEquals(lineage,store.financialLineage());assertTrue(store.financialRegistered());}
    @Test void sellbackCreditAndRemovalAreRecoveredTogether() {start();var c=claim();bank.hideAfterCommit=true;assertFalse(ClaimLand.sellback(store,c,12));assertSame(c,store.claimAt("world",0,0));assertEquals(12,bank.balance("Owner"));restart();assertNull(store.claimAt("world",0,0));assertEquals(12,bank.balance("Owner"));}
    @Test void regionSaleMovesAllChunksRolesTaxAndAccountsOnce() {start();var c=claim();var adjacent=new Claim("world",1,0,"Owner",now);store.add(adjacent);c.salePrice=30;adjacent.salePrice=30;assertTrue(ClaimLand.trade(store,c,"Buyer",3));assertSame(c,store.claimAt("world",0,0));assertSame(adjacent,store.claimAt("world",1,0));assertEquals("Buyer",c.owner);assertEquals("Buyer",adjacent.owner);assertTrue(c.members.isEmpty());assertTrue(c.explicitRoles().isEmpty());assertEquals(3,store.taxBurned());assertEquals(27,bank.balance("Owner"));assertEquals(70,bank.balance("Buyer"));restart();assertEquals(3,store.taxBurned());}
    @Test void sellerCapRefusesEntireSaleAndKeepsMemberRoles() {start();var c=claim();c.salePrice=30;bank.root.setBalance(owner,"Owner",BankStore.MAX_BALANCE);assertFalse(ClaimLand.trade(store,c,"Buyer",3));assertEquals("Owner",c.owner);assertEquals(Claim.Role.ACCESS,c.roleOf("Friend"));assertEquals(100,bank.balance("Buyer"));assertEquals(0,store.taxBurned());}
    @Test void rentalPrepayDebtAndTenantAccessPublishTogetherAndPreserveReferences() {start();var c=claim();c.debtDays=2;c.rentOffer=30;assertNull(ClaimRentals.take(store,c,"Buyer",30,now,2));assertSame(c,store.claimAt("world",0,0));assertEquals("Buyer",c.tenant);assertEquals(24,bank.balance("Owner"));assertEquals(70,bank.balance("Buyer"));assertEquals(0,c.debtDays);assertEquals(now+ClaimMath.DAY_MILLIS,c.rentUntil);assertTrue(c.paidUntil>=c.rentUntil);restart();assertEquals("Buyer",store.claimAt("world",0,0).tenant);}
    @Test void rentalUnknownDoesNotGrantAccessAndPendingBlocksOfferStop() {start();var c=claim();c.rentOffer=30;bank.hideAfterCommit=true;assertEquals("finance.unavailable",ClaimRentals.take(store,c,"Buyer",30,now,2));assertEquals("",c.tenant);assertEquals("finance.unavailable",ClaimRentals.offer(store,c,"Owner",0,now));assertEquals("finance.unavailable",ClaimRentals.stop(store,c,"Buyer"));restart();assertEquals("Buyer",store.claimAt("world",0,0).tenant);assertEquals(70,bank.balance("Buyer"));}
    @Test void renewalUsesFrozenAgreedPriceAndNoDowntimeBackcharge() {start();var c=claim();c.rentOffer=10;assertNull(ClaimRentals.take(store,c,"Buyer",10,now,0));c.rentOffer=99;long late=now+30*ClaimMath.DAY_MILLIS;assertEquals(1,ClaimRentals.settle(store,c,late,0));assertEquals(late+ClaimMath.DAY_MILLIS,c.rentUntil);assertEquals(80,bank.balance("Buyer"));assertEquals(0,ClaimRentals.settle(store,c,late,0));}
    @Test void genuineShortageEndsRentalWithoutDebitButUnavailablePreservesIt() {start();var c=claim();c.rentOffer=100;assertNull(ClaimRentals.take(store,c,"Buyer",100,now,0));bank.hide=true;assertEquals(0,ClaimRentals.settle(store,c,now+ClaimMath.DAY_MILLIS,0));assertEquals("Buyer",c.tenant);bank.hide=false;assertEquals(-1,ClaimRentals.settle(store,c,now+ClaimMath.DAY_MILLIS,0));assertEquals("",c.tenant);assertEquals(0,bank.balance("Buyer"));}
    @Test void upkeepChargeDebtAndDateAreOneDomainTransition() {start();var c=claim();bank.root.deposit(owner,"Owner",12);c.debtDays=2;var step=ClaimUpkeep.day(store,c,3,3);assertNotNull(step);assertEquals(9,step.charged());assertEquals(0,c.debtDays);assertEquals(now+ClaimMath.DAY_MILLIS,c.paidUntil);assertEquals(3,bank.balance("Owner"));restart();assertEquals(now+ClaimMath.DAY_MILLIS,store.claimAt("world",0,0).paidUntil);}
    @Test void debtPaymentPublishesDecrementWithDebit() {start();var c=claim();c.debtDays=3;bank.root.deposit(owner,"Owner",7);assertEquals(6,ClaimUpkeep.debt(store,c,3));assertEquals(1,c.debtDays);assertEquals(1,bank.balance("Owner"));restart();assertEquals(1,store.claimAt("world",0,0).debtDays);}
    @Test void graceReleaseIsInSameDurableZeroMoneyTransition() {start();var c=claim();c.debtDays=3;bank.hideAfterCommit=true;assertNull(ClaimUpkeep.day(store,c,3,3));assertSame(c,store.claimAt("world",0,0));restart();assertNull(store.claimAt("world",0,0));assertEquals(0,bank.balance("Owner"));}
    @Test void upkeepUnavailableDoesNotAdvanceDateOrDebt() {start();var c=claim();bank.hide=true;assertNull(ClaimUpkeep.day(store,c,3,3));assertEquals(now,c.paidUntil);assertEquals(0,c.debtDays);}
    @Test void physicalFundingRemainsInPersonalBankIfDomainRefuses() {start();bank.root.setBalance(buyer,"Buyer",2);bank.physical=28;bank.refuse=true;assertFalse(buy());assertEquals(30,bank.balance("Buyer"));assertEquals(0,bank.physical);assertEquals(1,bank.fundCalls);assertNull(store.claimAt("world",0,0));}
    @Test void sourceRevisionChangeDuringFundingCannotPublishAnObsoleteDomain() {start();bank.root.setBalance(buyer,"Buyer",2);bank.physical=28;bank.afterFunding=()->store.addNotice("Buyer","notice");assertFalse(buy());assertNull(store.claimAt("world",0,0));assertEquals(30,bank.balance("Buyer"));assertNull(store.financialPending());}
    @Test void autosaveCannotReplaceFrozenPendingWithStaleRam() throws Exception {start();bank.hideAfterCommit=true;buy();String wal=Files.readString(file());store.markDirty();store.save();assertEquals(wal,Files.readString(file()));restart();assertEquals(30,store.diamondsSpent());}
    @Test void canonicalLongDatesAndRoleMapsSurviveFrozenRestartExactly() {start();var c=claim();c.paidUntil=9_007_199_254_740_993L;c.members.add("Another");c.setRole("Another",Claim.Role.CONTAINER);bank.hideAfterCommit=true;assertFalse(ClaimLand.buy(store,"other",2,3,"Buyer",1,now+1));restart();assertEquals(9_007_199_254_740_993L,store.claimAt("world",0,0).paidUntil);assertEquals(Claim.Role.ACCESS,store.claimAt("world",0,0).roleOf("Friend"));assertEquals(Claim.Role.CONTAINER,store.claimAt("world",0,0).roleOf("Another"));}
    @Test void malformedFinancialMetadataKeepsLandProtectedAndOriginalUntouched() throws Exception {start();claim();store.save();var json=JsonParser.parseString(Files.readString(file())).getAsJsonObject();json.getAsJsonObject("finance").addProperty("sequence",1.5);String bad=json.toString();Files.writeString(file(),bad);store=new ClaimStore(dir.resolve("claims"),log);store.load();assertFalse(store.moneyAvailable());assertNotNull(store.claimAt("world",0,0));store.save();assertEquals(bad,Files.readString(file()));}
    @Test void liveRestoreCannotRewindFiscalLineage() {start();assertEquals(ClaimStore.RestoreResult.PROTECTED,store.restoreFrom("claims-data.json.bak"));}
    @Test void alteredLiveReferenceCannotOverwriteAcceptedWal() throws Exception {start();var c=claim();c.rentOffer=30;bank.hideAfterCommit=true;ClaimRentals.take(store,c,"Buyer",30,now,2);String wal=Files.readString(file());c.owner="Tampered";store.markDirty();store.save();assertEquals(wal,Files.readString(file()));bank.hide=false;assertFalse(store.finance.recover());assertFalse(store.moneyAvailable());restart();assertEquals("Owner",store.claimAt("world",0,0).owner);assertEquals("Buyer",store.claimAt("world",0,0).tenant);}
    @Test void pendingBlocksStoreMutatorsAndPreservesDeferredNotices() {start();var c=claim();store.addNotice("Owner","saved");c.rentOffer=30;bank.hideAfterCommit=true;ClaimRentals.take(store,c,"Buyer",30,now,2);assertThrows(IllegalStateException.class,()->store.remove(c));assertThrows(IllegalStateException.class,()->store.setLimitOverride("Owner",5));assertThrows(IllegalStateException.class,()->store.addNotice("Owner","new"));assertTrue(store.pollNotices("Owner").isEmpty());restart();assertEquals(List.of("saved"),store.pollNotices("Owner"));}
    @Test void mutableReferenceChangeWithoutRevisionIsDetectedAfterFunding() {start();var c=claim();bank.root.setBalance(buyer,"Buyer",2);bank.physical=28;bank.afterFunding=()->c.owner="External";assertFalse(ClaimLand.buy(store,"other",2,3,"Buyer",30,now+1));assertEquals("External",c.owner);assertNull(store.claimAt("other",2,3));assertNull(store.financialPending());assertEquals(30,bank.balance("Buyer"));}
    @Test void clampedCatchupDateIsFrozenWithThePaidDay() {start();var c=claim();bank.root.deposit(owner,"Owner",10);long clamp=now+100*ClaimMath.DAY_MILLIS;assertNotNull(ClaimUpkeep.day(store,c,3,3,clamp));assertEquals(clamp,c.paidUntil);restart();assertEquals(clamp,store.claimAt("world",0,0).paidUntil);assertEquals(7,bank.balance("Owner"));}
    @Test void futureSchemaRetainsKnownLandButRefusesAllFinancialWrites() throws Exception {start();claim();store.save();var json=JsonParser.parseString(Files.readString(file())).getAsJsonObject();json.addProperty("version",5);String bad=json.toString();Files.writeString(file(),bad);store=new ClaimStore(dir.resolve("claims"),log);store.load();assertFalse(store.moneyAvailable());assertNotNull(store.claimAt("world",0,0));store.save();assertEquals(bad,Files.readString(file()));}

    @Test void overflowingSpendStatisticRefusesBeforeAnyNativeFunding() {start();store.addDiamondsSpent(Long.MAX_VALUE);bank.root.setBalance(buyer,"Buyer",2);bank.physical=28;assertFalse(buy());assertEquals(2,bank.balance("Buyer"));assertEquals(28,bank.physical);assertEquals(0,bank.fundCalls);assertNull(store.claimAt("world",0,0));assertEquals(Long.MAX_VALUE,store.diamondsSpent());}
    @Test void unreadableSourceCannotMakeUnknownLandUnprotected() throws Exception {start();String bad="{ broken JSON";Files.writeString(file(),bad);store=new ClaimStore(dir.resolve("claims"),log);store.load();assertFalse(store.moneyAvailable());assertNotNull(store.protectedClaimAt("world",123,456));store.save();assertEquals(bad,Files.readString(file()));}
    @Test void liveReloadCannotEvadeThePendingGuard() {start();bank.hideAfterCommit=true;buy();assertThrows(IllegalStateException.class,store::load);assertNotNull(store.financialPending());}

    boolean shareClan(Claim.Role role) {
        return store.finance.execute("clan-share", shadow -> ClaimClanSharing.set(shadow,"world",0,0,"Owner","stable-clan",role),Boolean.TRUE::equals,false);
    }
    @Test void sharingIsDurableWithoutChangingAnyPersonalOrCollectiveBalance() {
        start(); var c=claim(); long before=bank.balance("Buyer");
        assertTrue(shareClan(Claim.Role.CONTAINER)); assertSame(c,store.claimAt("world",0,0));
        assertEquals("stable-clan",c.clanId()); assertEquals(before,bank.balance("Buyer")); assertEquals(0,bank.balance("Owner"));
        restart(); assertEquals("stable-clan",store.claimAt("world",0,0).clanId());
        assertEquals(Claim.Role.ACCESS,store.claimAt("world",0,0).roleOf("Friend"));
    }
    @Test void sharingUnknownCommitKeepsOldProtectionUntilExactFrozenRecovery() {
        start(); claim(); bank.hideAfterCommit=true; assertFalse(shareClan(Claim.Role.BUILD));
        assertEquals("",store.claimAt("world",0,0).clanId()); assertNotNull(store.financialPending());
        restart(); assertEquals("stable-clan",store.claimAt("world",0,0).clanId());
        assertEquals(1,store.financialSequence()); assertEquals(0,bank.balance("Owner"));
    }
    @Test void rejectedSharingNeverPublishesMetadata() {
        start(); claim(); bank.refuse=true; assertFalse(shareClan(Claim.Role.BUILD));
        assertEquals("",store.claimAt("world",0,0).clanId()); assertNull(store.financialPending());
    }
    @Test void saleClearsOldClanBindingInsideSameMoneyDomain() {
        start(); var c=claim(); c.shareClan("old-clan",Claim.Role.BUILD); c.salePrice=30;
        assertTrue(ClaimLand.trade(store,c,"Buyer",3)); assertEquals("",store.claimAt("world",0,0).clanId());
        restart(); assertEquals("",store.claimAt("world",0,0).clanId());
    }

}
