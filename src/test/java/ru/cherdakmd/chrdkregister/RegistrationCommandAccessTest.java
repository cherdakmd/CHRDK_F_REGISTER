package ru.cherdakmd.chrdkregister;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Регрессия F01: фактический dispatch не вызывает admin-ветку до завершения входа. */
class RegistrationCommandAccessTest {
    private final Messages messages = Messages.from(getClass().getResourceAsStream("/messages.yml"),
            null, Logger.getAnonymousLogger());
    private static final List<String> PASS_NAMES = List.of("pass", "fregister", "prohodka",
            "chrdk_core:pass", "chrdk_core:fregister", "CHRDK_CORE:PROHODKA");
    private static final List<String> ADMIN_ACTIONS = List.of("unregister", "resetpass", "reset2fa",
            "disable2fa", "un2fa", "add", "remove", "revoke", "reload", "backup", "bank", "bal",
            "approve", "reject", "sessions", "logoutall", "audit", "doctor", "status", "shopbuy",
            "shopgift", "shoprevoke", "shoppay", "update", "future-admin-command");

    private <T extends CommandSender> T sender(Class<T> type, boolean permission, List<Component> sent) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "hasPermission", "isOp" -> permission;
                case "sendMessage" -> {
                    if (args.length != 1 || !(args[0] instanceof Component text))
                        throw new AssertionError("Ожидалось сообщение MiniMessage");
                    sent.add(text); yield null;
                }
                case "toString" -> "CommandAccessTestSender";
                default -> throw new AssertionError("Неожиданный вызов: " + method);
            };
        }));
    }

    @Test void allIncompleteStagesDenyEveryPrivilegedPassBranchEvenForOp() {
        for (var stage : AccessManager.PlayerStage.values()) {
            if (stage == AccessManager.PlayerStage.AUTHENTICATED) continue;
            for (String name : PASS_NAMES) for (String sub : ADMIN_ACTIONS) for (boolean permission : List.of(false, true)) {
                List<Component> sent = new ArrayList<>();
                Player player = sender(Player.class, permission, sent);
                AtomicInteger mutations = new AtomicInteger();
                String context = stage + " " + name + " " + sub + " admin=" + permission;
                assertTrue(RegistrationCommandAccess.dispatch(player, name, new String[]{sub, "Owner"},
                        p -> stage, messages, () -> { mutations.incrementAndGet(); return false; }), context);
                assertEquals(0, mutations.get(), context);
                assertEquals(1, sent.size(), context);
                assertTrue(PlainTextComponentSerializer.plainText().serialize(sent.getFirst())
                        .contains("Сначала завершите вход"), context);
                assertFalse(RegistrationCommandAccess.authenticatedSender(player, p -> stage), context);
            }
        }
    }

    @Test void bothTwoFactorResetAliasesAreDeniedBeforeAuthentication() {
        for (var stage : AccessManager.PlayerStage.values()) {
            if (stage == AccessManager.PlayerStage.AUTHENTICATED) continue;
            for (String name : List.of("2fa", "totp", "twofactor", "chrdk_core:2fa", "CHRDK_CORE:TOTP")) {
                for (String sub : List.of("reset", "reset2fa", "RESET")) {
                    var sent = new ArrayList<Component>();
                    var player = sender(Player.class, true, sent);
                    AtomicBoolean reset = new AtomicBoolean();
                    assertTrue(RegistrationCommandAccess.dispatch(player, name, new String[]{sub, "Owner"},
                            p -> stage, messages, () -> { reset.set(true); return true; }));
                    assertFalse(reset.get(), stage + " " + name + " " + sub);
                    assertEquals(1, sent.size());
                }
            }
        }
    }

    @Test void resetCannotCompleteTheAttackersPendingSecondFactor() {
        var stage = new AtomicReference<>(AccessManager.PlayerStage.NEEDS_2FA);
        var player = sender(Player.class, true, new ArrayList<>());
        var reset = new AtomicBoolean();
        assertTrue(RegistrationCommandAccess.dispatch(player, "pass", new String[]{"reset2fa", "Owner"},
                p -> stage.get(), messages, () -> {
                    reset.set(true); stage.set(AccessManager.PlayerStage.AUTHENTICATED); return true;
                }));
        assertFalse(reset.get());
        assertEquals(AccessManager.PlayerStage.NEEDS_2FA, stage.get());
    }

    @Test void authenticatedPlayersStillNeedTheOriginalPermissions() {
        for (boolean permission : List.of(false, true)) {
            var sent = new ArrayList<Component>();
            var player = sender(Player.class, permission, sent);
            var calls = new AtomicInteger();
            boolean result = RegistrationCommandAccess.dispatch(player, "pass", new String[]{"reset2fa", "Owner"},
                    p -> AccessManager.PlayerStage.AUTHENTICATED, messages, () -> {
                        calls.incrementAndGet(); return player.hasPermission("chrdkregister.admin");
                    });
            assertEquals(permission, result);
            assertEquals(1, calls.get());
            assertTrue(sent.isEmpty());
            assertTrue(RegistrationCommandAccess.authenticatedSender(player, p -> AccessManager.PlayerStage.AUTHENTICATED));
        }
    }

    @Test void consoleAndRconKeepTheirHandlersAndDoNotAcquireNewPrivileges() {
        for (var type : List.of(ConsoleCommandSender.class, RemoteConsoleCommandSender.class)) {
            for (boolean permission : List.of(false, true)) {
                var sent = new ArrayList<Component>();
                var sender = sender(type, permission, sent);
                var calls = new AtomicInteger();
                assertEquals(permission, RegistrationCommandAccess.dispatch(sender, "pass", new String[]{"shoppay", "Owner", "899"},
                        p -> { throw new AssertionError("У консоли нет стадии игрока"); }, messages, () -> {
                            calls.incrementAndGet(); return sender.hasPermission("chrdkregister.admin");
                        }));
                assertEquals(1, calls.get());
                assertTrue(sent.isEmpty());
            }
        }
    }

    @Test void publicPassAndAuthenticationFlowsReachTheirExistingHandlers() {
        List<String[]> passArgs = List.of(new String[0], new String[]{"menu"}, new String[]{"gui"},
                new String[]{"donate"}, new String[]{"donates"}, new String[]{"claim", "123"},
                new String[]{"shop"}, new String[]{"catalog"}, new String[]{"my"}, new String[]{"purchases"});
        var player = sender(Player.class, true, new ArrayList<>());
        for (String name : PASS_NAMES) for (String[] args : passArgs) {
            assertTrue(RegistrationCommandAccess.dispatch(player, name, args,
                    p -> { throw new AssertionError("Публичная подсказка не требует admin-auth"); }, messages, () -> true));
        }
        for (String name : List.of("register", "login", "captcha", "logout", "session", "2fa", "totp")) {
            for (String sub : List.of("setup", "confirm", "qr", "verify", "status", "cancel", "disable")) {
                assertFalse(RegistrationCommandAccess.requiresAuthentication(name, new String[]{sub}));
            }
        }
    }

    @Test void readingNamedPurchasesRequiresAuthenticationAndNullStageFailsClosed() {
        var player = sender(Player.class, true, new ArrayList<>());
        for (String sub : List.of("my", "purchases")) {
            assertTrue(RegistrationCommandAccess.requiresAuthentication("pass", new String[]{sub, "Owner"}));
            assertTrue(RegistrationCommandAccess.dispatch(player, "pass", new String[]{sub, "Owner"},
                    p -> null, messages, () -> { throw new AssertionError("Не должна читаться чужая история"); }));
        }
    }

    @Test void preprocessChecksSubcommandsAndPreservesOrdinaryAuthenticationCommands() {
        for (var stage : AccessManager.PlayerStage.values()) {
            boolean authenticated = stage == AccessManager.PlayerStage.AUTHENTICATED;
            for (String command : List.of("/pass reset2fa Owner", "/fregister   RESETpass Owner",
                    "/chrdk_core:prohodka bank give Owner 100", "/pass \tmy Owner")) {
                assertEquals(authenticated, PlayerRestrictionListener.isCommandAllowed(stage,
                        PlayerRestrictionListener.extractCommandLabel(command),
                        PlayerRestrictionListener.extractCommandArguments(command)), stage + " " + command);
            }
            assertTrue(PlayerRestrictionListener.isCommandAllowed(stage, "pass", new String[]{"donate"}));
            for (String name : List.of("register", "reg", "login", "l", "2fa", "totp", "twofactor", "captcha", "session")) {
                assertEquals(PlayerRestrictionListener.isCommandAllowed(stage, name),
                        PlayerRestrictionListener.isCommandAllowed(stage, name, new String[]{"confirm", "123456"}));
            }
        }
        assertArrayEquals(new String[]{"reset2fa", "Owner"},
                PlayerRestrictionListener.extractCommandArguments(" /pass   reset2fa\tOwner "));
    }
}
