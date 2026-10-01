package io.github.underconnor.passport.core;

import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public final class ApiClient implements AutoCloseable {
    private final URI base;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Semaphore slots = new Semaphore(32);
    public ApiClient(String baseUrl, String token, boolean allowInsecure) {
        this.base = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
            || !(base.getScheme().equals("https") || (allowInsecure && base.getScheme().equals("http"))))
            throw new IllegalArgumentException("API requires HTTPS; opt in only for isolated private HTTP testing");
        if (token == null || token.length() < 32 || token.contains("\n") || token.contains("\r")) throw new IllegalArgumentException("API_SERVICE_TOKEN must be at least 32 characters");
        this.token = token;
    }
    public CompletableFuture<PolicyEvents> events(String after) {
        if (after != null) PolicyEvents.cursorNumber(after);
        return request("GET", "v1/minecraft/events" + (after == null ? "" : "?after=" + after), null)
            .thenApply(PolicyEvents::parse);
    }
    public CompletableFuture<Void> heartbeat(String source, List<ServerRegistration> servers) {
        return request("POST", "v1/minecraft/servers/heartbeat", ServerRegistration.payload(source, servers)).thenApply(ignored -> null);
    }
    public CompletableFuture<Policy> policy(UUID uuid) {
        return request("GET", "v1/minecraft/policies/" + uuid, null)
            .thenApply(body -> Policy.parse(body, uuid, Instant.now()));
    }
    public CompletableFuture<JsonObject> createLink(UUID uuid, String name, String session) {
        JsonObject body = identity(uuid, session);
        body.addProperty("minecraftName", name);
        return request("POST", "v1/link-sessions", body).thenApply(s -> JsonParser.parseString(s).getAsJsonObject());
    }
    public CompletableFuture<LinkInspection> gameInspect(String id, UUID uuid, String session) {
        String expectedId = UUID.fromString(id).toString();
        return request("POST", "v1/link-sessions/" + expectedId + "/game-inspect", identity(uuid, session))
            .thenApply(body -> LinkInspection.parse(body, expectedId, Instant.now()));
    }
    public CompletableFuture<JsonObject> confirm(String id, UUID uuid, String session) {
        return request("POST", "v1/link-sessions/" + UUID.fromString(id) + "/game-confirm", identity(uuid, session))
            .thenApply(s -> JsonParser.parseString(s).getAsJsonObject());
    }
    public CompletableFuture<Void> cancel(String id, UUID uuid, String session) {
        return request("DELETE", "v1/link-sessions/" + UUID.fromString(id), identity(uuid, session)).thenApply(s -> null);
    }
    private JsonObject identity(UUID uuid, String session) {
        JsonObject body = new JsonObject(); body.addProperty("minecraftUuid", uuid.toString()); body.addProperty("gameSessionId", session); return body;
    }
    private CompletableFuture<String> request(String method, String path, JsonObject body) {
        if (!slots.tryAcquire()) return CompletableFuture.failedFuture(new IllegalStateException("API busy"));
        try {
            HttpRequest request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(2))
                .header("Authorization", "Bearer " + token).header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).orTimeout(2, TimeUnit.SECONDS)
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body().length > 65536)
                        throw new CompletionException(ApiFailure.fromResponse(response.statusCode(), response.body()));
                    return new String(response.body(), StandardCharsets.UTF_8);
                }).whenComplete((result, error) -> slots.release());
        } catch (RuntimeException e) { slots.release(); return CompletableFuture.failedFuture(e); }
    }
    public static String env(String name, String fallback) { return System.getenv().getOrDefault(name, fallback); }
    @Override public void close() { http.shutdownNow(); }
}
