package ru.cherdakmd.chrdkregister.api;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import java.util.*;

/** Reconstructs the exact storage-only consume/grant. No inventory mutation, drops or repricing. */
public final class ShopInventory {
    private static boolean plain(ItemStack i) { return i!=null&&!i.getType().isAir()&&!i.hasItemMeta()&&i.getAmount()>0&&i.getAmount()<=i.getMaxStackSize(); }
    public static boolean valid(ShopExchange t) {
        try {
            if(t==null||!t.valid()) return false;
            Material material=Material.matchMaterial(t.material()); if(material==null||!material.isItem()) return false;
            for(String currency:t.rates().keySet()) { var m=Material.matchMaterial(currency); if(m==null||!m.isItem()) return false; }
            var items=ChargeInventory.decode(t.before());
            if(t.buy()) {
                long left=t.money()-t.debit(),paid=0; var order=new ArrayList<Integer>();
                for(int i=0;i<36;i++) if(plain(items[i])&&t.rates().containsKey(items[i].getType().name())) order.add(i);
                order.sort(Comparator.comparingInt(i->t.rates().get(items[i].getType().name())));
                for(int slot:order) {
                    if(left<=0) break;
                    var i=items[slot]; long rate=t.rates().get(i.getType().name()); int n=(int)Math.min(i.getAmount(),(left+rate-1)/rate);
                    paid+=n*rate; left-=n*rate; i.setAmount(i.getAmount()-n); if(i.getAmount()==0) items[slot]=null;
                }
                if(left>0||paid!=t.physical()) return false;
                long units=t.units(); int max=material.getMaxStackSize();
                for(int slot=0;slot<36&&units>0;slot++) if(plain(items[slot])&&items[slot].getType()==material) {
                    int n=(int)Math.min(units,max-items[slot].getAmount()); items[slot].setAmount(items[slot].getAmount()+n); units-=n;
                }
                for(int slot=0;slot<36&&units>0;slot++) if(items[slot]==null||items[slot].getType().isAir()) {
                    int n=(int)Math.min(units,max); items[slot]=new ItemStack(material,n); units-=n;
                }
                if(units!=0) return false;
            } else {
                long units=t.units();
                for(int slot=0;slot<36&&units>0;slot++) if(plain(items[slot])&&items[slot].getType()==material) {
                    int n=(int)Math.min(units,items[slot].getAmount()); items[slot].setAmount(items[slot].getAmount()-n); units-=n; if(items[slot].getAmount()==0) items[slot]=null;
                }
                if(units!=0) return false;
            }
            return ChargeInventory.matches(items,t.after());
        } catch(RuntimeException error) { return false; }
    }
    private ShopInventory() { }
}
