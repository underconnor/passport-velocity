package io.github.underconnor.passport.core;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TeleportMessageTest {
    private static final String KEY="synthetic-teleport-key-for-tests-only";
    private static final Instant NOW=Instant.parse("2026-10-01T00:00:00Z");
    private TeleportMessage request() { return TeleportMessage.request(UUID.randomUUID(),UUID.randomUUID(),"survival",NOW); }
    @Test void requestAndResponseRoundTrip() {
        var request=request();
        assertEquals(request,TeleportMessage.decode(request.encode(KEY),KEY,NOW));
        assertEquals(request.reply("ok"),TeleportMessage.decode(request.reply("ok").encode(KEY),KEY,NOW.plusSeconds(4)));
    }
    @Test void tamperedWrongKeyOversizedAndTruncatedMessagesAreRejected() {
        byte[] valid=request().encode(KEY), tampered=valid.clone(); tampered[50]^=1;
        assertThrows(IllegalArgumentException.class,() -> TeleportMessage.decode(tampered,KEY,NOW));
        assertThrows(IllegalArgumentException.class,() -> TeleportMessage.decode(valid,KEY+"other",NOW));
        assertThrows(IllegalArgumentException.class,() -> TeleportMessage.decode(new byte[513],KEY,NOW));
        assertThrows(IllegalArgumentException.class,() -> TeleportMessage.decode(Arrays.copyOf(valid,70),KEY,NOW));
    }
    @Test void expiredFutureAndUnknownOutcomeAreRejected() {
        var request=request();
        assertThrows(IllegalArgumentException.class,() -> TeleportMessage.decode(request.encode(KEY),KEY,NOW.plusSeconds(15)));
        assertThrows(IllegalArgumentException.class,() -> TeleportMessage.decode(request.encode(KEY),KEY,NOW.minusSeconds(1)));
        assertThrows(IllegalArgumentException.class,() -> request.reply("anything"));
    }
}
