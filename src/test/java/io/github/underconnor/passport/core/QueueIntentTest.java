package io.github.underconnor.passport.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class QueueIntentTest {
    @Test void leaveInvalidatesPendingAdmissionsAndPausesAutomaticLobbyRouting() {
        QueueIntent intent=new QueueIntent(); long pending=intent.generation();
        assertFalse(intent.automaticPaused()); intent.cancel();
        assertFalse(intent.current(pending)); assertTrue(intent.automaticPaused());
        // Merely refreshing status does not resume auto routing; a deliberate valid server selection does.
        long manual=intent.change(); assertTrue(intent.automaticPaused()); intent.resume();
        assertFalse(intent.automaticPaused()); assertTrue(intent.current(manual));
    }
    @Test void newLoginHasNoStaleCancellationOrGeneration() {
        QueueIntent old=new QueueIntent(); old.cancel();
        QueueIntent reconnected=new QueueIntent(); assertFalse(reconnected.automaticPaused()); assertEquals(0,reconnected.generation());
    }
    @Test void aFailedQueueConnectionPausesAutomaticReenrolment() {
        QueueIntent intent=new QueueIntent(); intent.failed(); assertTrue(intent.automaticPaused());
    }
}
