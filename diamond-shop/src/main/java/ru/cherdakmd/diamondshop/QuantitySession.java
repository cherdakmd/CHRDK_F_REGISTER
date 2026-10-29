package ru.cherdakmd.diamondshop;

import java.util.Objects;
import java.util.UUID;

/** Состояние одного экрана на главном потоке: подтверждение одноразово, закрытие окончательно. */
final class QuantitySession {
    private final UUID owner;
    private boolean submitted;
    private boolean closed;

    QuantitySession(UUID owner) {
        this.owner = Objects.requireNonNull(owner);
    }

    boolean editable(UUID viewer) {
        return owner.equals(viewer) && !submitted && !closed;
    }

    boolean submit(UUID viewer) {
        if (!editable(viewer)) return false;
        submitted = true;
        return true;
    }

    boolean pending(UUID viewer) {
        return owner.equals(viewer) && submitted && !closed;
    }

    void close() {
        closed = true;
    }
}
