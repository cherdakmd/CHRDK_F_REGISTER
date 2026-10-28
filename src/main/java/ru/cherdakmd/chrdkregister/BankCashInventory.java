package ru.cherdakmd.chrdkregister;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkregister.api.ChargeInventory;

/** Только storage 36 слотов. Named/components, offhand, броня и курсор не являются разменными деньгами. */
final class BankCashInventory {
    record Plan(ItemStack[] after,long amount) { }
    private static boolean plain(ItemStack stack,Material material) {
        return stack!=null&&stack.getType()==material&&!stack.hasItemMeta()&&stack.getAmount()>0&&stack.getAmount()<=64;
    }
    static Plan deposit(ItemStack[] before,int selected,long request,int blockWorth,long balance) {
        if(before==null||before.length!=36||blockWorth<1||blockWorth>64) return null;
        int[] counts=new int[36]; long[] values=new long[36];
        for(int i=0;i<36;i++) {
            if(plain(before[i],Material.DIAMOND)) { counts[i]=before[i].getAmount(); values[i]=1; }
            else if(request==-1&&plain(before[i],Material.DIAMOND_BLOCK)) { counts[i]=before[i].getAmount(); values[i]=blockWorth; }
        }
        var plan=BankDepositPlan.plan(counts,values,selected,request,balance); if(plan==null) return null;
        var after=ChargeInventory.copy(before);
        for(int i=0;i<36;i++) if(counts[i]!=plan.remaining()[i]) {
            if(plan.remaining()[i]==0) after[i]=null; else after[i].setAmount(plan.remaining()[i]);
        }
        return new Plan(after,plan.credit());
    }
    /** Чистый план вместимости снятия, используемый native adapter и тестами. -1 — чужой/недопустимый слот. */
    static int[] withdrawal(int[] amounts,long amount) {
        if(amounts==null||amounts.length!=36||amount<1||amount>2304) return null;
        var after=amounts.clone(); long left=amount;
        for(int i=0;i<36;i++) {
            if(amounts[i]<-1||amounts[i]>64) return null;
            if(after[i]<0) continue;
            int add=(int)Math.min(left,64-after[i]); after[i]+=add; left-=add;
        }
        return left==0?after:null;
    }
    static Plan withdraw(ItemStack[] before,long amount) {
        if(before==null||before.length!=36) return null;
        int[] counts=new int[36];
        for(int i=0;i<36;i++) counts[i]=before[i]==null||before[i].getType().isAir()?0:plain(before[i],Material.DIAMOND)?before[i].getAmount():-1;
        var plan=withdrawal(counts,amount); if(plan==null) return null;
        var after=ChargeInventory.copy(before);
        for(int i=0;i<36;i++) if(counts[i]!=plan[i]) after[i]=new ItemStack(Material.DIAMOND,plan[i]);
        return new Plan(after,amount);
    }
    static boolean valid(BankCash.Intent intent) {
        try {
            if(intent==null||!intent.valid()) return false;
            var before=ChargeInventory.decode(intent.before());
            var plan=intent.kind()==BankCash.Kind.DEPOSIT?deposit(before,intent.selected(),intent.request(),intent.blockWorth(),intent.beforeBalance()):withdraw(before,intent.amount());
            return plan!=null&&plan.amount()==intent.amount()&&ChargeInventory.matches(plan.after(),intent.after());
        } catch(RuntimeException error) { return false; }
    }
    private BankCashInventory() { }
}
