package ru.cherdakmd.chrdkgenerator;

/**
 * Начисление производства — чистая арифметика без Bukkit.
 *
 * <p>Модель: продукция капает непрерывно, но считается кусками от метки
 * последнего расчёта. Паука длиннее {@link #ONLINE_GAP_MILLIS} — простой
 * (выгрузка чанка, выключение сервера, выход игрока): она идёт с КПД
 * {@code offline-efficiency} и ограничена бюджетом {@code offline-hours};
 * с модулем «Чанк» чанк грузится всегда — полная скорость без бюджета
 * (п.31, §6–§7: «AFK не даёт преимуществ перед офлайном: онлайн-цикл ×1.0,
 * офлайн ×0.5»). Короткая паука — обычный цикл на полной скорости.</p>
 *
 * <p>Остаток дробного цикла не теряется: {@link Settle#advanceMillis()}
 * возвращает только реально оплаченное время, и метка расчёта сдвигается
 * на него, а не на «сейчас». Переполнение бункера сгорает вместе со
 * временем (лишнее не копится).</p>
 */
final class GeneratorProduction {

    /** Паука дольше этой считается простоем (миллисекунды). */
    static final long ONLINE_GAP_MILLIS = 90_000L;

    /** Результат одного расчёта: единицы продукции и оплаченное время. */
    record Settle(long units, long advanceMillis) {
    }

    private GeneratorProduction() {
    }

    /**
     * Считает производство за {@code elapsedMillis}.
     *
     * @param elapsedMillis      времени с прошлого расчёта
     * @param cycleMillis        длительность цикла (с учётом модуля «Скорость»)
     * @param unitsPerCycle      единиц продукции за цикл (тир)
     * @param rateBonusPercent   постоянный бонус перерождений, %
     * @param chunkModule        модуль «Чанк»: чанк грузится всегда
     * @param offlineEfficiency  КПД простоя (0.0–1.0)
     * @param offlineBudgetMillis бюджет простоя (offline-hours)
     * @param bunkerRoom         сколько единиц влезет в бункер
     */
    static Settle settle(long elapsedMillis, double cycleMillis, long unitsPerCycle,
                         double rateBonusPercent, boolean chunkModule,
                         double offlineEfficiency, long offlineBudgetMillis, long bunkerRoom) {
        return settle(elapsedMillis, cycleMillis, unitsPerCycle, rateBonusPercent,
                chunkModule, offlineEfficiency, offlineBudgetMillis, bunkerRoom,
                elapsedMillis > ONLINE_GAP_MILLIS);
    }

    /** Production and fuel consumption are both bounded by the same available work time. */
    record FuelSettle(long units, long advanceMillis, long fuelUsedMillis, boolean exhausted) { }

    static FuelSettle settleWithFuel(long elapsedMillis, double cycleMillis, long unitsPerCycle,
                                    double rateBonusPercent, boolean chunkModule,
                                    double offlineEfficiency, long offlineBudgetMillis,
                                    long bunkerRoom, long fuelRemainingMillis) {
        if (elapsedMillis <= 0 || cycleMillis <= 0 || unitsPerCycle <= 0) {
            return new FuelSettle(0, 0, 0, false);
        }
        var fuel = GeneratorFuelBudget.plan(elapsedMillis, Math.max(0, fuelRemainingMillis), true);
        boolean exhausted = fuel.remainingMillis() == 0;
        Settle result = settle(fuel.workingMillis(), cycleMillis, unitsPerCycle,
                rateBonusPercent, chunkModule, offlineEfficiency, offlineBudgetMillis,
                bunkerRoom, elapsedMillis > ONLINE_GAP_MILLIS);
        // Once empty, discard the unproductive interval, including partial cycles.
        // Otherwise a later refill could retroactively produce without fuel.
        return new FuelSettle(result.units(), exhausted ? elapsedMillis : result.advanceMillis(),
                exhausted ? fuel.workingMillis() : result.advanceMillis(), exhausted);
    }

    private static Settle settle(long elapsedMillis, double cycleMillis, long unitsPerCycle,
                                 double rateBonusPercent, boolean chunkModule,
                                 double offlineEfficiency, long offlineBudgetMillis,
                                 long bunkerRoom, boolean offline) {
        if (elapsedMillis <= 0 || cycleMillis <= 0 || unitsPerCycle <= 0) {
            return new Settle(0L, 0L);
        }
        if (bunkerRoom <= 0) {
            return new Settle(0L, elapsedMillis);
        }
        double efficiency = 1.0;
        double budgetMillis = elapsedMillis;
        if (offline && !chunkModule) {
            efficiency = clampEfficiency(offlineEfficiency);
            budgetMillis = Math.min(elapsedMillis, Math.max(0L, offlineBudgetMillis));
        }
        double perMillis = unitsPerCycle * (1.0 + Math.max(0.0, rateBonusPercent) / 100.0)
                * efficiency / cycleMillis;
        long computed = (long) Math.floor(budgetMillis * perMillis);
        long units = Math.min(computed, bunkerRoom);
        if (units >= computed && !offline) {
            // Онлайн без переполнения: оплачиваем только целые циклы,
            // дробный остаток остаётся в метке расчёта.
            long advance = Math.round(units / perMillis);
            return new Settle(units, Math.min(advance, elapsedMillis));
        }
        // Простой или сгоревший излишек: всё время списывается.
        return new Settle(units, elapsedMillis);
    }

    private static double clampEfficiency(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
