package ru.cherdakmd.chrdkclaims;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;

/** Gson tree-parser принимает duplicate keys: финансовое состояние так читать нельзя. */
final class ClaimJson {
    static JsonObject read(Path file) throws IOException {
        if(Files.size(file)>64L*1024*1024) throw new IOException("claim capacity");
        try(var input=new JsonReader(Files.newBufferedReader(file))) {
            input.setStrictness(Strictness.STRICT);
            var value=read(input,0);
            if(input.peek()!=JsonToken.END_DOCUMENT||!value.isJsonObject()) throw new IOException("claim root/trailing JSON");
            return value.getAsJsonObject();
        }
    }
    static JsonObject object(String json) {
        try(var input=new JsonReader(new StringReader(json))) {
            input.setStrictness(Strictness.STRICT);var value=read(input,0);
            if(input.peek()!=JsonToken.END_DOCUMENT||!value.isJsonObject()) throw new IOException("claim domain/trailing JSON");
            return value.getAsJsonObject();
        } catch(IOException e) {throw new IllegalArgumentException("claim domain JSON",e);}
    }
    private static JsonElement read(JsonReader input,int depth) throws IOException {
        if(depth>64) throw new IOException("claim nesting");
        return switch(input.peek()) {
            case BEGIN_OBJECT -> {
                var object=new JsonObject(); input.beginObject();
                while(input.hasNext()) {
                    String key=input.nextName(); if(object.has(key)) throw new IOException("duplicate claim field: "+key);
                    object.add(key,read(input,depth+1));
                }
                input.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                var array=new JsonArray(); input.beginArray(); while(input.hasNext()) array.add(read(input,depth+1)); input.endArray(); yield array;
            }
            case STRING -> new JsonPrimitive(input.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(input.nextString()));
            case BOOLEAN -> new JsonPrimitive(input.nextBoolean());
            case NULL -> { input.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IOException("claim JSON token");
        };
    }
    private ClaimJson() { }
}
