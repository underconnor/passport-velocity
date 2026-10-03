package io.github.underconnor.passport.core;

import java.time.Instant;
import java.util.UUID;

/** One invitation decision per proxy login, using responses requested by that login only. */
public final class DiscordInvitation {
    private final UUID minecraftUuid;
    private Policy observed;
    private boolean settled;

    public DiscordInvitation(UUID minecraftUuid) { this.minecraftUuid = minecraftUuid; }

    /** Called only for a response accepted by the policy cache for the still-current session. */
    public synchronized void observe(Policy policy, Instant now) {
        if (policy == null || !minecraftUuid.equals(policy.minecraftUuid()) || !policy.valid(now)) return;
        observed = policy;
        // A confirmed link suppresses the invitation for this entire login, including backend transfers.
        if (Boolean.TRUE.equals(policy.discordLinked())) settled = true;
    }

    public synchronized boolean claim(Policy currentPolicy, boolean currentSession, boolean connected, Instant now) {
        if (settled || !currentSession || !connected || currentPolicy == null || !currentPolicy.valid(now)
                || !currentPolicy.equals(observed) || !Boolean.FALSE.equals(currentPolicy.discordLinked())) return false;
        settled = true;
        return true;
    }
}
