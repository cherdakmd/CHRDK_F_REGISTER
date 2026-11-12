package ru.cherdakmd.diamondshop;

/** Безопасное восстановление lineage после потери регистрации в Core. */
final class ShopRegistration {
    /** Только рынок без единой принятой последовательности и без замороженной сделки. */
    static boolean canRestore(long sequence,boolean pending) { return sequence==0&&!pending; }
    static boolean canAdopt(long localSequence,boolean pending,long coreSequence) {
        return canRestore(localSequence,pending)&&coreSequence==0;
    }
    private ShopRegistration() { }
}
