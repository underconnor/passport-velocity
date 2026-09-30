package io.github.underconnor.passport.core;
import java.time.Instant;
import java.util.Optional;

/** Authentication completion may leave the waiting room; status never relocates a playing user. */
public final class AutomaticRouting {
    private AutomaticRouting() {}
    public static Optional<String> target(Policy policy, String currentServer, String waitingServer, String defaultServer, Instant now) {
        return currentServer.equals(waitingServer) && policy.allows(defaultServer, now)
            ? Optional.of(defaultServer) : Optional.empty();
    }
}
