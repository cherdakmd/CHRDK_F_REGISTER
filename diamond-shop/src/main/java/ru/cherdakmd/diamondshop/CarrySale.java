package ru.cherdakmd.diamondshop;
import ru.cherdakmd.chrdkregister.api.ShopSaleRemainder;
/** Все оплачиваемые предметы, включая остатки; при лимите непоместившееся остаётся у игрока. */
final class CarrySale {
    static int maximum(String remainder,int batch,long price,long available,long budget) {
        if(available<1 || budget<0) return 0;
        int low=0, high=(int)Math.min(2304,available);
        try {
            while(low<high) {
                int mid=low+(high-low+1)/2;
                if(ShopSaleRemainder.quote(remainder,batch,price,mid).money()<=budget) low=mid; else high=mid-1;
            }
            return low;
        } catch(RuntimeException error) { return 0; }
    }
    private CarrySale() { }
}
