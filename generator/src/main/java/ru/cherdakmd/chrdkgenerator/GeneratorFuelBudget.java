package ru.cherdakmd.chrdkgenerator;

/**
 * Расчёт рабочего времени по уже принятому запасу топлива.
 * Не перемещает предметы и не сохраняет состояние: вызывающий код обязан
 * фиксировать продукцию, остаток топлива и метку времени одним переходом.
 */
final class GeneratorFuelBudget {
    record Plan(long workingMillis, long remainingMillis, long idleMillis) { }

    private GeneratorFuelBudget() { }

    /**
     * При отсутствии топлива время простоя не становится долгом на будущее.
     * При неработающей камере топливо не расходуется.
     */
    static Plan plan(long elapsedMillis, long fuelMillis, boolean working) {
        if (elapsedMillis < 0 || fuelMillis < 0) {
            throw new IllegalArgumentException("negative generator time or fuel");
        }
        if (!working) {
            return new Plan(0, fuelMillis, elapsedMillis);
        }
        long work = Math.min(elapsedMillis, fuelMillis);
        return new Plan(work, fuelMillis - work, elapsedMillis - work);
    }

    /** Перевод длительности печного топлива в миллисекунды без переполнения. */
    static long fromFurnaceTicks(long ticks, long count) {
        if (ticks < 0 || count < 0) {
            throw new IllegalArgumentException("negative fuel ticks or count");
        }
        return Math.multiplyExact(Math.multiplyExact(ticks, 50L), count);
    }
}
