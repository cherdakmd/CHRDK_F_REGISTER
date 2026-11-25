package ru.cherdakmd.chrdkfishing.bukkit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.bukkit.plugin.ServicesManager;
import ru.cherdakmd.chrdkfishing.AlbumRewardJournal;
import ru.cherdakmd.chrdkfishing.FishAlbum;

/** Empty initialization only; loss or mismatch of either side always requires manual recovery. */
final class AlbumRewardsSetup {
    private static final String BOOTSTRAP_LATCH = "horizons-rewards-started", LATCH_TEXT = "started\n";
    static AlbumRewards open(Path folder, ServicesManager services, Class<?> api, ClassLoader loader, FishAlbum album, java.util.function.Predicate<org.bukkit.entity.Player> authenticated, boolean resetBootstrapping, boolean horizons) throws Exception {
        Object service = services.load(api);
        Class<?> provider = Class.forName("ru.cherdakmd.chrdkregister.api.IncomeSourceLineageProvider", true, loader);
        if (service == null || !provider.isInstance(service) || !Boolean.TRUE.equals(api.getMethod("personalBankAvailable").invoke(service)))
            throw new IOException("Core bank/lineage unavailable");
        var bank = new CoreAlbumBank(services, api);
        UUID registered = (UUID) provider.getMethod("incomeSourceLineage", String.class).invoke(service, CoreAlbumBank.SOURCE);
        Path identity = folder.resolve("album-rewards-lineage.txt"), database = folder.resolve("album-rewards.db");
        boolean hasIdentity = Files.exists(identity, LinkOption.NOFOLLOW_LINKS), hasDatabase = Files.exists(database, LinkOption.NOFOLLOW_LINKS);
        boolean reconstruct = prepareBootstrap(folder, horizons, resetBootstrapping, registered, hasIdentity, hasDatabase);
        boolean fresh = !hasIdentity;
        UUID lineage;
        if (fresh) {
            lineage = reconstruct ? registered : UUID.randomUUID();
            try (FileChannel channel = FileChannel.open(identity, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.US_ASCII.encode(lineage + "\n");
                while (bytes.hasRemaining()) channel.write(bytes); channel.force(true);
            }
            try (FileChannel directory = FileChannel.open(folder, StandardOpenOption.READ)) { directory.force(true); }
        } else {
            if (!Files.isRegularFile(identity, LinkOption.NOFOLLOW_LINKS) || Files.size(identity) != 37) throw new IOException("album rewards lineage invalid");
            String raw = Files.readString(identity, StandardCharsets.US_ASCII);
            try { lineage = UUID.fromString(raw.substring(0, 36)); if (!raw.equals(lineage + "\n")) throw new IllegalArgumentException("lineage"); }
            catch (RuntimeException error) { throw new IOException("album rewards lineage invalid", error); }
            if (!lineage.equals(registered)) throw new IOException("album bank lineage mismatch");
        }
        var journal = new AlbumRewardJournal(database, lineage, fresh);
        try {
            if (fresh && !reconstruct && !Boolean.TRUE.equals(api.getMethod("registerIncomeSource", String.class, UUID.class)
                    .invoke(service, CoreAlbumBank.SOURCE, lineage))) throw new IOException("album source registration failed");
            if (reconstruct) journal.bootstrapFromBank(bank);
            if (!journal.reconciled(bank)) throw new IOException("album receipts mismatch or bank unavailable");
            if (resetBootstrapping && journal.hasUnsettled()) throw new IOException("unfinished album reward; manual recovery required");
            return new AlbumRewards(journal, bank, album, authenticated);
        } catch (Exception | LinkageError error) { journal.close(); throw error; }
    }
    static boolean prepareBootstrap(Path folder, boolean horizons, boolean bootstrapping, UUID registered,
                                    boolean hasIdentity, boolean hasDatabase) throws IOException {
        if (hasIdentity != hasDatabase) throw new IOException("album rewards missing; manual recovery required");
        boolean fresh = !hasIdentity;
        if (!horizons) {
            if (fresh && registered != null) throw new IOException("album rewards missing; manual recovery required");
            return false;
        }
        Path latch = folder.resolve(BOOTSTRAP_LATCH);
        if (!fresh) { FishingGeneration.requireFile(latch, LATCH_TEXT); return false; }
        if (!bootstrapping || registered == null || Files.exists(latch, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("album bootstrap unavailable or interrupted; manual recovery required");
        // Receipts cannot establish whether an old journal had unpaid claims.
        // A consistent external audit is a prerequisite, not performed here.
        FishingGeneration.durableCreate(latch, LATCH_TEXT);
        return true;
    }
    private AlbumRewardsSetup() { }
}
