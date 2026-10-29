package ru.cherdakmd.chrdkregister.api;

import com.google.gson.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Один pending на модуль. Публикация source.next разрешена только после авторитетной банковской квитанции. */
public final class ChargeJournal {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_STATE = 16 * 1024 * 1024, MAX_INVENTORY = 4 * 1024 * 1024;
    public record Pending(BankCharge payment, String beforeInventory, String afterInventory, String beforeState, String afterState) { }
    private static final class Data {
        int schema = 1;
        String source;
        UUID lineage;
        long sequence;
        Pending pending;
    }
    @FunctionalInterface public interface WriteCheckpoint { void afterReplace() throws IOException; }
    private final Path file;
    private final WriteCheckpoint checkpoint;
    private Data data;
    private boolean healthy = true;
    public ChargeJournal(Path folder, String source, UUID lineage) throws IOException { this(folder, source, lineage, () -> { }); }
    public ChargeJournal(Path folder, String source, UUID lineage, WriteCheckpoint checkpoint) throws IOException {
        file = folder.resolve("bank-charge.json"); this.checkpoint = Objects.requireNonNull(checkpoint);
        if (source == null || !source.matches("[a-z][a-z0-9-]{0,47}") || lineage == null) throw new IllegalArgumentException("source identity");
        if (Files.exists(file)) {
            try {
                if (Files.size(file) > 64L * 1024 * 1024) throw new IllegalArgumentException("journal capacity");
                var object = JsonParser.parseString(Files.readString(file)).getAsJsonObject(); FinanceNumbers.requireIntegral(object);
                for (String field : List.of("schema", "source", "lineage", "sequence"))
                    if (!object.has(field)) throw new IllegalArgumentException("missing " + field);
                data = JSON.fromJson(object, Data.class); validate(data);
                if (!source.equals(data.source) || !lineage.equals(data.lineage)) throw new IllegalArgumentException("journal lineage");
            } catch (RuntimeException error) { healthy = false; throw new IOException("Повреждён bank-charge.json; не удаляйте журнал", error); }
        } else {
            var fresh = new Data(); fresh.source = source; fresh.lineage = lineage; write(fresh);
        }
    }
    public synchronized boolean healthy() { return healthy; }
    public synchronized Pending pending() { return data.pending; }
    public synchronized long nextSequence() { return data.sequence + 1; }
    private void writable() { if (!healthy) throw new IllegalStateException("charge journal quarantined"); }
    private static void validate(Data d) {
        if (d == null || d.schema != 1 || d.source == null || d.lineage == null || d.sequence < 0 || d.sequence == Long.MAX_VALUE)
            throw new IllegalArgumentException("charge journal");
        if (d.pending == null) return;
        var p = d.pending; var c = p.payment;
        if (c == null || !c.valid() || !d.source.equals(c.source()) || !d.lineage.equals(c.lineage()) || c.sequence() != d.sequence + 1
                || p.beforeInventory == null || p.afterInventory == null || p.beforeState == null || p.afterState == null
                || p.beforeState.equals(p.afterState) || (c.cash() == 0) != p.beforeInventory.equals(p.afterInventory)
                || p.beforeInventory.length() > MAX_INVENTORY || p.afterInventory.length() > MAX_INVENTORY
                || p.beforeState.getBytes(StandardCharsets.UTF_8).length > MAX_STATE || p.afterState.getBytes(StandardCharsets.UTF_8).length > MAX_STATE
                || !c.inventoryHash().equals(AuctionPayment.hash(p.beforeInventory, p.afterInventory))
                || !c.stateHash().equals(AuctionPayment.hash(p.beforeState, p.afterState))) throw new IllegalArgumentException("charge intent");
    }
    private void write(Data next) throws IOException {
        validate(next);
        try {
            Files.createDirectories(file.getParent()); Path temp = file.resolveSibling("bank-charge.json.tmp");
            byte[] bytes = JSON.toJson(next).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 64 * 1024 * 1024) throw new IllegalArgumentException("journal capacity");
            Files.write(temp, bytes);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            checkpoint.afterReplace();
            try (FileChannel directory = FileChannel.open(file.getParent(), StandardOpenOption.READ)) { directory.force(true); }
            data = next;
        } catch (IOException | RuntimeException error) { healthy = false; throw error; }
    }
    public synchronized BankCharge prepare(UUID payer, long price, long debit, long cash, long change,
                                            String beforeInventory, String afterInventory, String beforeState, String afterState) throws IOException {
        writable(); if (data.pending != null) throw new IllegalStateException("unfinished charge");
        var c = new BankCharge(data.source, data.lineage, nextSequence(), UUID.randomUUID(), payer, price, debit, cash, change,
                AuctionPayment.hash(beforeInventory, afterInventory), AuctionPayment.hash(beforeState, afterState));
        var next = copy(); next.pending = new Pending(c, beforeInventory, afterInventory, beforeState, afterState); write(next); return c;
    }
    private Data copy() { return JSON.fromJson(JSON.toJson(data), Data.class); }
    public synchronized void complete(BankCharge verifiedCommitted) throws IOException {
        writable(); requirePending(verifiedCommitted); var next = copy(); next.sequence = verifiedCommitted.sequence(); next.pending = null; write(next);
    }
    public synchronized void rollback(BankCharge verifiedMissing) throws IOException {
        writable(); requirePending(verifiedMissing); var next = copy(); next.pending = null; write(next);
    }
    private void requirePending(BankCharge payment) {
        if (payment == null || data.pending == null || !data.pending.payment.equals(payment)) throw new IllegalArgumentException("pending identity");
    }
}
