package io.github.underconnor.passport.core;

import com.google.gson.*;
import java.time.Instant;
import java.util.*;

/** Service-only link state. This never grants Minecraft server access. */
public record LinkInspection(String id, String status, Instant expiresAt, boolean webConfirmed, boolean gameConfirmed) {
    public static LinkInspection parse(String body, String expectedId, Instant now) {
        try {
            JsonObject value = JsonParser.parseString(body).getAsJsonObject();
            if (!value.keySet().equals(Set.of("id", "status", "expiresAt", "webConfirmed", "gameConfirmed"))) throw new IllegalArgumentException();
            String id = text(value, "id"), status = text(value, "status");
            Instant expires = Instant.parse(text(value, "expiresAt"));
            boolean web = bool(value, "webConfirmed"), game = bool(value, "gameConfirmed");
            if (!UUID.fromString(id).toString().equals(expectedId) || !Set.of("pending", "linked").contains(status)
                || !expires.isAfter(now) || expires.isAfter(now.plusSeconds(305))
                || (status.equals("linked") && (!web || !game))) throw new IllegalArgumentException();
            return new LinkInspection(id, status, expires, web, game);
        } catch (RuntimeException ignored) {
            // Never retain parser messages, request identities or upstream text.
            throw new IllegalArgumentException("Invalid link inspection response");
        }
    }
    public static String confirmationStatus(JsonObject value, String expectedId, Instant expectedExpiry, Instant now) {
        try {
            if (!value.keySet().equals(Set.of("id", "status", "expiresAt"))) throw new IllegalArgumentException();
            String status = text(value, "status");
            if (!UUID.fromString(text(value, "id")).toString().equals(expectedId) || !Set.of("pending", "linked").contains(status)
                || !Instant.parse(text(value, "expiresAt")).equals(expectedExpiry) || !expectedExpiry.isAfter(now)) throw new IllegalArgumentException();
            return status;
        } catch (RuntimeException ignored) { throw new IllegalArgumentException("Invalid link confirmation response"); }
    }
    private static String text(JsonObject value, String key) {
        JsonElement element = value.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
        return element.getAsString();
    }
    private static boolean bool(JsonObject value, String key) {
        JsonElement element = value.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException();
        return element.getAsBoolean();
    }
}
