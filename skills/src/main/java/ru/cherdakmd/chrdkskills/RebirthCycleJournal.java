package ru.cherdakmd.chrdkskills;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Проверка журнала циклов до каких-либо финансовых операций. Пока не подключена к игровым командам. */
final class RebirthCycleJournal {
    static final String LEGACY = "legacy";
    private RebirthCycleJournal() { }

    /** Последняя денежная квитанция цикла; null, если денежных выплат не было. */
    record Cycle(String id, SkillStore.PendingRebirth last) { }

    static String marker(String id) { return "reset:" + id; }
    static String event(String id, int count) {
        return LEGACY.equals(id) ? "rebirth:" + count : "rebirth:" + id + ":" + count;
    }

    private static boolean validId(String id) {
        if (LEGACY.equals(id)) return true;
        try { return id != null && UUID.fromString(id).toString().equals(id); }
        catch (IllegalArgumentException error) { return false; }
    }

    /**
     * cycles включает старый цикл первым и текущий последним. Для каждого нового
     * цикла в журнале обязана существовать нулевая отметка; лишняя отметка
     * блокирует работу, в частности при откате снимка Skills. Pending относится
     * исключительно к текущему циклу и может ещё отсутствовать в журнале.
     * Не проверяет и не выполняет переход генератора или запись намерения сброса.
     */
    static boolean matches(List<Cycle> cycles, SkillStore.PendingRebirth pending, Map<String, Long> journal) {
        if (cycles == null || cycles.isEmpty() || journal == null || cycles.get(0) == null
                || !LEGACY.equals(cycles.get(0).id())) return false;
        Map<String, Cycle> ids = new HashMap<>();
        for (Cycle cycle : cycles) {
            if (cycle == null || !validId(cycle.id()) || ids.putIfAbsent(cycle.id(), cycle) != null) return false;
            var last = cycle.last();
            if (last != null && (!validReceipt(last) || !Long.valueOf(last.amount()).equals(journal.get(event(cycle.id(), last.count()))))) return false;
            if (!LEGACY.equals(cycle.id()) && !Long.valueOf(0).equals(journal.get(marker(cycle.id())))) return false;
        }
        Cycle current = cycles.get(cycles.size() - 1);
        if (pending != null && (!validReceipt(pending)
                || current.last() != null && pending.count() <= current.last().count())) return false;
        for (var entry : journal.entrySet()) {
            String key = entry.getKey();
            Long amount = entry.getValue();
            if (key == null || amount == null) return false;
            if (key.startsWith("reset:")) {
                String id = key.substring("reset:".length());
                if (LEGACY.equals(id) || !ids.containsKey(id) || amount != 0) return false;
                continue;
            }
            if (!key.startsWith("rebirth:") || amount <= 0 || amount > 1_000_000) return false;
            String suffix = key.substring("rebirth:".length());
            int split = suffix.indexOf(':');
            String id = split < 0 ? LEGACY : suffix.substring(0, split);
            String number = split < 0 ? suffix : suffix.substring(split + 1);
            if (!ids.containsKey(id) || !number.matches("[1-9][0-9]{0,6}")) return false;
            int count = Integer.parseInt(number);
            if (count > 1_000_000 || !key.equals(event(id, count))) return false;
            Cycle cycle = ids.get(id);
            if (cycle.last() != null && count <= cycle.last().count()) continue;
            if (cycle != current || pending == null || count != pending.count() || amount != pending.amount()) return false;
        }
        return true;
    }

    private static boolean validReceipt(SkillStore.PendingRebirth receipt) {
        return receipt.count() > 0 && receipt.count() <= 1_000_000
                && receipt.amount() > 0 && receipt.amount() <= 1_000_000;
    }
}
