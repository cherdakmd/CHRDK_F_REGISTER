package ru.cherdakmd.chrdkregister;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import ru.cherdakmd.chrdkregister.api.DiamondExchangePolicy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class DiamondMarketStoreTest {
    @TempDir Path dir;
    private final UUID a=UUID.randomUUID(),b=UUID.randomUUID();
    private BankStore load(){var s=new BankStore(dir,Logger.getAnonymousLogger());s.load();return s;}
    @Test void minorTransfersTaxBalancesAndCountersSurviveRestart()throws Exception{
        var s=load();assertEquals(1000,s.depositMinor(a,"A",1000,true));assertEquals(1000,s.depositMinor(b,"B",1000,true));
        var r=s.transferMinor(a,"A",b,"B",123,2);assertTrue(r.ok());assertEquals(2,r.tax());assertEquals(121,r.credited());
        assertEquals(877,s.balanceMinor(a));assertEquals(1121,s.balanceMinor(b));
        s.flush();assertEquals(877,load().balanceMinor(a));assertEquals(1121,load().balanceMinor(b));
        assertEquals(10,JsonParser.parseString(Files.readString(s.file())).getAsJsonObject().get("schema").getAsInt());
    }
    @Test void quoteAndMarketFlowsSurviveRestartAndEveryRevalueIsBounded(){
        var s=load();s.depositMinor(a,"A",500_000,true);
        var q=s.revalueMarket(10,1_000_000);assertTrue(Math.abs(q.rateBps()-10_000)<=250);
        s.flush();var loaded=load();assertEquals(q.rateBps(),loaded.diamondRateBps());
        assertEquals(q.rateBps(),loaded.revalueMarket(10,1_000_001).rateBps());
        var second=loaded.revalueMarket(10,1_400_000);assertTrue(Math.abs(second.rateBps()-q.rateBps())<=250);
    }
    @Test void minorExchangeAndRewardsAreAtomicAndIdempotent(){
        var s=load();s.depositMinor(a,"A",1000,true);s.depositMinor(b,"B",1000,true);
        assertTrue(s.exchangeAccountsMinor(Map.of(a,-125L,b,125L),()->{}));
        assertEquals(875,s.balanceMinor(a));assertEquals(1125,s.balanceMinor(b));
        assertFalse(s.exchangeAccountsMinor(Map.of(a,-100_000L),()->{}));assertEquals(875,s.balanceMinor(a));
        String receipt="bp:"+"a".repeat(64);UUID lineage=UUID.nameUUIDFromBytes("CHRDK:BattlePass:money:v1".getBytes());
        assertTrue(s.registerIncomeSource("battlepass-rewards",lineage));
        assertEquals(ChrdkRegisterApi.MoneyGrant.ADDED,s.grantBattlePassMoneyMinorOnce(a,37,receipt));
        assertEquals(ChrdkRegisterApi.MoneyGrant.ALREADY,s.grantBattlePassMoneyMinorOnce(a,37,receipt));
        assertEquals(ChrdkRegisterApi.MoneyGrant.FAILED,s.grantBattlePassMoneyMinorOnce(a,38,receipt));
        assertEquals(912,s.balanceMinor(a));
    }
    @Test void marketCashCarriesExactBeforeAndDelta(){
        var s=load();s.depositMinor(a,"A",125,true);
        var intent=s.prepareMarketCash(a,"A",BankCash.Kind.DEPOSIT,1,75,1,-1,-1,"before","after");
        assertNotNull(intent);assertEquals(125,intent.beforeBalanceMinor());assertEquals(75,intent.minorDelta());
        assertTrue(intent.valid());assertTrue(s.cashLocked(a));assertEquals(125,s.balanceMinor(a));
    }
}
