package ru.cherdakmd.chrdkfishing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FishSigningKeyTest {
    @TempDir Path dir;
    @Test void createOnceLoadAgainAndNeverOverwrite() throws Exception {
        Path file = dir.resolve("fish-signing.key");
        byte[] generated = FishSigningKey.create(file);
        assertEquals(32, generated.length);
        assertArrayEquals(generated, FishSigningKey.load(file));
        assertThrows(IOException.class, () -> FishSigningKey.create(file));
        assertArrayEquals(generated, FishSigningKey.load(file));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }
    @Test void missingTruncatedOrInsecureKeyNeverRegenerates() throws Exception {
        Path file = dir.resolve("fish-signing.key");
        assertThrows(IOException.class, () -> FishSigningKey.load(file));
        assertFalse(Files.exists(file));
        FishSigningKey.create(file);
        Files.write(file, new byte[5]);
        assertThrows(IOException.class, () -> FishSigningKey.load(file));
        assertEquals(5, Files.size(file));
        Files.write(file, new byte[32]);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(IOException.class, () -> FishSigningKey.load(file));
    }
    @Test void symbolicLinkIsNeverFollowed() throws Exception {
        Path real = dir.resolve("real"); Path link = dir.resolve("link");
        FishSigningKey.create(real); Files.createSymbolicLink(link, real);
        assertThrows(IOException.class, () -> FishSigningKey.load(link));
        assertThrows(IOException.class, () -> FishSigningKey.create(link));
    }
}
