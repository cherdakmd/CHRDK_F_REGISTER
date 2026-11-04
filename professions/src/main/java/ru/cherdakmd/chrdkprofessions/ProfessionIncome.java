package ru.cherdakmd.chrdkprofessions;

import java.util.List;
import java.util.UUID;

/**
 * Доставка заработанных ремеслами алмазов на личный банковский счёт (1.4.282).
 *
 * <p>Используется тот же накопительный WAL-протокол ядра, что у заказов, охоты
 * и ежедневных наград: {@code professions.json} хранит кумулятивный
 * {@code incomeTotal} по игроку, а банк получает только разницу
 * {@code total - credited}. Поэтому рестарт, повторный тик и недоступный банк
 * не удваивают выплату и не теряют её.</p>
 *
 * <p>Класс не знает о Bukkit: {@link Bank} реализуется плагином поверх
 * {@code ChrdkRegisterApi}, а в тестах — поверх настоящего {@code BankStore}.</p>
 */
public final class ProfessionIncome {

    /** Имя источника дохода в банке ядра. */
    public static final String SOURCE = "professions-income";

    /** Минимальная граница ядра для накопительных выплат. */
    public interface Bank {
        boolean available();

        boolean registered(UUID lineage);

        boolean register(UUID lineage);

        boolean known(UUID owner);

        /** Уже зачислено банком по этому игроку; {@code -1} — банк недоступен. */
        long credited(UUID lineage, UUID owner);

        /** Кумулятивный {@code total}; повтор с тем же total ничего не начисляет. */
        boolean credit(UUID lineage, UUID owner, long total);
    }

    private final ProfessionStore store;
    private final Bank bank;
    private int cursor;

    public ProfessionIncome(ProfessionStore store, Bank bank) {
        this.store = store;
        this.bank = bank;
    }

    /**
     * Доставляет доход одного игрока. {@code false} не откатывает total:
     * выплата могла пройти частично, и следующий вызов повторит её идемпотентно.
     */
    public boolean deliver(UUID owner) {
        if (owner == null || store == null || bank == null || !bank.available() || !bank.known(owner)) {
            return false;
        }
        long total = store.incomeTotal(owner);
        if (total <= 0) {
            return false;
        }
        UUID lineage = store.incomeLineage();
        if (lineage == null || !bank.registered(lineage) && !bank.register(lineage)) {
            return false;
        }
        long credited = bank.credited(lineage, owner);
        if (credited < 0 || credited > total) {
            return false;
        }
        return bank.credit(lineage, owner, total);
    }

    /**
     * Фоновая повторная доставка «зависших» выплат (банк был недоступен,
     * сервер перезапустился). За один тик — не больше {@code limit} игроков.
     */
    public int tick(int limit) {
        if (bank == null || !bank.available() || limit <= 0) {
            return 0;
        }
        List<UUID> owners = store.incomeOwners();
        if (owners.isEmpty()) {
            return 0;
        }
        int done = 0;
        for (int i = 0; i < owners.size() && done < limit; i++) {
            UUID owner = owners.get(Math.floorMod(cursor++, owners.size()));
            if (deliver(owner)) {
                done++;
            }
        }
        return done;
    }
}
