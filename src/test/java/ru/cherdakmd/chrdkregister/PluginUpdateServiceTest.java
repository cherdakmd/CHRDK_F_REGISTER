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
        final Map<String, byte[]> documents = new TreeMap<>(Map.of(
                PluginUpdateService.MEMO_NAME, "Порядок обновления\nВерсия: 1.4.73\n".getBytes(StandardCharsets.UTF_8)));
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
            var entries = new TreeMap<>(jars);
            entries.putAll(documents);
            publishArchive(zip(entries));
        }
        void publishArchive(byte[] bytes) {
            archive = bytes;
            while (!assets.isEmpty()) assets.remove(0);
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

    @Test void checkVerifiesStableZipMetadataButDoesNotDownloadOrWriteFiles() throws Exception {
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

    @Test void retiredJarBlocksStagingBeforeAnyDownloadAndNeverDeletesServerFiles() throws Exception {
        Fixture fixture = new Fixture(); fixture.install("CHRDK_Core", "1.4.72");
        for (String plugin : List.of("CHRDK_Bosses", "CHRDK_Treasures", "CHRDK_Streams", "CHRDK_Events", "CHRDK_Queue")) {
            Path old = root.resolve("renamed-retired-plugin.jar"); byte[] original = jar(plugin, "1.4.72");
            Files.write(old, original);
            try (var updater = fixture.service()) {
                IOException failure = assertThrows(IOException.class, () -> updater.execute(true, "1.4.72"));
                assertTrue(failure.getMessage().contains(plugin));
                assertEquals(0, fixture.downloads);
                assertFalse(Files.exists(root.resolve("update")));
                assertArrayEquals(original, Files.readAllBytes(old));
            }
            Files.delete(old);
        }
        assertFalse(PluginUpdateService.MODULES.containsKey("CHRDK_Bosses"));
        assertFalse(PluginUpdateService.MODULES.containsKey("CHRDK_Treasures"));
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
            assertFalse(Files.exists(result.updateDirectory().resolve(PluginUpdateService.MEMO_NAME)));
            assertEquals(1, fixture.assets.size(), "Релиз содержит только ZIP, без отдельных JAR-assets");
            assertEquals("5.5.87", PluginUpdateService.descriptor(unrelated).version());
            assertThrows(IOException.class, () -> updater.execute(true, "1.4.72")); // no overwriting staged work
        }
    }

    @Test void validatesAllModulesButOnlyStagesInstalledAddonsAndCanSynchronizeMixedVersions() throws Exception {
        Fixture fixture = new Fixture();
        fixture.install("CHRDK_Core", VERSION);
        fixture.install("CHRDK_Claims", "1.4.72");
        try (var updater = fixture.service()) {
            var result = updater.execute(true, VERSION);
            assertTrue(result.staged());
            assertEquals(2, result.count());
        }
    }

    @Test void retiredQueueJarBlocksStagingAndIsNeverSilentlyDeleted() throws Exception {
        Fixture fixture = new Fixture();
        fixture.install("CHRDK_Core", "1.4.72");
        Path retired = root.resolve("renamed-old-module.jar");
        byte[] content = jar("CHRDK_Queue", "1.4.247");
        Files.write(retired, content);
        try (var updater = fixture.service()) {
            IOException failure = assertThrows(IOException.class, () -> updater.execute(true, "1.4.72"));
            assertTrue(failure.getMessage().contains("CHRDK_Queue"));
            assertEquals(0, fixture.downloads);
            assertFalse(Files.exists(root.resolve("update")));
            assertArrayEquals(content, Files.readAllBytes(retired), "снятие старого JAR — отдельная установка при остановке");
            Files.delete(retired); // имитация отдельно выполненного администратором снятия старого модуля
            assertEquals(1, updater.execute(true, "1.4.72").count());
        }
    }

    @Test void allCurrentFilesDoNotDownloadAgainAndDowngradesAreRefused() throws Exception {
        Fixture fixture = new Fixture();
        fixture.install("CHRDK_Core", VERSION);
        try (var updater = fixture.service()) {
            assertTrue(updater.execute(true, VERSION).filesCurrent());
            assertEquals(0, fixture.downloads);
            assertThrows(IOException.class, () -> updater.execute(true, "1.4.74"));
            fixture.install("CHRDK_Claims", "1.4.74");
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

    @Test void outerDigestAndCompleteZipContentsAreBothRequired() throws Exception {
        Fixture fixture = new Fixture();
        fixture.install("CHRDK_Core", "1.4.72");
        try (var updater = fixture.service()) {
            assetsFirst(fixture).addProperty("digest", "sha256:" + "0".repeat(64));
            assertThrows(IOException.class, () -> updater.execute(true, "1.4.72"));
            fixture.jars.remove(fixture.jars.keySet().iterator().next());
            fixture.refresh(); // Даже с верным SHA-256 неполный комплект запрещён.
            assertThrows(IOException.class, () -> updater.execute(true, "1.4.72"));
            assertFalse(Files.exists(root.resolve("update")));
        }
    }

    @Test void individualJarAssetsAreNeitherRequiredNorUsedAsAuthority() throws Exception {
        Fixture fixture = new Fixture(); fixture.install("CHRDK_Core", "1.4.72");
        fixture.addAsset("chrdk-core-" + VERSION + ".jar", new byte[]{1, 2, 3});
        var unrelated = new JsonObject(); unrelated.addProperty("name", "manual.txt");
        fixture.assets.add(unrelated);
        try (var updater = fixture.service()) {
            assertTrue(updater.execute(true, "1.4.72").staged());
            assertEquals(1, fixture.downloads, "Скачивается только проверенный ZIP");
        }
    }

    @Test void actualWorkflowPackagerProducesAnAcceptedZipOnlyRelease() throws Exception {
        Fixture fixture = new Fixture();
        Path live = fixture.install("CHRDK_Core", "1.4.72");
        byte[] original = Files.readAllBytes(live);
        Path input = Files.createDirectory(root.resolve("release-input"));
        for (var entry : fixture.jars.entrySet()) Files.write(input.resolve(entry.getKey()), entry.getValue());
        Files.writeString(input.resolve(PluginUpdateService.MEMO_NAME),
                Files.readString(Path.of("docs/releases/update-memo.txt")) + "\nВерсия: " + VERSION);
        Path archive = root.resolve("chrdk-plugins-" + VERSION + ".zip");
        assertTrue(Files.readString(Path.of(".github/workflows/release.yml"))
                .contains("python3 .github/scripts/pack_release.py"));
        Process process = new ProcessBuilder("python3", ".github/scripts/pack_release.py",
                input.toString(), VERSION, archive.toString()).redirectErrorStream(true).start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished, "Упаковщик должен завершиться за 30 секунд");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        fixture.publishArchive(Files.readAllBytes(archive));
        assertEquals(1, fixture.assets.size());
        try (var updater = fixture.service()) {
            var result = updater.execute(true, "1.4.72");
            assertTrue(result.staged());
            assertEquals(1, result.count(), "Проверены все модули, установлен только Core");
            assertArrayEquals(fixture.jars.get("chrdk-core-" + VERSION + ".jar"),
                    Files.readAllBytes(result.updateDirectory().resolve(live.getFileName())));
            assertArrayEquals(original, Files.readAllBytes(live));
            assertFalse(Files.exists(result.updateDirectory().resolve(PluginUpdateService.MEMO_NAME)));
        }
    }

    @Test void memoIsRequiredBoundedUtf8AndNeverPublishedAsAPlugin() throws Exception {
        Fixture fixture = new Fixture(); fixture.install("CHRDK_Core", "1.4.72");
        try (var updater = fixture.service()) {
            fixture.documents.clear(); fixture.refresh();
            assertThrows(IOException.class, () -> updater.execute(true, "1.4.72"));
            for (byte[] invalid : List.of(new byte[0], "  \n".getBytes(StandardCharsets.UTF_8),
                    new byte[]{(byte)0xc3, 0x28}, "Текст\0".getBytes(StandardCharsets.UTF_8),
                    new byte[PluginUpdateService.MEMO_LIMIT + 1], "\uFEFF".getBytes(StandardCharsets.UTF_8))) {
                fixture.documents.put(PluginUpdateService.MEMO_NAME, invalid); fixture.refresh();
                assertThrows(IOException.class, () -> updater.execute(true, "1.4.72"));
                assertFalse(Files.exists(root.resolve("update")));
            }
            fixture.documents.put(PluginUpdateService.MEMO_NAME, "\uFEFFОбновление\r\n".getBytes(StandardCharsets.UTF_8));
            fixture.refresh();
            assertTrue(updater.execute(true, "1.4.72").staged());
            assertFalse(Files.exists(root.resolve("update").resolve(PluginUpdateService.MEMO_NAME)));
        }
    }

    @Test void foreignFilesDirectoriesAndRenamedMemoAreRejected() throws Exception {
        Fixture fixture = new Fixture(); fixture.install("CHRDK_Core", "1.4.72");
        try (var updater = fixture.service()) {
            for (String name : List.of("README.txt", "notes/", "../ОБНОВЛЕНИЕ.txt", "notes/ОБНОВЛЕНИЕ.txt",
                    "./ОБНОВЛЕНИЕ.txt", "ОБНОВЛЕНИЕ.txt.jar", "notes\\ОБНОВЛЕНИЕ.txt")) {
                fixture.documents.put(name, "Обновление".getBytes(StandardCharsets.UTF_8)); fixture.refresh();
                assertThrows(IOException.class, () -> updater.execute(true, "1.4.72"), name);
                assertFalse(Files.exists(root.resolve("update")));
                fixture.documents.remove(name);
            }
        }
    }

    @Test void duplicateMemoAndJarEntriesAreRejected() throws Exception {
        Fixture fixture = new Fixture(); fixture.install("CHRDK_Core", "1.4.72");
        try (var updater = fixture.service()) {
            for (String repeated : List.of(PluginUpdateService.MEMO_NAME, "chrdk-core-" + VERSION + ".jar")) {
                var entries = new TreeMap<>(fixture.jars); entries.putAll(fixture.documents);
                byte[] target = repeated.getBytes(StandardCharsets.UTF_8);
                String marker = "x".repeat(target.length);
                entries.put(marker, entries.get(repeated));
                byte[] bytes = zip(entries);
                byte[] from = marker.getBytes(StandardCharsets.UTF_8);
                int replacements = 0;
                for (int i = 0; i <= bytes.length - from.length; i++) {
                    if (Arrays.equals(bytes, i, i + from.length, from, 0, from.length)) {
                        System.arraycopy(target, 0, bytes, i, target.length); replacements++; i += from.length - 1;
                    }
                }
                assertEquals(2, replacements, "Local header и central directory содержат одно и то же имя");
                fixture.publishArchive(bytes);
                var error = assertThrows(IOException.class, () -> updater.execute(true, "1.4.72"));
                assertTrue(error.getMessage().contains("повторяющийся"));
                assertFalse(Files.exists(root.resolve("update")));
            }
        }
    }

    @Test void duplicateZipAssetAndInvalidZipSizesAreRejectedBeforeDownload() throws Exception {
        Fixture fixture = new Fixture(); fixture.install("CHRDK_Core", "1.4.72");
        try (var updater = fixture.service()) {
            fixture.addAsset("chrdk-plugins-" + VERSION + ".zip", fixture.archive);
            assertThrows(IOException.class, () -> updater.execute(true, "1.4.72"));
            for (Number invalid : List.<Number>of(0, -1, PluginUpdateService.ZIP_LIMIT + 1L, 1.25)) {
                fixture.refresh(); assetsFirst(fixture).addProperty("size", invalid);
                assertThrows(IOException.class, () -> updater.execute(true, "1.4.72"));
            }
            fixture.refresh(); assetsFirst(fixture).addProperty("size", "1234");
            assertThrows(IOException.class, () -> updater.execute(true, "1.4.72"));
            assertEquals(0, fixture.downloads);
        }
    }

    @Test void corruptedOrOversizedJarNeverPublishesAnUpdate() throws Exception {
        Fixture fixture = new Fixture(); fixture.install("CHRDK_Core", "1.4.72");
        try (var updater = fixture.service()) {
            for (byte[] invalid : List.of(new byte[]{1, 2, 3}, new byte[PluginUpdateService.JAR_LIMIT + 1])) {
                fixture.jars.put("chrdk-core-" + VERSION + ".jar", invalid); fixture.refresh();
                assertThrows(IOException.class, () -> updater.execute(true, "1.4.72"));
                assertFalse(Files.exists(root.resolve("update")));
            }
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
        assertEquals(25, PluginUpdateService.MODULES.size());
        assertFalse(PluginUpdateService.MODULES.containsKey("CHRDK_VK"),
                "CHRDK_VK публикуется отдельным JAR рядом с общим архивом, а не внутри ZIP");
        Set<String> standalone = Set.of("CHRDK_VK");
        Set<String> actual = new HashSet<>();
        List<Path> descriptors = new ArrayList<>();
        descriptors.add(Path.of("src/main/resources/plugin.yml"));
        try (var dirs = Files.list(Path.of("."))) {
            dirs.map(dir -> dir.resolve("src/main/resources/plugin.yml")).filter(Files::exists).forEach(descriptors::add);
        }
        for (Path file : descriptors) {
            var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file.toFile());
            String plugin = yaml.getString("name");
            if (standalone.contains(plugin)) continue;
            actual.add(plugin);
            String basename;
            if ("CHRDK_Core".equals(plugin)) {
                var properties = new Properties();
                try (var input = Files.newInputStream(Path.of("gradle.properties"))) { properties.load(input); }
                basename = properties.getProperty("archives_base_name");
            } else {
                String gradle = Files.readString(Path.of(file.normalize().getName(0).toString(), "build.gradle"));
                var matcher = java.util.regex.Pattern.compile("archivesName\\s*=\\s*'([^']+)'").matcher(gradle);
                assertTrue(matcher.find(), file.toString());
                basename = matcher.group(1);
            }
            assertEquals(basename, PluginUpdateService.MODULES.get(plugin), "Имя архива сборки: " + plugin);
        }
        assertEquals(actual, PluginUpdateService.MODULES.keySet());
    }

    @Test void standaloneVkAssetAndInstalledJarDoNotBreakGeneralPackUpdater() throws Exception {
        Fixture fixture = new Fixture();
        fixture.install("CHRDK_Core", "1.4.72");
        byte[] vkJar = jar("CHRDK_VK", VERSION);
        fixture.addAsset("chrdk-vk-" + VERSION + ".jar", vkJar);
        Path installedVk = root.resolve("chrdk-vk-1.4.72.jar");
        Files.write(installedVk, jar("CHRDK_VK", "1.4.72"));
        try (var updater = fixture.service()) {
            var result = updater.execute(true, "1.4.72");
            assertTrue(result.staged());
            assertEquals(1, result.count(), "обновляются установленные плагины общего комплекта, а отдельный CHRDK_VK не блокирует обновление");
            assertTrue(Files.exists(installedVk));
        }
    }
}
