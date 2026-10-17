package ru.cherdakmd.chrdkregister;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Подписочная модель Boosty: привязки «игрок ⇄ ник на Boosty» и автопродление
 * проходки, пока подписка блога активна.
 *
 * <p>Флоу: игрок пишет {@code /pass boost <ник_boosty>} → менеджер ищет его в
 * выдаче подписчиков блога → если подписка активна и уровень не ниже порога —
 * привязка сохраняется, проходка продлевается до «сейчас + days-per-period».
 * Фоновая перепроверка (раз в recheck-hours часов) топит горизонт проходки снова.
 * Подписка умерла → продление прекращается (проходка сгорает по своему сроку,
 * отзывать резко не будем — честный grace-период).</p>
 */
public final class BoostyManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final RegisterPlugin plugin;
    private final RegistrationStore store;
    private final BoostyTokenStore tokenStore;
    private final BoostyClient client;

    private final Path bindingsFile;
    /** canonical (lower-case) имя игрока → привязка. */
    private final Map<String, Binding> bindings = new HashMap<>();

    public BoostyManager(RegisterPlugin plugin, RegistrationStore store, PluginConfig initialConfig) {
        this.plugin = plugin;
        this.store = store;
        this.tokenStore = new BoostyTokenStore(plugin.getDataFolder(), plugin.getLogger());
        this.tokenStore.load();
        // токены из конфига — только «посев»; рабочие версии живут в boosty-tokens.json
        PluginConfig.BoostySettings bs = initialConfig.boosty();
        tokenStore.seedFrom(bs.token(), bs.refreshToken(), bs.deviceId());
        this.client = new BoostyClient(initialConfig, tokenStore, plugin.getLogger());
        this.bindingsFile = plugin.getDataFolder().toPath().resolve("boosty-bindings.json");
        loadBindings();
    }

    public BoostyClient client() {
        return client;
    }

    public BoostyTokenStore tokenStore() {
        return tokenStore;
    }

    /** Обновление настроек после /pass reload. Токены из конфига пересеянются только в пустое хранилище. */
    public void updateConfig(PluginConfig config) {
        client.updateConfig(config);
        PluginConfig.BoostySettings bs = config.boosty();
        tokenStore.seedFrom(bs.token(), bs.refreshToken(), bs.deviceId());
    }

    /* ------------------------------------------------------------------ *
     *  Привязка
     * ------------------------------------------------------------------ */

    public record Binding(String mcName, String boostyName, long boostyUserId, long linkedAtMillis) {
    }

    public record BindOutcome(Kind kind, BoostyClient.Subscriber subscriber, String holder) {
        public enum Kind { LINKED, CHANGED, NOT_FOUND, INACTIVE, LOW_LEVEL, TAKEN }

        static BindOutcome simple(Kind kind) {
            return new BindOutcome(kind, null, null);
        }
    }

    public synchronized Optional<Binding> bindingOf(String mcName) {
        return Optional.ofNullable(bindings.get(key(mcName)));
    }

    /** Map-копия всех привязок (для логов/отладки). */
    public synchronized Map<String, Binding> bindings() {
        return Map.copyOf(bindings);
    }

    /**
     * Привязать игрока к Boosty-нику. ВЫПОЛНЯТЬ ВНЕ ГЛАВНОГО ПОТОКА (сеть).
     * Выдача/продление проходки выполняется внутри при успехе.
     */
    public synchronized BindOutcome bindFor(String mcName, String boostyName, PluginConfig config)
            throws IOException {
        PluginConfig.BoostySettings bs = config.boosty();
        List<BoostyClient.Subscriber> all = client.fetchSubscribers();

        BoostyClient.Subscriber found = null;
        for (BoostyClient.Subscriber candidate : all) {
            if (candidate.name().equalsIgnoreCase(boostyName.trim())) {
                found = candidate;
                break;
            }
        }
        if (found == null) {
            return BindOutcome.simple(BindOutcome.Kind.NOT_FOUND);
        }

        // один Boosty-аккаунт → один игрок: ник уже занят кем-то другим?
        for (Binding other : bindings.values()) {
            if (other.boostyName().equalsIgnoreCase(found.name())
                    && !other.mcName().equalsIgnoreCase(mcName)) {
                return new BindOutcome(BindOutcome.Kind.TAKEN, found, other.mcName());
            }
        }

        if (!found.subscribed() || found.blacklisted()) {
            return new BindOutcome(BindOutcome.Kind.INACTIVE, found, null);
        }
        if (found.levelPriceRub() < bs.minLevelPrice()) {
            return new BindOutcome(BindOutcome.Kind.LOW_LEVEL, found, null);
        }

        boolean changed = bindings.containsKey(key(mcName)) && !bs.dryRun();
        if (bs.dryRun()) {
            plugin.getLogger().info("[Boosty] DRY-RUN: привязка " + mcName + " ⇄ " + found.name() + " не сохранена.");
            return new BindOutcome(BindOutcome.Kind.LINKED, found, null);
        }
        bindings.put(key(mcName), new Binding(mcName, found.name(), found.id(), System.currentTimeMillis()));
        saveBindings();
        renewPass(mcName, bs.daysPerPeriod(), found.name());
        return new BindOutcome(changed ? BindOutcome.Kind.CHANGED : BindOutcome.Kind.LINKED, found, null);
    }

    /** Отвязать игрока. true, если привязка была. */
    public synchronized boolean unlink(String mcName) {
        Binding removed = bindings.remove(key(mcName));
        if (removed != null) {
            saveBindings();
        }
        return removed != null;
    }

    /* ------------------------------------------------------------------ *
     *  Периодическая перепроверка
     * ------------------------------------------------------------------ */

    public record RecheckStats(int extended, int lapsed, int total, java.util.List<String> revived) {
        static RecheckStats empty() {
            return new RecheckStats(0, 0, 0, java.util.List.of());
        }
    }

    /**
     * Перепроверить все привязки. ВЫПОЛНЯТЬ ВНЕ ГЛАВНОГО ПОТОКА (сеть).
     *
     * @return статистика прогона (для консоли); выбрасывает IOException при отказе API.
     */
    public synchronized RecheckStats recheckAll(PluginConfig config) throws IOException {
        Logger log = plugin.getLogger();
        int total = bindings.size();
        if (total == 0) {
            return RecheckStats.empty();
        }

        List<BoostyClient.Subscriber> all = client.fetchSubscribers();
        Map<String, BoostyClient.Subscriber> byName = new HashMap<>();
        for (BoostyClient.Subscriber subscriber : all) {
            byName.put(subscriber.name().toLowerCase(Locale.ROOT), subscriber);
        }

        PluginConfig.BoostySettings bs = config.boosty();
        int extended = 0;
        int lapsed = 0;
        List<String> revived = new ArrayList<>();
        for (Binding binding : new ArrayList<>(bindings.values())) {
            BoostyClient.Subscriber subscriber = byName.get(binding.boostyName().toLowerCase(Locale.ROOT));
            boolean active = subscriber != null && subscriber.countsForPass(bs.minLevelPrice());
            if (active) {
                if (bs.dryRun()) {
                    log.info("[Boosty] DRY-RUN: продление для " + binding.mcName() + " НЕ применено.");
                } else {
                    long before = store.getAccount(binding.mcName()).map(acc -> acc.passUntil).orElse(0L);
                    boolean wasExpired = before < System.currentTimeMillis();
                    renewPass(binding.mcName(), bs.daysPerPeriod(), subscriber.name());
                    if (wasExpired) {
                        revived.add(binding.mcName()); // сгорал → возродился: нужно LuckPerms/венты
                    }
                }
                extended++;
            } else {
                lapsed++;
                log.warning("[Boosty] Подписка игрока " + binding.mcName() + " (Boosty: " + binding.boostyName()
                        + ") НЕ подтверждена — проходка догорит по своему сроку.");
            }
        }
        return new RecheckStats(extended, lapsed, total, revived);
    }

    /** Горизонт «сейчас + days»: не складывается, а доводится до уровня (анти-компаунд). */
    private long renewPass(String mcName, int days, String boostyName) {
        long now = System.currentTimeMillis();
        long targetUntil = now + (long) days * 86_400_000L;
        RegistrationStore.PlayerAccount account = store.renewPassUntil(mcName, now, targetUntil,
                BoostyClient.SOURCE + " (" + boostyName + ")");
        return account == null ? 0L : account.passUntil;
    }

    /* ------------------------------------------------------------------ *
     *  Сериализация привязок
     * ------------------------------------------------------------------ */

    private void loadBindings() {
        if (!Files.exists(bindingsFile)) {
            return;
        }
        try {
            JsonObject json = JsonParser.parseString(
                    Files.readString(bindingsFile, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!json.has("bindings") || !json.get("bindings").isJsonArray()) {
                return;
            }
            for (JsonElement element : json.getAsJsonArray("bindings")) {
                JsonObject entry = element.getAsJsonObject();
                Binding binding = new Binding(
                        BoostyTokenStore.optString(entry, "mcName", ""),
                        BoostyTokenStore.optString(entry, "boostyName", ""),
                        entry.has("boostyUserId") ? entry.get("boostyUserId").getAsLong() : 0L,
                        entry.has("linkedAt") ? entry.get("linkedAt").getAsLong() : 0L);
                if (!binding.mcName().isBlank()) {
                    bindings.put(key(binding.mcName()), binding);
                }
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("[Boosty] Не удалось прочитать boosty-bindings.json: " + exception.getMessage());
        }
    }

    private synchronized void saveBindings() {
        JsonArray array = new JsonArray();
        for (Binding binding : bindings.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("mcName", binding.mcName());
            entry.addProperty("boostyName", binding.boostyName());
            entry.addProperty("boostyUserId", binding.boostyUserId());
            entry.addProperty("linkedAt", binding.linkedAtMillis());
            array.add(entry);
        }
        JsonObject json = new JsonObject();
        json.add("bindings", array);
        try {
            Path temp = bindingsFile.resolveSibling(bindingsFile.getFileName() + ".tmp");
            Files.createDirectories(bindingsFile.getParent());
            Files.writeString(temp, GSON.toJson(json), StandardCharsets.UTF_8);
            Files.move(temp, bindingsFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            plugin.getLogger().warning("[Boosty] Не удалось сохранить boosty-bindings.json: " + exception.getMessage());
        }
    }

    private static String key(String mcName) {
        return mcName == null ? "" : mcName.toLowerCase(Locale.ROOT);
    }
}
