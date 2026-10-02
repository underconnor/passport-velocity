package io.github.underconnor.passport.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TeleportSecretsTest {
    private static final String SHARED="synthetic-teleport-signing-secret-123456";
    @Test void usesDedicatedSecretWhenServiceKeysDiffer() {
        assertEquals(SHARED,TeleportSecrets.resolve(SHARED,"psk_proxy-scoped-token"));
        assertEquals(SHARED,TeleportSecrets.resolve(SHARED,"psk_paper-scoped-token"));
    }
    @Test void scopedCredentialNeverSilentlyBecomesChannelSecret() {
        assertThrows(IllegalArgumentException.class,()->TeleportSecrets.resolve(null,"psk_scoped-token"));
        assertThrows(IllegalArgumentException.class,()->TeleportSecrets.resolve("psk_"+SHARED,SHARED));
    }
    @Test void validatesSecretAndKeepsLegacyHandoff() {
        assertEquals(SHARED,TeleportSecrets.resolve(null,SHARED));
        assertThrows(IllegalArgumentException.class,()->TeleportSecrets.resolve("short",SHARED));
        assertThrows(IllegalArgumentException.class,()->TeleportSecrets.resolve(SHARED+"\n",SHARED));
    }
}
