package ru.cherdakmd.chrdkregister;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;

/**
 * Готовое содержимое книги-путеводителя: заголовок, автор и страницы из {@code messages.yml}.
 * Чистые данные без Bukkit — тестируются обычными модульными тестами, а
 * {@link GuideBookService} только упаковывает их в предмет.
 *
 * <p>Ограничения Minecraft соблюдаются здесь же: заголовок ≤ 32 символов, страниц ≤ 100,
 * каждая страница ≤ 1024 символов. Пустые страницы выбрасываются.</p>
 */
public record GuideBookContent(String title, String author, List<Component> pages) {

    public static final int MAX_TITLE_LENGTH = 32;
    public static final int MAX_PAGES = 100;
    public static final int MAX_PAGE_LENGTH = 1024;

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    public static GuideBookContent from(Messages messages) {
        String title = shorten(messages.plain("guide.title").trim());
        String author = shorten(messages.plain("guide.author").trim());
        if (author.isEmpty()) {
            author = "server";
        }
        List<Component> pages = new ArrayList<>();
        for (Component page : messages.getList("guide.pages")) {
            if (PLAIN.serialize(page).isBlank()) {
                continue;
            }
            pages.add(page);
            if (pages.size() >= MAX_PAGES) {
                break;
            }
        }
        return new GuideBookContent(title, author, List.copyOf(pages));
    }

    /** Есть ли что выдавать: нужен заголовок и хотя бы одна страница. */
    public boolean isUsable() {
        return !title.isBlank() && !pages.isEmpty();
    }

    /** Текст страницы без разметки (для логов и тестов). */
    public String plainPage(int index) {
        return PLAIN.serialize(pages.get(index));
    }

    private static String shorten(String value) {
        return value.length() <= MAX_TITLE_LENGTH ? value : value.substring(0, MAX_TITLE_LENGTH);
    }
}
