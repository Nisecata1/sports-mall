package com.sportsmall.api;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.*;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.*;
import java.util.Optional;

/** 两端共用JSON编码；金额保持十进制精度，日期时间使用ISO格式。 */
public final class ApiJson {
    private ApiJson() {}
    public static final Gson GSON = new GsonBuilder()
            .setStrictness(Strictness.STRICT).disableJdkUnsafe().serializeNulls()
            .registerTypeAdapter(Instant.class, new TextAdapter<>(Instant::parse))
            .registerTypeAdapter(LocalDate.class, new TextAdapter<>(LocalDate::parse))
            .registerTypeAdapter(LocalDateTime.class, new TextAdapter<>(LocalDateTime::parse))
            .registerTypeAdapter(BigDecimal.class, new TextAdapter<>(BigDecimal::new))
            .registerTypeAdapterFactory(new OptionalFactory())
            .create();
    private record TextAdapter<T>(java.util.function.Function<String,T> parse)
            implements JsonSerializer<T>, JsonDeserializer<T> {
        @Override public JsonElement serialize(T value, Type type, JsonSerializationContext context) {
            return new JsonPrimitive(value.toString());
        }
        @Override public T deserialize(JsonElement value, Type type, JsonDeserializationContext context) {
            return parse.apply(value.getAsString());
        }
    }
    private static final class OptionalFactory implements TypeAdapterFactory {
        @Override @SuppressWarnings("unchecked")
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            if (type.getRawType() != Optional.class) return null;
            Type member = ((ParameterizedType) type.getType()).getActualTypeArguments()[0];
            TypeAdapter<Object> adapter = (TypeAdapter<Object>) gson.getAdapter(TypeToken.get(member));
            return (TypeAdapter<T>) new TypeAdapter<Optional<?>>() {
                @Override public void write(JsonWriter writer, Optional<?> value) throws IOException {
                    if (value == null || value.isEmpty()) writer.nullValue();
                    else adapter.write(writer, value.get());
                }
                @Override public Optional<?> read(JsonReader reader) throws IOException {
                    if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return Optional.empty(); }
                    return Optional.ofNullable(adapter.read(reader));
                }
            };
        }
    }
}
