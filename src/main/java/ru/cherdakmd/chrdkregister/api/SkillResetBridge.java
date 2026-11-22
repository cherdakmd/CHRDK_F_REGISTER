package ru.cherdakmd.chrdkregister.api;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Межплагинный протокол: отсутствие службы или снимка означает неизвестное состояние. */
public final class SkillResetBridge {
    private SkillResetBridge() { }
    public record State(String epoch, int count, String pendingEpoch) {
        public boolean resetting() { return pendingEpoch != null; }
    }
    @FunctionalInterface public interface Skills {
        Optional<State> state(UUID owner);
    }
    @FunctionalInterface public interface Generator {
        /** Только главный поток; false означает повторить без объявления успеха. */
        boolean reset(Player player, String previousEpoch, String nextEpoch, int previousCount);
    }
}
