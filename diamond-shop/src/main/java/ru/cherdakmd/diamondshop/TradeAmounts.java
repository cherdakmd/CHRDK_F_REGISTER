package ru.cherdakmd.diamondshop;

/** Поштучная торговля: только целые алмазы, без изменения цены эталонной партии. */
final class TradeAmounts {
    static final int MAX_UNITS=2304;
    private static boolean valid(int batch,long price){return batch>=1&&batch<=MAX_UNITS&&price>=1&&price<=WholeTrades.MAX;}
    /** Покупка округляется вверх, продажа вниз; нулевая продажа — только preview, не сделка. */
    static long quote(int batch,long price,long units,boolean buy){
        if(!valid(batch,price)||units<1||units>MAX_UNITS)return -1;
        long product=price*units;
        long money=buy?(product+batch-1)/batch:product/batch;
        return money>WholeTrades.MAX?-1:money;
    }
    static int minimumSale(int batch,long price){return valid(batch,price)?(int)Math.max(1,(batch+price-1)/price):0;}
    /** Максимальная выручка в пределах бюджета, но минимум предметов для этой выручки. */
    static int saleAll(int batch,long price,long available,long budget){
        if(!valid(batch,price)||available<1||budget<1)return 0;
        budget=Math.min(budget,WholeTrades.MAX);
        long max=Math.min(Math.min(available,MAX_UNITS),((budget+1)*batch-1)/price);
        if(max<1)return 0;
        long income=price*max/batch;
        return income==0?0:(int)((income*batch+price-1)/price);
    }
    /** Объём и котировка принимаются вместе: исполнение не выбирает цену повторно. */
    record Sale(int batch,long price,int units,long money) {
        Sale {
            if(money<1||quote(batch,price,units,false)!=money)throw new IllegalArgumentException("sale quote");
        }
    }
    static Sale selectSale(int batch,long price,long available,long budget){
        int units=saleAll(batch,price,available,budget);
        return units==0?null:new Sale(batch,price,units,quote(batch,price,units,false));
    }
    static int adjust(int current,int delta){return (int)Math.max(1,Math.min(MAX_UNITS,(long)current+delta));}
    private TradeAmounts(){}
}
