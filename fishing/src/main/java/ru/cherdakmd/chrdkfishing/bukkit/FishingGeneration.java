package ru.cherdakmd.chrdkfishing.bukkit;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import ru.cherdakmd.chrdkfishing.FishSigningKey;

/** Explicitly isolated, opt-in source generation. Not an automatic reset. */
final class FishingGeneration {
    static final String MARKER = "fishing-generation.txt";
    static final String KEY_LATCH = "horizons-key-started";
    static final String INITIALIZED = "horizons-initialized";
    private static final String GENERATION_TEXT = "horizons-v1\n", LATCH_TEXT = "started\n", READY_TEXT = "initialized\n";
    static final FishingGeneration LEGACY = new FishingGeneration(false);
    static final FishingGeneration HORIZONS = new FishingGeneration(true);
    final boolean horizons;
    private FishingGeneration(boolean horizons) { this.horizons = horizons; }
    String rodSource() { return horizons ? "fishing-horizons-rod" : "fishing-upgrades"; }
    String baitSource() { return horizons ? "fishing-horizons-bait" : "fishing-bait"; }
    String saleSource() { return horizons ? "fishing-horizons-sale" : "fishing-sale"; }

    static FishingGeneration resolve(Path folder) throws IOException {
        Path marker = folder.resolve(MARKER);
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(folder.resolve(KEY_LATCH), LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(folder.resolve(INITIALIZED), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("generation marker lost; manual recovery required");
            return LEGACY;
        }
        requireFile(marker, GENERATION_TEXT);
        return HORIZONS;
    }
    static void requireFile(Path file, String expected) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != expected.length()
                || !expected.equals(Files.readString(file, StandardCharsets.US_ASCII)))
            throw new IOException("invalid generation marker");
    }
    private static void durableCreate(Path file, String text) throws IOException {
        try (var c = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var bytes = StandardCharsets.US_ASCII.encode(text); while (bytes.hasRemaining()) c.write(bytes); c.force(true);
        }
        try (var dir = FileChannel.open(file.getParent(), StandardOpenOption.READ)) { dir.force(true); }
    }
    boolean bootstrapping(Path folder) throws IOException {
        if (!horizons) return false;
        Path marker = folder.resolve(INITIALIZED);
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return true;
        requireFile(marker, READY_TEXT); return false;
    }
    void markInitialized(Path folder) throws IOException {
        if (horizons && bootstrapping(folder)) durableCreate(folder.resolve(INITIALIZED), READY_TEXT);
    }
    /** New generation needs the existing financial reward ledger; it must not create a new one. */
    byte[] signingKey(Path folder) throws IOException {
        if (!horizons) return FishingPlugin.signingKey(folder);
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) throw new IOException("invalid generation folder");
        requireFile(folder.resolve(MARKER), GENERATION_TEXT);
        Path key = folder.resolve("fish-signing.key"), latch = folder.resolve(KEY_LATCH);
        boolean hasKey = Files.exists(key, LinkOption.NOFOLLOW_LINKS), hasLatch = Files.exists(latch, LinkOption.NOFOLLOW_LINKS);
        if (hasKey) {
            if (!hasLatch) throw new IOException("generation key latch missing");
            requireFile(latch, LATCH_TEXT); return FishSigningKey.load(key);
        }
        if (hasLatch) throw new IOException("generation key missing; restore required");
        Set<String> allowed = Set.of(MARKER, "album-rewards.db", "album-rewards-lineage.txt");
        try (var files = Files.list(folder)) {
            if (files.anyMatch(p -> !allowed.contains(p.getFileName().toString()) || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)))
                throw new IOException("generation folder is not clean");
        }
        if (!Files.isRegularFile(folder.resolve("album-rewards.db"), LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(folder.resolve("album-rewards-lineage.txt"), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("existing reward history required");
        durableCreate(latch, LATCH_TEXT); // A failed create permanently requires recovery, never a new key.
        return FishSigningKey.create(key);
    }
    /** Check Core policy before creating a new key or journal. */
    void verifyCore(ClassLoader loader) throws ReflectiveOperationException {
        if (!horizons) return;
        Class<?> cls = Class.forName("ru.cherdakmd.chrdkregister.api.BankCharge", true, loader);
        var permission = cls.getMethod("permission", String.class); var bankOnly = cls.getMethod("bankOnlySource", String.class);
        for (String source : new String[]{rodSource(), baitSource()}) {
            if (!"chrdkfishing.use".equals(permission.invoke(null, source)) || !Boolean.TRUE.equals(bankOnly.invoke(null, source)))
                throw new IllegalStateException("Core does not support the new bank-only fishing sources");
        }
    }
}
