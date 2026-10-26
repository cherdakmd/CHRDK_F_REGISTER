package ru.cherdakmd.chrdkregister;

import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;

import java.util.Arrays;

/** Доверенная доставка RCON либо ручное подтверждение квитанции. Не проверяет оплату через API магазина. */
final class PaymentConfirmation {
    enum Mode { RECEIPT, STORE_DELIVERY, DENIED }

    /** Строка source, право admin или имя отправителя не делают игровую команду RCON-доставкой. */
    static Mode mode(boolean remoteConsole, boolean localConsole, boolean storeEnabled, String reference) {
        if (!remoteConsole && !localConsole) return Mode.DENIED;
        if (reference == null) return Mode.DENIED;
        if (!reference.isEmpty()) return reference.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{2,127}") ? Mode.RECEIPT : Mode.DENIED;
        return remoteConsole && storeEnabled ? Mode.STORE_DELIVERY : Mode.DENIED;
    }

    private final Mode mode;
    private final String actor;
    private final String reference;
    private final java.util.concurrent.atomic.AtomicBoolean consumed = new java.util.concurrent.atomic.AtomicBoolean();
    boolean consume() { return consumed.compareAndSet(false, true); }
    private PaymentConfirmation(String actor, String reference, Mode mode) {
        this.mode = mode;
        this.actor = actor;
        this.reference = reference;
    }
    String actor() { return actor + (mode == Mode.STORE_DELIVERY ? ":store-delivery=" : ":payment=") + reference; }
    void finish(AccessManager access, String player, String status) {
        if (mode == Mode.STORE_DELIVERY) access.store().finishStoreDelivery(reference, status);
        else access.store().finishPayment(reference, status);
        access.audit().log(mode == Mode.STORE_DELIVERY ? "STORE_RCON_DELIVERY_FINISHED" : "PAYMENT_FULFILLED", actor, player, "",
                AuditLog.details(mode == Mode.STORE_DELIVERY ? "localDeliveryId" : "reference", reference, "status", status));
    }

    static String reference(String[] args) {
        if (args.length == 0) return "";
        String last = args[args.length - 1];
        return last.startsWith("payment=") ? last.substring(8) : "";
    }

    static String[] arguments(String[] args) {
        return reference(args).isEmpty() ? args : Arrays.copyOf(args, args.length - 1);
    }

    static PaymentConfirmation confirm(CommandSender sender, String reference, String operation, AccessManager access) {
        // Явно переданный пустой payment= — ошибка, а не переход к режиму без квитанции.
        if (reference != null && reference.isEmpty() && operation.matches("(?s).*(?:^|\\s)payment=.*")) reference = null;
        Mode mode = mode(sender instanceof RemoteConsoleCommandSender, sender instanceof ConsoleCommandSender,
                access.config().trademc().enabled(), reference);
        if (!sender.hasPermission("chrdkregister.admin") || mode == Mode.DENIED) {
            access.messages().send(sender, "paid-only.required");
            return null;
        }
        if (mode == Mode.STORE_DELIVERY) {
            String deliveryId = access.store().reserveStoreDelivery(sender.getName(), operation, System.currentTimeMillis());
            if (deliveryId.isEmpty()) {
                access.messages().send(sender, "paid-only.delivery-storage-error");
                return null;
            }
            access.audit().log("STORE_RCON_DELIVERY_ACCEPTED", sender.getName(), "", "",
                    AuditLog.details("localDeliveryId", deliveryId, "operation", operation,
                            "paymentVerifiedByCore", false, "providerOrderIdAvailable", false));
            return new PaymentConfirmation(sender.getName(), deliveryId, mode);
        }
        if (!access.store().reservePayment(reference, sender.getName(), operation, System.currentTimeMillis())) {
            access.messages().send(sender, "paid-only.duplicate");
            return null;
        }
        access.audit().log("PAYMENT_CONFIRMED", sender.getName(), "", "",
                AuditLog.details("reference", reference, "operation", operation));
        return new PaymentConfirmation(sender.getName(), reference, mode);
    }
}
