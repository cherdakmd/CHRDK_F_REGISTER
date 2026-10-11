package ru.cherdakmd.chrdkregister;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Native Paper dialogs used instead of (or in addition to) chat commands for entering a password or
 * a 2FA code. Passwords typed into a dialog never reach the chat, the server console or the log files,
 * which removes the need for masking yet another piece of sensitive data.
 *
 * <p>The whole class is optional: on server builds without the dialog API every method silently does
 * nothing and the traditional {@code /login <пароль>} flow keeps working.</p>
 */
public final class AuthDialogs {
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_CONFIRM = "confirm";
    private static final String KEY_CODE = "code";

    private static final int INPUT_WIDTH = 300;
    private static final int BUTTON_WIDTH = 100;

    private final JavaPlugin plugin;
    private final AccessManager accessManager;
    private final Supplier<PluginConfig> configSupplier;
    private final Logger logger;

    private volatile boolean unavailable;
    private volatile Messages messages;

    public AuthDialogs(
            JavaPlugin plugin,
            AccessManager accessManager,
            Supplier<PluginConfig> configSupplier,
            Messages messages,
            Logger logger
    ) {
        this.plugin = plugin;
        this.accessManager = accessManager;
        this.configSupplier = configSupplier;
        this.messages = messages;
        this.logger = logger;
    }

    /** Обновляет тексты после {@code /pass reload}. */
    public void setMessages(Messages messages) {
        this.messages = messages;
    }

    /** Shows the password dialog of the login step. */
    public void showLogin(Player player) {
        PluginConfig config = configSupplier.get();
        run(player, () -> player.showDialog(Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(title(messages.get("dialog.login.title")))
                        .canCloseWithEscape(false)
                        .pause(false)
                        .body(List.of(DialogBody.plainMessage(messages.get("dialog.login.body"))))
                        .inputs(List.of(DialogInput.text(KEY_PASSWORD, label(messages.get("dialog.password-label")))
                                .maxLength(Math.max(8, Math.min(64, config.maxPasswordLength())))
                                .width(INPUT_WIDTH)
                                .build()))
                        .build())
                .type(DialogType.confirmation(
                        button(messages.get("dialog.login.submit"), DialogAction.customClick(
                                (response, audience) -> submitLogin(player, response), singleUse())),
                        button(messages.get("dialog.cancel"), null))))));
    }

    /** Shows the registration dialog (password + confirmation) of the first join. */
    public void showRegister(Player player) {
        PluginConfig config = configSupplier.get();
        int maxLength = Math.max(8, Math.min(64, config.maxPasswordLength()));
        int minLength = Math.max(4, config.minPasswordLength());
        run(player, () -> player.showDialog(Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(title(messages.get("dialog.register.title")))
                        .canCloseWithEscape(false)
                        .pause(false)
                        .body(List.of(DialogBody.plainMessage(
                                messages.get("dialog.register.body", "min", minLength, "max", maxLength))))
                        .inputs(List.of(
                                DialogInput.text(KEY_PASSWORD, label(messages.get("dialog.password-label")))
                                        .maxLength(maxLength)
                                        .width(INPUT_WIDTH)
                                        .build(),
                                DialogInput.text(KEY_CONFIRM, label(messages.get("dialog.register.confirm-label")))
                                        .maxLength(maxLength)
                                        .width(INPUT_WIDTH)
                                        .build()))
                        .build())
                .type(DialogType.confirmation(
                        button(messages.get("dialog.register.submit"), DialogAction.customClick(
                                (response, audience) -> submitRegister(player, response), singleUse())),
                        button(messages.get("dialog.cancel"), null))))));
    }

    /** Shows the 2FA code dialog of the second authentication step. */
    public void showTwoFactor(Player player) {
        run(player, () -> player.showDialog(Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(title(messages.get("dialog.two-factor.title")))
                        .canCloseWithEscape(false)
                        .pause(false)
                        .body(List.of(DialogBody.plainMessage(messages.get("dialog.two-factor.body"))))
                        .inputs(List.of(DialogInput.text(KEY_CODE, label(messages.get("dialog.two-factor.code-label")))
                                .maxLength(8)
                                .width(INPUT_WIDTH)
                                .build()))
                        .build())
                .type(DialogType.confirmation(
                        button(messages.get("dialog.two-factor.submit"), DialogAction.customClick(
                                (response, audience) -> submitTwoFactor(player, response), singleUse())),
                        button("Отмена", null))))));
    }

    private void submitLogin(Player player, DialogResponseView response) {
        String password = response.getText(KEY_PASSWORD);
        if (password == null || password.isEmpty()) {
            warn(player, messages.get("dialog.password-missing"));
            showLogin(player);
            return;
        }
        onMainThread(() -> accessManager.handleLoginCommand(player, password));
    }

    private void submitRegister(Player player, DialogResponseView response) {
        String password = response.getText(KEY_PASSWORD);
        String confirmation = response.getText(KEY_CONFIRM);
        if (password == null || password.isEmpty()) {
            warn(player, messages.get("dialog.password-missing"));
            showRegister(player);
            return;
        }
        if (!password.equals(confirmation)) {
            warn(player, messages.get("dialog.confirm-mismatch"));
            showRegister(player);
            return;
        }
        onMainThread(() -> accessManager.handleRegisterCommand(player, password, confirmation));
    }

    private void submitTwoFactor(Player player, DialogResponseView response) {
        String code = response.getText(KEY_CODE);
        if (code == null || code.isBlank()) {
            warn(player, messages.get("dialog.code-missing"));
            showTwoFactor(player);
            return;
        }
        onMainThread(() -> accessManager.handleTwoFactorLoginCode(player, code.trim()));
    }

    private void onMainThread(Runnable action) {
        try {
            if (Bukkit.isPrimaryThread()) {
                action.run();
            } else {
                Bukkit.getScheduler().runTask(plugin, action);
            }
        } catch (Throwable throwable) {
            report(throwable);
        }
    }

    private void warn(Player player, String message) {
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }

    private void run(Player player, Runnable action) {
        if (unavailable || player == null || !player.isOnline()) {
            return;
        }
        try {
            action.run();
        } catch (Throwable throwable) {
            report(throwable);
        }
    }

    private void report(Throwable throwable) {
        if (!unavailable) {
            unavailable = true;
            logger.log(Level.WARNING, "Нативные диалоги Paper недоступны, используется ввод через команды", throwable);
        }
    }

    private static Component title(Component text) {
        return text;
    }

    private static Component label(Component text) {
        return text;
    }

    private static ActionButton button(Component text, DialogAction action) {
        return ActionButton.create(text, null, BUTTON_WIDTH, action);
    }

    private static ClickCallback.Options singleUse() {
        return ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build();
    }
}
