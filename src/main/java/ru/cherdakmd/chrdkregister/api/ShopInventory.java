package ru.cherdakmd.chrdkregister.api;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import java.util.*;

/** Reconstructs the exact storage-only consume/grant. No inventory mutation, drops or repricing.
 * API42: для замороженного гранта воспроизводится та же оплата и вставка exact isSimilar clone.
 * Книги чар проверются по снимкам слотов: они не стакаются и не пересоздаются из материала. */
public final class ShopInventory {
    private static boolean plain(ItemStack i) { return i!=null&&!i.getType().isAir()&&!i.hasItemMeta()&&i.getAmount()>0&&i.getAmount()<=i.getMaxStackSize(); }

    /** Точная вставка клонов PDC-прототипа: сначала в похожий неполный стак, затем в пустой слот. */
    public static boolean insertExact(ItemStack[] items, ItemStack prototype, int units) {
        if (items == null || items.length != 36 || prototype == null || units < 1) return false;
        int maximum = prototype.getMaxStackSize();
        if (maximum < 1) return false;
        int left = units;
        for (int slot = 0; slot < 36 && left > 0; slot++) {
            ItemStack stack = items[slot];
            if (stack != null && !stack.getType().isAir() && stack.isSimilar(prototype)) {
                int room = stack.getMaxStackSize() - stack.getAmount();
                if (room <= 0) continue;
                int add = Math.min(left, room);
                stack.setAmount(stack.getAmount() + add);
                left -= add;
            }
        }
        for (int slot = 0; slot < 36 && left > 0; slot++) {
            if (items[slot] != null && !items[slot].getType().isAir()) continue;
            int add = Math.min(left, maximum);
            ItemStack clone = prototype.clone();
            clone.setAmount(add);
            items[slot] = clone;
            left -= add;
        }
        return left == 0;
    }

    /** Полный native after, восстановленный из before. null — сделка невоспроизводима. */
    public static ItemStack[] replay(ShopExchange t) {
        try {
            if(t==null||!t.valid()) return null;
            Material material;
            for(String currency:t.rates().keySet()) { var m=Material.matchMaterial(currency); if(m==null||!m.isItem()) return null; }
            var items=ChargeInventory.decode(t.before());
            if(ChargeInventory.matches(items,t.after())) return null;
            // Ключ книги не является ванильным Material: книжная ветка идёт до matchMaterial.
            if(t.isBook()) return validBook(t,items)?items:null;
            material=Material.matchMaterial(t.material()); if(material==null||!material.isItem()) return null;
            if(t.buy()) {
                long left=physicalLeft(t),paid=0; var order=new ArrayList<Integer>();
                for(int i=0;i<36;i++) if(plain(items[i])&&t.rates().containsKey(items[i].getType().name())) order.add(i);
                order.sort(Comparator.comparingInt(i->t.rates().get(items[i].getType().name())));
                for(int slot:order) {
                    if(left<=0) break;
                    var i=items[slot]; long rate=t.rates().get(i.getType().name()); int n=(int)Math.min(i.getAmount(),(left+rate-1)/rate);
                    paid+=n*rate; left-=n*rate; i.setAmount(i.getAmount()-n); if(i.getAmount()==0) items[slot]=null;
                }
                if(left>0||paid!=t.physical()) return null;
                if(t.carriesGrant()) {
                    var prototype=ForgeMarketCatalog.prototype(t.grant());
                    if(prototype==null||!insertExact(items,prototype,(int)t.units())) return null;
                } else {
                    long units=t.units(); int max=material.getMaxStackSize();
                    for(int slot=0;slot<36&&units>0;slot++) if(plain(items[slot])&&items[slot].getType()==material) {
                        int n=(int)Math.min(units,max-items[slot].getAmount()); items[slot].setAmount(items[slot].getAmount()+n); units-=n;
                    }
                    for(int slot=0;slot<36&&units>0;slot++) if(items[slot]==null||items[slot].getType().isAir()) {
                        int n=(int)Math.min(units,max); items[slot]=new ItemStack(material,n); units-=n;
                    }
                    if(units!=0) return null;
                }
            } else {
                long units=t.units();
                for(int slot=0;slot<36&&units>0;slot++) if(plain(items[slot])&&items[slot].getType()==material) {
                    int n=(int)Math.min(units,items[slot].getAmount()); items[slot].setAmount(items[slot].getAmount()-n); units-=n; if(items[slot].getAmount()==0) items[slot]=null;
                }
                if(units!=0) return null;
            }
            return items;
        } catch(RuntimeException error) { return null; }
    }

    public static boolean valid(ShopExchange t) {
        var items = replay(t);
        return items != null && ChargeInventory.matches(items, t.after());
    }

    /**
     * Книги чар не стакаются и не пересоздаются здесь: сверяем снимки по слотам.
     * Добавленная/изъятая книга обязана быть помеченной магазином и точно
     * соответствовать ключу товара (см. {@link ShopBooks}).
     */
    private static boolean validBook(ShopExchange t,ItemStack[] working) {
        if(!t.material().equals(t.book())||ShopBookKey.fromKey(t.book())==null) return false;
        long units=t.units();
        if(units>36) return false;
        long left=physicalLeft(t),paid=0;
        if(t.buy()) {
            var order=new ArrayList<Integer>();
            for(int i=0;i<36;i++) if(plain(working[i])&&t.rates().containsKey(working[i].getType().name())) order.add(i);
            order.sort(Comparator.comparingInt(i->t.rates().get(working[i].getType().name())));
            for(int slot:order) {
                if(left<=0) break;
                var i=working[slot]; long rate=t.rates().get(i.getType().name()); int n=(int)Math.min(i.getAmount(),(left+rate-1)/rate);
                paid+=n*rate; left-=n*rate; i.setAmount(i.getAmount()-n); if(i.getAmount()==0) working[slot]=null;
            }
            if(left>0||paid!=t.physical()) return false;
        } else if(t.physical()!=0) return false;
        var after=ChargeInventory.decode(t.after());
        long changed=0;
        for(int i=0;i<36;i++) {
            if(Objects.equals(working[i],after[i])) continue;
            if(t.buy()) {
                if(working[i]!=null||after[i]==null||after[i].getAmount()!=1||!ShopBooks.matchesKey(after[i],t.book())) return false;
            } else {
                if(after[i]!=null||working[i]==null||working[i].getAmount()!=1||!ShopBooks.matchesKey(working[i],t.book())) return false;
            }
            working[i]=after[i]; changed++;
        }
        return changed==units&&ChargeInventory.matches(working,t.after());
    }
    private static long physicalLeft(ShopExchange t) {
        long bankMinor = t.fractional() ? t.debit() : Math.multiplyExact(t.debit(), DiamondMoney.SCALE);
        long leftMinor = Math.multiplyExact(t.money(), t.fractional() ? 1 : DiamondMoney.SCALE) - bankMinor;
        return (leftMinor + DiamondMoney.SCALE - 1) / DiamondMoney.SCALE;
    }
    private ShopInventory() { }
}
