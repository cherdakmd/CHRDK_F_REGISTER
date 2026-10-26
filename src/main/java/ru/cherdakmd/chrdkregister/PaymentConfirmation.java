package ru.cherdakmd.chrdkregister;

import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;

import java.util.Arrays;

/** Подтверждение владельцем реальной оплаты. Метка source из игрового API таким подтверждением не является. */
final class PaymentConfirmation {
    private final String actor;
    private final String reference;
    private final java.util.concurrent.atomic.AtomicBoolean consumed = new java.util.concurrent.atomic.AtomicBoolean();
    boolean consume() { return consumed.compareAndSet(false, true); }
    private PaymentConfirmation(String actor, String reference) {
        this.actor = actor;
        this.reference = reference;
    }
    String actor() { return actor + ":payment=" + reference; }
    void finish(AccessManager access, String player, String status) {
        access.store().finishPayment(reference, status);
        access.audit().log("PAYMENT_FULFILLED", actor, player, "",
                AuditLog.details("reference", reference, "status", status));
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
        if (!(sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender)
                || !sender.hasPermission("chrdkregister.admin")
                || reference == null || !reference.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{2,127}")) {
            access.messages().send(sender, "paid-only.required");
            return null;
        }
        if (!access.store().reservePayment(reference, sender.getName(), operation, System.currentTimeMillis())) {
            access.messages().send(sender, "paid-only.duplicate");
            return null;
        }
        access.audit().log("PAYMENT_CONFIRMED", sender.getName(), "", "",
                AuditLog.details("reference", reference, "operation", operation));
        return new PaymentConfirmation(sender.getName(), reference);
    }
}
