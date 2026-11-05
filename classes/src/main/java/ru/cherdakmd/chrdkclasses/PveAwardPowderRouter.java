package ru.cherdakmd.chrdkclasses;

import ru.cherdakmd.chrdkregister.pve.PveAwardContract;
import ru.cherdakmd.chrdkregister.pve.PveAwardLedger;
import ru.cherdakmd.chrdkregister.pve.PveDeathEvidence;
import ru.cherdakmd.chrdkregister.pve.PveRecipient;
import ru.cherdakmd.chrdkregister.pve.PveVerdict;
import ru.cherdakmd.chrdkskills.tree.PerkTreePowderSource;
import ru.cherdakmd.chrdkskills.tree.PerkTreeState;
import ru.cherdakmd.chrdkskills.tree.PerkTreeStore;
import ru.cherdakmd.chrdkskills.tree.TreeDomain;

import java.util.UUID;

/**
 * Первый подключённый получатель общего PvE-контракта: пыль деревьев перков.
 *
 * <p>Вызывается из уже существующего {@link PveProgressListener#death} <b>одним
 * вызовом</b> после подтверждения смерти моба. Нового листенера на
 * {@code EntityDamageByEntityEvent} нет, вклад и происхождение не
 * перепроверяются заново: их уже собрал листенер, а решение принимает
 * {@link PveAwardContract}.</p>
 *
 * <p>Фаза 2: состояние, квитанции и дневные счётчики живут в
 * {@link PerkTreeStore} на диске, а не в памяти. Дубликат смерти проверяется
 * дважды — общим реестром {@link PveAwardLedger} (все шесть получателей, общий
 * дневной бюджет) и долговечной квитанцией самого дерева (защита после
 * рестарта). Запись на диск идёт снимком через пульс плагина, а не на каждого
 * убитого моба.</p>
 */
final class PveAwardPowderRouter {

    static final long DAILY_POWDER_CAP = 2_000L;

    private final PerkTreeStore store;

    PveAwardPowderRouter(PerkTreeStore store) {
        this.store = store;
    }

    long accept(PveDeathEvidence evidence, PveVerdict verdict,
                TreeDomain domain, int classLevel) {
        if (evidence == null || verdict == null || !verdict.allowed() || domain == null
                || !ClassPerkTreeBridge.isClassSourced(domain) || !store.available()) {
            return 0L;
        }
        store.beginDay(evidence.dayKey());
        if (!PveAwardLedger.shared().register(evidence.playerId(),
                PveAwardContract.receipt(evidence.deathId(), PveRecipient.PERK_POWDER,
                        evidence.dayKey()))) {
            return 0L;
        }
        if (store.hasReceipt(evidence.playerId(), evidence.dayKey(), evidence.deathId())) {
            return 0L;
        }
        PerkTreeState state = store.state(evidence.playerId(), domain);
        if (state == null) {
            state = ClassPerkTreeBridge.initialState(domain, classLevel);
        }
        long creditedToday = store.creditedToday(evidence.playerId(), evidence.dayKey());
        PerkTreePowderSource.Result result = PerkTreePowderSource.credit(state, domain, verdict,
                PerkTreePowderSource.BASE_POWDER, DAILY_POWDER_CAP, creditedToday);
        if (!result.applied()) {
            return 0L;
        }
        store.putState(evidence.playerId(), result.state());
        store.addReceipt(evidence.playerId(), evidence.dayKey(), evidence.deathId());
        store.addCredited(evidence.playerId(), evidence.dayKey(), result.credited());
        return result.credited();
    }

    void flush() {
        store.flush();
    }

    void forget(UUID player) {
        store.forget(player);
    }

    PerkTreeState stateOf(UUID player, TreeDomain domain) {
        return store.state(player, domain);
    }

    int receipts(UUID player, String day) {
        return store.receiptCount(player, day);
    }

    long creditedToday(UUID player, String day) {
        return store.creditedToday(player, day);
    }
}
