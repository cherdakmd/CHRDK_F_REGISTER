package ru.cherdakmd.chrdktreasures;

import ru.cherdakmd.chrdkregister.Messages;

/**
 * Строка «что именно выпало» для чата: собирается из шаблонов
 * {@code messages.yml} ядром, поэтому чистая {@link TreasureLootText} остаётся
 * без зависимости от {@code Messages} и проверяется без сервера.
 *
 * <p>Имя предмета приходит уже готовой разметкой — тег языка клиента или
 * экранированное имя находки, — поэтому подставляется как доверенное.</p>
 */
final class TreasureLootMarkup {

    private TreasureLootMarkup() {
    }

    /** Строка добычи; пустая добыча даёт пустую строку. */
    static String markup(Messages messages, TreasureLootText.Summary summary) {
        if (messages == null || summary == null || summary.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        String separator = messages.raw("loot-list-separator");
        for (TreasureLootText.Part part : summary.parts()) {
            if (out.length() > 0) {
                out.append(separator);
            }
            out.append(messages.raw("loot-list-entry",
                    "name", Messages.trusted(part.markup()),
                    "amount", String.valueOf(part.amount())));
        }
        if (summary.hidden() > 0) {
            out.append(messages.raw("loot-list-more",
                    "count", String.valueOf(summary.hidden())));
        }
        return out.toString();
    }
}
