package ru.cherdakmd.chrdkbp;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.BankStore;
import ru.cherdakmd.chrdkregister.RegistrationStore;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Core store/bank + BP store/witness: отдельные durable домены, без Bukkit/диска production. */
class BattlePassUpgradeLedgerTest {
    @TempDir Path folder;
    static final Logger LOG=Logger.getAnonymousLogger();
    final UUID owner=UUID.randomUUID();
    final int[] registrations={0};
    ChrdkRegisterApi api(BankStore bank,RegistrationStore core) {
        return (ChrdkRegisterApi)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ChrdkRegisterApi.class},(p,m,a)->switch(m.getName()) {
            case "personalBankAvailable" -> bank.available();
            case "incomeSourceAvailable" -> bank.incomeSourceAvailable((String)a[0],(UUID)a[1]);
            case "registerIncomeSource" -> {registrations[0]++;yield bank.registerIncomeSource((String)a[0],(UUID)a[1]);}
            case "grantBattlePassMoneyOnce" -> bank.grantBattlePassMoneyOnce((UUID)a[0],(long)a[2],(String)a[3]);
            case "grantRewardBoostOnce" -> core.grantRewardBoostOnce((UUID)a[0],(String)a[1],(int)a[2],24,(String)a[3]);
            default -> throw new AssertionError("Unexpected API call: "+m);
        });
    }
    @Test void previouslyClaimedBoostGetsOnlyNewCashAndLostAckSpendingAndSecondUpgradeCannotPayTwice() throws Exception {
        Path coreFolder=folder.resolve("core"),bpFolder=folder.resolve("bp");Files.createDirectories(coreFolder);Files.createDirectories(bpFolder);
        Path config=bpFolder.resolve("config.yml");try(var input=getClass().getResourceAsStream("/upgrade-1.4.281/config.yml")){assertNotNull(input);Files.copy(input,config);}
        var old=new YamlConfiguration();old.load(config.toFile());
        var oldSeason=BattlePassConfig.season("old",old.getString("battlepass.season-start"),List.of());
        String receipt=BattlePassClaims.receipt(oldSeason.startEpochMillis(),owner,"mine_diamonds","FREE");
        var core=new RegistrationStore(coreFolder,LOG);core.recordPlayerSeen(owner,"Steve");
        assertEquals(ChrdkRegisterApi.BoostGrant.ADDED,core.grantRewardBoostOnce(owner,"Steve",6,24,receipt));
        core.consumeRewardBoost("Steve",3600000);core.flushBlocking();
        var local=new BattlePassStore(bpFolder,LOG);local.load();local.setProgress(owner,"mine_diamonds",16,16);local.markClaimed(owner,"mine_diamonds","FREE");local.save();
        var bank=new BankStore(coreFolder,LOG);bank.load();bank.deposit(owner,"Steve",125);bank.saveNow();
        assertTrue(BattlePassStockMigration.apply(config,name->getClass().getResourceAsStream("/"+name)).updated());
        var current=new YamlConfiguration();current.load(config.toFile());var rows=new ArrayList<Map<?,?>>();for(Object row:current.getList("battlepass.weeks"))rows.add((Map<?,?>)row);
        var mission=BattlePassConfig.parseWeeks(rows).getFirst().missions().getFirst();
        var nextSeason=BattlePassConfig.season("new",current.getString("battlepass.season-start"),List.of());
        assertEquals(receipt,BattlePassClaims.receipt(nextSeason.startEpochMillis(),owner,mission.id(),"FREE"));
        assertFalse(BattlePassClaims.claimed(local,owner,mission,"FREE"));
        var api=api(bank,core);assertTrue(BattlePassMoneySource.initialize(bpFolder,api,LOG));
        assertEquals(ChrdkRegisterApi.BoostGrant.ALREADY,api.grantRewardBoostOnce(owner,"Steve",mission.freeHours(),receipt));
        assertEquals(ChrdkRegisterApi.MoneyGrant.ADDED,api.grantBattlePassMoneyOnce(owner,"Steve",mission.freeDiamonds(),receipt));
        // Core commit выполнен, локальный *_MONEY ACK ещё не записан: имитация разрыва между доменами.
        bank=new BankStore(coreFolder,LOG);bank.load();core=new RegistrationStore(coreFolder,LOG);api=api(bank,core);
        local=new BattlePassStore(bpFolder,LOG);local.load();
        assertFalse(BattlePassClaims.claimed(local,owner,mission,"FREE"));
        assertTrue(BattlePassMoneySource.initialize(bpFolder,api,LOG));assertEquals(1,registrations[0]);
        assertEquals(ChrdkRegisterApi.MoneyGrant.ALREADY,api.grantBattlePassMoneyOnce(owner,"Steve",mission.freeDiamonds(),receipt));
        local.markClaimed(owner,mission.id(),"FREE_MONEY");local.save();
        assertTrue(BattlePassClaims.claimed(local,owner,mission,"FREE"));assertEquals(525,bank.balance(owner));
        assertEquals(5L*3600000,core.rewardBoostRemaining("Steve"));
        assertTrue(bank.withdraw(owner,100));bank.saveNow();
        assertFalse(BattlePassStockMigration.apply(config,name->getClass().getResourceAsStream("/"+name)).updated());
        assertEquals(ChrdkRegisterApi.MoneyGrant.ALREADY,api.grantBattlePassMoneyOnce(owner,"Steve",4000,receipt));
        var reboot=new BankStore(coreFolder,LOG);reboot.load();assertEquals(425,reboot.balance(owner));
        var replay=new BattlePassStore(bpFolder,LOG);replay.load();assertTrue(BattlePassClaims.claimed(replay,owner,mission,"FREE"));
        assertEquals(16,replay.progressOf(owner,mission.id()));assertEquals(1,registrations[0]);
    }
}
