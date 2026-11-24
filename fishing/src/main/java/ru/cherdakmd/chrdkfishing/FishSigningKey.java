package ru.cherdakmd.chrdkfishing;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Set;

/**
 * Draft key storage for the future server adapter. Create is a one-time explicit
 * action, never a fallback for a missing or invalid existing key. Loss of this
 * file requires recovery from a consistent backup; silently rotating it would
 * invalidate fish and cannot establish the state of unpaid sales.
 */
public final class FishSigningKey {
    private static final Set<PosixFilePermission> SAFE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");
    private FishSigningKey() { }

    /** On a new installation only. Parent directory must already exist. Never overwrites an existing file. */
    public static byte[] create(Path file) throws IOException {
        if (file == null || file.toAbsolutePath().getParent() == null) throw new IOException("invalid key path");
        byte[] key = new byte[FishToken.KEY_BYTES];
        new SecureRandom().nextBytes(key);
        try {
            try (FileChannel channel = FileChannel.open(file,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(SAFE_PERMISSIONS))) {
                var bytes = ByteBuffer.wrap(key);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            } catch (UnsupportedOperationException error) {
                throw new IOException("secure key file permissions unsupported", error);
            }
            // If sync fails, keep the file and fail closed. Never overwrite or regenerate it automatically.
            try (FileChannel directory = FileChannel.open(file.toAbsolutePath().getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            }
            return key.clone();
        } finally { Arrays.fill(key, (byte) 0); }
    }

    /** Existing installations only. Missing, symlinked, truncated or exposed keys cause a hard failure. */
    public static byte[] load(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("fish key missing or not a regular file");
        final Set<PosixFilePermission> permissions;
        try { permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS); }
        catch (UnsupportedOperationException error) { throw new IOException("secure key file permissions unsupported", error); }
        if (!SAFE_PERMISSIONS.containsAll(permissions) || !permissions.contains(PosixFilePermission.OWNER_READ))
            throw new IOException("fish key permissions are unsafe");
        byte[] result = new byte[FishToken.KEY_BYTES];
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() != result.length) throw new IOException("fish key has wrong length");
            var bytes = ByteBuffer.wrap(result);
            while (bytes.hasRemaining()) if (channel.read(bytes) < 0) throw new IOException("fish key truncated");
            if (channel.size() != result.length) throw new IOException("fish key changed while reading");
        } catch (IOException error) {
            Arrays.fill(result, (byte) 0);
            throw error;
        }
        return result;
    }
}
