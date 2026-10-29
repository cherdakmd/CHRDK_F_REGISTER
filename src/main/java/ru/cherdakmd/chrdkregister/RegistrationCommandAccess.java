package ru.cherdakmd.chrdkregister;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Граница входа для команд аккаунта. Permission не доказывает завершение
 * аутентификации: OP/LuckPerms могут выдать права ещё до пароля и TOTP.
 * Проверка прав и доверенного источника остаётся в самих обработчиках.
 */
final class RegistrationCommandAccess {
    private static final Set<String> PASS_NAMES = Set.of("pass", "fregister", "prohodka");
    private static final Set<String> TOTP_NAMES = Set.of("2fa", "totp", "twofactor");
    private static final Set<String> PUBLIC_PASS = Set.of(
            "menu", "gui", "donate", "donates", "claim", "shop", "catalog", "my", "purchases");

    private RegistrationCommandAccess() { }

    /** Незнакомая подкоманда /pass закрыта до входа: новые admin-ветки безопасны по умолчанию. */
    static boolean requiresAuthentication(String command, String[] args) {
        String name = command.toLowerCase(Locale.ROOT);
        name = name.substring(name.lastIndexOf(':') + 1);
        if (args.length == 0) return false;
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (PASS_NAMES.contains(name)) {
            // Свои покупки доступны вместе с подсказками входа, чужие — только после входа и permission.
            if (("my".equals(sub) || "purchases".equals(sub)) && args.length > 1) return true;
            return !PUBLIC_PASS.contains(sub);
        }
        return TOTP_NAMES.contains(name) && ("reset".equals(sub) || "reset2fa".equals(sub));
    }

    static boolean authenticatedSender(CommandSender sender,
                                       Function<Player, AccessManager.PlayerStage> stages) {
        return !(sender instanceof Player player)
                || stages.apply(player) == AccessManager.PlayerStage.AUTHENTICATED;
    }

    /** Callback — настоящий command dispatch; при отказе бизнес-обработчик вообще не вызывается. */
    static boolean dispatch(CommandSender sender, String command, String[] args,
                            Function<Player, AccessManager.PlayerStage> stages,
                            Messages messages, BooleanSupplier action) {
        if (requiresAuthentication(command, args) && !authenticatedSender(sender, stages)) {
            messages.send(sender, "restriction.admin-auth-required");
            return true;
        }
        return action.getAsBoolean();
    }
}
