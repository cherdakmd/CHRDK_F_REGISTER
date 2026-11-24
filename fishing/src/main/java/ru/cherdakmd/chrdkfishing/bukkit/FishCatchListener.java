package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.bukkit.GameMode;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkfishing.CatchPolicy;
import ru.cherdakmd.chrdkfishing.FishToken;
import ru.cherdakmd.chrdkfishing.FishingModel;
import ru.cherdakmd.chrdkfishing.FishingRules;

/**
 * Staged event adapter, deliberately not registered by a plugin. It must remain
 * gated until Core authorisation and Skills extra-catch identity handling are
 * correctly wired, item/cooking protection is in place, prices are configured,
 * and a real server integration test proves listener ordering and cancellation.
 * A HIGHEST listener cannot guarantee that a later plugin won't cancel or mutate
 * the event; the final runtime must explicitly address that interaction.
 */
public final class FishCatchListener implements Listener {
    private final Predicate<Player> authenticated;
    private final BooleanSupplier integrationReady;
    private final Function<Player, Integer> skillLevel;
    private final Function<Player, Integer> rodLevel;
    private final Function<PlayerFishEvent, FishingRules.Context> context;
    private final Function<FishToken.Token, ItemStack> itemFactory;
    private final Supplier<UUID> ids;
    private final byte[] secret;
    private final FishingRules rules;
    private final int chance;
    private final RandomGenerator random;

    public FishCatchListener(Predicate<Player> authenticated, BooleanSupplier integrationReady,
                             Function<Player, Integer> skillLevel, Function<Player, Integer> rodLevel,
                             Function<PlayerFishEvent, FishingRules.Context> context,
                             Function<FishToken.Token, ItemStack> itemFactory, Supplier<UUID> ids,
                             byte[] secret, FishingRules rules, int chance, RandomGenerator random) {
        this.authenticated = Objects.requireNonNull(authenticated);
        this.integrationReady = Objects.requireNonNull(integrationReady);
        this.skillLevel = Objects.requireNonNull(skillLevel);
        this.rodLevel = Objects.requireNonNull(rodLevel);
        this.context = Objects.requireNonNull(context);
        this.itemFactory = Objects.requireNonNull(itemFactory);
        this.ids = Objects.requireNonNull(ids);
        if (secret == null || secret.length != FishToken.KEY_BYTES || chance < 0 || chance > 10_000)
            throw new IllegalArgumentException("fishing event configuration");
        this.secret = secret.clone(); this.rules = Objects.requireNonNull(rules); this.chance = chance;
        this.random = Objects.requireNonNull(random);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event == null || event.isCancelled() || !integrationReady.getAsBoolean()
                || event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item caught)) return;
        Player player = event.getPlayer();
        if (player == null || !player.isOnline() || player.hasMetadata("NPC")) return;
        ItemStack before = caught.getItemStack();
        if (before == null) return;
        GameMode mode = player.getGameMode();
        var attempt = new CatchPolicy.Attempt(true, authenticated.test(player),
                mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE, event.isCancelled(), true,
                before.hasItemMeta(), before.getType().name(), before.getAmount());
        if (!CatchPolicy.replace(attempt, chance, random)) return;
        int skill = skillLevel.apply(player), rod = rodLevel.apply(player);
        var fish = FishingModel.roll(random, rod, skill, rules, context.apply(event));
        // Provisional points only: server integration must choose and freeze a real currency mapping.
        var token = FishToken.issue(secret, ids.get(), fish, FishingModel.price(fish));
        ItemStack replacement = itemFactory.apply(token);
        if (replacement == null || replacement.getAmount() != 1 || replacement.getType() != org.bukkit.Material.COD)
            throw new IllegalStateException("fish item factory failed");
        caught.setItemStack(replacement);
        // Do not call Skills award here: the existing monitor listener already awards this event.
    }
}
