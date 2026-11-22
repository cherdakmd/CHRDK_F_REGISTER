package ru.cherdakmd.chrdkgenerator;

import java.util.UUID;
import ru.cherdakmd.chrdkregister.api.SkillResetBridge;

/** Несовпадение или незавершённый переход никогда не разрешает производство. */
final class GeneratorCycleGate {
    private GeneratorCycleGate() { }
    static boolean allows(SkillResetBridge.State state, UUID generatorEpoch) {
        if (state == null || state.resetting() || state.count() < 0) return false;
        String epoch = generatorEpoch == null ? "legacy" : generatorEpoch.toString();
        return epoch.equals(state.epoch());
    }
}
