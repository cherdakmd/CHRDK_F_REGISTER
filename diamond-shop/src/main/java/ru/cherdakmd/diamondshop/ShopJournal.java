package ru.cherdakmd.diamondshop;

import ru.cherdakmd.chrdkregister.api.ShopExchange;

/** Frozen source effect. One global pending prevents virtual stock/quota interleaving. */
final class ShopJournal {
    record Pending(ShopExchange trade,long stock,long sold,long bought,long time,long day,double used,double turnover,
                   double price,int historyPoints,long historyInterval) {
        boolean valid() {
            return trade!=null&&trade.valid()&&stock>=1&&sold>=0&&bought>=0&&time>=0&&day==time-Math.floorMod(time,86400000L)
                    &&(used==-1||Double.isFinite(used)&&used>=trade.money())&&Double.isFinite(turnover)&&turnover>=trade.money()
                    &&Double.isFinite(price)&&price>0&&historyPoints>=4&&historyPoints<=168&&historyInterval>=0;
        }
        boolean matches(ShopStore.ItemState state,double oldUsed,double oldTurnover) {
            try {
                return valid()&&state!=null&&stock==Math.max(1,Math.addExact(state.stock,trade.buy()?-trade.units():trade.units()))
                    &&sold==Math.addExact(state.soldByPlayers,trade.buy()?0:trade.units())
                    &&bought==Math.addExact(state.boughtByPlayers,trade.buy()?trade.units():0)
                    &&turnover==PriceEngine.round(oldTurnover+trade.money())
                    &&(used==-1||used==PriceEngine.round(oldUsed+trade.money()));
            } catch(ArithmeticException e) { return false; }
        }
    }
    interface Bank {
        ShopExchange.Status status(ShopExchange t);
        ShopExchange.Status commit(ShopExchange t);
    }
    /** Bank receipt may survive newer owner cash exchanges: query market receipt, not current inventory shape. */
    static boolean recover(ShopStore store,Bank bank) {
        var pending=store.pending(); if(pending==null) return store.legacyAvailable();
        if(!store.legacyAvailable()) return false;
        var status=bank.status(pending.trade());
        if(status==ShopExchange.Status.MISSING||status==ShopExchange.Status.PREPARED) status=bank.commit(pending.trade());
        if(status==ShopExchange.Status.CONFLICT) { store.stop("Конфликт квитанции рынка; не сбрасывайте журнал"); return false; }
        return status==ShopExchange.Status.COMMITTED&&store.finish(pending);
    }
    private ShopJournal() { }
}
