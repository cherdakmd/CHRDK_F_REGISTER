package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import ru.cherdakmd.chrdkfishing.FishingRules;
import ru.cherdakmd.chrdkskills.ChrdkSkillsApi;
import ru.cherdakmd.chrdkskills.FishingCatchBonus;

/** Connects dependencies for the experimental prototype; not a production release. */
final class FishingActivation {
    record Active(FishCatchRouter router, FishCatchListener listener, FishingBonusProvider provider) { }
    private FishingActivation() { }
    static Active install(Plugin plugin, PluginManager plugins, ServicesManager services,
                          Predicate<Player> authenticated, BooleanSupplier coreAvailable, BooleanSupplier primaryThread,
                          ChrdkSkillsApi skills, byte[] key, int chance, RandomGenerator random) {
        Objects.requireNonNull(plugin); Objects.requireNonNull(plugins); Objects.requireNonNull(services);
        Objects.requireNonNull(authenticated); Objects.requireNonNull(coreAvailable); Objects.requireNonNull(primaryThread);
        Objects.requireNonNull(skills);
        if (!coreAvailable.getAsBoolean() || services.load(ChrdkSkillsApi.class) != skills)
            throw new IllegalStateException("Core/Skills unavailable");
        if (services.load(FishingCatchBonus.class) != null) throw new IllegalStateException("bonus provider already registered");
        FishItemCodec codec = new FishItemCodec(key);
        FishingBonusProvider provider = new FishingBonusProvider(codec, authenticated, primaryThread, UUID::randomUUID);
        FishCatchListener delegate = new FishCatchListener(authenticated,
                () -> primaryThread.getAsBoolean() && coreAvailable.getAsBoolean()
                        && services.load(ChrdkSkillsApi.class) == skills && services.load(FishingCatchBonus.class) == provider,
                player -> Math.clamp(skills.level(player, "fishing"), 1, 100),
                player -> 1, FishingActivation::context, codec::build, UUID::randomUUID, key,
                FishingRules.defaults(), chance, random);
        FishCatchRouter router = new FishCatchRouter(() -> delegate);
        services.register(FishingCatchBonus.class, provider, plugin, ServicePriority.Normal);
        try { plugins.registerEvents(router, plugin); }
        catch (RuntimeException | LinkageError error) { services.unregister(FishingCatchBonus.class, provider); throw error; }
        return new Active(router, delegate, provider);
    }
    private static FishingRules.Context context(PlayerFishEvent event) {
        var world = event.getHook().getWorld();
        long time = world.getTime();
        boolean night = time >= 13000 && time < 23000;
        return new FishingRules.Context(null, null, night, world.hasStorm());
    }
}
