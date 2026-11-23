package ru.cherdakmd.chrdkenchants;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * Recovery protocol for a future partial anvil operation. This class is not a
 * Bukkit listener and cannot authorize or issue an anvil result on its own.
 *
 * The caller must durably write the intent first and freeze all external access
 * to the operation. The before/after snapshots must cover the complete native
 * player-owned output destination and every experience field, not merely storage.
 * A before witness is valid only after inputs have been escrowed and the player
 * data and witness have been saved together. World anvil slots, cursor, event
 * cancellation and currency payment still need their own proven integration.
 */
final class PartialAnvilRecovery {
    private static final int MAX_SNAPSHOT = 4 * 1024 * 1024;
    record Intent(UUID operation, UUID owner, String before, String after) {
        Intent {
            if (operation == null || owner == null || before == null || after == null
                    || before.isEmpty() || after.isEmpty() || before.equals(after)
                    || before.length() > MAX_SNAPSHOT || after.length() > MAX_SNAPSHOT)
                throw new IllegalArgumentException("partial anvil intent");
        }
        String beforeWitness() { return operation + ":before"; }
        String afterWitness() { return operation + ":after"; }
    }
    enum Outcome { WAIT, APPLIED, UNRESOLVED }
    interface PlayerState {
        UUID owner();
        boolean available();
        String witness();
        boolean matches(String snapshot);
        /** Apply all after fields and witness, then save them in the same playerdata. */
        void applyAndSave(String snapshot, String witness) throws IOException;
        /** Save the current state without reconstructing or reissuing any items. */
        void checkpoint() throws IOException;
    }
    private PartialAnvilRecovery() { }

    static Outcome recover(Intent intent, PlayerState state) throws IOException {
        Objects.requireNonNull(intent, "intent");
        if (state == null || !intent.owner().equals(state.owner()) || !state.available()) return Outcome.WAIT;
        String witness = state.witness();
        if (intent.afterWitness().equals(witness)) {
            if (!state.matches(intent.after())) return Outcome.UNRESOLVED;
            state.checkpoint();
            return intent.afterWitness().equals(state.witness()) && state.matches(intent.after())
                    ? Outcome.APPLIED : Outcome.UNRESOLVED;
        }
        if (!intent.beforeWitness().equals(witness) || !state.matches(intent.before())
                || state.matches(intent.after())) return Outcome.UNRESOLVED;
        // Never roll back in memory on IO failure: the save may have succeeded.
        state.applyAndSave(intent.after(), intent.afterWitness());
        if (!intent.afterWitness().equals(state.witness()) || !state.matches(intent.after())) return Outcome.UNRESOLVED;
        // Checkpoint is required even when an adapter reports that save succeeded.
        state.checkpoint();
        return intent.afterWitness().equals(state.witness()) && state.matches(intent.after())
                ? Outcome.APPLIED : Outcome.UNRESOLVED;
    }
}
