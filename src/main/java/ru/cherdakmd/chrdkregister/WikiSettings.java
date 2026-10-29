package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.FileConfiguration;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ссылка на вики игрока (секция {@code wiki:}).
 *
 * <p>Вики — статический сайт из этого же репозитория ({@code wiki/*.md} →
 * GitHub Pages). В игре браузер не открыть, поэтому плагин не «показывает
 * сайт», а отдаёт <b>кликабельную ссылку</b>: клик в чате открывает браузер,
 * второй клик копирует адрес, если браузер не поднялся.</p>
 *
 * <p>Адрес проверяется, а не берётся как есть: строка попадает внутрь тега
 * MiniMessage ({@code <click:open_url:'...'>}), поэтому кавычки, угловые
 * скобки, пробелы и обратный слэш запрещены. Так владелец сервера не сможет
 * случайно сломать разметку сообщения ссылкой из конфига.</p>
 *
 * <p>Класс чистый (Bukkit — только для чтения конфига), поэтому нормализация
 * адреса и сборка адреса страницы проверяются юнит-тестами.</p>
 *
 * @param enabled показывать ссылку игрокам
 * @param url     нормализованный адрес сайта со слэшем на конце (пусто — не задан)
 * @param pages   слаги для подсказки в TAB и списка «попробуйте эти»
 */
public record WikiSettings(boolean enabled, String url, List<String> pages) {

    /** Адрес сайта по умолчанию — тот же, что в {@code docs/WIKI.md}. */
    public static final String DEFAULT_URL = "https://cherdakmd.github.io/CHRDK_F_REGISTER/";

    /** Страницы, которые чаще всего нужны в игре: слаг = имя файла на сайте. */
    public static final List<String> DEFAULT_PAGES = List.of(
            "start", "menu", "commands", "ranks", "economy", "claims", "faq");

    /** Потолок подсказок: длиннее список не влезает в TAB и в сообщение об ошибке. */
    public static final int MAX_PAGES = 20;

    /** Максимальная длина слага: самые длинные имена страниц короче. */
    public static final int MAX_SLUG_LENGTH = 40;

    public static final WikiSettings DISABLED = new WikiSettings(false, "", List.of());

    public WikiSettings {
        url = url == null ? "" : url.trim();
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    /** Разбор секции {@code wiki}. Битые значения выключают ссылку, а не роняют плагин. */
    public static WikiSettings from(FileConfiguration config) {
        if (config == null) {
            return DISABLED;
        }
        return of(config.getBoolean("wiki.enabled", true),
                config.getString("wiki.url", DEFAULT_URL),
                config.getStringList("wiki.pages"));
    }

    /**
     * @param enabled включает команду и кнопку меню
     * @param rawUrl  адрес сайта; невалидный превращается в пустой (ссылка выключается)
     * @param rawPages желаемые подсказки; мусор отбрасывается
     */
    public static WikiSettings of(boolean enabled, String rawUrl, List<String> rawPages) {
        String url = normalizeUrl(rawUrl);
        Set<String> unique = new LinkedHashSet<>();
        if (rawPages != null) {
            for (String raw : rawPages) {
                String slug = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
                if (validSlug(slug) && !RetiredAddons.source(slug)) {
                    unique.add(slug);
                }
                if (unique.size() >= MAX_PAGES) {
                    break;
                }
            }
        }
        List<String> pages = unique.isEmpty() ? DEFAULT_PAGES : List.copyOf(new ArrayList<>(unique));
        return new WikiSettings(enabled && !url.isEmpty(), url, pages);
    }

    /** Ссылка реально есть: включена и адрес прошёл проверку. */
    public boolean usable() {
        return enabled && !url.isEmpty();
    }

    /**
     * Адрес страницы вики.
     *
     * @param slug слаг страницы; пустой или невалидный — главная
     * @return абсолютный адрес без {@code null}
     */
    public String pageUrl(String slug) {
        if (!usable()) {
            // Выключенная ссылка не должна отдавать адрес: команда покажет
            // «не настроена», а не уведёт игрока на неизвестный домен.
            return "";
        }
        String clean = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
        if (clean.isEmpty() || !validSlug(clean) || RetiredAddons.source(clean)) {
            return url;
        }
        return url + clean + ".html";
    }

    /**
     * Приводит адрес к виду «схема + хост + путь со слэшем».
     *
     * @return нормализованный адрес или пустая строка, если ссылку использовать нельзя
     */
    static String normalizeUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.isEmpty() || value.length() > 300) {
            return "";
        }
        for (int index = 0; index < value.length(); index++) {
            char symbol = value.charAt(index);
            // Кавычка и угловые скобки сломали бы тег MiniMessage, пробел — тег и клиент.
            if (symbol <= ' ' || symbol == '\'' || symbol == '"' || symbol == '<' || symbol == '>'
                    || symbol == '\\' || Character.isWhitespace(symbol)) {
                return "";
            }
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            return "";
        }
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return "";
            }
        } catch (IllegalArgumentException broken) {
            return "";
        }
        return value.endsWith("/") ? value : value + "/";
    }

    /**
     * Слаг страницы: латиница, цифры и дефис, как имена файлов в {@code wiki/}.
     * Заглавные буквы не принимаются — иначе одна страница жила бы в двух адресах.
     */
    static boolean validSlug(String slug) {
        if (slug == null || slug.isEmpty() || slug.length() > MAX_SLUG_LENGTH) {
            return false;
        }
        if (slug.charAt(0) == '-' || slug.charAt(slug.length() - 1) == '-') {
            return false;
        }
        for (int index = 0; index < slug.length(); index++) {
            char symbol = slug.charAt(index);
            boolean latin = symbol >= 'a' && symbol <= 'z';
            if (!latin && !(symbol >= '0' && symbol <= '9') && symbol != '-') {
                return false;
            }
        }
        return true;
    }
}
