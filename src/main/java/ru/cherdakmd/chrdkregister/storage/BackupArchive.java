package ru.cherdakmd.chrdkregister.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Проверяемые ZIP-снимки файлового состояния всех установленных CHRDK-плагинов. */
public final class BackupArchive {
    public static final String MANIFEST = "CHRDK-BACKUP.json";
    public static final String SUFFIX = ".chrdk.zip";
    private static final int MAX_FILES = 20_000;
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;
    private static final int MAX_JSON_BYTES = 64 * 1024 * 1024;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneId.of("UTC"));

    private final Path pluginsDirectory;
    private final Path outputDirectory;
    private final ZoneId zone;

    public BackupArchive(Path pluginsDirectory, Path outputDirectory, ZoneId zone) {
        this.pluginsDirectory = pluginsDirectory.toAbsolutePath().normalize();
        this.outputDirectory = outputDirectory.toAbsolutePath().normalize();
        this.zone = zone;
    }

    /** Возвращает созданный и уже проверенный архив. Любая ошибка отменяет снимок целиком. */
    public Path create(String reason, Instant now, Retention retention) throws IOException {
        String safeReason = reason == null ? "manual" : reason.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "");
        if (safeReason.isBlank()) safeReason = "manual";
        Files.createDirectories(outputDirectory);
        Path target = outputDirectory.resolve("chrdk-" + STAMP.format(now) + "-" + safeReason + SUFFIX);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        if (Files.exists(target) || Files.exists(temporary)) {
            throw new IOException("Снимок с таким именем уже существует: " + target.getFileName());
        }
        try {
            LinkedHashMap<String, Source> sources = collect();
            Manifest manifest = new Manifest(1, now.toString(), safeReason, sources.values().stream()
                    .map(source -> new Entry(source.name, source.data.length, sha256(source.data))).toList());
            try (OutputStream file = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW);
                 FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE);
                 ZipOutputStream zip = new ZipOutputStream(file, StandardCharsets.UTF_8)) {
                writeEntry(zip, MANIFEST, GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
                for (Source source : sources.values()) writeEntry(zip, source.name, source.data);
                zip.finish();
                channel.force(true);
            }
            moveDurable(temporary, target);
            privateFile(target);
            verify(target);
            prune(now, retention);
            return target;
        } catch (Throwable failure) {
            Files.deleteIfExists(temporary);
            if (failure instanceof IOException io) throw io;
            throw new IOException("Не удалось создать снимок", failure);
        }
    }

    /** Проверяет CRC, точный состав, размеры, SHA-256, JSON и безопасность путей. Ничего не распаковывает. */
    public Verification verify(Path archive) throws IOException {
        if (archive == null || !Files.isRegularFile(archive) || Files.isSymbolicLink(archive)) {
            throw new IOException("Архив не найден или не является обычным файлом");
        }
        Manifest manifest = null;
        Map<String, Entry> expected = new HashMap<>();
        Set<String> seen = new HashSet<>();
        long total = 0;
        try (InputStream input = Files.newInputStream(archive);
             ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            ZipEntry item;
            while ((item = zip.getNextEntry()) != null) {
                String name = item.getName();
                if (!seen.add(name)) throw new IOException("Повторяющийся путь: " + name);
                byte[] data;
                if (MANIFEST.equals(name)) {
                    data = readLimited(zip, MAX_JSON_BYTES);
                    manifest = parseManifest(data);
                    for (Entry entry : manifest.entries) {
                        safeEntryName(entry.path);
                        if (expected.put(entry.path, entry) != null) {
                            throw new IOException("Повторяющийся путь в манифесте: " + entry.path);
                        }
                    }
                    continue;
                }
                safeEntryName(name);
                Entry entry = expected.get(name);
                if (manifest == null || entry == null) throw new IOException("Файл вне манифеста: " + name);
                data = readLimited(zip, Math.min(MAX_FILE_BYTES, entry.size + 1));
                if (data.length != entry.size || !sha256(data).equals(entry.sha256)) {
                    throw new IOException("Размер или SHA-256 не совпал: " + name);
                }
                validateJsonIfNeeded(name, data);
                total = Math.addExact(total, data.length);
                if (total > MAX_TOTAL_BYTES) throw new IOException("Снимок превышает лимит распакованных данных");
            }
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw new IOException("Повреждённый манифест или JSON", failure);
        }
        if (manifest == null) throw new IOException("В архиве нет " + MANIFEST);
        if (manifest.format != 1 || manifest.entries == null) throw new IOException("Неподдерживаемый формат снимка");
        if (!seen.containsAll(expected.keySet()) || seen.size() != expected.size() + 1) {
            throw new IOException("Состав архива не совпадает с манифестом");
        }
        return new Verification(manifest.createdAt, manifest.reason, expected.size(), total);
    }

    public List<Path> list() throws IOException {
        if (Files.notExists(outputDirectory)) return List.of();
        try (var files = Files.list(outputDirectory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(SUFFIX))
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .toList();
        }
    }

    /** Возвращает true только для существующего обычного ZIP внутри каталога снимков. */
    public Path resolve(String fileName) throws IOException {
        if (fileName == null || fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")
                || !fileName.endsWith(SUFFIX)) throw new IOException("Нужно имя файла " + SUFFIX);
        Path path = outputDirectory.resolve(fileName).normalize();
        if (!path.getParent().equals(outputDirectory)) throw new IOException("Недопустимый путь снимка");
        return path;
    }

    private LinkedHashMap<String, Source> collect() throws IOException {
        LinkedHashMap<String, Source> result = new LinkedHashMap<>();
        List<Path> pluginFolders;
        try (var folders = Files.newDirectoryStream(pluginsDirectory, "CHRDK_*")) {
            pluginFolders = new ArrayList<>();
            folders.forEach(pluginFolders::add);
        }
        pluginFolders.sort(Comparator.comparing(path -> path.getFileName().toString()));
        long total = 0;
        for (Path folder : pluginFolders) {
            if (!Files.isDirectory(folder) || Files.isSymbolicLink(folder)) continue;
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                    if (!directory.equals(folder) && Files.isSymbolicLink(directory)) return FileVisitResult.SKIP_SUBTREE;
                    String name = directory.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!directory.equals(folder) && Set.of("backups", "snapshots", "cache", "logs", "audit",
                            "update-backups", "tmp").contains(name)) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile() || Files.isSymbolicLink(file) || !included(file)) return FileVisitResult.CONTINUE;
                    if (attrs.size() > MAX_FILE_BYTES) throw new IOException("Файл слишком велик: " + file);
                    String name = pluginsDirectory.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
                    safeEntryName(name);
                    byte[] data = Files.readAllBytes(file);
                    if (data.length != attrs.size() || Files.size(file) != data.length) {
                        throw new IOException("Файл изменился во время копирования: " + name);
                    }
                    validateJsonIfNeeded(name, data);
                    result.put(name, new Source(name, data));
                    return FileVisitResult.CONTINUE;
                }
            });
            if (result.size() > MAX_FILES) throw new IOException("Слишком много файлов состояния");
            for (Source source : result.values()) total = Math.addExact(total, source.data.length);
            if (total > MAX_TOTAL_BYTES) throw new IOException("Общий размер состояния превышает 512 МиБ");
        }
        if (result.isEmpty()) throw new IOException("Не найдено файлов состояния CHRDK");
        return result;
    }

    private boolean included(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".tmp") || name.endsWith(".bak") || name.endsWith(".gz") || name.endsWith(".zip")
                || name.contains("corrupt") || name.contains("pre-restore") || name.startsWith("config-before-schema-")) {
            return false;
        }
        if (name.equals("boosty-tokens.json") || name.contains("token") || name.contains("secret")
                || name.endsWith(".key") || name.endsWith(".pem")) return false;
        if (name.equals("config.yml") || name.equals("config.yaml") || name.startsWith("messages")) return false;
        return name.endsWith(".json") || name.endsWith(".jsonl") || name.endsWith(".yml") || name.endsWith(".yaml");
    }

    private void prune(Instant now, Retention retention) throws IOException {
        Map<String, Path> daily = new HashMap<>();
        Map<String, Path> weekly = new HashMap<>();
        List<Path> all = list();
        Set<Path> keep = new HashSet<>(all.subList(0, Math.min(retention.hourly, all.size())));
        for (Path path : all) {
            Instant time = timestamp(path);
            if (time == null) {
                keep.add(path); // Неизвестный сторонний архив никогда не удаляем автоматически.
                continue;
            }
            LocalDate date = time.atZone(zone).toLocalDate();
            daily.putIfAbsent(date.toString(), path);
            String week = date.getYear() + "-W" + date.get(WeekFields.ISO.weekOfWeekBasedYear());
            weekly.putIfAbsent(week, path);
        }
        daily.values().stream().sorted().limit(retention.daily).forEach(keep::add);
        weekly.values().stream().sorted().limit(retention.weekly).forEach(keep::add);
        for (Path path : all) if (!keep.contains(path)) Files.deleteIfExists(path);
    }

    private Instant timestamp(Path path) {
        String name = path.getFileName().toString();
        if (!name.startsWith("chrdk-") || name.length() < 22) return null;
        try {
            return Instant.from(STAMP.parse(name.substring(6, 21)));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Manifest parseManifest(byte[] data) throws IOException {
        JsonElement root = parseJson(new InputStreamReader(new java.io.ByteArrayInputStream(data), StandardCharsets.UTF_8));
        if (!root.isJsonObject()) throw new IOException("Манифест не является JSON-объектом");
        Manifest value = GSON.fromJson(root, Manifest.class);
        if (value == null || value.format != 1 || value.entries == null || value.entries.size() > MAX_FILES
                || value.createdAt == null || value.reason == null) throw new IOException("Неверный манифест");
        return value;
    }

    private static void validateJsonIfNeeded(String name, byte[] data) {
        if (name.endsWith(".json")) {
            parseJson(new InputStreamReader(new java.io.ByteArrayInputStream(data), StandardCharsets.UTF_8));
        } else if (name.endsWith(".jsonl")) {
            String text = new String(data, StandardCharsets.UTF_8);
            for (String line : text.split("\\R")) {
                if (!line.isBlank()) parseJson(new java.io.StringReader(line));
            }
        }
    }

    private static JsonElement parseJson(Reader reader) {
        JsonReader strict = new JsonReader(reader);
        strict.setStrictness(Strictness.STRICT);
        JsonElement value = JsonParser.parseReader(strict);
        try {
            if (strict.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) {
                throw new IllegalStateException("Дополнительные данные после JSON");
            }
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return value;
    }

    private static byte[] readLimited(InputStream input, long limit) throws IOException {
        if (limit < 0 || limit > MAX_FILE_BYTES) throw new IOException("Недопустимый размер файла в манифесте");
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long remaining = limit;
        while (true) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) break;
            output.write(buffer, 0, read);
            remaining -= read;
        }
        if (input.read() != -1) throw new IOException("Файл в архиве больше объявленного размера");
        return output.toByteArray();
    }

    private static void safeEntryName(String name) throws IOException {
        Path path = Path.of(name);
        if (name.isBlank() || name.startsWith("/") || name.contains("\\") || path.isAbsolute()
                || name.contains(":") || !name.startsWith("CHRDK_") || MANIFEST.equals(name)) {
            throw new IOException("Небезопасный путь в архиве: " + name);
        }
        for (Path part : path) {
            if (part.toString().equals("..") || part.toString().equals(".")) {
                throw new IOException("Небезопасный путь в архиве: " + name);
            }
        }
        String first = path.getName(0).toString();
        if (!first.startsWith("CHRDK_")) throw new IOException("Посторонний плагин в архиве: " + name);
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.equals("config.yml") || lower.contains("token") || lower.contains("secret")) {
            throw new IOException("Конфигурация и токены не входят в снимок: " + name);
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static void moveDurable(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
        forceDirectory(to.toAbsolutePath().getParent());
    }

    public static void forceDirectory(Path directory) {
        if (directory == null) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows и часть ФС не позволяют fsync каталога; данные файла уже принудительно записаны.
        }
    }

    private static void privateFile(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Не-POSIX ФС: права назначает владелец серверного процесса.
        }
    }

    public record Retention(int hourly, int daily, int weekly) {
        public Retention {
            hourly = Math.clamp(hourly, 1, 168);
            daily = Math.clamp(daily, 1, 365);
            weekly = Math.clamp(weekly, 1, 260);
        }
    }

    public record Verification(String createdAt, String reason, int files, long bytes) { }
    private record Source(String name, byte[] data) { }
    private record Manifest(int format, String createdAt, String reason, List<Entry> entries) { }
    private record Entry(String path, long size, String sha256) { }
}
