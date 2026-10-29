package ru.cherdakmd.chrdkregister;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
class MoneyLootTest {
 @TempDir Path dir;final UUID owner=UUID.randomUUID();
 MilestoneStore load(String source)throws Exception{return new MilestoneStore(dir.resolve(source),source);}
 BankStore bank(){var b=new BankStore(dir.resolve("core"),Logger.getAnonymousLogger());b.load();return b;}
 @Test void ordinaryUnitsDoNotConvertResourcesOrEquipment(){assertEquals(1,MoneyLoot.unit("DIAMOND"));assertEquals(9,MoneyLoot.unit("DIAMOND_BLOCK"));assertEquals(0,MoneyLoot.unit("DIAMOND_ORE"));assertEquals(0,MoneyLoot.unit("DIAMOND_SWORD"));}
 @Test void distinctEventMobIdsFreezeBeforeBankAndDoNotRequoteOnRetry()throws Exception{for(String source:List.of("events-loot")){var s=load(source);s.initialize(Map.of());String id="mob:"+UUID.randomUUID();s.reserve(owner,id,18);s=load(source);s.reserve(owner,id,99);s.reserve(owner,"mob:"+UUID.randomUUID(),2);assertEquals(20,load(source).totals().get(owner));var b=bank();var g=new MilestoneFinanceTest.Gateway(b);assertTrue(MilestoneAwards.deliver(s,g,source,owner,20));assertTrue(b.withdraw(owner,20));b.flush();assertTrue(MilestoneAwards.deliver(load(source),g,source,owner,20));assertEquals(0,b.balance(owner));}}
 @Test void unavailableAndUnknownOwnerPreserveSourceAcrossRestart()throws Exception{for(String source:List.of("events-loot")){var s=load(source);s.initialize(Map.of());s.reserve(owner,"mob:"+UUID.randomUUID(),9);var g=new MilestoneFinanceTest.Gateway(bank());g.available=false;assertFalse(MilestoneAwards.deliver(s,g,source,owner,9));g.available=true;g.known=false;assertFalse(MilestoneAwards.deliver(load(source),g,source,owner,9));assertEquals(9,load(source).totals().get(owner));g.known=true;assertTrue(MilestoneAwards.deliver(s,g,source,owner,9));}}
 @Test void fullAndPartialCapDoNotLoseAcceptedCustomLoot()throws Exception{var s=load("events-loot");s.initialize(Map.of());s.reserve(owner,"mob:"+UUID.randomUUID(),9);var b=bank();b.setBalance(owner,"Steve",BankStore.MAX_BALANCE);var g=new MilestoneFinanceTest.Gateway(b);assertFalse(MilestoneAwards.deliver(s,g,"events-loot",owner,9));b.withdraw(owner,4);assertFalse(MilestoneAwards.deliver(load("events-loot"),g,"events-loot",owner,9));b.withdraw(owner,5);assertTrue(MilestoneAwards.deliver(load("events-loot"),g,"events-loot",owner,9));assertEquals(BankStore.MAX_BALANCE,b.balance(owner));}
 @Test void failedSourceWriteDoesNotInventACredit()throws Exception{var s=load("events-loot");s.initialize(Map.of());Files.createDirectory(dir.resolve("events-loot/milestone-awards.json.tmp"));assertThrows(java.io.IOException.class,()->s.reserve(owner,"mob:"+UUID.randomUUID(),9));assertTrue(load("events-loot").totals().isEmpty());assertEquals(0,bank().balance(owner));}
 @Test void productionHasNoCurrencyPhysicalFallbackAndUsesEntityIdentity()throws Exception{String event=Files.readString(Path.of("events/src/main/java/ru/cherdakmd/chrdkevents/EventsListener.java"));assertTrue(event.contains("MoneyLoot.unit(picked.name())"));assertTrue(event.contains("event.getEntity().getUniqueId(),money);return;"));}
}
