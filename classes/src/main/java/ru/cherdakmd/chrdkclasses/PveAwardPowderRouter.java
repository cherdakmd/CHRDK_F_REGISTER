package ru.cherdakmd.chrdkclasses;

import ru.cherdakmd.chrdkregister.pve.PveAwardContract;
import ru.cherdakmd.chrdkregister.pve.PveDeathEvidence;
import ru.cherdakmd.chrdkregister.pve.PveReceipt;
import ru.cherdakmd.chrdkregister.pve.PveRecipient;
import ru.cherdakmd.chrdkregister.pve.PveVerdict;
import ru.cherdakmd.chrdkskills.tree.ClassPerkTreeBridge;
import ru.cherdakmd.chrdkskills.tree.PerkTreePowderSource;
import ru.cherdakmd.chrdkskills.tree.PerkTreeState;
import ru.cherdakmd.chrdkskills.tree.TreeDomain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>Состояние <b>в памяти</b> до Фазы 2 ({@code PerkTreeStore}): пыль,
 * квитанции и дневные счётчики не переживают рестарт сервера. Это прототип
 * контракта, а не публикация баланса: персистентность, общий потолок активных
 * деревьев и GUI появятся отдельно. Журнал ограничен днём и числом игроков,
 * бесконечной истории каждого удара нет.</p>
 */
final class PveAwardPowderRouter {

    static final long DAILY_POWDER_CAP = 2_000L;
    private static final int MAX_TRACKED = 8_192;

    /** Ключ — игрок + домен: у каждого дерева своё состояние, чужое не перетирается. */
    private final Map<String, PerkTreeState> states = new LinkedHashMap<>();
    private final Map<String, List<PveReceipt>> receiptsByPlayerDay = new HashMap<>();
    private final Map<String, Long> creditedByPlayerDay = new HashMap<>();
    private String currentDay;

    synchronized long accept(PveDeathEvidence evidence, PveVerdict verdict,
                             TreeDomain domain, int classLevel) {
        if (evidence == null || verdict == null || !verdict.allowed() || domain == null
                || !ClassPerkTreeBridge.isClassSourced(domain)) {
            return 0L;
        }
        rollDay(evidence.dayKey());
        String playerDay = evidence.playerId() + "|" + evidence.dayKey();
        List<PveReceipt> today = receiptsByPlayerDay.computeIfAbsent(playerDay, k -> new ArrayList<>());
        PveReceipt candidate = PveAwardContract.receipt(evidence.deathId(),
                PveRecipient.PERK_POWDER, evidence.dayKey());
        if (!PveAwardContract.canAward(today, candidate, PveAwardContract.DAILY_AWARD_CAP)) {
            return 0L;
        }
        String stateKey = evidence.playerId() + "|" + domain.id();
        PerkTreeState state = states.get(stateKey);
        if (state == null) {
            state = ClassPerkTreeBridge.initialState(domain, classLevel);
        }
        long creditedToday = creditedByPlayerDay.getOrDefault(playerDay, 0L);
        PerkTreePowderSource.Result result = PerkTreePowderSource.credit(state, domain, verdict,
                PerkTreePowderSource.BASE_POWDER, DAILY_POWDER_CAP, creditedToday);
        if (!result.applied()) {
            return 0L;
        }
        states.put(stateKey, result.state());
        today.add(candidate);
        creditedByPlayerDay.put(playerDay, creditedToday + result.credited());
        trim();
        return result.credited();
    }

    /** Смена дня UTC сбрасывает только квитанции и счётчики, не запас пыли дерева. */
    private void rollDay(String dayKey) {
        if (dayKey.equals(currentDay)) {
            return;
        }
        currentDay = dayKey;
        receiptsByPlayerDay.clear();
        creditedByPlayerDay.clear();
    }

    private void trim() {
        while (states.size() > MAX_TRACKED) {
            String oldest = states.keySet().iterator().next();
            states.remove(oldest);
        }
    }

    synchronized PerkTreeState stateOf(UUID player, TreeDomain domain) {
        return states.get(player + "|" + domain.id());
    }

    synchronized int receipts(UUID player, String day) {
        List<PveReceipt> list = receiptsByPlayerDay.get(player + "|" + day);
        return list == null ? 0 : list.size();
    }

    synchronized long creditedToday(UUID player, String day) {
        return creditedByPlayerDay.getOrDefault(player + "|" + day, 0L);
    }

    synchronized void forget(UUID player) {
        String prefix = player.toString();
        states.keySet().removeIf(key -> key.startsWith(prefix + "|"));
        receiptsByPlayerDay.keySet().removeIf(key -> key.startsWith(prefix + "|"));
        creditedByPlayerDay.keySet().removeIf(key -> key.startsWith(prefix + "|"));
    }
}
