package ru.cherdakmd.chrdkregister.pve;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Общий реестр квитанций PvE-наград: один дневной бюджет на игрока <b>по всем
 * получателям</b>.
 *
 * <p>{@link #shared()} — единственный экземпляр в рантайме. Класс живёт в ядре
 * CHRDK_Core, а модули подключаются через {@code compileOnly project(':')},
 * поэтому их обработчики смерти видят один и тот же реестр и не создают шесть
 * независимых дневных лимитов. Конструктор открыт для изолированных JVM-тестов
 * и сценариев, где общий экземпляр не нужен.</p>
 *
 * <p>Реестр хранит <b>только текущий день UTC</b>: при смене дня квитанции и
 * счётчики сбрасываются, бесконечной истории каждого убитого моба нет. Долговечные
 * квитанции деревьев перков ведутся отдельно в {@code PerkTreeStore}. Состояние
 * реестра в памяти: рестарт сервера начинает новый день с чистыми счётчиками,
 * а штатные защиты модулей от повторной награды (PDC, claims, их store)
 * остаются на диске и не ослабляются.</p>
 */
public final class PveAwardLedger {

    private static final int MAX_PLAYERS = 8_192;
    private static final PveAwardLedger SHARED = new PveAwardLedger();

    public static PveAwardLedger shared() {
        return SHARED;
    }

    /** access-order: при переполнении вытесняется давно не активный игрок, а не случайный ключ. */
    private final Map<UUID, Set<String>> keys = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<UUID, Integer> spent = new HashMap<>();
    private String currentDay;

    public PveAwardLedger() {
    }

    /**
     * Регистрирует награду. {@code false} — квитанция уже есть или общий
     * дневной бюджет исчерпан; в этом случае вызывающий модуль пропускает
     * начисление. Численные награды модулей не меняются.
     */
    public synchronized boolean register(UUID playerId, PveReceipt receipt) {
        if (playerId == null || receipt == null) {
            return false;
        }
        if (!receipt.dayKey().equals(currentDay)) {
            roll(receipt.dayKey());
        }
        Set<String> mine = keys.computeIfAbsent(playerId, ignored -> new HashSet<>());
        if (!mine.add(receipt.key())) {
            return false;
        }
        int already = spent.getOrDefault(playerId, 0);
        if (already + receipt.units() > PveAwardContract.DAILY_AWARD_CAP) {
            mine.remove(receipt.key());
            return false;
        }
        spent.put(playerId, already + receipt.units());
        trim();
        return true;
    }

    private void roll(String day) {
        currentDay = day;
        keys.clear();
        spent.clear();
    }

    /** LRU: iterator access-order карты начинается с наименее недавно использованного. */
    private void trim() {
        while (keys.size() > MAX_PLAYERS) {
            UUID oldest = keys.keySet().iterator().next();
            keys.remove(oldest);
            spent.remove(oldest);
        }
    }

    public synchronized int spent(UUID playerId) {
        return spent.getOrDefault(playerId, 0);
    }

    public synchronized int receipts(UUID playerId) {
        Set<String> mine = keys.get(playerId);
        return mine == null ? 0 : mine.size();
    }

    /** Для тестов и диагностики: сколько игроков сейчас в реестре. */
    public synchronized int players() {
        return keys.size();
    }

    public synchronized String currentDay() {
        return currentDay;
    }

    /** Для тестов и полной перезагрузки: сбрасывает текущий день. */
    public synchronized void clear() {
        currentDay = null;
        keys.clear();
        spent.clear();
    }
}
