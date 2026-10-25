package ru.cherdakmd.chrdkregister;

import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Реестр разделов {@code /profile}, которые присылают аддоны (API v8).
 *
 * <p>Ядро не знает имён аддонов и их данных: каждый модуль регистрирует свой
 * {@link ChrdkRegisterApi.ProfileProvider} под собственным id. Реестр
 * сортирует разделы по {@link ChrdkRegisterApi.ProfileProvider#order()}, скрывает
 * пустые и изолирует сбой одного провайдера — исключение логируется, остальные
 * разделы всё равно показываются.</p>
 *
 * <p>Регистрация идёт при включении аддона (главный поток), а чтение — при
 * открытии карточки. Коллекция параллельная, поэтому перезагрузка аддона во
 * время открытого меню не роняет {@code /profile}.</p>
 */
public final class ProfileRegistry {

    private final Map<String, ChrdkRegisterApi.ProfileProvider> providers =
            new ConcurrentHashMap<>();
    private final Logger logger;

    public ProfileRegistry(Logger logger) {
        this.logger = logger;
    }

    /**
     * Регистрирует раздел; повторный вызов с тем же id заменяет прежний.
     *
     * @return {@code true}, если раздел действительно сменился
     */
    public boolean register(String id, ChrdkRegisterApi.ProfileProvider provider) {
        String key = normalize(id);
        if (key.isEmpty() || provider == null) {
            return false;
        }
        return providers.put(key, provider) != provider;
    }

    /** Снимает раздел; {@code true} — он был зарегистрирован. */
    public boolean unregister(String id) {
        String key = normalize(id);
        return !key.isEmpty() && providers.remove(key) != null;
    }

    /** Сколько разделов зарегистрировано (для {@code /profile info} и логов). */
    public int size() {
        return providers.size();
    }

    /** Снимок id разделов по порядку — для диагностики. */
    public List<String> ids() {
        List<Map.Entry<String, ChrdkRegisterApi.ProfileProvider>> entries = sorted();
        List<String> out = new ArrayList<>(entries.size());
        for (Map.Entry<String, ChrdkRegisterApi.ProfileProvider> entry : entries) {
            out.add(entry.getKey());
        }
        return List.copyOf(out);
    }

    /**
     * Собирает разделы для игрока. Пустые разделы и сбои провайдеров
     * пропускаются, порядок стабилен: {@code order()}, затем id.
     */
    public List<ChrdkRegisterApi.ProfileSection> sections(
            ChrdkRegisterApi.ProfileContext context) {
        if (context == null) {
            return List.of();
        }
        List<ChrdkRegisterApi.ProfileSection> out = new ArrayList<>();
        for (Map.Entry<String, ChrdkRegisterApi.ProfileProvider> entry : sorted()) {
            try {
                ChrdkRegisterApi.ProfileSection section = entry.getValue().section(context);
                if (section != null && !section.lines().isEmpty()) {
                    out.add(section);
                }
            } catch (RuntimeException | LinkageError exception) {
                if (logger != null) {
                    logger.log(Level.WARNING, "[CHRDK_Core] Раздел профиля «" + entry.getKey()
                            + "» не собран: " + exception.getMessage());
                }
            }
        }
        return List.copyOf(out);
    }

    private List<Map.Entry<String, ChrdkRegisterApi.ProfileProvider>> sorted() {
        List<Map.Entry<String, ChrdkRegisterApi.ProfileProvider>> entries =
                new ArrayList<>(providers.entrySet());
        entries.sort(Comparator
                .comparingInt((Map.Entry<String, ChrdkRegisterApi.ProfileProvider> entry) ->
                        orderOf(entry.getValue()))
                .thenComparing(Map.Entry::getKey));
        return entries;
    }

    private static int orderOf(ChrdkRegisterApi.ProfileProvider provider) {
        try {
            return provider.order();
        } catch (RuntimeException exception) {
            return 100;
        }
    }

    static String normalize(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }
}
