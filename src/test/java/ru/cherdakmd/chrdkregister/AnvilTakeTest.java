package ru.cherdakmd.chrdkregister;

import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.AnvilCosts;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Реальные Core handler + cost bridge; модель предметов/платформы, не CraftItemStack или live Minecraft. */
class AnvilTakeTest {
    @AfterEach void clear() { AnvilCosts.clearAll(); }

    static final class Stack extends ItemStack {
        final String fingerprint;
        int amount;
        Component name;
        Stack(String fingerprint,int amount) { this(fingerprint,amount,null); }
        Stack(String fingerprint,int amount,Component name) { super();this.fingerprint=fingerprint;this.amount=amount;this.name=name; }
        @Override public Material getType() { return Material.DIAMOND_SWORD; }
        @Override public boolean isEmpty() { return amount==0 || fingerprint.equals("air"); }
        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int amount) { this.amount=amount; }
        @Override public Stack clone() { return new Stack(fingerprint,amount,name); }
        @Override public ItemMeta getItemMeta() {
            Component[] value={name};
            return AnvilCostsTest.proxy(ItemMeta.class,(method,args) -> {
                if (method.equals("displayName")) {
                    if(args!=null && args.length==1) {value[0]=(Component)args[0];return null;}
                    return value[0];
                }
                return null;
            });
        }
        @Override public boolean setItemMeta(ItemMeta meta) { name=meta.displayName();return true; }
        @Override public boolean equals(Object other) { return other instanceof Stack s && amount==s.amount && fingerprint.equals(s.fingerprint) && Objects.equals(name,s.name); }
        @Override public int hashCode() { return Objects.hash(fingerprint,amount,name); }
        @Override public String toString() { return fingerprint+" x"+amount+" name="+name; }
    }
    static class Frame {
        ItemStack[] slots={new Stack("equipment:enchants:pdc:damage",1),new Stack("material:foreign:pdc",8),new Stack("repaired:enchants:pdc:damage",1)};
        ItemStack cursor=new Stack("air",0);
        int cost=80,materials=3,maximum=Integer.MAX_VALUE,level=100,total=1234;
        int charges=0,finishes=0,failures=0,updates=0;
        float progress=.37f;
        String rename="";
        GameMode mode=GameMode.SURVIVAL;
        boolean auth=true,online=true,dead=false,accessible=true;
        String failOnce;
        final List<ru.cherdakmd.chrdkregister.api.events.AnvilPaidResultEvent> paid=new ArrayList<>();
        void fault(String point) {
            if(point.equals(failOnce)) {failOnce=null;throw new IllegalStateException("injected "+point);}
        }
        final Player player=AnvilCostsTest.proxy(Player.class,(method,args) -> switch(method) {
            case "getGameMode"->mode; case "isOnline"->online;case "isDead"->dead;
            case "getLevel"->level;case "getTotalExperience"->total;case "getExp"->progress;
            case "giveExpLevels"->{level+=(int)args[0];charges++;fault("xp");yield null;}
            case "setLevel"->{level=(int)args[0];yield null;}
            case "setTotalExperience"->{total=(int)args[0];yield null;}
            case "setExp"->{progress=(float)args[0];yield null;}
            case "setItemOnCursor"->{cursor=args[0]==null?new Stack("air",0):(ItemStack)args[0];fault("cursor");yield null;}
            case "updateInventory"->{updates++;yield null;}
            default->null;
        });
        final AnvilInventory inventory=AnvilCostsTest.proxy(AnvilInventory.class,(method,args)-> switch(method) {
            case "getItem"->slots[(int)args[0]];
            case "setItem"->{int index=(int)args[0];slots[index]=(ItemStack)args[1];fault("slot"+index);yield null;}
            case "getSize"->3;
            default->null;
        });
        final AnvilView view=AnvilCostsTest.proxy(AnvilView.class,(method,args)->switch(method) {
            case "getPlayer"->player;case "getTopInventory"->inventory;case "getType"->InventoryType.ANVIL;
            case "getRepairCost"->cost;case "setRepairCost"->{cost=(int)args[0];yield null;}
            case "getRepairItemCountCost"->materials;case "getMaximumRepairCost"->maximum;case "getRenameText"->rename;
            case "getCursor"->cursor;case "getItem"->slots[(int)args[0]];case "convertSlot"->args[0];
            default->null;
        });
        final AnvilCostListener listener;
        Frame() {
            var messages=Messages.from(new java.io.ByteArrayInputStream("messages:\n  anvil.take-help: 'XP %xp%'\n  anvil.full-price: 'XP %xp%'\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)),null,java.util.logging.Logger.getAnonymousLogger());
            listener=new AnvilCostListener(() -> messages,p -> auth,(v,p)->accessible,(v,p)->finishes++,e -> failures++,paid::add);
        }
        void quote() { AnvilCosts.remember(view,slots[2]); }
        InventoryClickEvent event(ClickType click) {return new InventoryClickEvent(view,InventoryType.SlotType.RESULT,2,click,InventoryAction.PICKUP_ALL);}
        void take() { listener.take(event(ClickType.LEFT)); }
        void unchanged(ItemStack left,ItemStack right) {
            assertEquals(left,slots[0]);assertEquals(right,slots[1]);assertTrue(cursor.isEmpty());assertEquals(0,charges);assertEquals(0,finishes);
        }
    }
    @Test void paysFullEightyNotVisualThirtyNineAndKeepsExactMaterialRemainder() {
        var f=new Frame();var expected=f.slots[2].clone();f.quote();assertEquals(39,f.cost);
        var click=f.event(ClickType.LEFT);f.listener.take(click);
        assertTrue(click.isCancelled());assertEquals(20,f.level);assertEquals(1,f.charges);assertEquals(1,f.finishes);assertEquals(1,f.paid.size());assertEquals(80,f.paid.getFirst().levels());
        assertNull(f.slots[0]);assertNull(f.slots[2]);assertEquals(5,f.slots[1].getAmount());
        assertEquals(expected,f.cursor);assertEquals(-1,f.cost);assertNull(AnvilCosts.quote(f.view));
        f.take();assertEquals(1,f.charges);assertEquals(1,f.finishes);assertEquals(expected,f.cursor);
    }
    @Test void fortyCostsFortyAndInsufficientExperienceDoesNotSpendAnything() {
        var f=new Frame();f.cost=40;f.level=39;f.quote();var left=f.slots[0].clone();var right=f.slots[1].clone();
        f.take();f.unchanged(left,right);assertEquals(39,f.level);
        f.level=40;f.take();assertEquals(0,f.level);assertEquals(1,f.charges);
    }
    @Test void vanillaThirtyNineAndCreativeRemainNativeWhileAdventurePaysNormally() {
        var f=new Frame();f.cost=39;f.quote();var click=f.event(ClickType.LEFT);f.listener.take(click);
        assertFalse(click.isCancelled());assertEquals(0,f.charges);
        f=new Frame();f.mode=GameMode.CREATIVE;f.quote();click=f.event(ClickType.LEFT);f.listener.take(click);
        assertFalse(click.isCancelled());assertEquals(0,f.charges);
        f=new Frame();f.mode=GameMode.ADVENTURE;f.quote();f.take();assertEquals(20,f.level);assertEquals(1,f.finishes);
    }
    @Test void unsupportedClickTypesAndOccupiedCursorCannotTransferOrPay() {
        for(var click:List.of(ClickType.RIGHT,ClickType.SHIFT_LEFT,ClickType.NUMBER_KEY,ClickType.DOUBLE_CLICK,ClickType.DROP,ClickType.SWAP_OFFHAND)) {
            var f=new Frame();f.quote();var left=f.slots[0].clone();var right=f.slots[1].clone();
            f.listener.take(f.event(click));f.unchanged(left,right);
        }
        var f=new Frame();f.quote();var occupied=new Stack("cursor:not-owned",1);f.cursor=occupied;f.take();
        assertEquals(occupied,f.cursor);assertEquals(0,f.charges);assertNotNull(f.slots[0]);
    }
    @Test void failedAuthDeadOfflineSpectatorAndLostAnvilAccessCannotPay() {
        for(String scenario:List.of("auth","dead","offline","spectator","range")) {
            var f=new Frame();if(scenario.equals("spectator")) f.mode=GameMode.SPECTATOR;f.quote();
            switch(scenario) {case "auth"->f.auth=false;case "dead"->f.dead=true;case "offline"->f.online=false;case "range"->f.accessible=false;default->{}}
            var left=f.slots[0].clone();var right=f.slots[1].clone();f.take();f.unchanged(left,right);
        }
    }
    @Test void everyChangedQuoteFieldRejectsAStaleResult() {
        for(String scenario:List.of("left","right","result","amount","rename","materials","maximum","mode","price")) {
            var f=new Frame();f.quote();
            switch(scenario) {
                case "left"->f.slots[0]=new Stack("changed:pdc",1);case "right"->f.slots[1]=new Stack("changed:pdc",8);
                case "result"->f.slots[2]=new Stack("changed:pdc",1);case "amount"->f.slots[1].setAmount(7);
                case "rename"->f.rename="changed";case "materials"->f.materials=4;case "maximum"->f.maximum=40;
                case "mode"->f.mode=GameMode.ADVENTURE;case "price"->f.cost=38;
            }
            var left=f.slots[0].clone();var right=f.slots[1].clone();f.take();f.unchanged(left,right);
        }
    }
    @Test void eachMutationFailureRollsBackInputsCursorAndAllExperienceFields() {
        for(String point:List.of("slot2","slot0","slot1","xp","cursor")) {
            var f=new Frame();f.quote();var left=f.slots[0].clone();var right=f.slots[1].clone();f.failOnce=point;
            f.take();assertEquals(left,f.slots[0],point);assertEquals(right,f.slots[1],point);
            assertTrue(f.cursor.isEmpty(),point);assertNull(f.slots[2]);assertNull(AnvilCosts.quote(f.view));
            assertEquals(100,f.level,point);assertEquals(1234,f.total,point);assertEquals(.37f,f.progress,point);
            assertEquals(0,f.finishes,point);assertTrue(f.paid.isEmpty(),point);assertEquals(1,f.failures,point);
        }
    }
    @Test void cancelledEventsAreUntouchedAndNativeZeroMaterialCountConsumesTheWholeRightInput() {
        var f=new Frame();f.quote();var event=f.event(ClickType.LEFT);event.setCancelled(true);f.listener.take(event);
        assertEquals(0,f.charges);assertNotNull(f.slots[0]);
        f=new Frame();f.materials=0;f.quote();f.take();assertNull(f.slots[1]);assertEquals(1,f.charges);
    }
    @Test void impossibleMaterialCountNeverPaysOrConsumesAndCloseInvalidatesQuote() {
        var f=new Frame();f.materials=9;f.quote();var left=f.slots[0].clone();var right=f.slots[1].clone();
        f.take();f.unchanged(left,right);
        f.listener.close(new InventoryCloseEvent(f.view));assertNull(AnvilCosts.quote(f.view));f.take();f.unchanged(left,right);
    }
    @Test void prepareUsesItsOwnResultRatherThanStaleSlotAndExplicitThirtyNineWins() {
        var f=new Frame();f.quote();assertEquals(80,AnvilCosts.actual(f.view));
        var fresh=new Stack("fresh-native-result",1);
        assertEquals(39,AnvilCosts.actual(f.view,fresh));
        f.cost=80;AnvilCosts.price(f.view,39);AnvilCosts.remember(f.view,fresh);f.slots[2]=fresh;
        assertEquals(39,AnvilCosts.actual(f.view));assertEquals(39,AnvilCosts.quote(f.view).price());
    }
    @Test void renameOnlyCeilingDoesNotDiscountAChangedForeignPayload() {
        var f=new Frame();f.slots[1]=null;f.slots[2]=f.slots[0].clone();((Stack)f.slots[2]).name=Component.text("New name");
        AnvilCosts.capRename(f.view,f.slots[2]);assertEquals(39,f.cost);
        f.cost=80;f.slots[2]=new Stack("changed:foreign:pdc",1,Component.text("New name"));
        AnvilCosts.capRename(f.view,f.slots[2]);assertEquals(80,f.cost);
    }
    @Test void paidEventIsSynchronousNotCancellableAndSnapshotsCannotBeMutatedByListener() {
        var f=new Frame();f.quote();f.take();var event=f.paid.getFirst();
        assertSame(f.player,event.getPlayer());assertSame(f.view,event.view());assertFalse(event.isAsynchronous());
        assertFalse(org.bukkit.event.Cancellable.class.isInstance(event));
        assertSame(ru.cherdakmd.chrdkregister.api.events.AnvilPaidResultEvent.getHandlerList(),event.getHandlers());
        event.left().setAmount(42);event.right().setAmount(42);event.result().setAmount(42);
        assertEquals(1,event.left().getAmount());assertEquals(8,event.right().getAmount());assertEquals(1,event.result().getAmount());
        assertEquals(1,f.cursor.getAmount());
        assertThrows(IllegalArgumentException.class,()->new ru.cherdakmd.chrdkregister.api.events.AnvilPaidResultEvent(f.player,f.view,event.left(),null,event.result(),39));
    }
    @Test void nullAndZeroSizedResultsInvalidateTheQuoteWithoutResurrection() {
        var f=new Frame();f.quote();assertFalse(AnvilCosts.remember(f.view,new Stack("zero",0)));assertNull(AnvilCosts.quote(f.view));
        f.cost=80;f.quote();assertFalse(AnvilCosts.remember(f.view,null));assertNull(AnvilCosts.quote(f.view));
    }
}
