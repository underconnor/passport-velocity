package io.github.underconnor.passport.core;

import com.google.gson.*;
import java.util.*;

/** Authenticated invalidation hints; events themselves never grant permission. */
public record PolicyEvents(String cursor, boolean reset, List<Change> events) {
    public record Change(String id, UUID minecraftUuid, long policyVersion) {}
    public static long cursorNumber(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException("event cursor");
        return Long.parseLong(value);
    }
    private static String decimalString(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("event cursor type");
        String value = element.getAsString(); cursorNumber(value); return value;
    }
    public static PolicyEvents parse(String body) {
        JsonObject object = JsonParser.parseString(body).getAsJsonObject();
        String cursor = decimalString(object.get("cursor"));
        JsonElement reset = object.get("reset");
        if (reset == null || !reset.isJsonPrimitive() || !reset.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("event reset type");
        JsonArray values = object.getAsJsonArray("events");
        if (values.size() > 500) throw new IllegalArgumentException("event batch limit");
        List<Change> events = new ArrayList<>(); long previous = 0;
        for (JsonElement value : values) {
            JsonObject event = value.getAsJsonObject();
            String id = decimalString(event.get("id")); long number = cursorNumber(id);
            if (number <= previous || number > cursorNumber(cursor)) throw new IllegalArgumentException("event ordering");
            previous = number;
            String uuid = event.get("minecraftUuid").getAsString();
            if (!uuid.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) throw new IllegalArgumentException("event UUID");
            JsonPrimitive rawVersion = event.getAsJsonPrimitive("policyVersion");
            if (!rawVersion.isNumber() || !rawVersion.getAsString().matches("[1-9][0-9]{0,9}")) throw new IllegalArgumentException("event version");
            long version = rawVersion.getAsLong();
            if (version > Integer.MAX_VALUE) throw new IllegalArgumentException("event version");
            events.add(new Change(id, UUID.fromString(uuid), version));
        }
        return new PolicyEvents(cursor, reset.getAsBoolean(), List.copyOf(events));
    }
    public void validateAfter(String after) {
        if (after == null && !reset) throw new IllegalArgumentException("initial event reset required");
        if (reset) return;
        long before = cursorNumber(after);
        if (cursorNumber(cursor) < before || events.stream().anyMatch(e -> cursorNumber(e.id()) <= before))
            throw new IllegalArgumentException("event cursor regression");
    }
}
