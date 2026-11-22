package ru.cherdakmd.chrdkregister;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

/** Постепенная автоматическая миграция аккаунтов, включая офлайн-игроков. */
final class BoostMigrationRunner {
    private static final int SCAN_PER_TICK = 16;
    private final RegistrationStore store;
    private final BankStore bank;
    private final Logger logger;
    private final Set<String> warned = new HashSet<>();
    private int cursor;

    BoostMigrationRunner(RegistrationStore store, BankStore bank, Logger logger) {
        this.store = store; this.bank = bank; this.logger = logger;
    }

    /** Не более одного финансового перехода за вызов; остальным очередь не голодает. */
    void tick() {
        if (!bank.available()) return;
        var names = store.accountNames();
        if (names.isEmpty()) { cursor = 0; return; }
        int scanned = Math.min(SCAN_PER_TICK, names.size());
        for (int i = 0; i < scanned; i++) {
            String name = names.get(Math.floorMod(cursor++, names.size()));
            if (store.rewardBoostRemaining(name) <= 0) { warned.remove(name); continue; }
            try {
                var result = store.settleBoostCompensation(name, bank);
                if (result == RegistrationStore.BoostSettlement.COMPLETE || result == RegistrationStore.BoostSettlement.NONE) {
                    warned.remove(name);
                } else if (warned.add(name)) {
                    logger.warning("[boost-migration] Компенсация ожидает: " + name + " (" + result
                            + "). Остаток сохранён; проверьте банк и идентичность аккаунта, не редактируйте данные вручную.");
                }
            } catch (RuntimeException error) {
                if (warned.add(name)) logger.warning("[boost-migration] Ошибка обработки " + name + ": " + error.getMessage());
            }
            break;
        }
    }
}
