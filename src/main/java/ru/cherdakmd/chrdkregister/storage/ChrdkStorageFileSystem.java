package ru.cherdakmd.chrdkregister.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.cherdakmd.chrdkregister.api.storage.StorageMode;
import ru.cherdakmd.chrdkregister.storage.db.ChrdkDatabase;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.ClosedDirectoryStreamException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.ProviderMismatchException;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * NIO-совместимая прослойка: в {@code MYSQL}-режиме runtime JSON/YAML из
 * {@code storage-inventory.json} читаются и пишутся в {@code chrdk_storage_file},
 * а конфиги, сообщения, логи, ресурсы и service secrets остаются на обычном
 * диске. Существующие Store продолжают пользоваться {@code Files.*},
 * {@code SafeJsonWriter} и {@code FileChannel.open}, не зная про JDBC.
 */
public final class ChrdkStorageFileSystem extends FileSystem {

    private static final String SCHEME = "chrdkstorage";
    private static final Set<String> EXCLUDED_PREFIXES = Set.of(
            "assets/", "logs/", "work-journal/", "update/", "update-backups/",
            "paid-audit/", "src/", "upgrade-"
    );
    private static final Set<String> KNOWN_DIRECTORIES = Set.of("players", "backups");

    private static final ChrdkStorageFileSystem INSTANCE = new ChrdkStorageFileSystem();

    private final StorageProvider provider;
    private final StorageFileStore fileStore;
    private final Map<String, Path> roots = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> databasePaths = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> excludedPaths = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> databaseBasenames = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> excludedBasenames = new ConcurrentHashMap<>();

    private volatile StorageMode mode = StorageMode.FILE;
    private volatile ChrdkDatabase database;
    private volatile boolean open = true;

    private ChrdkStorageFileSystem() {
        loadInventory();
        this.provider = new StorageProvider();
        this.fileStore = new StorageFileStore();
    }

    public static ChrdkStorageFileSystem instance() {
        return INSTANCE;
    }

    public synchronized void configure(StorageMode newMode, ChrdkDatabase newDatabase) {
        Objects.requireNonNull(newMode, "mode");
        if (newMode == StorageMode.MYSQL && newDatabase == null) {
            throw new IllegalStateException("backend=MYSQL требует инициализированный ChrdkDatabase");
        }
        this.mode = newMode;
        this.database = newDatabase;
        this.open = true;
    }

    public StorageMode mode() {
        return mode;
    }

    public ChrdkDatabase database() {
        ChrdkDatabase current = database;
        if (mode == StorageMode.MYSQL && current == null) {
            throw new IllegalStateException("CHRDK storage database is not initialized");
        }
        return current;
    }

    public boolean readOnly() {
        return mode == StorageMode.FILE_READONLY;
    }

    public StoragePath root(String namespace, Path defaultRoot) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(defaultRoot, "defaultRoot");
        roots.putIfAbsent(namespace, defaultRoot);
        return new StoragePath(namespace, defaultRoot, "", false);
    }

    boolean isDatabasePath(String namespace, String relative) {
        if (relative.isEmpty()) return false;
        String lower = relative.toLowerCase(Locale.ROOT);
        for (String prefix : EXCLUDED_PREFIXES) {
            if (lower.startsWith(prefix)) return false;
        }
        String leaf = basename(relative);
        Set<String> excluded = excludedPaths.get(namespace);
        if (excluded != null) {
            if (excluded.contains(relative)) return false;
            for (String base : excludedBasenames.getOrDefault(namespace, Set.of())) {
                if (leaf.startsWith(base)) return false;
            }
        }
        Set<String> paths = databasePaths.get(namespace);
        if (paths == null) return false;
        if (paths.contains(relative)) return true;
        for (String base : databaseBasenames.getOrDefault(namespace, Set.of())) {
            if (leaf.startsWith(base)) return true;
        }
        String prefix = relative + "/";
        for (String path : paths) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    private void loadInventory() {
        try (InputStream stream = ChrdkStorageFileSystem.class.getResourceAsStream("/storage-inventory.json")) {
            if (stream == null) throw new IllegalStateException("storage-inventory.json отсутствует в core JAR");
            JsonObject root = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonArray files = root.getAsJsonArray("files");
            for (JsonElement element : files) {
                JsonObject row = element.getAsJsonObject();
                String namespace = row.get("plugin").getAsString();
                String path = row.get("path").getAsString();
                String secret = row.has("secret_class") ? row.get("secret_class").getAsString() : "none";
                String migration = row.has("migration") ? row.get("migration").getAsString() : "db_document";
                if (!"none".equals(secret) || "exclude".equals(migration) || "auth_reset".equals(migration)) {
                    excludedPaths.computeIfAbsent(namespace, ignored -> ConcurrentHashMap.newKeySet()).add(path);
                    excludedBasenames.computeIfAbsent(namespace, ignored -> ConcurrentHashMap.newKeySet())
                            .add(basename(path));
                    continue;
                }
                databasePaths.computeIfAbsent(namespace, ignored -> ConcurrentHashMap.newKeySet()).add(path);
                databaseBasenames.computeIfAbsent(namespace, ignored -> ConcurrentHashMap.newKeySet())
                        .add(basename(path));
            }
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Не удалось загрузить inventory хранения CHRDK", error);
        }
    }

    private static String basename(String relative) {
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? relative : relative.substring(slash + 1);
    }

    private StoragePath pathFromUri(URI uri) {
        if (uri.getScheme() != null && !SCHEME.equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Unexpected storage URI: " + uri);
        }
        String path = uri.getPath();
        if (path == null || !path.startsWith("/")) throw new IllegalArgumentException("Unexpected storage URI: " + uri);
        String body = path.substring(1);
        int slash = body.indexOf('/');
        String namespace = slash < 0 ? body : body.substring(0, slash);
        String relative = slash < 0 ? "" : body.substring(slash + 1);
        Path defaultRoot = roots.getOrDefault(namespace, Paths.get("."));
        return new StoragePath(namespace, defaultRoot, normalizeRelative(relative),
                isDatabasePath(namespace, normalizeRelative(relative)));
    }

    private StoragePath pathFromString(String first, String... more) {
        String text = first;
        if (more.length > 0) text = text + "/" + String.join("/", more);
        if (text.startsWith(SCHEME + ":")) return pathFromUri(URI.create(text));
        String stripped = text.startsWith("/") ? text.substring(1) : text;
        int slash = stripped.indexOf('/');
        String namespace = slash < 0 ? stripped : stripped.substring(0, slash);
        String relative = slash < 0 ? "" : stripped.substring(slash + 1);
        Path defaultRoot = roots.getOrDefault(namespace, Paths.get("."));
        String normalized = normalizeRelative(relative);
        return new StoragePath(namespace, defaultRoot, normalized, isDatabasePath(namespace, normalized));
    }

    static String normalizeRelative(String raw) {
        String value = Objects.requireNonNull(raw, "path").replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        Deque<String> parts = new ArrayDeque<>();
        for (String part : value.split("/")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!parts.isEmpty()) parts.removeLast();
            } else {
                parts.addLast(part);
            }
        }
        return String.join("/", parts);
    }

    private static String join(String base, String other) {
        String value = other.replace('\\', '/');
        if (value.startsWith("/")) return normalizeRelative(value);
        return normalizeRelative(base.isEmpty() ? value : base + "/" + value);
    }

    private static List<String> parts(String relative) {
        if (relative.isEmpty()) return List.of();
        return List.of(relative.split("/"));
    }

    private static boolean startsWithParts(List<String> value, List<String> prefix) {
        if (prefix.size() > value.size()) return false;
        for (int i = 0; i < prefix.size(); i++) {
            if (!value.get(i).equals(prefix.get(i))) return false;
        }
        return true;
    }

    private static boolean endsWithParts(List<String> value, List<String> suffix) {
        if (suffix.size() > value.size()) return false;
        int offset = value.size() - suffix.size();
        for (int i = 0; i < suffix.size(); i++) {
            if (!value.get(offset + i).equals(suffix.get(i))) return false;
        }
        return true;
    }

    private static String parent(String relative) {
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? "" : relative.substring(0, slash);
    }

    @Override
    public FileSystemProvider provider() {
        return provider;
    }

    @Override
    public void close() {
        open = false;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public boolean isReadOnly() {
        return readOnly();
    }

    @Override
    public String getSeparator() {
        return "/";
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        List<Path> out = new ArrayList<>();
        for (Map.Entry<String, Path> entry : roots.entrySet()) {
            out.add(new StoragePath(entry.getKey(), entry.getValue(), "", false));
        }
        return out;
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        return Collections.<FileStore>singletonList(fileStore);
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return Set.of("basic", "posix");
    }

    @Override
    public Path getPath(String first, String... more) {
        return pathFromString(first, more);
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        return FileSystems.getDefault().getPathMatcher(syntaxAndPattern);
    }

    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException("CHRDK storage does not support watch services");
    }

    @Override
    public java.nio.file.attribute.UserPrincipalLookupService getUserPrincipalLookupService() {
        return FileSystems.getDefault().getUserPrincipalLookupService();
    }

    /** Путь, который маршрутизирует операции в БД или в обычную файловую систему. */
    public final class StoragePath implements Path {

        private final String namespace;
        private final Path defaultRoot;
        private final String relative;
        private final boolean db;

        private StoragePath(String namespace, Path defaultRoot, String relative, boolean db) {
            this.namespace = namespace;
            this.defaultRoot = defaultRoot;
            this.relative = normalizeRelative(relative);
            this.db = db;
        }

        String namespace() {
            return namespace;
        }

        String relative() {
            return relative;
        }

        boolean db() {
            return db;
        }

        Path defaultPath() {
            return relative.isEmpty() ? defaultRoot : defaultRoot.resolve(relative);
        }

        private StoragePath with(String newRelative) {
            return new StoragePath(namespace, defaultRoot, newRelative, isDatabasePath(namespace, newRelative));
        }

        @Override
        public FileSystem getFileSystem() {
            return ChrdkStorageFileSystem.this;
        }

        @Override
        public boolean isAbsolute() {
            return true;
        }

        @Override
        public Path getRoot() {
            return new StoragePath(namespace, defaultRoot, "", false);
        }

        @Override
        public Path getFileName() {
            if (relative.isEmpty()) return null;
            String leaf = basename(relative);
            return new StoragePath(namespace, defaultRoot, leaf, isDatabasePath(namespace, leaf));
        }

        @Override
        public Path getParent() {
            if (relative.isEmpty()) return null;
            String parent = parent(relative);
            return new StoragePath(namespace, defaultRoot, parent, isDatabasePath(namespace, parent));
        }

        @Override
        public int getNameCount() {
            return parts(relative).size();
        }

        @Override
        public Path getName(int index) {
            List<String> names = parts(relative);
            if (index < 0 || index >= names.size()) throw new IllegalArgumentException("name index");
            String name = names.get(index);
            return new StoragePath(namespace, defaultRoot, name, isDatabasePath(namespace, name));
        }

        @Override
        public Path subpath(int beginIndex, int endIndex) {
            List<String> names = parts(relative);
            if (beginIndex < 0 || endIndex > names.size() || beginIndex >= endIndex) {
                throw new IllegalArgumentException("subpath range");
            }
            String joined = String.join("/", names.subList(beginIndex, endIndex));
            return new StoragePath(namespace, defaultRoot, joined, isDatabasePath(namespace, joined));
        }

        @Override
        public boolean startsWith(Path other) {
            if (other instanceof StoragePath path) {
                return namespace.equals(path.namespace) && startsWithParts(parts(relative), parts(path.relative));
            }
            return toString().startsWith(other.toString());
        }

        @Override
        public boolean startsWith(String other) {
            return toString().startsWith(other);
        }

        @Override
        public boolean endsWith(Path other) {
            if (other instanceof StoragePath path) {
                return namespace.equals(path.namespace) && endsWithParts(parts(relative), parts(path.relative));
            }
            return toString().endsWith(other.toString());
        }

        @Override
        public boolean endsWith(String other) {
            return toString().endsWith(other);
        }

        @Override
        public Path normalize() {
            return with(relative);
        }

        @Override
        public Path resolve(Path other) {
            if (other instanceof StoragePath path && path.namespace.equals(namespace)) {
                return with(join(relative, path.relative));
            }
            return resolve(other.toString());
        }

        @Override
        public Path resolve(String other) {
            return with(join(relative, other));
        }

        @Override
        public Path resolveSibling(Path other) {
            if (other instanceof StoragePath path && path.namespace.equals(namespace)) {
                return with(join(parent(relative), path.relative));
            }
            return resolveSibling(other.toString());
        }

        @Override
        public Path resolveSibling(String other) {
            return with(join(parent(relative), other));
        }

        @Override
        public Path relativize(Path other) {
            if (!(other instanceof StoragePath path) || !namespace.equals(path.namespace)) {
                throw new ProviderMismatchException("relativize requires the same storage namespace");
            }
            List<String> from = parts(relative);
            List<String> to = parts(path.relative);
            int common = 0;
            while (common < from.size() && common < to.size() && from.get(common).equals(to.get(common))) common++;
            Deque<String> result = new ArrayDeque<>();
            for (int i = common; i < from.size(); i++) result.addLast("..");
            for (int i = common; i < to.size(); i++) result.addLast(to.get(i));
            String joined = String.join("/", result);
            return new StoragePath(namespace, defaultRoot, normalizeRelative(joined),
                    isDatabasePath(namespace, normalizeRelative(joined)));
        }

        @Override
        public URI toUri() {
            try {
                return new URI(SCHEME, null, "/" + namespace + (relative.isEmpty() ? "" : "/" + relative), null);
            } catch (URISyntaxException error) {
                throw new IllegalStateException("Cannot build storage URI", error);
            }
        }

        @Override
        public Path toAbsolutePath() {
            return new StoragePath(namespace, defaultRoot.toAbsolutePath(), relative, db);
        }

        @Override
        public Path toRealPath(LinkOption... options) {
            return toAbsolutePath();
        }

        @Override
        public File toFile() {
            if (!db) return defaultPath().toFile();
            try {
                Path temp = java.nio.file.Files.createTempFile("chrdk-materialize-", ".bin");
                ChrdkDatabase.FileRecord record = database().read(namespace, relative);
                java.nio.file.Files.write(temp, record == null ? new byte[0] : record.content());
                temp.toFile().deleteOnExit();
                return temp.toFile();
            } catch (IOException error) {
                throw new UncheckedIOException("Cannot materialize storage path", error);
            }
        }

        @Override
        public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events,
                                 WatchEvent.Modifier... modifiers) {
            throw new UnsupportedOperationException("CHRDK storage does not support watch services");
        }

        @Override
        public WatchKey register(WatchService watcher, WatchEvent.Kind<?>... events) {
            throw new UnsupportedOperationException("CHRDK storage does not support watch services");
        }

        @Override
        public int compareTo(Path other) {
            return toString().compareTo(other.toString());
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof StoragePath path)) return false;
            return namespace.equals(path.namespace) && relative.equals(path.relative)
                    && db == path.db && defaultRoot.equals(path.defaultRoot);
        }

        @Override
        public int hashCode() {
            return Objects.hash(namespace, relative, db, defaultRoot);
        }

        @Override
        public String toString() {
            return SCHEME + ":/" + namespace + (relative.isEmpty() ? "" : "/" + relative);
        }
    }

    /** Реализация NIO provider; делегирует конфиги/логи/default FS в неизменном виде. */
    private final class StorageProvider extends FileSystemProvider {

        @Override
        public String getScheme() {
            return SCHEME;
        }

        @Override
        public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
            throw new FileSystemAlreadyExistsException(uri.toString());
        }

        @Override
        public FileSystem getFileSystem(URI uri) {
            return ChrdkStorageFileSystem.this;
        }

        @Override
        public Path getPath(URI uri) {
            return pathFromUri(uri);
        }

        @Override
        public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                                                  FileAttribute<?>... attrs) throws IOException {
            StoragePath target = storagePath(path);
            boolean writable = isWritable(options);
            ensureWritable(writable);
            if (!target.db) return java.nio.file.Files.newByteChannel(target.defaultPath(), options, attrs);
            Path temp = materialize(target);
            Set<OpenOption> normalized = normalizeOptions(options);
            SeekableByteChannel delegate = java.nio.file.Files.newByteChannel(temp, normalized);
            return new StorageByteChannel(delegate, temp, target, writable);
        }

        @Override
        public FileChannel newFileChannel(Path path, Set<? extends OpenOption> options,
                                          FileAttribute<?>... attrs) throws IOException {
            StoragePath target = storagePath(path);
            boolean writable = isWritable(options);
            ensureWritable(writable);
            if (!target.db) return FileChannel.open(target.defaultPath(), options, attrs);
            Path temp = materialize(target);
            Set<OpenOption> normalized = normalizeOptions(options);
            FileChannel delegate = FileChannel.open(temp, normalized);
            return new StorageFileChannel(delegate, temp, target, writable);
        }

        @Override
        public AsynchronousFileChannel newAsynchronousFileChannel(Path path, Set<? extends OpenOption> options,
                                                                   ExecutorService executor,
                                                                   FileAttribute<?>... attrs) {
            throw new UnsupportedOperationException("CHRDK storage does not support asynchronous channels");
        }

        @Override
        public DirectoryStream<Path> newDirectoryStream(Path dir,
                                                         DirectoryStream.Filter<? super Path> filter)
                throws IOException {
            StoragePath directory = storagePath(dir);
            if (!directory.db) {
                return java.nio.file.Files.newDirectoryStream(directory.defaultPath(), filter);
            }
            String prefix = directory.relative.isEmpty() ? "" : directory.relative + "/";
            Set<String> children = new LinkedHashSet<>();
            for (String path : database().list(directory.namespace, prefix)) {
                if (!path.startsWith(prefix)) continue;
                String rest = path.substring(prefix.length());
                int slash = rest.indexOf('/');
                String child = slash < 0 ? rest : rest.substring(0, slash);
                if (!child.isEmpty()) children.add(child);
            }
            List<Path> result = new ArrayList<>();
            for (String child : children) {
                String childRelative = prefix + child;
                StoragePath childPath = new StoragePath(directory.namespace, directory.defaultRoot, childRelative,
                        isDatabasePath(directory.namespace, childRelative));
                if (filter.accept(childPath)) result.add(childPath);
            }
            return new StorageDirectoryStream(result);
        }

        @Override
        public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
            StoragePath target = storagePath(dir);
            ensureWritable(true);
            if (!target.db) java.nio.file.Files.createDirectory(target.defaultPath(), attrs);
        }

        @Override
        public void delete(Path path) throws IOException {
            StoragePath target = storagePath(path);
            ensureWritable(true);
            if (!target.db) {
                java.nio.file.Files.delete(target.defaultPath());
                return;
            }
            if (isDirectory(target)) return; // виртуальный каталог в БД
            if (!database().delete(target.namespace, target.relative)) {
                throw new NoSuchFileException(target.toString());
            }
        }

        @Override
        public boolean deleteIfExists(Path path) throws IOException {
            StoragePath target = storagePath(path);
            ensureWritable(true);
            if (!target.db) return java.nio.file.Files.deleteIfExists(target.defaultPath());
            if (isDirectory(target)) return false;
            return database().delete(target.namespace, target.relative);
        }

        @Override
        public void copy(Path source, Path target, CopyOption... options) throws IOException {
            StoragePath from = storagePath(source);
            StoragePath to = storagePath(target);
            if (!from.db && !to.db) {
                java.nio.file.Files.copy(from.defaultPath(), to.defaultPath(), options);
                return;
            }
            boolean replace = hasOption(options, REPLACE_EXISTING);
            if (!replace && existsPath(to)) throw new FileAlreadyExistsException(to.toString());
            byte[] bytes = readBytes(from);
            writeBytes(to, bytes);
        }

        @Override
        public void move(Path source, Path target, CopyOption... options) throws IOException {
            StoragePath from = storagePath(source);
            StoragePath to = storagePath(target);
            boolean replace = hasOption(options, REPLACE_EXISTING);
            if (from.db && to.db && from.namespace.equals(to.namespace)) {
                database().move(from.namespace, from.relative, to.relative, replace);
                return;
            }
            if (!from.db && !to.db) {
                java.nio.file.Files.move(from.defaultPath(), to.defaultPath(), options);
                return;
            }
            if (!replace && existsPath(to)) throw new FileAlreadyExistsException(to.toString());
            byte[] bytes = readBytes(from);
            writeBytes(to, bytes);
            deletePath(from);
        }

        @Override
        public boolean isSameFile(Path path, Path path2) throws IOException {
            StoragePath first = storagePath(path);
            StoragePath second = storagePath(path2);
            if (first.namespace.equals(second.namespace)) return first.relative.equals(second.relative);
            if (!first.db && !second.db) {
                return java.nio.file.Files.isSameFile(first.defaultPath(), second.defaultPath());
            }
            return false;
        }

        @Override
        public boolean isHidden(Path path) {
            StoragePath target = storagePath(path);
            if (!target.db) return java.nio.file.Files.isHidden(target.defaultPath());
            return false;
        }

        @Override
        public FileStore getFileStore(Path path) {
            return fileStore;
        }

        @Override
        public void checkAccess(Path path, AccessMode... modes) throws IOException {
            StoragePath target = storagePath(path);
            if (!target.db) {
                target.defaultPath().getFileSystem().provider().checkAccess(target.defaultPath(), modes);
                return;
            }
            for (AccessMode mode : modes) {
                if (mode == AccessMode.WRITE && readOnly()) throw new AccessDeniedException(target.toString());
            }
            if (isDirectory(target)) return;
            if (!database().exists(target.namespace, target.relative)) {
                throw new NoSuchFileException(target.toString());
            }
        }

        @Override
        public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type,
                                                                     LinkOption... options) {
            StoragePath target = storagePath(path);
            if (!target.db) {
                return target.defaultPath().getFileSystem().provider()
                        .getFileAttributeView(target.defaultPath(), type, options);
            }
            boolean supported = BasicFileAttributeView.class.isAssignableFrom(type)
                    || PosixFileAttributeView.class.isAssignableFrom(type)
                    || FileOwnerAttributeView.class.isAssignableFrom(type);
            if (!supported) return null;
            return type.cast(new StorageAttributeView(attributes(target)));
        }

        @Override
        public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type,
                                                                LinkOption... options) throws IOException {
            StoragePath target = storagePath(path);
            if (!target.db) {
                return target.defaultPath().getFileSystem().provider()
                        .readAttributes(target.defaultPath(), type, options);
            }
            StorageAttributes attrs = attributes(target);
            if (!BasicFileAttributes.class.isAssignableFrom(type)) {
                throw new UnsupportedOperationException("Unsupported attributes: " + type);
            }
            return type.cast(attrs);
        }

        @Override
        public Map<String, Object> readAttributes(Path path, String attributes,
                                                  LinkOption... options) throws IOException {
            StoragePath target = storagePath(path);
            if (!target.db) {
                return target.defaultPath().getFileSystem().provider()
                        .readAttributes(target.defaultPath(), attributes, options);
            }
            StorageAttributes attrs = attributes(target);
            Map<String, Object> result = new LinkedHashMap<>();
            String requested = attributes.toLowerCase(Locale.ROOT);
            boolean all = requested.equals("*") || requested.endsWith(":*");
            if (all || requested.contains("size")) result.put("size", attrs.size());
            if (all || requested.contains("isregularfile")) result.put("isRegularFile", attrs.isRegularFile());
            if (all || requested.contains("isdirectory")) result.put("isDirectory", attrs.isDirectory());
            if (all || requested.contains("lastmodifiedtime")) result.put("lastModifiedTime", attrs.lastModifiedTime());
            if (all || requested.contains("creationtime")) result.put("creationTime", attrs.creationTime());
            if (all || requested.contains("permissions")) result.put("permissions", attrs.permissions());
            return result;
        }

        @Override
        public void setAttribute(Path path, String attribute, Object value, LinkOption... options)
                throws IOException {
            StoragePath target = storagePath(path);
            if (!target.db) {
                target.defaultPath().getFileSystem().provider()
                        .setAttribute(target.defaultPath(), attribute, value, options);
                return;
            }
            ensureWritable(true);
            String lower = attribute.toLowerCase(Locale.ROOT);
            if (lower.contains("permissions") || lower.contains("lastmodifiedtime")
                    || lower.contains("owner") || lower.contains("group")) return;
            throw new UnsupportedOperationException("Unsupported storage attribute: " + attribute);
        }

        @Override
        public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs) {
            throw new UnsupportedOperationException("CHRDK storage does not support symbolic links");
        }

        @Override
        public void createLink(Path link, Path existing) {
            throw new UnsupportedOperationException("CHRDK storage does not support hard links");
        }

        @Override
        public Path readSymbolicLink(Path link) {
            throw new UnsupportedOperationException("CHRDK storage does not support symbolic links");
        }

        private StoragePath storagePath(Path path) {
            if (!(path instanceof StoragePath storagePath)) {
                throw new ProviderMismatchException("Expected CHRDK storage path");
            }
            return storagePath;
        }

        private void ensureWritable(boolean writable) {
            if (writable && readOnly()) throw new ReadOnlyFileSystemException();
        }

        private boolean isDirectory(StoragePath target) throws IOException {
            if (!target.db) return java.nio.file.Files.isDirectory(target.defaultPath());
            if (target.relative.isEmpty()) return true;
            String prefix = target.relative + "/";
            if (!database().list(target.namespace, prefix).isEmpty()) return true;
            if (database().exists(target.namespace, target.relative)) return false;
            if (KNOWN_DIRECTORIES.contains(target.relative)) return true;
            for (String path : databasePaths.getOrDefault(target.namespace, Set.of())) {
                if (path.startsWith(prefix)) return true;
            }
            return false;
        }

        private StorageAttributes attributes(StoragePath target) throws IOException {
            boolean directory = isDirectory(target);
            long size = 0L;
            long updated = 0L;
            if (!directory) {
                ChrdkDatabase.FileRecord record = database().read(target.namespace, target.relative);
                if (record == null) throw new NoSuchFileException(target.toString());
                size = record.content().length;
                updated = record.updatedAtMillis();
            }
            return new StorageAttributes(directory, size, updated, target.namespace + ":" + target.relative);
        }

        private boolean existsPath(Path path) throws IOException {
            StoragePath target = storagePath(path);
            if (!target.db) return java.nio.file.Files.exists(target.defaultPath());
            return isDirectory(target) || database().exists(target.namespace, target.relative);
        }

        private byte[] readBytes(Path path) throws IOException {
            StoragePath target = storagePath(path);
            if (!target.db) return java.nio.file.Files.readAllBytes(target.defaultPath());
            ChrdkDatabase.FileRecord record = database().read(target.namespace, target.relative);
            if (record == null) throw new NoSuchFileException(target.toString());
            return record.content();
        }

        private void writeBytes(Path path, byte[] bytes) throws IOException {
            StoragePath target = storagePath(path);
            ensureWritable(true);
            if (!target.db) {
                java.nio.file.Files.write(target.defaultPath(), bytes);
                return;
            }
            database().write(target.namespace, target.relative, bytes);
        }

        private void deletePath(Path path) throws IOException {
            StoragePath target = storagePath(path);
            ensureWritable(true);
            if (!target.db) {
                java.nio.file.Files.delete(target.defaultPath());
                return;
            }
            if (!database().delete(target.namespace, target.relative)) {
                throw new NoSuchFileException(target.toString());
            }
        }

        private Path materialize(StoragePath target) throws IOException {
            Path temp = java.nio.file.Files.createTempFile("chrdk-db-", ".bin");
            ChrdkDatabase.FileRecord record = database().read(target.namespace, target.relative);
            java.nio.file.Files.write(temp, record == null ? new byte[0] : record.content());
            return temp;
        }

        private void persist(Path temp, StoragePath target, boolean writable) throws IOException {
            if (!writable || !target.db) return;
            byte[] bytes = java.nio.file.Files.readAllBytes(temp);
            database().write(target.namespace, target.relative, bytes);
        }

        private boolean isWritable(Set<? extends OpenOption> options) {
            return options.contains(StandardOpenOption.WRITE)
                    || options.contains(StandardOpenOption.APPEND)
                    || options.contains(StandardOpenOption.TRUNCATE_EXISTING)
                    || options.contains(StandardOpenOption.CREATE)
                    || options.contains(StandardOpenOption.CREATE_NEW)
                    || options.contains(StandardOpenOption.DELETE_ON_CLOSE);
        }

        private Set<OpenOption> normalizeOptions(Set<? extends OpenOption> options) {
            Set<OpenOption> result = new LinkedHashSet<>();
            for (OpenOption option : options) result.add(option);
            if (result.remove(StandardOpenOption.CREATE_NEW)) {
                result.add(StandardOpenOption.CREATE);
                result.add(StandardOpenOption.TRUNCATE_EXISTING);
            }
            result.remove(StandardOpenOption.DELETE_ON_CLOSE);
            if (result.contains(StandardOpenOption.APPEND)) result.remove(StandardOpenOption.TRUNCATE_EXISTING);
            return result;
        }

        private boolean hasOption(CopyOption[] options, CopyOption expected) {
            for (CopyOption option : options) {
                if (option == expected) return true;
            }
            return false;
        }
    }

    private final class StorageByteChannel implements SeekableByteChannel {

        private final SeekableByteChannel delegate;
        private final Path temp;
        private final StoragePath target;
        private final boolean writable;
        private boolean closed;

        private StorageByteChannel(SeekableByteChannel delegate, Path temp, StoragePath target,
                                   boolean writable) {
            this.delegate = delegate;
            this.temp = temp;
            this.target = target;
            this.writable = writable;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            return delegate.read(dst);
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            return delegate.write(src);
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            delegate.position(newPosition);
            return this;
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            delegate.truncate(size);
            return this;
        }

        @Override
        public boolean isOpen() {
            return !closed && delegate.isOpen();
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                delegate.close();
                persist(temp, target, writable);
            } finally {
                java.nio.file.Files.deleteIfExists(temp);
            }
        }
    }

    private final class StorageFileChannel extends FileChannel {

        private final FileChannel delegate;
        private final Path temp;
        private final StoragePath target;
        private final boolean writable;
        private boolean closed;

        private StorageFileChannel(FileChannel delegate, Path temp, StoragePath target, boolean writable) {
            this.delegate = delegate;
            this.temp = temp;
            this.target = target;
            this.writable = writable;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            return delegate.read(dst);
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
            return delegate.read(dsts, offset, length);
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            return delegate.write(src);
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
            return delegate.write(srcs, offset, length);
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public FileChannel position(long newPosition) throws IOException {
            delegate.position(newPosition);
            return this;
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public FileChannel truncate(long size) throws IOException {
            delegate.truncate(size);
            return this;
        }

        @Override
        public void force(boolean metaData) throws IOException {
            delegate.force(metaData);
            persist(temp, target, writable);
        }

        @Override
        public long transferTo(long position, long count, WritableByteChannel targetChannel) throws IOException {
            return delegate.transferTo(position, count, targetChannel);
        }

        @Override
        public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
            return delegate.transferFrom(src, position, count);
        }

        @Override
        public int read(ByteBuffer dst, long position) throws IOException {
            return delegate.read(dst, position);
        }

        @Override
        public int write(ByteBuffer src, long position) throws IOException {
            return delegate.write(src, position);
        }

        @Override
        public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
            return delegate.map(mode, position, size);
        }

        @Override
        public FileLock lock(long position, long size, boolean shared) throws IOException {
            return delegate.lock(position, size, shared);
        }

        @Override
        public FileLock tryLock(long position, long size, boolean shared) throws IOException {
            return delegate.tryLock(position, size, shared);
        }

        @Override
        protected void implCloseChannel() throws IOException {
            if (closed) return;
            closed = true;
            try {
                delegate.close();
                persist(temp, target, writable);
            } finally {
                java.nio.file.Files.deleteIfExists(temp);
            }
        }
    }

    private static final class StorageDirectoryStream implements DirectoryStream<Path> {

        private final List<Path> children;
        private boolean closed;

        private StorageDirectoryStream(List<Path> children) {
            this.children = List.copyOf(children);
        }

        @Override
        public Iterator<Path> iterator() {
            if (closed) throw new ClosedDirectoryStreamException();
            return children.iterator();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class StorageAttributes implements BasicFileAttributes, PosixFileAttributes {

        private static final UserPrincipal OWNER = () -> "chrdk";
        private static final GroupPrincipal GROUP = () -> "chrdk";

        private final boolean directory;
        private final long size;
        private final long updated;
        private final Object fileKey;

        private StorageAttributes(boolean directory, long size, long updated, Object fileKey) {
            this.directory = directory;
            this.size = size;
            this.updated = updated;
            this.fileKey = fileKey;
        }

        @Override
        public FileTime lastModifiedTime() {
            return FileTime.fromMillis(updated);
        }

        @Override
        public FileTime lastAccessTime() {
            return FileTime.fromMillis(updated);
        }

        @Override
        public FileTime creationTime() {
            return FileTime.fromMillis(updated);
        }

        @Override
        public boolean isRegularFile() {
            return !directory;
        }

        @Override
        public boolean isDirectory() {
            return directory;
        }

        @Override
        public boolean isSymbolicLink() {
            return false;
        }

        @Override
        public boolean isOther() {
            return false;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public Object fileKey() {
            return fileKey;
        }

        @Override
        public UserPrincipal owner() {
            return OWNER;
        }

        @Override
        public GroupPrincipal group() {
            return GROUP;
        }

        @Override
        public Set<PosixFilePermission> permissions() {
            return directory
                    ? PosixFilePermissions.fromString("rwxr-xr-x")
                    : PosixFilePermissions.fromString("rw-------");
        }
    }

    private static final class StorageAttributeView implements PosixFileAttributeView {

        private final StorageAttributes attrs;

        private StorageAttributeView(StorageAttributes attrs) {
            this.attrs = attrs;
        }

        @Override
        public String name() {
            return "posix";
        }

        @Override
        public PosixFileAttributes readAttributes() {
            return attrs;
        }

        @Override
        public void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime) {
            // DB хранит revision и updated_at; времена файлов не записываются аддонами.
        }

        @Override
        public UserPrincipal getOwner() {
            return attrs.owner();
        }

        @Override
        public void setOwner(UserPrincipal owner) {
            // Владелец фиксирован ядром.
        }

        @Override
        public void setGroup(GroupPrincipal group) {
            // Группа фиксирована ядром.
        }

        @Override
        public void setPermissions(Set<PosixFilePermission> permissions) {
            // Права доступа к строке БД управляются пользователем MySQL, не POSIX.
        }
    }

    private final class StorageFileStore extends FileStore {

        @Override
        public String name() {
            return SCHEME;
        }

        @Override
        public String type() {
            return mode == StorageMode.MYSQL ? "chrdk-mysql" : "chrdk-readonly";
        }

        @Override
        public boolean isReadOnly() {
            return readOnly();
        }

        @Override
        public long getTotalSpace() {
            return Long.MAX_VALUE;
        }

        @Override
        public long getUsableSpace() {
            return Long.MAX_VALUE;
        }

        @Override
        public long getUnallocatedSpace() {
            return Long.MAX_VALUE;
        }

        @Override
        public boolean supportsFileAttributeView(Class<? extends FileAttributeView> type) {
            return BasicFileAttributeView.class.isAssignableFrom(type)
                    || PosixFileAttributeView.class.isAssignableFrom(type);
        }

        @Override
        public boolean supportsFileAttributeView(String name) {
            return "basic".equals(name) || "posix".equals(name);
        }

        @Override
        public <V extends FileStoreAttributeView> V getFileAttributeView(Class<V> type) {
            return null;
        }

        @Override
        public Object getAttribute(String attribute) throws IOException {
            if ("totalSpace".equals(attribute)) return getTotalSpace();
            if ("usableSpace".equals(attribute)) return getUsableSpace();
            if ("unallocatedSpace".equals(attribute)) return getUnallocatedSpace();
            throw new UnsupportedOperationException("Unsupported FileStore attribute: " + attribute);
        }
    }
}
