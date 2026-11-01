package ru.cherdakmd.chrdkregister;

import com.google.gson.*;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.function.BiPredicate;

/** Core владеет обязательством второго фактора; отсутствие аддона не отменяет его. */
final class VkSecondFactor {
    enum Purpose { LOGIN, ENABLE, DISABLE }
    static final long TTL = 120_000, COOLDOWN = 30_000;
    static final int ATTEMPTS = 5;
    static final class Ticket {
        final UUID session, nonce = UUID.randomUUID();
        final String name;
        final long recipient, expires;
        final Purpose purpose;
        private final byte[] hash;
        int attempts;
        boolean delivered, verified, committed;
        Ticket(String name, UUID session, long recipient, Purpose purpose, long now, String code) {
            this.name = name; this.session = session; this.recipient = recipient;
            this.purpose = purpose; this.expires = now + TTL; this.hash = digest(code, nonce);
        }
        @Override public String toString() { return "VkSecondFactor.Ticket[redacted]"; }
    }
    static final class Issued {
        final Ticket ticket;
        final String code;
        Issued(Ticket ticket, String code) { this.ticket = ticket; this.code = code; }
        @Override public String toString() { return "VkSecondFactor.Issued[redacted]"; }
    }
    private final Path file;
    private final Logger logger;
    private final Object commitLock = new Object();
    private final BiPredicate<Path, String> writer;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> enabled = new HashMap<>(), lastSent = new HashMap<>();
    private final Map<UUID, Ticket> pending = new HashMap<>();
    private boolean blocked;

    VkSecondFactor(Path folder, Logger logger) {
        this(folder, logger, (file, json) -> SafeJsonWriter.write(file, json, logger, "[VK 2FA]"));
    }
    VkSecondFactor(Path folder, Logger logger, BiPredicate<Path, String> writer) {
        file = folder.resolve("vk-second-factor.json"); this.logger = logger; this.writer = writer;
        if (!Files.exists(file)) return;
        try {
            if (Files.size(file) > 1_000_000) throw new IllegalStateException();
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (!root.get("schema").toString().equals("1")) throw new IllegalStateException();
            for (var entry : root.getAsJsonObject("accounts").entrySet()) {
                if (!entry.getValue().toString().matches("[1-9][0-9]{0,9}")) throw new IllegalStateException();
                long id = entry.getValue().getAsLong();
                if (!entry.getKey().matches("[a-z0-9_]{1,16}") || id <= 0 || id >= 2_000_000_000L) throw new IllegalStateException();
                enabled.put(entry.getKey(), id);
            }
        } catch (Exception failure) {
            blocked = true;
            logger.severe("[VK 2FA] Хранилище защиты не прочитано: завершение входа заблокировано. Восстановите файл из резервной копии, не удаляйте его.");
        }
    }
    static String key(String name) { return name.toLowerCase(Locale.ROOT); }
    synchronized boolean required(String name) { return blocked || enabled.containsKey(key(name)); }
    synchronized boolean healthy() { return !blocked; }
    synchronized long recipient(String name) { return enabled.getOrDefault(key(name), 0L); }
    synchronized Ticket pending(UUID session) { return pending.get(session); }
    synchronized void forget(UUID session) { pending.remove(session); }
    synchronized void forgetAll() { pending.clear(); }

    synchronized Issued issue(String name, UUID session, long recipient, Purpose purpose, long now) {
        if (blocked || recipient <= 0 || recipient >= 2_000_000_000L || !name.matches("[A-Za-z0-9_]{1,16}")) return null;
        String key = key(name);
        if (now - lastSent.getOrDefault(key, Long.MIN_VALUE / 2) < COOLDOWN) return null;
        // Старый challenge того же аккаунта недействителен, даже при другом UUID подключения.
        pending.values().removeIf(t -> t.name.equals(key));
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        Ticket ticket = new Ticket(key, session, recipient, purpose, now, code);
        pending.put(session, ticket); lastSent.put(key, now);
        return new Issued(ticket, code);
    }
    synchronized void delivered(Ticket ticket, boolean success) {
        if (pending.get(ticket.session) == ticket) ticket.delivered = success;
    }
    synchronized Ticket verify(String name, UUID session, Purpose purpose, String code, long now) {
        Ticket ticket = pending.get(session);
        if (ticket == null || !ticket.name.equals(key(name)) || ticket.purpose != purpose) return null;
        if (now < ticket.expires - TTL || now >= ticket.expires || ticket.attempts >= ATTEMPTS) { pending.remove(session); return null; }
        if (!ticket.delivered) return null;
        ticket.attempts++;
        if (code == null || !code.matches("[0-9]{6}") || !MessageDigest.isEqual(ticket.hash, digest(code, ticket.nonce))) return null;
        pending.remove(session); ticket.verified = true; return ticket;
    }
    /** Вызывать вне игрового потока. ACK только после атомарной записи; ошибка не меняет защиту. */
    boolean commit(Ticket ticket) {
        // Записи сериализованы отдельно: чтение защиты в игровом потоке не ждёт диск.
        synchronized (commitLock) {
            Map<String, Long> next;
            synchronized (this) {
                if (blocked || ticket == null || !ticket.verified || ticket.committed || ticket.purpose == Purpose.LOGIN) return false;
                ticket.committed = true;
                long current = recipient(ticket.name);
                if (ticket.purpose == Purpose.ENABLE && current != 0 || ticket.purpose == Purpose.DISABLE && current != ticket.recipient) return false;
                next = new TreeMap<>(enabled);
                if (ticket.purpose == Purpose.ENABLE) next.put(ticket.name, ticket.recipient); else next.remove(ticket.name);
            }
            JsonObject root = new JsonObject(); root.addProperty("schema", 1);
            root.add("accounts", new Gson().toJsonTree(next));
            String json = root.toString();
            if (json.length() > 1_000_000 || !writer.test(file, json)) return false;
            synchronized (this) { enabled.clear(); enabled.putAll(next); }
            return true;
        }
    }
    private static byte[] digest(String code, UUID nonce) {
        try { return MessageDigest.getInstance("SHA-256").digest((nonce + ":" + code).getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
