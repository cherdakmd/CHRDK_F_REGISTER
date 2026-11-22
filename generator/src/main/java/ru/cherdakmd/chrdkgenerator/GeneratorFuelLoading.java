package ru.cherdakmd.chrdkgenerator;

/** Чистый план расхода одного предмета топлива; сохранение и перенос выполняет вызывающий код. */
final class GeneratorFuelLoading {
    record Stack(GeneratorFurnaceFuel.Fuel fuel, int amount) {
        Stack {
            if (fuel == null || amount < 1) throw new IllegalArgumentException("generator fuel stack");
        }
    }

    /** Остаток стека может быть null; возвращаемый контейнер сохраняется отдельно. */
    record Plan(Stack remaining, long burnMillis, String remainder) { }

    private GeneratorFuelLoading() { }

    /**
     * Не расходовать новый предмет, пока горит предыдущий или некуда положить
     * пустую тару. План и продукция должны быть сохранены одной транзакцией;
     * сам этот метод не переносит предметы и не меняет хранилище.
     */
    static Plan start(Stack stack, long currentBurnMillis, boolean remainderRoom) {
        if (currentBurnMillis < 0) throw new IllegalArgumentException("negative fuel balance");
        if (stack == null || currentBurnMillis > 0) return new Plan(stack, currentBurnMillis, null);
        var fuel = stack.fuel();
        if (fuel.remainder() != null && !remainderRoom) return new Plan(stack, 0, null);
        Stack remaining = stack.amount() == 1 ? null : new Stack(fuel, stack.amount() - 1);
        return new Plan(remaining, fuel.burnMillis(), fuel.remainder());
    }
}
