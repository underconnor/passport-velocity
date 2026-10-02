package io.github.underconnor.passport.core;

import com.google.gson.*;
import java.time.*;
import java.text.Normalizer;
import java.util.*;

public record Policy(UUID minecraftUuid, String status, Set<String> allowedServerIds,
                     String roleLabel, String displayName, long version, Instant issuedAt, Instant expiresAt,
                     boolean member, String admissionYear, boolean administrator, Map<String,String> serverLabels, boolean telemetryEnabled, UUID telemetryEpoch,
                     Set<String> telemetryServerIds, boolean presenceEnabled, Map<String,String> serverCommandNames) {
    public Policy(UUID uuid, String status, Set<String> ids, String role, String name, long version, Instant issued, Instant expires, boolean member, String year, boolean admin, Map<String,String> labels, boolean telemetry, UUID epoch, Set<String> telemetryIds, boolean presence) {
        this(uuid,status,ids,role,name,version,issued,expires,member,year,admin,labels,telemetry,epoch,telemetryIds,presence,Map.of());
    }
    public Policy(UUID uuid, String status, Set<String> ids, String role, String name, long version, Instant issued, Instant expires, boolean member, String year, boolean admin, Map<String,String> labels) {
        this(uuid,status,ids,role,name,version,issued,expires,member,year,admin,labels,false,null,Set.of(),false);
    }
    public Policy(UUID uuid, String status, Set<String> ids, String role, String name, long version, Instant issued, Instant expires) {
        this(uuid,status,ids,role,name,version,issued,expires,false,null,false,Map.of());
    }
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
        boolean member = display.has("member") && display.get("member").getAsBoolean();
        String year = display.has("admissionYear") && !display.get("admissionYear").isJsonNull() ? display.get("admissionYear").getAsString() : null;
        if (year != null && !year.matches("[0-9]{2}")) throw new IllegalArgumentException("admissionYear");
        boolean administrator = o.has("administrator") && o.get("administrator").getAsBoolean();
        Map<String,String> labels = new HashMap<>(), commandNames = new HashMap<>();
        Set<String> uniqueCommands = new HashSet<>();
        if (o.has("allowedServers")) for (JsonElement item : o.getAsJsonArray("allowedServers")) {
            JsonObject server = item.getAsJsonObject(); String id = server.get("id").getAsString();
            String label = plain(server.get("label").getAsString(),80);
            if (!ids.contains(id) || label.isBlank() || labels.put(id,label) != null) throw new IllegalArgumentException("server labels");
            if (server.has("commandName")) {
                JsonElement value=server.get("commandName");
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("server command");
                String command=value.getAsString();
                if (!command.matches("[a-z0-9가-힣_-]{1,64}") || !command.equals(normalizeCommand(command))
                        || !uniqueCommands.add(command) || (ids.contains(command) && !command.equals(id))) throw new IllegalArgumentException("server command");
                commandNames.put(id,command);
            }
        }
        // Only wholly legacy responses may fall back to IDs; a partial new response fails closed.
        if (!commandNames.isEmpty() && !commandNames.keySet().equals(ids)) throw new IllegalArgumentException("server commands incomplete");
        if (!status.equals("active") && (member || year != null)) throw new IllegalArgumentException("inactive identity");
        JsonObject telemetry=o.has("telemetry") ? o.getAsJsonObject("telemetry") : null;
        boolean telemetryEnabled=telemetry!=null && telemetry.get("enabled").getAsBoolean();
        UUID epoch=telemetry!=null && !telemetry.get("epoch").isJsonNull() ? UUID.fromString(telemetry.get("epoch").getAsString()) : null;
        if(telemetryEnabled != (epoch!=null) || (telemetryEnabled && !status.equals("active"))) throw new IllegalArgumentException("telemetry");
        Set<String> telemetryServers=new HashSet<>();
        if(telemetry!=null && telemetry.has("serverIds")) for(JsonElement item:telemetry.getAsJsonArray("serverIds")) {
            if(!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("telemetry server");
            String id=item.getAsString();
            if(!ids.contains(id) || !telemetryServers.add(id)) throw new IllegalArgumentException("telemetry server");
        }
        if(!telemetryEnabled && !telemetryServers.isEmpty()) throw new IllegalArgumentException("disabled telemetry servers");
        boolean presenceEnabled=false;
        if(telemetry!=null && telemetry.has("presenceEnabled")) {
            JsonElement item=telemetry.get("presenceEnabled");
            if(!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("presence");
            presenceEnabled=item.getAsBoolean();
            if(presenceEnabled && !status.equals("active")) throw new IllegalArgumentException("inactive presence");
        }
        // Older APIs did not advertise expanded game consent: do not expose their displayName as a real name.
        if(telemetry==null) { name=""; member=false; year=null; }
        return new Policy(uuid, status, Set.copyOf(ids), role, name, version, issued, expiry,member,year,administrator,Map.copyOf(labels),telemetryEnabled,epoch,Set.copyOf(telemetryServers),presenceEnabled,Map.copyOf(commandNames));
    }
    private static String plain(String value, int limit) {
        if (value.codePointCount(0,value.length()) > limit || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("display");
        return value;
    }
    public boolean valid(Instant now) { return expiresAt.isAfter(now) && !issuedAt.isAfter(now.plusSeconds(2)); }
    public boolean active(Instant now) { return "active".equals(status) && valid(now); }
    public String label(String id) { return serverLabels.getOrDefault(id,id); }
    public boolean hasServerCommandNames() { return !serverCommandNames.isEmpty(); }
    public String commandName(String id) { return serverCommandNames.getOrDefault(id,id); }
    public static String normalizeCommand(String value) { return Normalizer.normalize(value,Normalizer.Form.NFC).toLowerCase(Locale.ROOT); }
    public boolean collectsStatistics(String serverId,Instant now) {
        return telemetryEnabled && telemetryEpoch!=null && telemetryServerIds.contains(serverId) && allows(serverId,now);
    }
    public boolean allows(String serverId, Instant now) {
        return "active".equals(status) && expiresAt.isAfter(now) && !issuedAt.isAfter(now.plusSeconds(2)) && allowedServerIds.contains(serverId);
    }
}
