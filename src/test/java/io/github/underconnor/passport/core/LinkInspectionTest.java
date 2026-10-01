package io.github.underconnor.passport.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class LinkInspectionTest {
    private static final String ID="22222222-2222-2222-2222-222222222222";
    private static final Instant NOW=Instant.parse("2026-10-01T00:00:00Z"), EXPIRY=NOW.plusSeconds(300);
    private static JsonObject inspection() {
        JsonObject v=new JsonObject(); v.addProperty("id",ID); v.addProperty("status","pending"); v.addProperty("expiresAt",EXPIRY.toString());
        v.addProperty("webConfirmed",true); v.addProperty("gameConfirmed",false); return v;
    }
    @Test void validInspectionKeepsBothIndependentProofFlags() {
        LinkInspection v=LinkInspection.parse(inspection().toString(),ID,NOW);
        assertTrue(v.webConfirmed()); assertFalse(v.gameConfirmed()); assertEquals("pending",v.status());
    }
    @Test void wrongIdentityMalformedFlagsAndUnprovedLinkedStateAreRejected() {
        for(String field:new String[]{"id","status","expiresAt","webConfirmed","gameConfirmed","unexpected"}) {
            JsonObject v=inspection(); v.addProperty(field,"synthetic-private-marker");
            IllegalArgumentException error=assertThrows(IllegalArgumentException.class,() -> LinkInspection.parse(v.toString(),ID,NOW));
            assertNull(error.getCause()); assertFalse(error.toString().contains("synthetic-private-marker"));
        }
        JsonObject v=inspection(); v.addProperty("status","linked");
        assertThrows(IllegalArgumentException.class,() -> LinkInspection.parse(v.toString(),ID,NOW));
        assertThrows(IllegalArgumentException.class,() -> LinkInspection.parse(inspection().toString(),ID,EXPIRY));
    }
    @Test void confirmationMustMatchOriginalIdentityLeaseAndKnownStatus() {
        JsonObject v=inspection(); v.remove("webConfirmed"); v.remove("gameConfirmed");
        assertEquals("pending",LinkInspection.confirmationStatus(v,ID,EXPIRY,NOW));
        assertThrows(IllegalArgumentException.class,() -> LinkInspection.confirmationStatus(v,ID,EXPIRY.plusSeconds(1),NOW));
        v.addProperty("status","synthetic-private-marker");
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,() -> LinkInspection.confirmationStatus(v,ID,EXPIRY,NOW));
        assertNull(error.getCause()); assertFalse(error.toString().contains("synthetic-private-marker"));
    }
}
