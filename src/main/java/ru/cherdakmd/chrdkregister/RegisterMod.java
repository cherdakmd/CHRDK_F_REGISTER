package ru.cherdakmd.chrdkregister;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.UUID;

public final class RegisterMod implements ModInitializer {
    public static final String MOD_ID = "chrdk_f_register";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static AccessManager accessManager;

    @Override
    public void onInitialize() {
        ModConfig config = ModConfig.load(LOGGER);
        RegistrationStore store = new RegistrationStore(
                net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir(), LOGGER);
        TwitchApiClient twitch = new TwitchApiClient(config);
        DonatePayClient donatePay = new DonatePayClient(config);
        accessManager = new AccessManager(config, store, twitch, donatePay, LOGGER);

        ServerLifecycleEvents.SERVER_STARTED.register(accessManager::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(accessManager::onServerStopping);
        registerAdminCommands();

        LOGGER.info("CHRDK Registration loaded for Fabric 26.3.");
    }

    public static Component registrationLoginMessage(Object loginProfile, Component vanillaReason) {
        AccessManager manager = accessManager;
        LoginIdentity identity = readLoginIdentity(loginProfile);
        return manager == null || identity == null
                ? vanillaReason
                : manager.loginMessage(identity.uuid(), identity.name(), vanillaReason);
    }

    private static LoginIdentity readLoginIdentity(Object source) {
        Object current = source;
        for (int depth = 0; current != null && depth < 3; depth++) {
            Object rawId = invokeNoArg(current, "id", "getId", "uuid", "getUuid");
            Object rawName = invokeNoArg(current, "name", "getName");
            UUID uuid = toUuid(rawId);
            if (uuid != null && rawName instanceof String name && !name.isBlank()) {
                return new LoginIdentity(uuid, name);
            }
            Object nested = invokeNoArg(current, "profile", "getProfile", "gameProfile", "getGameProfile");
            if (nested == current) break;
            current = nested;
        }
        return null;
    }

    private static Object invokeNoArg(Object target, String... names) {
        if (target == null) return null;
        for (String name : names) {
            try {
                Method method = target.getClass().getMethod(name);
                return method.invoke(target);
            } catch (ReflectiveOperationException | SecurityException ignored) {
                // Try the next known accessor; covers both Authlib GameProfile and PlayerConfigEntry.
            }
        }
        return null;
    }

    private static UUID toUuid(Object value) {
        if (value instanceof UUID uuid) return uuid;
        if (value instanceof String string) {
            try {
                return UUID.fromString(string);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private record LoginIdentity(UUID uuid, String name) { }

    public static boolean isVanillaWhitelistDenial(Component component) {
        if (component instanceof MutableComponent mutable
                && mutable.getContents() instanceof TranslatableContents translated) {
            return "multiplayer.disconnect.not_whitelisted".equals(translated.getKey());
        }
        return false;
    }

    private static void registerAdminCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("fregister")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
                        .then(Commands.literal("status").executes(context -> {
                            context.getSource().sendSuccess(
                                    () -> Component.literal("CHRDK Registration: " + accessManager.statusLine()), false);
                            return 1;
                        }))
                        .then(Commands.literal("revoke")
                                .then(Commands.argument("player", StringArgumentType.word()).executes(context -> {
                                    String playerName = StringArgumentType.getString(context, "player");
                                    boolean revoked = accessManager.revokeByName(playerName);
                                    if (revoked) {
                                        context.getSource().sendSuccess(
                                                () -> Component.literal("Регистрация " + playerName + " отозвана."), false);
                                        return 1;
                                    }
                                    context.getSource().sendFailure(
                                            Component.literal("Для " + playerName + " не найдена регистрация."));
                                    return 0;
                                })))));
    }
}
