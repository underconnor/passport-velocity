package io.github.underconnor.passport.core;

import com.google.gson.*;
import java.util.*;

/** Discovery metadata only: a heartbeat never conveys an access grant or a backend address. */
public record ServerRegistration(String id, String label) {
    public ServerRegistration {
        if (id == null || !id.matches("[a-z][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("Invalid server ID");
        if (label == null) throw new IllegalArgumentException("Invalid server label");
        label = label.strip();
        if (label.isEmpty() || label.length() > 80 || label.chars().anyMatch(c -> c < 32 || c == 127))
            throw new IllegalArgumentException("Invalid server label");
    }
    public static List<ServerRegistration> backends(Collection<String> names, String waiting) {
        return names.stream().filter(name -> !Objects.equals(name, waiting)).sorted()
            .map(name -> new ServerRegistration(name, name)).toList();
    }
    public static JsonObject payload(String source, List<ServerRegistration> servers) {
        if (!("velocity".equals(source) || "paper".equals(source)) || servers == null || servers.size() > 64)
            throw new IllegalArgumentException("Invalid server heartbeat");
        Set<String> ids = new HashSet<>(); JsonArray entries = new JsonArray();
        for (ServerRegistration server : servers) {
            if (server == null || !ids.add(server.id())) throw new IllegalArgumentException("Invalid server heartbeat");
            JsonObject entry = new JsonObject(); entry.addProperty("id", server.id()); entry.addProperty("label", server.label()); entries.add(entry);
        }
        JsonObject body = new JsonObject(); body.addProperty("source", source); body.add("servers", entries); return body;
    }
}
