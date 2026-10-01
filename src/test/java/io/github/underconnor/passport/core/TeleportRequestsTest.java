package io.github.underconnor.passport.core;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TeleportRequestsTest {
    @Test void replyMustMatchActorTargetDestinationAndNonceAndOnlyCompleteOnce() {
        var now=Instant.now(); var tracker=new TeleportRequests();
        var request=TeleportMessage.request(UUID.randomUUID(),UUID.randomUUID(),"survival",now);
        var result=tracker.begin(request); var reply=request.reply("ok");
        assertFalse(tracker.accept(reply,"lobby",request.actor(),now));
        assertFalse(tracker.accept(reply,"survival",UUID.randomUUID(),now));
        var forged=new TeleportMessage("RESULT",request.requestId(),request.actor(),UUID.randomUUID(),"survival",request.expiresAt(),"ok");
        assertFalse(tracker.accept(forged,"survival",request.actor(),now));
        assertFalse(result.isDone());
        assertTrue(tracker.accept(reply,"survival",request.actor(),now)); assertEquals("ok",result.join());
        assertFalse(tracker.accept(reply,"survival",request.actor(),now));
    }
    @Test void actorCanOnlyHaveOneRequestAndTimeoutOrFailureReleasesIt() {
        var tracker=new TeleportRequests(); var request=TeleportMessage.request(UUID.randomUUID(),UUID.randomUUID(),"survival",Instant.now());
        var result=tracker.begin(request);
        assertTrue(tracker.begin(TeleportMessage.request(request.actor(),UUID.randomUUID(),"survival",Instant.now())).isCompletedExceptionally());
        tracker.fail(request.requestId()); assertEquals("unavailable",result.join());
        var second=tracker.begin(TeleportMessage.request(request.actor(),UUID.randomUUID(),"survival",Instant.now()));
        assertFalse(second.isDone()); tracker.close(); assertEquals("unavailable",second.join());
    }
}
