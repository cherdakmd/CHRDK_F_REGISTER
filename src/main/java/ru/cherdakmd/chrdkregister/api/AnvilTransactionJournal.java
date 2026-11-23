package ru.cherdakmd.chrdkregister.api;

import com.google.gson.*;
import ru.cherdakmd.chrdkregister.storage.StorageIo;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Durable single-pending anvil intent, keyed by player. Does not itself escrow,
 * debit, deliver or prove an operation; those steps must be guarded separately.
 * An uncertain write quarantines this instance. A new instance must reload the
 * disk and reconcile the pending record before making any further operation.
 */
public final class AnvilTransactionJournal {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final int MAX_PAYLOAD = 4 * 1024 * 1024;
    private static final int MAX_FILE = 8 * 1024 * 1024;
    private static final ConcurrentHashMap<Path, Object> LOCKS = new ConcurrentHashMap<>();

    public record Intent(UUID operation, UUID owner, String payload) {
        public Intent {
            if (operation == null || owner == null || payload == null || payload.isEmpty()
                    || payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD)
                throw new IllegalArgumentException("invalid anvil intent");
        }
    }
    @FunctionalInterface public interface Checkpoint { void afterReplace() throws IOException; }
    private final Path file;
    private final UUID owner;
    private final StorageIo io;
    private final Checkpoint checkpoint;
    private final Object lock;
    private byte[] observed;
    private Intent pending;
    private boolean healthy = true;

    public AnvilTransactionJournal(Path directory, UUID owner) throws IOException {
        this(directory, owner, StorageIo.shared(), () -> { });
    }
    public AnvilTransactionJournal(Path directory, UUID owner, StorageIo io, Checkpoint checkpoint) throws IOException {
        this.owner = Objects.requireNonNull(owner);
        this.io = Objects.requireNonNull(io);
        this.checkpoint = Objects.requireNonNull(checkpoint);
        file = Objects.requireNonNull(directory).toAbsolutePath().normalize().resolve(owner + ".json");
        lock = LOCKS.computeIfAbsent(file, ignored -> new Object());
        synchronized (lock) {
            observed = readBytes(); pending = parse(observed);
            if (!java.util.Arrays.equals(observed, readBytes())) throw new IOException("anvil journal changed while loading");
        }
    }
    public boolean healthy() { synchronized (lock) { return healthy; } }
    public Intent pending() { synchronized (lock) { return pending; } }

    /** Store an immutable full-item snapshot. This does not establish escrow. */
    public void prepareSnapshot(AnvilEscrowSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        prepare(new Intent(snapshot.operation(), snapshot.owner(), snapshot.encode()));
    }
    /** Decode and bind the snapshot to its journal envelope; malformed data fails closed. */
    public AnvilEscrowSnapshot pendingSnapshot() throws IOException {
        synchronized (lock) {
            if (pending == null) return null;
            try {
                var snapshot = AnvilEscrowSnapshot.decode(pending.payload());
                if (!pending.operation().equals(snapshot.operation()) || !owner.equals(snapshot.owner()))
                    throw new IllegalArgumentException("anvil snapshot identity");
                return snapshot;
            } catch (IllegalArgumentException error) {
                healthy = false;
                throw new IOException("Повреждён снимок наковальни; не удаляйте журнал", error);
            }
        }
    }

    /** Persist intent before touching input items or any payment. */
    public void prepare(Intent intent) throws IOException {
        synchronized (lock) {
            writable();
            if (pending != null) throw new IllegalStateException("unfinished anvil transaction");
            if (intent == null || !owner.equals(intent.owner())) throw new IllegalArgumentException("owner");
            write(intent);
        }
    }
    /** Caller must independently prove that the entire operation was saved. */
    public void complete(UUID operation) throws IOException {
        synchronized (lock) {
            writable();
            if (pending == null || !pending.operation().equals(operation)) throw new IllegalArgumentException("pending operation");
            write(null);
        }
    }
    private void writable() { if (!healthy) throw new IllegalStateException("anvil journal quarantined"); }
    private void write(Intent next) throws IOException {
        byte[] bytes = encode(next);
        byte[] expected = observed;
        try {
            io.run(() -> {
                if (!java.util.Arrays.equals(expected, readBytes())) throw new IOException("anvil journal changed on disk");
                Files.createDirectories(file.getParent());
                Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    var buffer = java.nio.ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                checkpoint.afterReplace();
                try (FileChannel directory = FileChannel.open(file.getParent(), StandardOpenOption.READ)) { directory.force(true); }
            });
            observed = bytes; pending = next;
        } catch (IOException | RuntimeException error) {
            healthy = false;
            throw error;
        }
    }
    private byte[] encode(Intent intent) {
        var root = new JsonObject(); root.addProperty("schema", 1); root.addProperty("owner", owner.toString());
        if (intent == null) root.add("pending", JsonNull.INSTANCE);
        else {
            var value = new JsonObject(); value.addProperty("operation", intent.operation().toString());
            value.addProperty("owner", intent.owner().toString()); value.addProperty("payload", intent.payload());
            root.add("pending", value);
        }
        byte[] bytes = JSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE) throw new IllegalArgumentException("anvil journal capacity");
        return bytes;
    }
    private byte[] readBytes() throws IOException {
        if (Files.notExists(file)) return null;
        if (Files.size(file) > MAX_FILE) throw new IOException("anvil journal capacity");
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > MAX_FILE) throw new IOException("anvil journal capacity");
        return bytes;
    }
    private Intent parse(byte[] bytes) throws IOException {
        if (bytes == null) return null;
        try {
            // StrictFinancialJson rejects duplicate keys, lenient JSON and trailing data.
            var root = StrictFinancialJson.read(file);
            if (!root.keySet().equals(java.util.Set.of("schema", "owner", "pending"))
                    || !root.get("schema").isJsonPrimitive() || !root.get("schema").getAsJsonPrimitive().isNumber()
                    || !"1".equals(root.get("schema").getAsString()) || !owner.toString().equals(string(root, "owner")))
                throw new IllegalArgumentException("anvil journal header");
            if (root.get("pending").isJsonNull()) return null;
            var value = root.getAsJsonObject("pending");
            if (!value.keySet().equals(java.util.Set.of("operation", "owner", "payload"))) throw new IllegalArgumentException("anvil intent fields");
            var intent = new Intent(UUID.fromString(string(value, "operation")), UUID.fromString(string(value, "owner")), string(value, "payload"));
            if (!owner.equals(intent.owner())) throw new IllegalArgumentException("anvil intent owner");
            return intent;
        } catch (RuntimeException e) { throw new IOException("Повреждён журнал наковальни; не удаляйте файл", e); }
    }
    private static String string(JsonObject object, String key) {
        var value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("string " + key);
        return value.getAsString();
    }
}
