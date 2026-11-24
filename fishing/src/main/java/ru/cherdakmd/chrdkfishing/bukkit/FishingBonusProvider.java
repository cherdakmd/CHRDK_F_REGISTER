package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkskills.FishingCatchBonus;

/** Experimental Skills service, registered only in the non-release dev bootstrap. */
public final class FishingBonusProvider implements FishingCatchBonus {
    private final FishItemCodec codec;
    private final Predicate<Player> authenticated;
    private final BooleanSupplier primaryThread;
    private final Supplier<UUID> ids;
    private java.util.function.Function<org.bukkit.event.player.PlayerFishEvent, Integer> rodXp = event -> 0;
    public FishingBonusProvider(FishItemCodec codec, Predicate<Player> authenticated,
                                BooleanSupplier primaryThread, Supplier<UUID> ids) {
        this.codec = Objects.requireNonNull(codec); this.authenticated = Objects.requireNonNull(authenticated);
        this.primaryThread = Objects.requireNonNull(primaryThread); this.ids = Objects.requireNonNull(ids);
    }
    FishingBonusProvider useRodXp(java.util.function.Function<org.bukkit.event.player.PlayerFishEvent, Integer> resolver) {
        this.rodXp = Objects.requireNonNull(resolver); return this;
    }
    @Override public int rodXpBonusPercent(org.bukkit.event.player.PlayerFishEvent event) {
        if (event == null || event.isCancelled() || !primaryThread.getAsBoolean()
                || event.getState() != org.bukkit.event.player.PlayerFishEvent.State.CAUGHT_FISH) return 0;
        Player player = event.getPlayer();
        if (player == null || !player.isOnline() || player.hasMetadata("NPC") || !authenticated.test(player)
                || (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE)) return 0;
        try { return Math.clamp(rodXp.apply(event), 0, 20); }
        catch (RuntimeException | LinkageError error) { return 0; }
    }
    @Override public ItemStack createExtra(Player player, ItemStack original) {
        if (player == null || original == null || !primaryThread.getAsBoolean() || !player.isOnline()
                || player.hasMetadata("NPC") || !authenticated.test(player)) return null;
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) return null;
        try { return codec.duplicateForCatch(original, ids.get()).orElse(null); }
        catch (RuntimeException | LinkageError error) { return null; }
    }
}
