package ru.cherdakmd.chrdkclasses;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/** Результат чтения: ошибка/офлайн никогда не являются подтверждённым нулём. */
public record ClassDataRead(Status status, int value) {
    public enum Status { OK, NOT_REQUIRED, UNAVAILABLE, OFFLINE, ERROR, INVALID }

    public ClassDataRead {
        if (status == null || value < 0 || (status != Status.OK && value != 0)) {
            throw new IllegalArgumentException("Некорректный результат чтения классовых данных");
        }
    }

    public boolean usable() {
        return status == Status.OK || status == Status.NOT_REQUIRED;
    }

    public static ClassDataRead absent(Status status) {
        return new ClassDataRead(status, 0);
    }

    public static ClassDataRead read(BooleanSupplier available, IntSupplier source) {
        try {
            if (!available.getAsBoolean()) {
                return absent(Status.UNAVAILABLE);
            }
            int value = source.getAsInt();
            return value < 0 ? absent(Status.INVALID) : new ClassDataRead(Status.OK, value);
        } catch (RuntimeException | LinkageError error) {
            return absent(Status.ERROR);
        }
    }

    public ClassDataRead atMost(int maximum) {
        return usable() && value > maximum ? absent(Status.INVALID) : this;
    }
}
