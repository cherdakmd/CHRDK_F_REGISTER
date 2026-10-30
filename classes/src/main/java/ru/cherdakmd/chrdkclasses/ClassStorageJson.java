package ru.cherdakmd.chrdkclasses;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Строгое чтение без потери повторяющихся ключей и точности чисел времени/XP. */
final class ClassStorageJson {
    private ClassStorageJson() {
    }

    static Map<?, ?> read(String json) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            Object value = readValue(reader, 0);
            if (!(value instanceof Map<?, ?> result) || reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("Ожидался единственный JSON-объект classes.json");
            }
            return result;
        }
    }

    private static Object readValue(JsonReader reader, int depth) throws IOException {
        if (depth > 16) {
            throw new IOException("Слишком глубокая вложенность classes.json");
        }
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                Map<String, Object> result = new LinkedHashMap<>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (result.containsKey(key) || result.size() >= ClassStore.MAX_PLAYERS) {
                        throw new IOException("Повторный ключ или превышение размера объекта: " + key);
                    }
                    result.put(key, readValue(reader, depth + 1));
                }
                reader.endObject();
                yield result;
            }
            case BEGIN_ARRAY -> {
                ArrayList<Object> result = new ArrayList<>();
                reader.beginArray();
                while (reader.hasNext()) {
                    if (result.size() >= ClassStore.MAX_PLAYERS) {
                        throw new IOException("Превышен размер массива classes.json");
                    }
                    result.add(readValue(reader, depth + 1));
                }
                reader.endArray();
                yield result;
            }
            case STRING -> reader.nextString();
            case NUMBER -> new BigDecimal(reader.nextString());
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> {
                reader.nextNull();
                yield null;
            }
            default -> throw new IOException("Недопустимый элемент classes.json: " + reader.peek());
        };
    }
}
