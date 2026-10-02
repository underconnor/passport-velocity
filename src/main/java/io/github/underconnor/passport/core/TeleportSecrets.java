package io.github.underconnor.passport.core;

/** API credentials are per service; the proxy channel has its own shared key. */
public final class TeleportSecrets {
    private TeleportSecrets() {}
    public static String resolve(String configured, String serviceToken) {
        String secret=configured;
        if(secret==null || secret.isBlank()) {
            if(serviceToken==null || serviceToken.startsWith("psk_"))
                throw new IllegalArgumentException("PASSPORT_TELEPORT_SECRET is required with scoped API credentials");
            secret=serviceToken; // explicit legacy migration compatibility
        }
        if(secret.length()<32 || secret.contains("\n") || secret.contains("\r") || secret.startsWith("psk_"))
            throw new IllegalArgumentException("PASSPORT_TELEPORT_SECRET must be a separate random secret");
        return secret;
    }
}
