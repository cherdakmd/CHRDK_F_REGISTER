package ru.cherdakmd.chrdkteleports;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
class HomeFinanceTest {
    @TempDir Path dir;
    final UUID owner=UUID.randomUUID();
    HomeStore homes() { return new HomeStore(dir,Logger.getAnonymousLogger()); }
    final HomeLocation point=new HomeLocation("Дом","world",1.5,64,2.5,90,0);
    @Test void importsHomesOnceAndPaidSourceWinsOverStaleLegacyFile() throws Exception {
        var h=homes(); h.put(owner,point); h.saveNow(); var source=new PaidActionStore(dir); h.bind(source); h.remove(owner,"дом");
        var restart=homes(); restart.bind(new PaidActionStore(dir)); assertNull(restart.get(owner,"дом")); assertTrue(Files.readString(dir.resolve("teleports-data.json")).contains("Дом"));
    }
    @Test void paidHomeIsPublishedAtomicallyWithReceiptAndFreeEditsRemainDurable() throws Exception {
        var source=new PaidActionStore(dir); source.markRegistered(); var h=homes(); h.bind(source);
        String after=source.previewValue(HomeStore.financeKey(owner,point.name()),HomeStore.financeValue(point)); assertNull(h.get(owner,"дом"));
        source.publish(source.snapshot(),after); assertEquals(point,h.get(owner,"дом"));
        h.put(owner,new HomeLocation("bed","world",2,65,3,0,0)); var restart=homes(); restart.bind(new PaidActionStore(dir)); assertEquals(1,restart.countPlayerHomes(owner)); assertEquals(2,restart.count(owner));
    }
    @Test void corruptLegacyNeverOverwritesExistingDataOnDisable() throws Exception {
        String bad="{broken"; Files.writeString(dir.resolve("teleports-data.json"),bad); var h=homes(); assertThrows(IllegalStateException.class,h::load); h.saveNow(); assertEquals(bad,Files.readString(dir.resolve("teleports-data.json")));
    }
    @Test void nonemptyValuesWithoutImportFlagAreNeverOverwrittenByLegacyHomes() throws Exception {
        var source=new PaidActionStore(dir); source.putValue(HomeStore.financeKey(owner,point.name()),HomeStore.financeValue(point));
        var h=homes(); assertThrows(java.io.IOException.class,()->h.bind(source));
        assertEquals(HomeStore.financeValue(point),source.values().get(HomeStore.financeKey(owner,point.name())));
    }
    @Test void actualTeleportPricesCannotBeZeroButSettingPointCan() {
        var c=new org.bukkit.configuration.file.YamlConfiguration(); c.set("costs.home-diamonds",0); c.set("costs.rtp-diamonds",0); c.set("costs.tpa-diamonds",0);
        var parsed=TpConfig.parse(c,Logger.getAnonymousLogger()); assertEquals(1,parsed.costHome()); assertEquals(1,parsed.costRtp()); assertEquals(1,parsed.costTpa()); assertEquals(0,parsed.costSethome());
    }
    @Test void tpaPriceIsFrozenInRequestRatherThanReloadedAfterAcceptance() {
        var r=new TpaRequests(); var target=UUID.randomUUID();
        assertEquals(TpaRequests.CreateResult.OK,r.create(owner,target,true,1000,60,7));
        assertEquals(7,r.accept(target,2000).quotedCost());
        assertThrows(IllegalArgumentException.class,()->r.create(owner,target,false,3000,60,0));
    }
    @Test void productionCommandsUseOutcomeProtocolInsteadOfPhysicalWallet() throws Exception {
        String code=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkteleports/TeleportCommandHandler.java"));
        assertFalse(code.contains("DiamondWallet")); assertTrue(code.contains("payments().perform")); assertTrue(code.contains("payments().putValue")); assertTrue(code.contains("PaidActions.teleport(mover,target)"));
    }
}
