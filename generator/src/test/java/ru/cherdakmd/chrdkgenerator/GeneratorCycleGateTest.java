package ru.cherdakmd.chrdkgenerator;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.SkillResetBridge;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class GeneratorCycleGateTest {
    @Test void blocksUnavailablePendingAndRolledBackCyclesIncludingZeroCount() {
        UUID epoch = UUID.randomUUID();
        assertFalse(GeneratorCycleGate.allows(null, null));
        assertTrue(GeneratorCycleGate.allows(new SkillResetBridge.State("legacy", 0, null), null));
        assertFalse(GeneratorCycleGate.allows(new SkillResetBridge.State("legacy", 0, epoch.toString()), null));
        assertFalse(GeneratorCycleGate.allows(new SkillResetBridge.State("legacy", 0, null), epoch));
        assertFalse(GeneratorCycleGate.allows(new SkillResetBridge.State(epoch.toString(), 0, null), null));
        assertTrue(GeneratorCycleGate.allows(new SkillResetBridge.State(epoch.toString(), 0, null), epoch));
        assertFalse(GeneratorCycleGate.allows(new SkillResetBridge.State(epoch.toString(), -1, null), epoch));
    }
}
