package io.github.underconnor.passport.core;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Only fixed, public error categories survive an unsuccessful API response. */
public final class ApiFailure extends RuntimeException {
    public enum Reason {
        GAME_CONFIRMATION_CONSUMED, LINK_EXPIRED, LINK_CONSUMED,
        GAME_SESSION_MISMATCH, LINK_NOT_FOUND, MINECRAFT_ALREADY_LINKED, REJECTED
    }
    private final int status;
    private final Reason reason;

    private ApiFailure(int status, Reason reason) {
        super("API response rejected (HTTP " + status + ", " + reason.name() + ")");
        this.status = status;
        this.reason = reason;
    }
    public int status() { return status; }
    public Reason reason() { return reason; }

    public static ApiFailure fromResponse(int status, byte[] body) {
        Reason reason = Reason.REJECTED;
        if (body.length <= 65536) {
            try {
                JsonElement value = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
                JsonElement code = value.isJsonObject() ? value.getAsJsonObject().get("code") : null;
                if (code != null && code.isJsonPrimitive() && code.getAsJsonPrimitive().isString()) {
                    reason = switch (code.getAsString()) {
                        case "game_confirmation_consumed" -> status == 409 ? Reason.GAME_CONFIRMATION_CONSUMED : Reason.REJECTED;
                        case "link_expired" -> status == 410 ? Reason.LINK_EXPIRED : Reason.REJECTED;
                        case "link_consumed" -> status == 409 ? Reason.LINK_CONSUMED : Reason.REJECTED;
                        case "game_session_mismatch" -> status == 403 ? Reason.GAME_SESSION_MISMATCH : Reason.REJECTED;
                        case "link_not_found" -> status == 404 ? Reason.LINK_NOT_FOUND : Reason.REJECTED;
                        case "minecraft_already_linked" -> status == 409 ? Reason.MINECRAFT_ALREADY_LINKED : Reason.REJECTED;
                        default -> Reason.REJECTED;
                    };
                }
            } catch (RuntimeException ignored) {
                // Parser messages can contain response text; never retain them as a cause.
            }
        }
        return new ApiFailure(status, reason);
    }

    public static Reason reasonOf(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null)
            error = error.getCause();
        return error instanceof ApiFailure failure ? failure.reason() : Reason.REJECTED;
    }
}
