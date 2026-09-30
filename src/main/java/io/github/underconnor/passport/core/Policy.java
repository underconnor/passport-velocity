package io.github.underconnor.passport.core;

import com.google.gson.*;
import java.time.*;
import java.util.*;

public record Policy(UUID minecraftUuid, String status, Set<String> allowedServerIds,
                     String roleLabel, String displayName, long version, Instant issuedAt, Instant expiresAt) {
    private static final Set<String> STATUSES = Set.of("unlinked", "pending", "active", "suspended", "revoked", "stale");
    public static Policy parse(String body, UUID expected, Instant now) {
        JsonObject o = JsonParser.parseString(body).getAsJsonObject();
        if (!"0.1.0-draft".equals(o.get("contractVersion").getAsString())) throw new IllegalArgumentException("contract");
        UUID uuid = UUID.fromString(o.get("minecraftUuid").getAsString());
        if (!uuid.equals(expected)) throw new IllegalArgumentException("uuid");
        String status = o.get("status").getAsString();
        if (!STATUSES.contains(status)) throw new IllegalArgumentException("status");
        if (status.equals("active")) UUID.fromString(o.get("subjectId").getAsString());
        Set<String> ids = new HashSet<>();
        for (JsonElement value : o.getAsJsonArray("allowedServerIds")) {
            String id = value.getAsString();
            if (!id.matches("[a-z][a-z0-9_-]{0,63}") || !ids.add(id)) throw new IllegalArgumentException("server");
        }
        if (ids.size() > 64 || (!status.equals("active") && !ids.isEmpty())) throw new IllegalArgumentException("scopes");
        String rawVersion = o.get("policyVersion").getAsString();
        if (!rawVersion.matches("[1-9][0-9]{0,9}")) throw new IllegalArgumentException("version");
        long version = Long.parseLong(rawVersion);
        if (version > Integer.MAX_VALUE) throw new IllegalArgumentException("version");
        Instant issued = Instant.parse(o.get("issuedAt").getAsString());
        Instant expiry = Instant.parse(o.get("expiresAt").getAsString());
        if (!expiry.isAfter(issued) || Duration.between(issued, expiry).compareTo(Duration.ofSeconds(60)) > 0
                || issued.isAfter(now.plusSeconds(2)) || !expiry.isAfter(now)) throw new IllegalArgumentException("lease");
        JsonObject display = o.getAsJsonObject("display");
        String role = plain(display.get("roleLabel").getAsString(), 24);
        String name = plain(display.get("displayName").getAsString(), 40);
        return new Policy(uuid, status, Set.copyOf(ids), role, name, version, issued, expiry);
    }
    private static String plain(String value, int limit) {
        if (value.codePointCount(0,value.length()) > limit || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("display");
        return value;
    }
    public boolean allows(String serverId, Instant now) {
        return "active".equals(status) && expiresAt.isAfter(now) && !issuedAt.isAfter(now.plusSeconds(2)) && allowedServerIds.contains(serverId);
    }
}
