package io.github.underconnor.passport.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ServerCommandsTest {
    final UUID uuid=UUID.fromString("11111111-1111-4111-8111-111111111111");
    final Instant now=Instant.parse("2026-10-02T00:00:10Z");
    JsonObject body() {
        return JsonParser.parseString("""
            {"contractVersion":"0.1.0-draft","subjectId":"22222222-2222-4222-8222-222222222222",
             "minecraftUuid":"11111111-1111-4111-8111-111111111111","status":"active",
             "allowedServerIds":["ssu_lobby","ssu_build_2609"],"display":{"roleLabel":"","displayName":""},
             "allowedServers":[{"id":"ssu_lobby","label":"공용 로비","commandName":"로비"},
                               {"id":"ssu_build_2609","label":"자유 건축","commandName":"build-2"}],
             "policyVersion":1,"issuedAt":"2026-10-02T00:00:00Z","expiresAt":"2026-10-02T00:01:00Z"}
            """).getAsJsonObject();
    }
    Policy parse(JsonObject body) { return Policy.parse(body.toString(),uuid,now); }
    JsonObject server(JsonObject body,int index) { return body.getAsJsonArray("allowedServers").get(index).getAsJsonObject(); }

    @Test void commandNamesKeepRoutingAndLabelsSeparate() {
        Policy policy=parse(body());
        assertTrue(policy.hasServerCommandNames()); assertEquals("공용 로비",policy.label("ssu_lobby"));
        assertEquals("로비",policy.commandName("ssu_lobby"));
        assertTrue(policy.allows("ssu_lobby",now)); assertFalse(policy.allows("로비",now));
        assertEquals("build-2",policy.commandName("ssu_build_2609"));
    }
    @Test void malformedOrNoncanonicalCommandNamesFailClosed() {
        for(JsonElement invalid:new JsonElement[]{JsonNull.INSTANCE,new JsonPrimitive(12),new JsonPrimitive(true),new JsonArray(),new JsonObject(),
            new JsonPrimitive(""),new JsonPrimitive("Build"),new JsonPrimitive("build room"),new JsonPrimitive("/server"),
            new JsonPrimitive("로비\n"),new JsonPrimitive("가".repeat(65)),new JsonPrimitive("로비")}) {
            JsonObject body=body(); server(body,0).add("commandName",invalid);
            assertThrows(IllegalArgumentException.class,() -> parse(body));
        }
    }
    @Test void duplicateOrCrossServerIdCommandsFailClosed() {
        for(String conflicting:List.of("로비","ssu_lobby")) {
            JsonObject body=body(); server(body,1).addProperty("commandName",conflicting);
            assertThrows(IllegalArgumentException.class,() -> parse(body));
        }
    }
    @Test void partialCommandResponseCannotDowngradeToLegacyIds() {
        JsonObject body=body(); server(body,1).remove("commandName");
        assertThrows(IllegalArgumentException.class,() -> parse(body));
        body.getAsJsonArray("allowedServers").remove(1);
        assertThrows(IllegalArgumentException.class,() -> parse(body));
    }
    @Test void whollyLegacyResponseRetainsPreviousLookupAndSuggestions() {
        JsonObject body=body(); server(body,0).remove("commandName"); server(body,1).remove("commandName");
        Policy policy=parse(body);
        assertFalse(policy.hasServerCommandNames()); assertEquals("ssu_lobby",policy.commandName("ssu_lobby"));
        assertEquals(List.of("ssu_lobby"),CommandSelection.servers(policy,"공용 로비"));
        assertEquals(List.of("ssu_lobby"),CommandSelection.servers(policy,"ssu_lobby"));
        assertEquals(List.of("ssu_build_2609","ssu_lobby"),CommandSelection.serverSuggestions(policy,"ssu_"));
    }
    @Test void commandChangesAtIdenticalVersionAndIssueTimeCannotReplaceCachedPolicy() {
        PolicyCache cache=new PolicyCache(); JsonObject body=body(); Policy original=parse(body);
        assertTrue(cache.acceptOrCurrent(original));
        server(body,0).addProperty("commandName","새로비");
        assertFalse(cache.acceptOrCurrent(parse(body)));
        assertEquals("로비",cache.get(uuid).orElseThrow().commandName("ssu_lobby"));
    }
    @Test void modernCommandsResolveAliasesOnlyAndNormalizeKoreanInput() {
        Policy policy=parse(body());
        assertEquals(List.of("ssu_lobby"),CommandSelection.servers(policy,"로비"));
        assertEquals(List.of("ssu_lobby"),CommandSelection.servers(policy,"로비"));
        assertEquals(List.of("ssu_build_2609"),CommandSelection.servers(policy,"BUILD-2"));
        for(String rejected:List.of("ssu_lobby","ssu_build_2609","공용 로비","자유 건축","unknown","로비\n"))
            assertTrue(CommandSelection.servers(policy,rejected).isEmpty());
    }
    @Test void modernSuggestionsNeverIncludeStorageIdsOrDisplayLabels() {
        Policy policy=parse(body());
        assertEquals(List.of("build-2","로비"),CommandSelection.serverSuggestions(policy,""));
        assertEquals(List.of("로비"),CommandSelection.serverSuggestions(policy,"로"));
        assertEquals(List.of("build-2"),CommandSelection.serverSuggestions(policy,"BUI"));
        assertTrue(CommandSelection.serverSuggestions(policy,"ssu_").isEmpty());
        assertTrue(CommandSelection.serverSuggestions(policy,"자유").isEmpty());
    }
    @Test void playerLookupServerLabelRequiresCurrentViewerAccessAndNeverFallsBackToId() {
        Policy policy=parse(body());
        assertEquals(Optional.of("공용 로비"),CommandSelection.visibleServerLabel(policy,"ssu_lobby",now));
        assertTrue(CommandSelection.visibleServerLabel(policy,"secret_server",now).isEmpty());
        assertTrue(CommandSelection.visibleServerLabel(policy,"ssu_lobby",policy.expiresAt()).isEmpty());
        Policy legacy=new Policy(uuid,"active",Set.of("internal_lobby"),"","",1,now,now.plusSeconds(30));
        assertTrue(CommandSelection.visibleServerLabel(legacy,"internal_lobby",now).isEmpty());
    }
}
