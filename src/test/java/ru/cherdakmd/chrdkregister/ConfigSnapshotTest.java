package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.configuration.ConfigSnapshot;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.logging.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigSnapshotTest {
    @TempDir Path dir;
    private static final String DEFAULTS = "general:\n  enabled: true\n  locale: ru\n  count: 5\n  worlds: []\nrules:\n  price: 7\n  loot:\n    - item: BONE\n      amount: [1, 2]\n";
    private static final String TEXTS = "demo:\n  line: '<green>Default %n%</green>'\n  list: ['first', 'second']\n";
    private InputStream stream(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
    private Path config() { return dir.resolve("config.yml"); }
    private Path texts() { return dir.resolve("messages.yml"); }
    private ConfigSnapshot<Integer> load() throws Exception {
        return load((yaml, logger) -> yaml.getInt("general.count"));
    }
    private ConfigSnapshot<Integer> load(BiFunction<FileConfiguration, Logger, Integer> parser) throws Exception {
        return ConfigSnapshot.load(config(), stream(DEFAULTS), parser,
                (yaml, logger) -> Messages.fromChecked(stream(TEXTS), texts(), logger));
    }
    private void files(String config, String texts) throws IOException {
        Files.writeString(config(), config); Files.writeString(texts(), texts);
    }
    @Test void successfulBundleContainsSameRawParsedAndMessageSnapshotWithDefaults() throws Exception {
        files("general:\n  count: 9\n  enabled: false\n", "demo:\n  line: '<gold>Custom %n%</gold>'\n");
        var snapshot = load(); assertEquals(9, snapshot.value());
        assertEquals(9, snapshot.yaml().getInt("general.count")); assertFalse(snapshot.yaml().getBoolean("general.enabled"));
        assertEquals("ru", snapshot.yaml().getString("general.locale"));
        assertEquals("Custom 3", snapshot.messages().plain("line", "n", 3));
        assertEquals(2, snapshot.messages().getList("list").size());
    }
    @Test void malformedYamlNeverReplacesCurrentBundleOrTouchesFiles() throws Exception {
        files("general:\n  count: 9\n", "{}");
        AtomicReference<ConfigSnapshot<Integer>> current = new AtomicReference<>(load()); var previous = current.get();
        Files.writeString(config(), "general: [broken\n"); byte[] raw = Files.readAllBytes(config()), text = Files.readAllBytes(texts());
        assertThrows(InvalidConfigurationException.class, () -> current.set(load()));
        assertSame(previous, current.get()); assertEquals(9, current.get().value());
        assertArrayEquals(raw, Files.readAllBytes(config())); assertArrayEquals(text, Files.readAllBytes(texts()));
    }
    @Test void validConfigAndBadMessagesCannotPublishHalfUpdatedBundle() throws Exception {
        files("general:\n  count: 9\n", "{}"); var previous = load();
        AtomicReference<ConfigSnapshot<Integer>> current = new AtomicReference<>(previous);
        files("general:\n  count: 12\n", "demo: [broken\n");
        assertThrows(InvalidConfigurationException.class, () -> current.set(load()));
        assertSame(previous, current.get()); assertEquals(9, current.get().yaml().getInt("general.count"));
        assertEquals("Default 1", current.get().messages().plain("line", "n", 1));
    }
    @Test void missingConfigOrMessagesFailsInsteadOfSilentlyRestoringStock() throws Exception {
        files("{}", "{}"); var previous = load(); Files.delete(config());
        assertThrows(IOException.class, this::load); assertEquals(5, previous.value());
        Files.writeString(config(), "{}"); Files.delete(texts()); assertThrows(IOException.class, this::load);
        assertFalse(Files.exists(texts()));
    }
    @Test void scalarSectionBooleanNumberAndListTypeMistakesAreRejected() throws Exception {
        for (String yaml : List.of("general: false", "general: {enabled: 'false'}", "general: {count: wrong}",
                "general: {worlds: world}", "rules: {loot: [broken]}",
                "rules: {loot: [{item: 12}]}", "rules: {loot: [{amount: [one, two]}]}")) {
            files(yaml, "{}"); assertThrows(InvalidConfigurationException.class, this::load, yaml);
        }
    }
    @Test void omittedKeysCustomExtensionsAndNumericClampsRemainSupported() throws Exception {
        files("general:\n  count: 999\ncustom:\n  future: [a, b]\n", "{}");
        var snapshot = load((yaml, logger) -> Math.clamp(yaml.getInt("general.count"), 1, 10));
        assertEquals(10, snapshot.value()); assertEquals(999, snapshot.yaml().getInt("general.count"));
        assertTrue(snapshot.yaml().getBoolean("general.enabled"));
        assertEquals(List.of("a", "b"), snapshot.yaml().getStringList("custom.future"));
        files("general: {count: .NaN}", "{}");
        assertEquals(16, load((yaml, logger) -> Double.isFinite(yaml.getDouble("general.count")) ? 1 : 16).value(),
                "существующие finite/fallback guards числовых настроек не отменяются");
    }
    @Test void parserExceptionDoesNotPublishOrRunCleanup() throws Exception {
        files("{}", "{}"); AtomicInteger cleanups = new AtomicInteger(); var previous = load();
        AtomicReference<ConfigSnapshot<Integer>> current = new AtomicReference<>(previous);
        assertThrows(IllegalArgumentException.class, () -> {
            var candidate = load((yaml, logger) -> { throw new IllegalArgumentException("secret"); });
            current.set(candidate); candidate.committed(Logger.getAnonymousLogger(), cleanups::incrementAndGet);
        });
        assertSame(previous, current.get()); assertEquals(0, cleanups.get());
    }
    @Test void diagnosticsAreStagedBoundedAndOnlyEmittedAfterCommit() throws Exception {
        files("{}", "{}"); List<String> output = new ArrayList<>(); Logger logger = logger(output);
        var candidate = load((yaml, staged) -> { for (int i = 0; i < 100; i++) staged.warning("clamp " + i); return 5; });
        assertTrue(output.isEmpty()); assertEquals(64, candidate.diagnostics().size());
        assertThrows(UnsupportedOperationException.class, () -> candidate.diagnostics().clear());
        candidate.committed(logger); assertEquals(64, output.size());
    }
    @Test void postCommitCleanupFailureDoesNotPreventOtherHooksAndDoesNotLeakExceptionText() throws Exception {
        files("{}", "{}"); var candidate = load(); List<String> output = new ArrayList<>(); AtomicInteger called = new AtomicInteger();
        candidate.committed(logger(output), () -> { throw new IllegalStateException("TOP_SECRET"); }, called::incrementAndGet);
        assertEquals(1, called.get()); assertEquals(1, output.size()); assertFalse(output.getFirst().contains("TOP_SECRET"));
    }
    @Test void rejectionLoggingDoesNotExposeYamlOrExceptionPayload() {
        List<String> output = new ArrayList<>(); ConfigSnapshot.rejected(logger(output), new IOException("TOKEN: PRIVATE"));
        assertEquals(1, output.size()); assertTrue(output.getFirst().contains("IOException"));
        assertFalse(output.getFirst().contains("PRIVATE")); assertFalse(output.getFirst().contains("TOKEN"));
    }
    @Test void alreadyLoadedSnapshotDoesNotFollowSubsequentFileChanges() throws Exception {
        files("general: {count: 9}", "demo: {line: 'Old %n%'}"); var old = load();
        files("general: {count: 12}", "demo: {line: 'New %n%'}"); var updated = load();
        assertEquals(9, old.value()); assertEquals("Old 1", old.messages().plain("line", "n", 1));
        assertEquals(12, updated.value()); assertEquals("New 1", updated.messages().plain("line", "n", 1));
    }
    @Test void malformedOrMissingResourceIsNotTreatedAsEmptyDefaults() throws Exception {
        files("{}", "{}");
        assertThrows(IOException.class, () -> ConfigSnapshot.load(config(), null, (yaml, log) -> 1, (yaml, log) -> null));
        assertThrows(InvalidConfigurationException.class, () -> ConfigSnapshot.load(config(), stream("[broken"), (yaml, log) -> 1, (yaml, log) -> null));
    }
    @Test void resourcesAreClosedEvenWhenFileOrParserFails() throws Exception {
        files("{}", "{}"); AtomicInteger closed = new AtomicInteger();
        var resource = new ByteArrayInputStream(DEFAULTS.getBytes(StandardCharsets.UTF_8)) {
            @Override public void close() throws IOException { closed.incrementAndGet(); super.close(); }
        };
        Files.delete(config());
        assertThrows(IOException.class, () -> ConfigSnapshot.load(config(), resource, (yaml, log) -> 1, (yaml, log) -> null));
        assertTrue(closed.get() > 0);
    }
    private Logger logger(List<String> output) {
        Logger logger = Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { output.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        }); return logger;
    }
}
