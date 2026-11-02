package ru.cherdakmd.chrdkbp;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import static org.junit.jupiter.api.Assertions.*;

class BattlePassMoney282Test {
    @TempDir Path folder;
    final Logger log=Logger.getAnonymousLogger();
    BattlePassConfig.MissionDef mission() {
        return new BattlePassConfig.MissionDef("one","One",BattlePassConfig.MissionType.MINE,"STONE",1,6,4,20,
                List.of(),"","",8,List.of(),40,20,60);
    }
    @Test void stockMoneyTotalsAreVisibleAndStockMigrationPreservesEveryGoalAndHour() {
        var current=YamlConfiguration.loadConfiguration(Path.of("src/main/resources/config.yml").toFile());
        var old=YamlConfiguration.loadConfiguration(Path.of("src/main/resources/battlepass-default-legacy-1.4.282.yml").toFile());
        List<Map<?,?>> weeks=new ArrayList<>(), prior=new ArrayList<>();
        for(Object row:current.getList("battlepass.weeks"))weeks.add((Map<?,?>)row);
        for(Object row:old.getList("battlepass.weeks"))prior.add((Map<?,?>)row);
        var now=BattlePassConfig.parseWeeksDetailed(weeks);assertTrue(now.problems().isEmpty(),now.problems().toString());
        var previous=BattlePassConfig.parseWeeks(prior);
        var all=now.weeks().stream().flatMap(w->w.missions().stream()).toList();
        assertEquals(1740,all.stream().mapToLong(BattlePassConfig.MissionDef::freeDiamonds).sum());
        assertEquals(870,all.stream().mapToLong(BattlePassConfig.MissionDef::vipDiamonds).sum());
        assertEquals(2610,all.stream().mapToLong(BattlePassConfig.MissionDef::premiumDiamonds).sum());
        for(var week:previous)for(var oldMission:week.missions()) {
            var next=all.stream().filter(m->m.id().equals(oldMission.id())).findFirst().orElseThrow();
            assertEquals(oldMission.target(),next.target());assertEquals(oldMission.freeHours(),next.freeHours());
            assertEquals(oldMission.vipHours(),next.vipHours());assertEquals(oldMission.premiumHours(),next.premiumHours());
        }
    }
    @Test void oldBoostClaimsCanReceiveMoneyWithoutRegrantingBoostAndMarkersSurviveRestart() {
        var store=new BattlePassStore(folder,log);store.load();UUID player=UUID.randomUUID();var mission=mission();
        store.markClaimed(player,"one","FREE");
        assertFalse(BattlePassClaims.claimed(store,player,mission,"FREE"));
        store.markClaimed(player,"one","FREE_MONEY");store.save();
        var restored=new BattlePassStore(folder,log);restored.load();
        assertTrue(BattlePassClaims.claimed(restored,player,mission,"FREE"));
        assertFalse(BattlePassClaims.claimed(restored,player,mission,"VIP"));
        assertTrue(restored.isClaimed(player,"one","FREE"));
    }
    @Test void moneyOnlyTrackIsReadyAndDoesNotRequireAnInventedBoostClaim() {
        var mission=new BattlePassConfig.MissionDef("one","One",BattlePassConfig.MissionType.MINE,"STONE",1,0,0,20,
                List.of(),"","",0,List.of(),40,20,60);
        var view=new BattlePassView.Mission(mission,1,false,false,true,false,true);
        assertEquals(BattlePassView.State.READY,view.state());assertEquals(3,view.totalTracks());
        var store=new BattlePassStore(folder,log);UUID player=UUID.randomUUID();
        store.markClaimed(player,"one","FREE_MONEY");
        assertTrue(BattlePassClaims.claimed(store,player,mission,"FREE"));
    }
    @Test void moneyWitnessPreventsRegistrationAfterBankDomainDisappears() throws Exception {
        boolean[] registered={false};int[] registrations={0};
        var api=(ChrdkRegisterApi)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ChrdkRegisterApi.class},(proxy,method,args)->switch(method.getName()) {
            case "personalBankAvailable" -> true;
            case "incomeSourceAvailable" -> registered[0];
            case "registerIncomeSource" -> { registrations[0]++;registered[0]=true;yield true; }
            default -> throw new UnsupportedOperationException(method.getName());
        });
        assertTrue(BattlePassMoneySource.initialize(folder,api,log));assertEquals(1,registrations[0]);
        assertTrue(BattlePassMoneySource.initialize(folder,api,log));assertEquals(1,registrations[0]);
        registered[0]=false;
        assertFalse(BattlePassMoneySource.initialize(folder,api,log));assertEquals(1,registrations[0]);
        Files.writeString(folder.resolve("battlepass-money.json"),"broken");
        assertFalse(BattlePassMoneySource.initialize(folder,api,log));
        assertEquals("broken",Files.readString(folder.resolve("battlepass-money.json")));
    }
}
