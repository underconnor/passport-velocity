package io.github.underconnor.passport.core;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ServerCapacitiesTest {
    @Test void requiresEveryRegisteredServerIncludingLimboExactlyOnce() {
        Set<String> servers=Set.of("ssu_lobby","ssu_build_2609","passport-limbo");
        assertEquals(Map.of("ssu_lobby",100,"ssu_build_2609",50,"passport-limbo",100),
            ServerCapacities.parse("ssu_lobby=100,ssu_build_2609=50,passport-limbo=100",servers));
        assertThrows(IllegalArgumentException.class,() -> ServerCapacities.parse("ssu_lobby=100",servers));
        assertThrows(IllegalArgumentException.class,() -> ServerCapacities.parse("ssu_lobby=100,unknown=50",Set.of("ssu_lobby")));
    }
    @Test void missingDuplicateZeroNegativeMalformedAndExcessiveValuesFailClosed() {
        for(String value:Arrays.asList(null,"","lobby=0","lobby=-1","lobby=01","lobby=1001","lobby=1.5","lobby=1=2",
                "lobby=10,lobby=20","lobby=10,","Lobby=10","lobby= 10"))
            assertThrows(IllegalArgumentException.class,() -> ServerCapacities.parse(value,Set.of("lobby")),String.valueOf(value));
    }
}
