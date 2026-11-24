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

/** Experimental Skills service implementation. Not registered until the module is ready. */
public final class FishingBonusProvider implements FishingCatchBonus {
    private final FishItemCodec codec;
    private final Predicate<Player> authenticated;
    private final BooleanSupplier primaryThread;
    private final Supplier<UUID> ids;
    public FishingBonusProvider(FishItemCodec codec, Predicate<Player> authenticated,
                                BooleanSupplier primaryThread, Supplier<UUID> ids) {
        this.codec = Objects.requireNonNull(codec); this.authenticated = Objects.requireNonNull(authenticated);
        this.primaryThread = Objects.requireNonNull(primaryThread); this.ids = Objects.requireNonNull(ids);
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
