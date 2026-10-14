package ru.cherdakmd.chrdkregister;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.jar.JarFile;
import java.util.zip.ZipInputStream;

/** Blocking IO only: invoke off the server thread. Never replaces a running plugin JAR. */
public final class PluginUpdateService implements AutoCloseable {
    static final int JSON_LIMIT = 2 * 1024 * 1024;
    static final int ZIP_LIMIT = 64 * 1024 * 1024;
    static final int JAR_LIMIT = 32 * 1024 * 1024;
    static final Map<String, String> MODULES = modules();
    private final Path plugins, update, data;
    private final Supplier<String> repository;
    private final String serverVersion;
    private final Transport transport;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Object publicationLock = new Object();
    private volatile boolean closed;

    record Asset(String name, URI url, long size, String sha256) { }
    record Release(String version, String url, Asset zip, Map<String, Asset> jars) { }
    record Descriptor(String name, String version, String main, String api) { }
    record Installed(Path path, Descriptor descriptor, String hash) { }
    public record Result(String version, String url, boolean staged, boolean filesCurrent,
                         int count, Path backup, Path updateDirectory) { }

    @FunctionalInterface
    interface Transport extends AutoCloseable {
        byte[] get(URI uri, int limit) throws IOException, InterruptedException;
        default void close() { }
    }

    public PluginUpdateService(Path plugins, Path update, Path data, Supplier<String> repository, String serverVersion) {
        this(plugins, update, data, repository, serverVersion, new GithubTransport());
    }

    PluginUpdateService(Path plugins, Path update, Path data, Supplier<String> repository,
                        String serverVersion, Transport transport) {
        this.plugins = plugins.toAbsolutePath().normalize();
        this.update = update.toAbsolutePath().normalize();
        this.data = data.toAbsolutePath().normalize();
        this.repository = repository;
        this.serverVersion = serverVersion;
        this.transport = transport;
    }

    /** Explicit commands are independent of the automatic update-check timer's enabled flag. */
    public Result execute(boolean download, String runningVersion) throws IOException, InterruptedException {
        if (!busy.compareAndSet(false, true)) throw new IOException("Проверка или скачивание уже выполняется.");
        try {
            ensureOpen();
            Release release = fetchRelease();
            if (!download) return new Result(release.version(), release.url(), false, false, 0, null, null);
            if (UpdateChecker.compareVersions(release.version(), runningVersion) < 0)
                throw new IOException("Автоматическое понижение версии запрещено.");
            return stage(release);
        } finally {
            busy.set(false);
        }
    }

    private Release fetchRelease() throws IOException, InterruptedException {
        String repo = repository.get();
        if (repo == null || !repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
            throw new IOException("Некорректный updates.repo: требуется owner/name.");
        byte[] bytes = transport.get(URI.create("https://api.github.com/repos/" + repo + "/releases/latest"), JSON_LIMIT);
        if (bytes.length > JSON_LIMIT) throw new IOException("Слишком большой ответ GitHub.");
        try {
            JsonObject json = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if (json.get("draft").getAsBoolean() || json.get("prerelease").getAsBoolean())
                throw new IOException("Разрешены только опубликованные стабильные релизы.");
            String tag = json.get("tag_name").getAsString();
            if (!tag.matches("[vV]?[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}"))
                throw new IOException("Неподдерживаемый формат версии релиза.");
            String version = UpdateChecker.normalizeVersion(tag);
            String prefix = "https://github.com/" + repo + "/releases/download/" + tag + "/";
            Map<String, Asset> assets = new HashMap<>();
            for (var element : json.getAsJsonArray("assets")) {
                JsonObject item = element.getAsJsonObject();
                String name = item.get("name").getAsString();
                // Ignore unrelated assets, but require exact names, digests and URLs for this bundle.
                if (!name.equals("chrdk-plugins-" + version + ".zip")
                        && MODULES.values().stream().noneMatch(base -> name.equals(base + "-" + version + ".jar"))) continue;
                String digest = item.has("digest") && !item.get("digest").isJsonNull() ? item.get("digest").getAsString() : "";
                String url = item.get("browser_download_url").getAsString();
                long size = item.get("size").getAsLong();
                if (!digest.matches("sha256:[0-9a-fA-F]{64}") || !url.equals(prefix + name)
                        || size <= 0 || size > (name.endsWith(".zip") ? ZIP_LIMIT : JAR_LIMIT))
                    throw new IOException("Релиз не готов: неверный адрес, размер или SHA-256 файла " + name);
                if (assets.put(name, new Asset(name, URI.create(url), size, digest.substring(7).toLowerCase(Locale.ROOT))) != null)
                    throw new IOException("Повторяющийся файл релиза: " + name);
            }
            Map<String, Asset> jars = new TreeMap<>();
            for (var module : MODULES.entrySet()) {
                String name = module.getValue() + "-" + version + ".jar";
                Asset asset = assets.get(name);
                if (asset == null) throw new IOException("Релиз ещё не собран полностью: отсутствует " + name);
                jars.put(module.getKey(), asset);
            }
            Asset zip = assets.get("chrdk-plugins-" + version + ".zip");
            if (zip == null) throw new IOException("Релиз ещё не собран полностью: отсутствует общий ZIP.");
            return new Release(version, "https://github.com/" + repo + "/releases/tag/" + tag, zip, Map.copyOf(jars));
        } catch (RuntimeException exception) {
            throw new IOException("Некорректные метаданные релиза GitHub.", exception);
        }
    }

    private Result stage(Release release) throws IOException, InterruptedException {
        checkPaths();
        requireEmptyUpdateDirectory();
        Map<String, Installed> installed = installed();
        if (!installed.containsKey("CHRDK_Core")) throw new IOException("JAR CHRDK_Core не найден в каталоге plugins.");
        for (Installed entry : installed.values()) {
            if (!entry.descriptor().version().matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")
                    || UpdateChecker.compareVersions(entry.descriptor().version(), release.version()) > 0)
                throw new IOException("Нельзя понизить или определить версию " + entry.descriptor().name());
        }
        if (installed.values().stream().allMatch(entry -> entry.descriptor().version().equals(release.version())))
            return new Result(release.version(), release.url(), false, true, installed.size(), null, update);

        byte[] zip = transport.get(release.zip().url(), ZIP_LIMIT);
        verify(zip, release.zip());
        ensureOpen();
        Path prepared = Files.createTempDirectory(plugins, ".chrdk-update-");
        Path backup = null;
        boolean published = false;
        try {
            // All sixteen JARs must be valid, even when only some addons are installed.
            unpackAndVerify(zip, release, prepared);
            for (var entry : installed.entrySet()) {
                Path source = prepared.resolve(release.jars().get(entry.getKey()).name());
                Path target = prepared.resolve(entry.getValue().path().getFileName());
                // Keep the old filename: Bukkit uses it to replace the original on next startup.
                // A separate directory avoids collisions with other release filenames.
                Path ready = prepared.resolve("ready");
                Files.createDirectories(ready);
                Files.copy(source, ready.resolve(target.getFileName()));
            }
            Path backupRoot = data.resolve("update-backups");
            safeChild(backupRoot);
            Files.createDirectories(backupRoot);
            backup = Files.createTempDirectory(backupRoot, "before-" + release.version() + "-");
            JsonObject manifest = new JsonObject();
            manifest.addProperty("target", release.version());
            manifest.addProperty("release", release.url());
            manifest.addProperty("created", Instant.now().toString());
            JsonObject files = new JsonObject();
            for (Installed entry : installed.values()) {
                Path copy = backup.resolve(entry.path().getFileName());
                Files.copy(entry.path(), copy, LinkOption.NOFOLLOW_LINKS);
                if (Files.isSymbolicLink(copy) || !hash(copy).equals(entry.hash()))
                    throw new IOException("Исходный JAR изменился во время резервного копирования.");
                files.addProperty(copy.getFileName().toString(), entry.hash());
            }
            manifest.add("originalSha256", files);
            Files.writeString(backup.resolve("manifest.json"), manifest.toString());
            Files.writeString(backup.resolve("COMPLETE"), "Backups only. Restore with the server stopped.\n");
            if (!installed.equals(installed())) throw new IOException("Состав или содержимое plugins изменились. Повторите команду.");
            // Publish the ENTIRE batch in one rename. No partial set can appear in Bukkit's update folder.
            synchronized (publicationLock) {
                ensureOpen();
                safeChild(update);
                requireEmptyUpdateDirectory();
                if (Files.exists(update, LinkOption.NOFOLLOW_LINKS)) Files.delete(update); // empty only
                try {
                    Files.move(prepared.resolve("ready"), update, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException exception) {
                    throw new IOException("Файловая система не поддерживает атомарную подготовку обновления; используйте ручную установку.", exception);
                }
                published = true;
            }
            return new Result(release.version(), release.url(), true, false, installed.size(), backup, update);
        } finally {
            deleteTree(prepared);
            if (!published && backup != null) deleteTree(backup);
        }
    }

    private void unpackAndVerify(byte[] zip, Release release, Path prepared) throws IOException {
        Map<String, String> names = new HashMap<>();
        release.jars().forEach((plugin, asset) -> names.put(asset.name(), plugin));
        Set<String> seen = new HashSet<>();
        int total = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                String name = entry.getName();
                // No zip paths, directories, unknown entries or duplicate filenames.
                if (entry.isDirectory() || !names.containsKey(name) || !seen.add(name))
                    throw new IOException("Неожиданный или повторяющийся файл в ZIP.");
                byte[] jar = readLimited(input, JAR_LIMIT);
                total += jar.length;
                if (total > ZIP_LIMIT) throw new IOException("Распакованный комплект слишком большой.");
                String plugin = names.get(name);
                verify(jar, release.jars().get(plugin));
                Path path = prepared.resolve(name);
                Files.write(path, jar, StandardOpenOption.CREATE_NEW);
                Descriptor descriptor = descriptor(path);
                if (descriptor == null || !plugin.equals(descriptor.name()) || !release.version().equals(descriptor.version()))
                    throw new IOException("Имя или версия внутри JAR не соответствует релизу: " + name);
                if (descriptor.api() == null || !descriptor.api().matches("[0-9]+(?:\\.[0-9]+){1,2}")
                        || UpdateChecker.normalizeVersion(serverVersion).isEmpty()
                        || UpdateChecker.compareVersions(descriptor.api(), serverVersion) > 0)
                    throw new IOException("Несовместимая версия Minecraft API: " + name + " требует " + descriptor.api());
                try (JarFile file = new JarFile(path.toFile())) {
                    var main = file.getJarEntry(descriptor.main().replace('.', '/') + ".class");
                    if (main == null) throw new IOException("В JAR отсутствует главный класс: " + name);
                    try (DataInputStream stream = new DataInputStream(file.getInputStream(main))) {
                        if (stream.readInt() != 0xCAFEBABE) throw new IOException("Повреждён главный класс JAR.");
                        stream.readUnsignedShort();
                        if (stream.readUnsignedShort() > Runtime.version().feature() + 44)
                            throw new IOException("Для обновления требуется более новая Java: " + name);
                    }
                }
            }
        }
        if (seen.size() != MODULES.size()) throw new IOException("ZIP не содержит все 16 плагинов.");
    }

    private Map<String, Installed> installed() throws IOException {
        Map<String, Installed> found = new TreeMap<>();
        try (var paths = Files.list(plugins)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList()) {
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Автообновление не поддерживает ссылки вместо JAR: " + path.getFileName());
                Descriptor descriptor = descriptor(path);
                if (descriptor == null || !MODULES.containsKey(descriptor.name())) continue;
                if (found.put(descriptor.name(), new Installed(path, descriptor, hash(path))) != null)
                    throw new IOException("В plugins несколько JAR одного плагина: " + descriptor.name());
            }
        }
        return found;
    }

    static Descriptor descriptor(Path path) throws IOException {
        try (JarFile file = new JarFile(path.toFile())) {
            var entry = file.getJarEntry("plugin.yml");
            if (entry == null) return null; // unrelated Paper-only plugins
            YamlConfiguration yaml = new YamlConfiguration();
            try (InputStream stream = file.getInputStream(entry)) {
                yaml.loadFromString(new String(readLimited(stream, 256 * 1024), java.nio.charset.StandardCharsets.UTF_8));
            } catch (org.bukkit.configuration.InvalidConfigurationException exception) {
                throw new IOException("Некорректный plugin.yml: " + path.getFileName(), exception);
            }
            return new Descriptor(yaml.getString("name", ""), yaml.getString("version", ""),
                    yaml.getString("main", ""), yaml.getString("api-version"));
        }
    }

    private void checkPaths() throws IOException {
        if (!Files.isDirectory(plugins) || Files.isSymbolicLink(plugins)) throw new IOException("Небезопасный каталог plugins.");
        if (!update.getParent().equals(plugins) || update.equals(data))
            throw new IOException("Каталог обновлений должен быть отдельным непосредственным подкаталогом plugins.");
        safeChild(update);
        safeChild(data);
    }

    private void safeChild(Path path) throws IOException {
        if (!path.startsWith(plugins) || path.equals(plugins)) throw new IOException("Путь выходит за пределы plugins.");
        Path current = plugins;
        for (Path part : plugins.relativize(path)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Символические ссылки в путях автообновления запрещены.");
        }
    }

    private void requireEmptyUpdateDirectory() throws IOException {
        if (Files.exists(update, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(update, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Каталог обновлений занят файлом.");
            try (var files = Files.list(update)) {
                if (files.findAny().isPresent()) throw new IOException("Каталог обновлений уже занят. Перезапустите сервер для применения подготовленных файлов или разберите их вручную.");
            }
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Обновление отменено: плагин выключается.");
    }

    private static void verify(byte[] bytes, Asset asset) throws IOException {
        if (bytes.length != asset.size() || !sha256(bytes).equals(asset.sha256()))
            throw new IOException("Размер или SHA-256 не совпадает: " + asset.name());
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String hash(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                for (int size; (size = input.read(buffer)) != -1;) digest.update(buffer, 0, size);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static byte[] readLimited(InputStream input, int limit) throws IOException {
        byte[] bytes = input.readNBytes(limit + 1);
        if (bytes.length > limit) throw new IOException("Превышен допустимый размер файла.");
        return bytes;
    }

    private static void deleteTree(Path root) {
        // Never follow symlinks. Cleanup must not turn a successfully staged update into a reported failure.
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException | UncheckedIOException | SecurityException ignored) { }
    }

    @Override public void close() {
        synchronized (publicationLock) { closed = true; }
        transport.close();
    }

    private static Map<String, String> modules() {
        Map<String, String> result = new TreeMap<>();
        result.put("CHRDK_Core", "chrdk-core");
        result.put("CHRDK_Artifacts", "chrdk-artifacts");
        result.put("CHRDK_Auction", "chrdk-auction");
        result.put("CHRDK_BattlePass", "chrdk-battlepass");
        result.put("CHRDK_Claims", "chrdk-claims");
        result.put("CHRDK_CommonGoals", "chrdk-common-goals");
        result.put("CHRDK_Cosmetics", "chrdk-cosmetics");
        result.put("CHRDK_DailyRewards", "chrdk-daily-rewards");
        result.put("CHRDK_DiamondShop", "chrdk-diamond-shop");
        result.put("CHRDK_Enchants", "chrdk-enchants");
        result.put("CHRDK_Lottery", "chrdk-lottery");
        result.put("CHRDK_Queue", "chrdk-queue");
        result.put("CHRDK_Ranks", "chrdk-ranks");
        result.put("CHRDK_Referrals", "chrdk-referrals");
        result.put("CHRDK_Teleports", "chrdk-teleports");
        result.put("CHRDK_TierForge", "chrdk-tier-forge");
        return Collections.unmodifiableMap(result);
    }

    /** HTTPS only; bounded response bodies and request deadlines also apply to redirect responses. */
    static final class GithubTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();

        static void validateUri(URI uri) throws IOException {
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !Set.of("github.com", "api.github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com").contains(uri.getHost()))
                throw new IOException("Небезопасный адрес загрузки или перенаправления.");
        }

        @Override public byte[] get(URI uri, int limit) throws IOException, InterruptedException {
            for (int redirects = 0; redirects <= 5; redirects++) {
                validateUri(uri);
                HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(90))
                        .header("User-Agent", "CHRDK_Core-safe-updater")
                        .header("Accept", uri.getHost().equals("api.github.com") ? "application/vnd.github+json" : "application/octet-stream")
                        .GET().build();
                CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request, info -> new LimitedBody(limit));
                HttpResponse<byte[]> response;
                try {
                    // A total deadline includes the entire body, even if headers arrive promptly.
                    response = pending.get(90, TimeUnit.SECONDS);
                } catch (TimeoutException timeout) {
                    pending.cancel(true);
                    throw new IOException("Истекло время загрузки ответа GitHub.", timeout);
                } catch (ExecutionException exception) {
                    if (exception.getCause() instanceof IOException io) throw io;
                    throw new IOException("Ошибка загрузки GitHub.", exception.getCause());
                } catch (InterruptedException interrupted) {
                    pending.cancel(true);
                    throw interrupted;
                }
                if (response.statusCode() == 200) return response.body();
                if (Set.of(301, 302, 303, 307, 308).contains(response.statusCode())) {
                    String location = response.headers().firstValue("Location").orElseThrow(() -> new IOException("Перенаправление без адреса."));
                    try { uri = uri.resolve(location); }
                    catch (IllegalArgumentException exception) { throw new IOException("Некорректное перенаправление.", exception); }
                    continue;
                }
                throw new IOException("GitHub HTTP " + response.statusCode() + ": релиз недоступен (возможен лимит запросов).");
            }
            throw new IOException("Слишком много перенаправлений GitHub.");
        }

        @Override public void close() { client.shutdownNow(); }
    }

    /** Bounds allocations during HTTP transfer, not just after downloading an unbounded body. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - output.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("Превышен допустимый размер ответа HTTP."));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                output.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(output.toByteArray()); }
    }
}
