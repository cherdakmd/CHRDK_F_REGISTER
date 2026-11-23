package ru.cherdakmd.chrdkregister.api;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Recovery of a playerdata-only destination for an anvil escrow operation.
 * Not an event listener and not a transaction coordinator. The caller MUST
 * first durably escrow both anvil inputs, persist the before witness together
 * with the player's inventory/XP, freeze access to all involved slots, and
 * establish payment (including any bank receipt). This class does none of those
 * steps and must not be used as authorization to take an anvil result.
 *
 * Only two previously empty storage slots may change, to the exact output and
 * remainder. Cursor, armour, offhand and world/window inventories are NOT
 * covered. Ambiguous data is never repaired by guessing or replaying a grant.
 */
public final class AnvilPlayerRecovery {
    private static final NamespacedKey WITNESS = new NamespacedKey("chrdk_core", "anvil_escrow_v1");
    private AnvilPlayerRecovery() { }
    public enum Outcome { WAIT, APPLIED, UNRESOLVED }

    /** Main-thread adapter; the caller is responsible for keeping the player frozen. */
    public static State player(Player player, BooleanSupplier authenticated) {
        Objects.requireNonNull(player); Objects.requireNonNull(authenticated);
        return new State() {
            public UUID owner() { return player.getUniqueId(); }
            public boolean available() { return Bukkit.isPrimaryThread() && player.isOnline() && !player.isDead()
                    && !player.hasMetadata("NPC") && authenticated.getAsBoolean(); }
            public String witness() { return player.getPersistentDataContainer().get(WITNESS, PersistentDataType.STRING); }
            public boolean valid(AnvilEscrowSnapshot s) {
                try {
                    ItemStack left=AnvilEscrowSnapshot.item(s.left());
                    ItemStack book=AnvilEscrowSnapshot.item(s.book());
                    ItemStack result=AnvilEscrowSnapshot.item(s.result());
                    ItemStack remainder=AnvilEscrowSnapshot.item(s.remainder());
                    ItemStack[] before=ChargeInventory.decode(utf8(s.beforeState()));
                    ItemStack[] after=ChargeInventory.decode(utf8(s.afterState()));
                    return book.getType()==Material.ENCHANTED_BOOK && remainder.getType()==Material.ENCHANTED_BOOK
                            && left.getType()==result.getType() && destinations(before,after,result,remainder)
                            && levelTransition(s.beforeXp(),s.afterXp(),s.levelCost());
                } catch (RuntimeException e) { return false; }
            }
            public boolean matches(byte[] data, AnvilEscrowSnapshot.Experience xp) {
                try { return ChargeInventory.matches(player.getInventory().getStorageContents(),utf8(data))
                        && player.getLevel()==xp.level() && player.getTotalExperience()==xp.total()
                        && Float.compare(player.getExp(),xp.progress())==0; }
                catch (RuntimeException e) { return false; }
            }
            public void applyAndSave(byte[] data, AnvilEscrowSnapshot.Experience xp, String witness) {
                // Decode before the first mutation; do not roll back on an uncertain save.
                ItemStack[] storage=ChargeInventory.decode(utf8(data));
                player.getInventory().setStorageContents(storage);
                player.setLevel(xp.level()); player.setTotalExperience(xp.total()); player.setExp(xp.progress());
                player.getPersistentDataContainer().set(WITNESS,PersistentDataType.STRING,witness);
                player.saveData();
            }
            public void checkpoint() { player.saveData(); }
        };
    }

    /** A port used by the main-thread adapter and failure-injection tests. */
    public interface State {
        UUID owner(); boolean available(); String witness();
        boolean valid(AnvilEscrowSnapshot snapshot);
        boolean matches(byte[] data, AnvilEscrowSnapshot.Experience experience);
        void applyAndSave(byte[] data, AnvilEscrowSnapshot.Experience experience, String witness) throws IOException;
        void checkpoint() throws IOException;
    }
    public static String beforeWitness(UUID operation) { return Objects.requireNonNull(operation)+":before"; }
    public static String afterWitness(UUID operation) { return Objects.requireNonNull(operation)+":after"; }

    /** Does not clear the journal: the coordinator must independently finalize the operation. */
    public static Outcome recover(AnvilTransactionJournal journal, State state) throws IOException {
        Objects.requireNonNull(journal);
        if (!journal.healthy()) return Outcome.UNRESOLVED;
        AnvilEscrowSnapshot snapshot=journal.pendingSnapshot();
        if (snapshot==null) return Outcome.UNRESOLVED;
        if (state==null || !snapshot.owner().equals(state.owner()) || !state.available()) return Outcome.WAIT;
        if (!state.valid(snapshot)) return Outcome.UNRESOLVED;
        String before=beforeWitness(snapshot.operation()), after=afterWitness(snapshot.operation());
        if (after.equals(state.witness())) {
            if (!state.matches(snapshot.afterState(),snapshot.afterXp())) return Outcome.UNRESOLVED;
            state.checkpoint();
            return after.equals(state.witness()) && state.matches(snapshot.afterState(),snapshot.afterXp())
                    ? Outcome.APPLIED : Outcome.UNRESOLVED;
        }
        if (!before.equals(state.witness()) || !state.matches(snapshot.beforeState(),snapshot.beforeXp())
                || state.matches(snapshot.afterState(),snapshot.afterXp())) return Outcome.UNRESOLVED;
        state.applyAndSave(snapshot.afterState(),snapshot.afterXp(),after);
        if (!after.equals(state.witness()) || !state.matches(snapshot.afterState(),snapshot.afterXp())) return Outcome.UNRESOLVED;
        state.checkpoint();
        return after.equals(state.witness()) && state.matches(snapshot.afterState(),snapshot.afterXp())
                ? Outcome.APPLIED : Outcome.UNRESOLVED;
    }

    static boolean levelTransition(AnvilEscrowSnapshot.Experience before,AnvilEscrowSnapshot.Experience after,int cost) {
        return cost>=0 && before.level()>=cost && after.level()==before.level()-cost
                && before.total()==after.total() && Float.compare(before.progress(),after.progress())==0;
    }
    /** Exactly two originally vacant slots, nothing else modified; order is irrelevant. */
    static <T> boolean destinations(T[] before,T[] after,T result,T remainder) {
        if (before==null || after==null || before.length!=36 || after.length!=36 || result==null || remainder==null) return false;
        boolean resultFound=false,remainderFound=false;
        for(int i=0;i<36;i++) {
            if (Objects.equals(before[i],after[i])) continue;
            if (before[i]!=null || after[i]==null) return false;
            if (!resultFound && Objects.equals(after[i],result)) resultFound=true;
            else if (!remainderFound && Objects.equals(after[i],remainder)) remainderFound=true;
            else return false;
        }
        return resultFound && remainderFound;
    }
    private static String utf8(byte[] bytes) {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException e) { throw new IllegalArgumentException("invalid state encoding",e); }
    }
}
