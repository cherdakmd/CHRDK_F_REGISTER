package ru.cherdakmd.chrdkauction;

import ru.cherdakmd.chrdkregister.api.AuctionPayment;

/** Квитанция — авторитет оплаты. Снимок нужен только для физической части. */
final class AuctionRecovery {
    enum Decision { COMPLETE, APPLY_AFTER, ROLLBACK, WAIT, CONFLICT }
    static Decision decide(AuctionPayment.Status status, long cash, boolean before, boolean after) {
        return decide(status,cash,before,after,false);
    }
    static Decision decide(AuctionPayment.Status status,long cash,boolean before,boolean after,boolean witness) {
        if (status == null || status == AuctionPayment.Status.UNAVAILABLE) return Decision.WAIT;
        if (status == AuctionPayment.Status.CONFLICT) return Decision.CONFLICT;
        if (cash == 0) return status == AuctionPayment.Status.COMMITTED ? Decision.COMPLETE : Decision.ROLLBACK;
        if (status == AuctionPayment.Status.COMMITTED) return witness ? Decision.COMPLETE : before&&!after ? Decision.APPLY_AFTER : Decision.CONFLICT;
        return !witness&&before&&!after ? Decision.ROLLBACK : Decision.CONFLICT;
    }
    private AuctionRecovery() { }
}
