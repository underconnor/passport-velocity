package io.github.underconnor.passport.core;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DepartureMessageTest {
    final String secret="secret-test-key-never-used-for-production";
    final Instant now=Instant.parse("2026-10-04T00:00:00Z");
    @Test void signedRoundTripKeepsConnectionIdentityAndSuccessUsesItsOwnFreshExpiry() {
        var start=DepartureMessage.begin(UUID.randomUUID(),"ssu_build_2609",now);
        assertEquals(start,DepartureMessage.decode(start.encode(secret),secret,now));
        var departure=start.transferred(now.plusSeconds(3600));
        assertEquals(start.connectionId(),departure.connectionId()); assertEquals(start.actor(),departure.actor());
        assertEquals("TRANSFER",DepartureMessage.decode(departure.encode(secret),secret,now.plusSeconds(3600)).kind());
    }
    @Test void wrongKeyTamperingTruncationAndExpiredOrFutureMessagesAreRejected() {
        var start=DepartureMessage.begin(UUID.randomUUID(),"lobby",now); byte[] bytes=start.encode(secret);
        assertThrows(IllegalArgumentException.class,() -> DepartureMessage.decode(bytes,secret+"wrong",now));
        byte[] altered=bytes.clone(); altered[30]^=1;
        assertThrows(IllegalArgumentException.class,() -> DepartureMessage.decode(altered,secret,now));
        assertThrows(IllegalArgumentException.class,() -> DepartureMessage.decode(Arrays.copyOf(bytes,bytes.length-1),secret,now));
        assertThrows(IllegalArgumentException.class,() -> DepartureMessage.decode(bytes,secret,now.plusSeconds(10)));
        assertThrows(IllegalArgumentException.class,() -> DepartureMessage.decode(bytes,secret,now.minusSeconds(1)));
    }
    @Test void malformedAndOtherProtocolMessagesDoNotPass() {
        assertThrows(IllegalArgumentException.class,() -> DepartureMessage.begin(UUID.randomUUID(),"../server",now));
        assertThrows(IllegalArgumentException.class,() -> new DepartureMessage("FAILED",UUID.randomUUID(),UUID.randomUUID(),"lobby",now.toEpochMilli()));
        var teleport=TeleportMessage.request(UUID.randomUUID(),UUID.randomUUID(),"lobby",now);
        assertThrows(IllegalArgumentException.class,() -> DepartureMessage.decode(teleport.encode(secret),secret,now));
    }
}
