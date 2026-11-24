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
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicesManager;
import ru.cherdakmd.chrdkfishing.RodUpgradeJournal;

/** Establish the local and bank lineages once; ambiguous initialization fails closed. */
final class RodUpgradeSetup {
    private RodUpgradeSetup() { }
    static RodUpgradeEngine open(Path folder, ServicesManager services, Class<?> api, ClassLoader coreLoader,
                               RodItemCodec codec, byte[] key, Predicate<Player> authenticated) throws Exception {
        Object service = services.load(api);
        Class<?> provider = Class.forName("ru.cherdakmd.chrdkregister.api.IncomeSourceLineageProvider", true, coreLoader);
        if (service == null || !provider.isInstance(service)
                || !Boolean.TRUE.equals(api.getMethod("personalBankAvailable").invoke(service)))
            throw new IOException("Core bank/lineage service unavailable");
        // Resolve required capabilities before creating any persistent source state.
        var bank = new CoreRodBank(services, api, coreLoader);
        Class<?> nativeCodec = Class.forName("ru.cherdakmd.chrdkregister.api.ChargeInventory", true, coreLoader);
        nativeCodec.getMethod("encode", org.bukkit.inventory.ItemStack[].class);
        nativeCodec.getMethod("decode", String.class);
        nativeCodec.getMethod("matches", org.bukkit.inventory.ItemStack[].class, String.class);
        var sourceLineage = provider.getMethod("incomeSourceLineage", String.class);
        UUID registered = (UUID) sourceLineage.invoke(service, CoreRodBank.SOURCE);
        Path identity = folder.resolve("rod-lineage.txt"), database = folder.resolve("fishing-rods.db");
        boolean hasIdentity = Files.exists(identity, LinkOption.NOFOLLOW_LINKS);
        boolean hasDatabase = Files.exists(database, LinkOption.NOFOLLOW_LINKS);
        if (hasIdentity != hasDatabase || (!hasIdentity && registered != null))
            throw new IOException("rod lineage/journal missing; manual recovery required");
        UUID lineage;
        boolean fresh = !hasIdentity;
        if (fresh) {
            // Only a verified empty source may be initialized. A crash at any later
            // step deliberately requires manual recovery rather than guessing.
            lineage = UUID.randomUUID();
            try (FileChannel channel = FileChannel.open(identity, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.US_ASCII.encode(lineage.toString() + "\n");
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            try (FileChannel directory = FileChannel.open(folder, StandardOpenOption.READ)) { directory.force(true); }
        } else {
            if (!Files.isRegularFile(identity, LinkOption.NOFOLLOW_LINKS)) throw new IOException("rod lineage invalid");
            if (Files.size(identity) != 37) throw new IOException("rod lineage length invalid");
            String raw = Files.readString(identity, StandardCharsets.US_ASCII);
            try {
                if (raw.length() != 37 || !raw.endsWith("\n")) throw new IllegalArgumentException("lineage encoding");
                lineage = UUID.fromString(raw.substring(0, 36));
                if (!raw.equals(lineage + "\n")) throw new IllegalArgumentException("lineage canonical form");
            } catch (RuntimeException invalid) { throw new IOException("rod lineage invalid", invalid); }
            if (!lineage.equals(registered)) throw new IOException("bank lineage mismatch or missing; manual recovery required");
        }
        RodUpgradeJournal journal = new RodUpgradeJournal(database, lineage, key, fresh);
        try {
            if (fresh && !Boolean.TRUE.equals(api.getMethod("registerIncomeSource", String.class, UUID.class)
                    .invoke(service, CoreRodBank.SOURCE, lineage))) throw new IOException("rod source registration failed");
            if (bank.cursor(lineage) < 0) throw new IOException("rod bank unavailable");
            return new RodUpgradeEngine(journal, bank, codec, key, authenticated, coreLoader);
        } catch (Exception | LinkageError error) {
            journal.close();
            throw error;
        }
    }
}
