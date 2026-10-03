package io.github.underconnor.passport.core;

import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class RoutingAttemptsTest {
    private static long seconds(int n) { return TimeUnit.SECONDS.toNanos(n); }
    @Test void defaultConnectionIsSingleFlightAndFailuresDelayRetry() {
        RoutingAttempts r=new RoutingAttempts(); assertFalse(r.inProgress()); long first=r.begin(0); assertTrue(r.inProgress()); assertNotEquals(0,first); assertEquals(0,r.begin(0));
        assertTrue(r.complete(first,false,0)); assertFalse(r.inProgress()); assertEquals(0,r.begin(seconds(1)));
        long next=r.begin(seconds(2)); assertNotEquals(0,next);
        assertFalse(r.complete(next,false,seconds(2))); assertEquals(0,r.begin(seconds(5))); assertNotEquals(0,r.begin(seconds(6)));
    }
    @Test void fallbackInvalidatesOldAttemptSoLateSuccessCannotClearCooldown() {
        RoutingAttempts r=new RoutingAttempts(); long first=r.begin(0);
        assertTrue(r.failed(0)); assertFalse(r.complete(first,true,0)); assertEquals(0,r.begin(seconds(1)));
        long next=r.begin(seconds(2)); assertNotEquals(0,next); r.complete(next,true,seconds(2));
        long third=r.begin(seconds(2)); assertNotEquals(0,third); assertTrue(r.complete(third,false,seconds(2)));
    }
    @Test void extendedOutageKeepsRetryingWithThirtySecondBackoffAndRecoversWithoutManualInput() {
        RoutingAttempts r=new RoutingAttempts(); long now=0;
        for(int i=0;i<100;i++) {
            long attempt=r.begin(now); assertNotEquals(0,attempt);
            assertEquals(i==0,r.complete(attempt,false,now));
            if(i>=4) { assertEquals(0,r.begin(now+seconds(29))); }
            now+=seconds(30);
        }
        long recovered=r.begin(now); assertNotEquals(0,recovered);
        assertFalse(r.complete(recovered,true,now));
        assertNotEquals(0,r.begin(now));
    }
}
