package ru.cherdakmd.chrdkregister;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;

/** Работа с диапазонами видимого текста без разрезания тегов и потери цветов. */
final class NicknameComponents {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private NicknameComponents() { }

    /**
     * Старый товар хранит префикс-разметку, а реальный ник добавляется последним.
     * Выделяем именно последний диапазон: текст тега может совпадать с ником.
     * Чистую цветовую обёртку возвращаем без пересериализации градиента.
     */
    static String nameFromEndFirst(String name, String markup) {
        if (name == null || name.isEmpty()) return "";
        Component component = Messages.format(markup);
        String plain = PLAIN.serialize(component);
        if (!plain.endsWith(name)) return Messages.escape(name);
        int nameAt = plain.length() - name.length();
        int start = 0;
        int end = nameAt;
        while (start < end && boundary(plain.charAt(start))) start++;
        while (end > start && boundary(plain.charAt(end - 1))) end--;
        if (start == end) return markup;
        return MINI.serialize(slice(component, nameAt, plain.length()).compact())
                + " <dark_gray>·</dark_gray> <reset>"
                + MINI.serialize(slice(component, start, end).compact()) + "<reset>";
    }

    private static boolean boundary(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '·' || c == '|' || c == '•';
    }

    /** Индексы — в строке PlainTextComponentSerializer; пустые диапазоны допустимы. */
    static Component slice(Component node, int start, int end) {
        return crop(node, start, end, new int[]{0});
    }

    private static Component crop(Component node, int start, int end, int[] cursor) {
        String content = node instanceof TextComponent text ? text.content()
                : PLAIN.serialize(node.children(List.of()));
        int offset = cursor[0];
        cursor[0] += content.length();
        int from = Math.min(content.length(), Math.max(0, start - offset));
        int to = Math.min(content.length(), Math.max(from, end - offset));
        // VirtualComponent хранит исходные данные градиента даже после content().
        // Обычный текстовый узел сохраняет вычисленные цвета, но не старый текст.
        Component result = Component.text(content.substring(from, to)).style(node.style());
        List<Component> children = new ArrayList<>();
        for (Component child : node.children()) children.add(crop(child, start, end, cursor));
        return result.children(children);
    }
}
