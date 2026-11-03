package ru.cherdakmd.chrdktierforge;

import java.util.Objects;
import java.util.UUID;

/** Чистый контракт подтверждения. T — уже скопированное полное содержимое слота. */
record ForgeQuote<T>(T main, T off, int slot, UUID world, Object settings, long deadline, int xp) {
    boolean matches(T currentMain, T currentOff, int currentSlot, UUID currentWorld, Object currentSettings, long now, int levels) {
        return now <= deadline && settings == currentSettings && Objects.equals(world, currentWorld)
                && slot == currentSlot && Objects.equals(main, currentMain) && Objects.equals(off, currentOff) && levels >= xp;
    }
}
