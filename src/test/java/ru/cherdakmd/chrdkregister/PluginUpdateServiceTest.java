package ru.cherdakmd.chrdkregister;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.*;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

class PluginUpdateServiceTest {
    @TempDir Path root;
    private static final String REPO = "cherdakmd/CHRDK_F_REGISTER";
    private static final String VERSION = "1.4.73";

    private static byte[] jar(String name, String version) throws IOException {
        return jar(name, version, "26.3", Runtime.version().feature() + 44);
    }

    private static byte[] jar(String name, String version, String api, int javaMajor) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes)) {
            output.putNextEntry(new JarEntry("plugin.yml"));
            output.write(("name: " + name + "\nversion: '" + version + "'\nmain: example.Main\napi-version: '" + api + "'\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new JarEntry("example/Main.class"));
            output.write(new byte[]{(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe, 0, 0, (byte) (javaMajor >> 8), (byte) javaMajor});
            output.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static byte[] zip(Map<String, byte[]> jars) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (var entry : jars.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private final class Fixture {
        final Map<String, byte[]> jars = new TreeMap<>();
        final JsonObject metadata = new JsonObject();
        final JsonArray assets = new JsonArray();
        byte[] archive;
        int downloads;
        Fixture() throws IOException {
            Files.createDirectories(root.resolve("CHRDK_Core"));
            for (var module : PluginUpdateService.MODULES.entrySet())
                jars.put(module.getValue() + "-" + VERSION + ".jar", jar(module.getKey(), VERSION));
            metadata.addProperty("draft", false);
            metadata.addProperty("prerelease", false);
            metadata.addProperty("tag_name", "v" + VERSION);
            metadata.add("assets", assets);
            refresh();
        }
        void refresh() throws IOException {
            while (!assets.isEmpty()) assets.remove(0);
            archive = zip(jars);
            jars.forEach(this::addAsset);
            addAsset("chrdk-plugins-" + VERSION + ".zip", archive);
        }
        void addAsset(String name, byte[] content) {
            JsonObject asset = new JsonObject();
            asset.addProperty("name", name);
            asset.addProperty("size", content.length);
            asset.addProperty("digest", "sha256:" + PluginUpdateService.sha256(content));
            asset.addProperty("browser_download_url", "https://github.com/" + REPO + "/releases/download/v" + VERSION + "/" + name);
            assets.add(asset);
        }
        byte[] get(URI uri, int limit) {
            if (uri.getHost().equals("api.github.com")) return metadata.toString().getBytes(StandardCharsets.UTF_8);
            downloads++;
            return archive;
        }
        PluginUpdateService service() { return service(this::get); }
        PluginUpdateService service(PluginUpdateService.Transport transport) {
            return new PluginUpdateService(root, root.resolve("update"), root.resolve("CHRDK_Core"), () -> REPO, "26.3-R0.1-SNAPSHOT", transport);
        }
        Path install(String plugin, String version) throws IOException {
            Path path = root.resolve(PluginUpdateService.MODULES.get(plugin) + "-custom-name.jar");
            Files.write(path, jar(plugin, version));
            return path;
        }
    }

    @Test void checkNeedsCompleteStableReleaseButDoesNotDownloadOrWriteFiles() throws Exception {
        Fixture fixture = new Fixture();
        try (var updater = fixture.service()) {
            var result = updater.execute(false, "1.4.72");
            assertEquals(VERSION, result.version());
            assertFalse(result.staged());
            assertFalse(Files.exists(root.resolve("update")));
            assertFalse(Files.exists(root.resolve("CHRDK_Core/update-backups")));
            assertEquals(0, fixture.downloads);
        }
    }

    @Test void stagesWholeInstalledBatchWithOriginalFilenamesAndBackupsWithoutTouchingLiveJars() throws Exception {
        Fixture fixture = new Fixture();
        Map<Path, byte[]> originals = new HashMap<>();
        for (String name : PluginUpdateService.MODULES.keySet()) {
            Path file = fixture.install(name, "1.4.72");
            originals.put(file, Files.readAllBytes(file));
        }
        Path unrelated = root.resolve("LuckPerms.jar");
        Files.write(unrelated, jar("LuckPerms", "5.5.87"));
        Files.createDirectory(root.resolve("update")); // standard initially-empty Bukkit directory
        try (var updater = fixture.service()) {
            var result = updater.execute(true, "1.4.72");
            assertTrue(result.staged());
            assertEquals(26, result.count());
            try (var files = Files.list(result.updateDirectory())) { assertEquals(26, files.count()); }
            for (var entry : originals.entrySet()) {
                assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
                assertArrayEquals(entry.getValue(), Files.readAllBytes(result.backup().resolve(entry.getKey().getFileName())));
                assertEquals(VERSION, PluginUpdateService.descriptor(result.updateDirectory().resolve(entry.getKey().getFileName())).version());
            }
            assertTrue(Files.exists(result.backup().resolve("manifest.json")));
            assertTrue(Files.exists(result.backup().resolve("COMPLETE")));
            assertFalse(Files.exists(result.updateDirectory().resolve("LuckPerms.jar")));
            assertEquals("5.5.87", PluginUpdateService.descriptor(unrelated).version());
            assertThrows(IOException.class, () -> updater.execute(true, "1.4.72")); // no overwriting staged work
        }
    }

    @Test void validatesAllModulesButOnlyStagesInstalledAddonsAndCanSynchronizeMixedVersions() throws Exception {
        Fixture fixture = new Fixture();
        fixture.install("CHRDK_Core", VERSION);
        fixture.install("CHRDK_Queue", "1.4.72");
        try (var updater = fixture.service()) {
            var result = updater.execute(true, VERSION);
            assertTrue(result.staged());
            assertEquals(2, result.count());
        }
    }

    @Test void allCurrentFilesDoNotDownloadAgainAndDowngradesAreRefused() throws Exception {
        Fixture fixture = new Fixture();
        fixture.install("CHRDK_Core", VERSION);
        try (var updater = fixture.service()) {
            assertTrue(updater.execute(true, VERSION).filesCurrent());
            assertEquals(0, fixture.downloads);
            assertThrows(IOException.class, () -> updater.execute(true, "1.4.74"));
            fixture.install("CHRDK_Queue", "1.4.74");
            assertThrows(IOException.class, () -> updater.execute(true, VERSION));
        }
    }

    @Test void incompleteReleaseMissingDigestAndPrereleaseNeverStageAnything() throws Exception {
        Fixture fixture = new Fixture();
        fixture.install("CHRDK_Core", "1.4.72");
        try (var updater = fixture.service()) {
            assetsFirst(fixture).remove("digest");
            assertThrows(IOException.class, () -> updater.execute(true, "1.4.72"));
            fixture.refresh();
            fixture.assets.remove(0);
            assertThrows(IOException.class, () -> updater.execute(true, "1.4.72"));
            fixture.refresh();
            fixture.metadata.addProperty("prerelease", true);
            assertThrows(IOException.class, () -> updater.execute(true, "1.4.72"));
            assertFalse(Files.exists(root.resolve("update")));
            assertEquals(0, fixture.downloads);
        }
    }

    private static JsonObject assetsFirst(Fixture fixture) { return fixture.assets.get(0).getAsJsonObject(); }

    @Test void wrongUrlWrongDigestAndTamperedZipFailClosed() throws Exception {
        Fixture fixture = new Fixture();
        fixture.install("CHRDK_Core", "1.4.72");
        try (var updater = fixture.service()) {
            assetsFirst(fixture).addProperty("browser_download_url", "https://evil.example/payload.jar");
            assertThrows(IOException.class, () -> updater.execute(true, "1.4.72"));
            fixture.refresh();
            fixture.archive[fixture.archive.length / 2] ^= 1;
            assertThrows(IOException.class, () -> updater.execute(true, "1.4.72"));
            assertFalse(Files.exists(root.resolve("update")));
        }
    }

    @Test void individualJarDigestAndMissingZipEntryAreVerifiedIndependently() throws Exception {
        Fixture fixture = new Fixture();
        fixture.install("CHRDK_Core", "1.4.72");
        try (var updater = fixture.service()) {
            assetsFirst(fixture).addProperty("digest", "sha256:" + "0".repeat(64));
            assertThrows(IOException.class, () -> updater.execute(true, "1.4.72"));
            fixture.refresh();
            Map<String, byte[]> incomplete = new TreeMap<>(fixture.jars);
            incomplete.remove(incomplete.keySet().iterator().next());
            fixture.archive = zip(incomplete);
            JsonObject zip = fixture.assets.get(fixture.assets.size() - 1).getAsJsonObject();
            zip.addProperty("size", fixture.archive.length);
            zip.addProperty("digest", "sha256:" + PluginUpdateService.sha256(fixture.archive));
            assertThrows(IOException.class, () -> updater.execute(true, "1.4.72"));
            assertFalse(Files.exists(root.resolve("update")));
        }
    }

    @Test void zipTraversalAndMismatchedInternalPluginOrApiOrJavaAreRejected() throws Exception {
        Fixture fixture = new Fixture();
        fixture.install("CHRDK_Core", "1.4.72");
        String core = "chrdk-core-" + VERSION + ".jar";
        try (var updater = fixture.service()) {
            fixture.jars.put("../outside.jar", new byte[]{1});
            fixture.refresh();
            assertThrows(IOException.class, () -> updater.execute(true, "1.4.72"));
            fixture.jars.remove("../outside.jar");
            for (byte[] bad : List.of(jar("WrongPlugin", VERSION), jar("CHRDK_Core", "1.4.72"),
                    jar("CHRDK_Core", VERSION, "99.0", 69), jar("CHRDK_Core", VERSION, "26.3", 200))) {
                fixture.jars.put(core, bad);
                fixture.refresh();
                assertThrows(IOException.class, () -> updater.execute(true, "1.4.72"));
                assertFalse(Files.exists(root.resolve("update")));
            }
            assertFalse(Files.exists(root.getParent().resolve("outside.jar")));
        }
    }

    @Test void unrelatedStagedUpdatesDuplicatesAndSymlinksAreNotOverwritten() throws Exception {
        Fixture fixture = new Fixture();
        Path installed = fixture.install("CHRDK_Core", "1.4.72");
        try (var updater = fixture.service()) {
            Path update = Files.createDirectory(root.resolve("update"));
            Path foreign = Files.writeString(update.resolve("other.jar"), "keep");
            assertThrows(IOException.class, () -> updater.execute(true, "1.4.72"));
            assertEquals("keep", Files.readString(foreign));
            Files.delete(foreign);
            Path duplicate = Files.copy(installed, root.resolve("duplicate.JAR"));
            assertThrows(IOException.class, () -> updater.execute(true, "1.4.72"));
            Files.delete(duplicate);
            Files.delete(update);
            Path link = Files.createSymbolicLink(root.resolve("linked.jar"), installed);
            assertThrows(IOException.class, () -> updater.execute(true, "1.4.72"));
            Files.delete(link);
            Files.createSymbolicLink(update, root.resolve("CHRDK_Core"));
            assertThrows(IOException.class, () -> updater.execute(true, "1.4.72"));
        }
    }

    @Test void liveJarChangedDuringDownloadPreventsPublication() throws Exception {
        Fixture fixture = new Fixture();
        Path live = fixture.install("CHRDK_Core", "1.4.72");
        try (var updater = fixture.service((uri, limit) -> {
            if (!uri.getHost().equals("api.github.com")) Files.write(live, jar("CHRDK_Core", "1.4.71"));
            return fixture.get(uri, limit);
        })) {
            assertThrows(IOException.class, () -> updater.execute(true, "1.4.72"));
            assertFalse(Files.exists(root.resolve("update")));
        }
    }

    @Test void shutdownAndConcurrentInvocationCannotPublishABatch() throws Exception {
        Fixture fixture = new Fixture();
        fixture.install("CHRDK_Core", "1.4.72");
        CountDownLatch started = new CountDownLatch(1), proceed = new CountDownLatch(1);
        try (var updater = fixture.service((uri, limit) -> {
            if (!uri.getHost().equals("api.github.com")) {
                started.countDown();
                if (!proceed.await(10, TimeUnit.SECONDS)) throw new IOException("Test timed out");
            }
            return fixture.get(uri, limit);
        }); var executor = Executors.newSingleThreadExecutor()) {
            Future<?> pending = executor.submit(() -> {
                assertThrows(IOException.class, () -> updater.execute(true, "1.4.72"));
            });
            assertTrue(started.await(10, TimeUnit.SECONDS));
            assertThrows(IOException.class, () -> updater.execute(false, "1.4.72"));
            updater.close();
            proceed.countDown();
            pending.get(10, TimeUnit.SECONDS);
            assertFalse(Files.exists(root.resolve("update")));
        }
    }

    @Test void unsafeRedirectsAndOversizedBodiesAreRejected() throws Exception {
        for (String uri : List.of("http://github.com/a", "https:github.com/a", "https://localhost/a", "https://github.com.evil.example/a",
                "https://github.com@localhost/a", "https://github.com:444/a", "https://127.0.0.1/a"))
            assertThrows(IOException.class, () -> PluginUpdateService.GithubTransport.validateUri(URI.create(uri)));
        PluginUpdateService.GithubTransport.validateUri(URI.create("https://release-assets.githubusercontent.com/a?signature=ok"));
        assertThrows(IOException.class, () -> PluginUpdateService.readLimited(new ByteArrayInputStream(new byte[20]), 10));
        PluginUpdateService.LimitedBody body = new PluginUpdateService.LimitedBody(10);
        AtomicBoolean cancelled = new AtomicBoolean();
        body.onSubscribe(new Flow.Subscription() {
            public void request(long count) { }
            public void cancel() { cancelled.set(true); }
        });
        body.onNext(List.of(ByteBuffer.wrap(new byte[11])));
        assertTrue(cancelled.get());
        assertThrows(CompletionException.class, () -> body.getBody().toCompletableFuture().join());
    }

    @Test void downloadIsConsoleOrRconOnlyAndModuleListMatchesEveryDescriptor() throws Exception {
        for (Class<? extends CommandSender> type : List.of(ConsoleCommandSender.class, RemoteConsoleCommandSender.class)) {
            CommandSender sender = (CommandSender) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> null);
            assertTrue(RegisterCommandHandler.canDownloadUpdates(sender));
        }
        CommandSender generic = (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(), new Class<?>[]{CommandSender.class}, (p, m, a) -> null);
        assertFalse(RegisterCommandHandler.canDownloadUpdates(generic));
        assertEquals(26, PluginUpdateService.MODULES.size());
        Set<String> actual = new HashSet<>();
        List<Path> descriptors = new ArrayList<>();
        descriptors.add(Path.of("src/main/resources/plugin.yml"));
        try (var dirs = Files.list(Path.of("."))) {
            dirs.map(dir -> dir.resolve("src/main/resources/plugin.yml")).filter(Files::exists).forEach(descriptors::add);
        }
        for (Path file : descriptors) {
            var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file.toFile());
            actual.add(yaml.getString("name"));
        }
        assertEquals(actual, PluginUpdateService.MODULES.keySet());
    }
}
