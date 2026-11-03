package ru.cherdakmd.chrdkregister;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.view.AnvilView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.AnvilCosts;
import java.lang.reflect.Proxy;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Реальный cost bridge, интерфейсы Bukkit без сервера; не live-проверка клиента. */
class AnvilCostsTest {
    @AfterEach void clear() { AnvilCosts.clearAll(); }
    static class Frame {
        int price=39, materials=1, maximum=Integer.MAX_VALUE;
        String rename="name";
        GameMode mode=GameMode.SURVIVAL;
        final Player player=proxy(Player.class,(method,args)-> switch(method) {case "getGameMode"->mode; default->null;});
        final AnvilInventory inventory=proxy(AnvilInventory.class,(method,args)->null);
        final AnvilView view=proxy(AnvilView.class,(method,args)-> switch(method) {
            case "getPlayer"->player; case "getTopInventory"->inventory; case "getRepairCost"->price;
            case "setRepairCost"->{price=(int)args[0];yield null;}
            case "getRepairItemCountCost"->materials; case "getMaximumRepairCost"->maximum; case "getRenameText"->rename; default->null;
        });
        AnvilCosts.Quote quote(int full) {return new AnvilCosts.Quote(null,null,null,rename,full,materials,maximum,mode);}
    }
    interface Call { Object invoke(String method,Object[] args); }
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,Call call) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->switch(m.getName()) {
            case "hashCode"->System.identityHashCode(p); case "equals"->p==a[0]; case "toString"->type.getSimpleName();
            default->call.invoke(m.getName(),a);
        });
    }
    @SuppressWarnings("unchecked") void install(Frame frame,AnvilCosts.Quote quote) throws Exception {
        var field=AnvilCosts.class.getDeclaredField("QUOTES");field.setAccessible(true);
        ((Map<AnvilView,AnvilCosts.Quote>)field.get(null)).put(frame.view,quote);
    }
    @Test void ordinaryAdventureUsesVanillaXpButSpectatorCannotTake() {
        assertTrue(ru.cherdakmd.chrdkregister.api.AnvilUse.usesLevels(GameMode.SURVIVAL));
        assertTrue(ru.cherdakmd.chrdkregister.api.AnvilUse.usesLevels(GameMode.ADVENTURE));
        assertFalse(ru.cherdakmd.chrdkregister.api.AnvilUse.usesLevels(GameMode.SPECTATOR));
        assertFalse(ru.cherdakmd.chrdkregister.api.AnvilUse.usesLevels(GameMode.CREATIVE));
        assertFalse(ru.cherdakmd.chrdkregister.api.AnvilUse.usesLevels(null));
    }
    @Test void displayNeverShowsFortyButFullPriceIsNotClamped() {
        assertEquals(-1,AnvilCosts.display(-1));assertEquals(0,AnvilCosts.display(0));
        assertEquals(39,AnvilCosts.display(39));assertEquals(39,AnvilCosts.display(40));
        assertEquals(39,AnvilCosts.display(Integer.MAX_VALUE));
        assertEquals(120,AnvilCosts.renamePrice(120,false));assertEquals(39,AnvilCosts.renamePrice(120,true));
    }
    @Test void quoteRecoversFullPriceInsteadOfChargingClientThirtyNine() throws Exception {
        var f=new Frame();install(f,f.quote(120));
        assertEquals(120,AnvilCosts.actual(f.view));assertEquals(120,AnvilCosts.quote(f.view).price());
        assertEquals(39,AnvilCosts.actual(new Frame().view));
    }
    @Test void explicitNewPriceThirtyNineOverridesPreviousExpensiveQuote() throws Exception {
        var f=new Frame();install(f,f.quote(120));AnvilCosts.price(f.view,39);
        assertEquals(39,AnvilCosts.actual(f.view));
    }
    @Test void priceWritesAndRejectionAreVisibleToCustomEnchantBridge() {
        var f=new Frame();AnvilCosts.price(f.view,85);assertEquals(85,AnvilCosts.actual(f.view));
        AnvilCosts.price(f.view,-1);assertEquals(-1,AnvilCosts.actual(f.view));
    }
    @Test void inputContextChangesCannotReuseOldFullPrice() throws Exception {
        var f=new Frame();var quote=f.quote(120);install(f,quote);
        f.rename="changed";assertFalse(quote.matches(f.view));assertEquals(39,AnvilCosts.actual(f.view));
        f.rename="name";f.materials=2;assertFalse(quote.matches(f.view));
        f.materials=1;f.maximum=40;assertFalse(quote.matches(f.view));
        f.maximum=Integer.MAX_VALUE;f.mode=GameMode.CREATIVE;assertFalse(quote.matches(f.view));
    }
    @Test void closingAndClearAllDiscardQuotesAndWriteHints() throws Exception {
        var f=new Frame();install(f,f.quote(120));AnvilCosts.price(f.view,95);AnvilCosts.clear(f.view);
        assertNull(AnvilCosts.quote(f.view));assertEquals(95,AnvilCosts.actual(f.view));
        install(f,f.quote(95));AnvilCosts.clearAll();assertNull(AnvilCosts.quote(f.view));
    }
    @Test void nullResultIsNotResurrectedOrCharged() throws Exception {
        var f=new Frame();install(f,f.quote(120));assertFalse(AnvilCosts.remember(f.view,null));assertNull(AnvilCosts.quote(f.view));
    }
}
