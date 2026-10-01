package io.github.underconnor.passport.core;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class PolicyTest {
    final UUID uuid=UUID.fromString("11111111-1111-4111-8111-111111111111");
    final Instant now=Instant.parse("2026-09-30T00:00:10Z");
    String json(String status,int version,String issued,String expiry) {
        return """
          {"contractVersion":"0.1.0-draft","subjectId":"22222222-2222-4222-8222-222222222222",
           "minecraftUuid":"11111111-1111-4111-8111-111111111111","status":"%s",
           "allowedServerIds":%s,"display":{"roleLabel":"member","displayName":"test"},
           "policyVersion":%d,"issuedAt":"%s","expiresAt":"%s"}
        """.formatted(status,status.equals("active") ? "[\"lobby\"]" : "[]",version,issued,expiry);
    }
    Policy policy(String status,int v,String issued,String expiry) { return Policy.parse(json(status,v,issued,expiry),uuid,now); }
    @Test void onlyActiveUnexpiredMatchingServerIsAllowed() {
        Policy p=policy("active",1,"2026-09-30T00:00:00Z","2026-09-30T00:01:00Z");
        assertTrue(p.allows("lobby",now)); assertFalse(p.allows("admin",now));
        assertFalse(p.allows("lobby",Instant.parse("2026-09-30T00:01:00Z")));
        for(String status:new String[]{"unlinked","pending","suspended","revoked","stale"})
            assertFalse(policy(status,1,"2026-09-30T00:00:00Z","2026-09-30T00:01:00Z").allows("lobby",now));
    }
    @Test void rejectsUuidAndContractMismatch() {
        String body=json("active",1,"2026-09-30T00:00:00Z","2026-09-30T00:01:00Z");
        assertThrows(IllegalArgumentException.class,()->Policy.parse(body,UUID.randomUUID(),now));
        assertThrows(IllegalArgumentException.class,()->Policy.parse(body.replace("0.1.0-draft","2"),uuid,now));
    }
    @Test void rejectsInvalidLeasesAndVersion() {
        assertThrows(IllegalArgumentException.class,()->policy("active",1,"2026-09-30T00:00:00Z","2026-09-30T00:01:01Z"));
        assertThrows(IllegalArgumentException.class,()->policy("active",1,"2026-09-30T00:00:20Z","2026-09-30T00:01:00Z"));
        assertThrows(IllegalArgumentException.class,()->policy("active",1,"2026-09-30T00:00:00Z","2026-09-30T00:00:10Z"));
        assertThrows(IllegalArgumentException.class,()->policy("active",0,"2026-09-30T00:00:00Z","2026-09-30T00:01:00Z"));
    }
    @Test void revocationCannotBeUndoneByLateResponseOrRelinkVersionReset() {
        PolicyCache cache=new PolicyCache();
        assertTrue(cache.accept(policy("active",5,"2026-09-30T00:00:00Z","2026-09-30T00:01:00Z")));
        assertTrue(cache.accept(policy("revoked",6,"2026-09-30T00:00:01Z","2026-09-30T00:01:01Z")));
        assertFalse(cache.accept(policy("active",5,"2026-09-30T00:00:02Z","2026-09-30T00:01:02Z")));
        assertFalse(cache.accept(policy("active",1,"2026-09-30T00:00:03Z","2026-09-30T00:01:03Z")));
        assertFalse(cache.allows(uuid,"lobby",now));
    }
    @Test void sameVersionRequiresStrictlyNewerIssueTimeAndLeaseStillExpires() {
        PolicyCache cache=new PolicyCache();
        Policy p=policy("active",1,"2026-09-30T00:00:00Z","2026-09-30T00:01:00Z");
        assertTrue(cache.accept(p)); assertFalse(cache.accept(p));
        assertTrue(cache.accept(policy("active",1,"2026-09-30T00:00:01Z","2026-09-30T00:01:01Z")));
        assertFalse(cache.accept(p));
        assertFalse(cache.allows(uuid,"lobby",Instant.parse("2026-09-30T00:01:01Z")));
        assertFalse(cache.allows(UUID.randomUUID(),"lobby",now));
    }
    @Test void identicalCurrentResponseIsUsableButOlderAndConflictingResponsesAreNot() {
        PolicyCache cache=new PolicyCache();
        Policy current=policy("active",7,"2026-09-30T00:00:02Z","2026-09-30T00:01:02Z");
        assertTrue(cache.acceptOrCurrent(current));
        assertTrue(cache.acceptOrCurrent(current));
        assertEquals(current.expiresAt(),cache.get(uuid).orElseThrow().expiresAt());
        assertFalse(cache.acceptOrCurrent(policy("active",7,"2026-09-30T00:00:01Z","2026-09-30T00:01:01Z")));
        assertFalse(cache.acceptOrCurrent(policy("active",6,"2026-09-30T00:00:03Z","2026-09-30T00:01:03Z")));
        assertFalse(cache.acceptOrCurrent(policy("revoked",7,"2026-09-30T00:00:02Z","2026-09-30T00:01:02Z")));
        assertEquals(current,cache.get(uuid).orElseThrow());
        assertTrue(cache.acceptOrCurrent(policy("revoked",8,"2026-09-30T00:00:03Z","2026-09-30T00:01:03Z")));
        assertFalse(cache.acceptOrCurrent(current));
        assertFalse(cache.allows(uuid,"lobby",now));
    }
    @Test void malformedDenyCannotCarryScopes() {
        String body=json("active",1,"2026-09-30T00:00:00Z","2026-09-30T00:01:00Z").replace("\"active\"","\"revoked\"");
        assertThrows(IllegalArgumentException.class,()->Policy.parse(body,uuid,now));
    }
    @Test void identityExtensionsAreMinimalAndLabelsCannotExposeDeniedServers() {
        String body=json("active",1,"2026-09-30T00:00:00Z","2026-09-30T00:01:00Z")
            .replace("\"displayName\":\"test\"","\"displayName\":\"테스트\",\"member\":true,\"admissionYear\":\"26\"")
            .replace("\"policyVersion\":1","\"telemetry\":{\"enabled\":false,\"epoch\":null},\"administrator\":true,\"allowedServers\":[{\"id\":\"lobby\",\"label\":\"로비\"}],\"policyVersion\":1");
        Policy policy=Policy.parse(body,uuid,now); assertTrue(policy.member()); assertEquals("26",policy.admissionYear());
        assertTrue(policy.administrator()); assertEquals("로비",policy.label("lobby"));
        assertThrows(IllegalArgumentException.class,() -> Policy.parse(body.replace("\"26\"","\"20260000\""),uuid,now));
        assertThrows(IllegalArgumentException.class,() -> Policy.parse(body.replace("\"id\":\"lobby\"","\"id\":\"secret\""),uuid,now));
    }
    @Test void telemetryRequiresAnEpochAndActivePolicy() {
        String body=json("active",1,"2026-09-30T00:00:00Z","2026-09-30T00:01:00Z")
            .replace("\"policyVersion\":1","\"telemetry\":{\"enabled\":true,\"epoch\":\"33333333-3333-4333-8333-333333333333\"},\"policyVersion\":1");
        assertTrue(Policy.parse(body,uuid,now).telemetryEnabled());
        assertThrows(IllegalArgumentException.class,() -> Policy.parse(body.replace("\"33333333-3333-4333-8333-333333333333\"","null"),uuid,now));
        assertThrows(IllegalArgumentException.class,() -> Policy.parse(body.replace("\"enabled\":true","\"enabled\":false"),uuid,now));
    }
    @Test void administratorCapabilityDoesNotPreventGameAccessRevocation() {
        String body=json("revoked",9,"2026-09-30T00:00:00Z","2026-09-30T00:01:00Z")
            .replace("\"policyVersion\":9","\"administrator\":true,\"policyVersion\":9");
        Policy policy=Policy.parse(body,uuid,now);
        assertTrue(policy.administrator()); assertTrue(policy.valid(now));
        assertFalse(policy.active(now)); assertFalse(policy.allows("lobby",now));
        assertFalse(policy.valid(Instant.parse("2026-09-30T00:01:00Z")));
    }
}
